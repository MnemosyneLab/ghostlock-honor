package dev.ghostlock.honor.shizuku;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Process;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RestoreService extends IRestoreService.Stub {
    private static final String STATE =
        "printf 'boot_completed=%s\\n' \"$(getprop sys.boot_completed)\"\n" +
        "printf 'release=%s\\n' \"$(uname -r)\"\n" +
        "printf 'build=%s\\n' \"$(getprop ro.build.display.id)\"\n" +
        "printf 'selinux=%s\\n' \"$(getenforce)\"\n" +
        "printf 'verified=%s\\n' \"$(getprop ro.boot.verifiedbootstate)\"\n" +
        "printf 'locked=%s\\n' \"$(getprop ro.boot.flash.locked)\"\n" +
        "printf 'boot_id=%s\\n' \"$(cat /proc/sys/kernel/random/boot_id)\"\n" +
        "printf 'slot=%s\\n' \"$(getprop ro.boot.slot_suffix)\"\n" +
        "if grep -q '^kernelsu ' /proc/modules; then echo module=1; else echo module=0; fi\n";
    private final Context context;
    private final int clientUid;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String snapshot = "{\"phase\":\"idle\",\"message\":\"已连接，请检查准备条件。\",\"busy\":false}";
    private volatile File work;
    private volatile java.lang.Process nativeProcess;
    private boolean attempted;
    private boolean verified;
    private int invocations;
    private File bootLock;
    private boolean ownLock;
    private final StringBuilder events = new StringBuilder();

    public RestoreService(Context context) {
        this.context = context;
        this.clientUid = context.getApplicationInfo().uid;
    }

    private void caller() {
        if (Binder.getCallingUid() != clientUid) throw new SecurityException("Wrong caller");
    }

    @Override public String snapshot() { caller(); return snapshot; }
    @Override public void inspect() { caller(); begin(false); }
    @Override public void restore() { caller(); begin(true); }
    @Override public String readLog() {
        caller();
        StringBuilder out = new StringBuilder(snapshot).append('\n');
        synchronized (events) { out.append(events); }
        File dir = work;
        if (dir != null) {
            for (String name : new String[] {"native.log", "handoff.log", "root-handoff.log",
                                             "allowlist-before.sha256", "result.json"}) {
                File file = new File(dir, name);
                if (file.isFile()) {
                    out.append("\n--- ").append(name).append(" ---\n");
                    try { out.append(readTail(file, 24000)); } catch (IOException ignored) { }
                }
            }
        }
        return out.toString();
    }

    @Override public void destroy() {
        int uid = Binder.getCallingUid();
        if (uid != clientUid && uid != Process.SHELL_UID) throw new SecurityException("Wrong caller");
        // Never kill the shell owner while its native worker may still be active.
        if (!busy.get() && (nativeProcess == null || !nativeProcess.isAlive())) System.exit(0);
    }

    private void event(String message) {
        synchronized (events) {
            events.append(message).append('\n');
            if (events.length() > 24000) events.delete(0, events.length() - 24000);
        }
    }

    private void publish(String phase, String message, Map<String, String> state, boolean ready) {
        JSONObject value = new JSONObject();
        try {
            value.put("phase", phase).put("message", message).put("busy", busy.get())
                .put("canRestore", ready && !attempted).put("nativeInvocations", invocations)
                .put("bootPartitionsWritten", false).put("automaticReboot", false);
            if (state != null) value.put("state", new JSONObject(state));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        snapshot = value.toString();
    }

    private void begin(boolean restore) {
        if (!busy.compareAndSet(false, true)) return;
        publish("checking", "正在检查设备、资源和现有 root…", null, false);
        new Thread(() -> {
            Map<String, String> state = null;
            try {
                RecoveryPolicy.require(Process.myUid() == Process.SHELL_UID,
                    "请通过无线调试启动 Shizuku；当前不是 shell 身份。");
                // Procfs files report length 0; read them as streams, never by file length.
                String status = text(new FileInputStream("/proc/self/status"), 32000);
                RecoveryPolicy.require(status.matches("(?s).*\nSeccomp:\\s*0\n.*"),
                    "Shizuku 执行环境仍受 seccomp 过滤，未启动恢复。");
                event("serviceUid=" + Process.myUid() + " clientUid=" + clientUid + " Seccomp=0");
                state = state();
                RecoveryPolicy.supported(state, Supported.KERNEL, Supported.BUILD);
                File manager = managerApk();
                verifyOwnAssets();
                boolean root = root();
                if (RecoveryPolicy.healthy(state, root)) {
                    verifyBootImages(state);
                    publish("active", "临时 root 已正常工作，无需重复恢复。", state, false);
                    return;
                }
                RecoveryPolicy.require("0".equals(state.get("module")) && !root,
                    "已有 root/KernelSU 的状态不完整，请保留现场检查，未重复恢复。");
                RecoveryPolicy.require("Enforcing".equals(state.get("selinux")),
                    "SELinux 状态不符合已验证环境，未启动恢复。");
                if (!restore) {
                    publish("ready", "准备条件通过，可以恢复临时 root。", state, true);
                    return;
                }
                RecoveryPolicy.require(!attempted, "本次开机已经尝试过恢复，不会再次运行。");
                runOnce(state, manager);
            } catch (Exception error) {
                event("ERROR: " + error);
                publish("failed", error.getMessage() == null ? error.toString() : error.getMessage(), state, false);
            } finally {
                busy.set(false);
                try {
                    JSONObject value = new JSONObject(snapshot);
                    value.put("busy", false);
                    snapshot = value.toString();
                    if (work != null) write(new File(work, "result.json"), snapshot.getBytes(StandardCharsets.UTF_8));
                } catch (Exception error) { event("Result save: " + error); }
                if (!attempted || verified) cleanupPayloads();
            }
        }, "ghostlock-honor-restore").start();
    }

    private Map<String, String> state() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : command(15, "/system/bin/sh", "-c", STATE).split("\n")) {
            int index = line.indexOf('=');
            if (index > 0) result.put(line.substring(0, index), line.substring(index + 1));
        }
        return result;
    }

    private boolean root() throws Exception {
        CommandResult result = commandOptional(12, "/system/bin/sh", "-c", "su -c id");
        return result.code == 0 && RecoveryPolicy.rootIdentity(result.output);
    }

    private File managerApk() throws Exception {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(Supported.MANAGER, 0);
        File apk = new File(info.sourceDir);
        RecoveryPolicy.require(apk.isFile() && sha(new FileInputStream(apk)).equals(Supported.MANAGER_SHA),
            "请安装已验证的 KernelSU v3.3.1 官方管理器；当前 APK 不匹配。");
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(Supported.LOADER_ENTRY);
            RecoveryPolicy.require(entry != null && sha(zip.getInputStream(entry)).equals(Supported.LOADER_SHA),
                "KernelSU 内置加载器不匹配，未启动恢复。");
        }
        return apk;
    }

    private void verifyOwnAssets() throws Exception {
        try (ZipFile zip = new ZipFile(context.getApplicationInfo().sourceDir)) {
            for (int i = 0; i < Supported.ASSETS.length; i++) {
                ZipEntry entry = zip.getEntry("assets/honor400/" + Supported.ASSETS[i]);
                RecoveryPolicy.require(entry != null && sha(zip.getInputStream(entry)).equals(Supported.ASSET_SHA[i]),
                    "App 内置资源校验失败：" + Supported.ASSETS[i]);
            }
        }
    }

    private void verifyBootImages(Map<String, String> state) throws Exception {
        String slot = state.get("slot");
        RecoveryPolicy.require("_a".equals(slot) || "_b".equals(slot), "无法确认当前槽位。");
        StringBuilder cmd = new StringBuilder("sha256sum");
        for (String name : Supported.BOOT_NAMES) cmd.append(" /dev/block/by-name/").append(name).append(slot);
        String hashes = command(45, "/system/bin/sh", "-c", "su -c " + quote(cmd.toString()));
        event("Boot image hashes:\n" + hashes);
        for (int i = 0; i < Supported.BOOT_NAMES.length; i++) {
            String expected = Supported.BOOT_SHA[i] + "  /dev/block/by-name/" + Supported.BOOT_NAMES[i] + slot;
            RecoveryPolicy.require(Arrays.asList(hashes.trim().split("\n")).contains(expected),
                "启动分区哈希不匹配或无法读取：" + Supported.BOOT_NAMES[i] + "。请保持开机并核对备份。");
        }
    }

    private void runOnce(Map<String, String> before, File manager) throws Exception {
        bootLock = new File("/data/local/tmp/ghostlock-honor-lock-" + before.get("boot_id"));
        RecoveryPolicy.require(bootLock.mkdir(), "本次开机已有恢复锁，请勿并行运行恢复工具或删除锁重试。");
        ownLock = true;
        command(10, "/system/bin/chmod", "700", bootLock.getPath());
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        work = new File("/data/local/tmp/ghostlock-honor-run-" + token);
        RecoveryPolicy.require(work.mkdir(), "无法创建本次恢复目录。");
        command(10, "/system/bin/chmod", "700", work.getPath());
        try (ZipFile zip = new ZipFile(context.getApplicationInfo().sourceDir)) {
            for (String name : Supported.ASSETS) {
                try (InputStream input = zip.getInputStream(zip.getEntry("assets/honor400/" + name))) {
                    copy(input, new File(work, name));
                }
            }
        }
        try (ZipFile zip = new ZipFile(manager)) {
            ZipEntry entry = zip.getEntry(Supported.LOADER_ENTRY);
            RecoveryPolicy.require(entry != null, "管理器缺少加载器。");
            try (InputStream input = zip.getInputStream(entry)) { copy(input, new File(work, "ksud")); }
        }
        RecoveryPolicy.require(sha(new FileInputStream(new File(work, "ksud"))).equals(Supported.LOADER_SHA),
            "KernelSU 加载器校验失败，未启动恢复。");
        for (String name : new String[] {"ghostlock", "ksud", "handoff.sh"}) {
            command(10, "/system/bin/chmod", "700", new File(work, name).getPath());
        }
        command(10, "/system/bin/sh", "-n", new File(work, "handoff.sh").getPath());
        String launch = "#!/system/bin/sh\ncd " + quote(work.getPath()) + " || exit 90\n"
            + "test \"$(id -u)\" = 2000 || exit 91\n"
            + "test \"$(getenforce)\" = Enforcing || exit 92\n"
            + "test \"$(uname -r)\" = " + quote(Supported.KERNEL) + " || exit 93\n"
            + "if grep -q '^kernelsu ' /proc/modules; then exit 94; fi\n"
            + "test ! -e launch-started || exit 95\necho \"$$\" >launch-started\n"
            + ": >handoff.log\n: >root-handoff.log\n"
            + "exec ./ghostlock --load-prebuilt-profile ./profile.bin\n";
        write(new File(work, "launch.sh"), launch.getBytes(StandardCharsets.UTF_8));
        command(10, "/system/bin/sh", "-n", new File(work, "launch.sh").getPath());
        attempted = true;
        invocations++;
        publish("restoring", "正在恢复临时 root，请等待并保持手机开机。", before, false);
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", new File(work, "launch.sh").getPath())
            .directory(work).redirectErrorStream(true).redirectOutput(new File(work, "native.log"));
        builder.environment().put("GHOSTLOCK_HOME", work.getPath());
        builder.environment().put("TMPDIR", work.getPath());
        builder.environment().put("GHOSTLOCK_KSUD", new File(work, "handoff.sh").getPath());
        builder.environment().put("GHOSTLOCK_KSU_LOG", new File(work, "root-handoff.log").getPath());
        nativeProcess = builder.start();
        // Native output goes directly to disk; UI/Binder never backpressures its pipe.
        RecoveryPolicy.require(nativeProcess.waitFor(600, TimeUnit.SECONDS),
            "等待恢复超时。保留现场，不会终止原生工作线程、重试或自动重启。");
        int code = nativeProcess.exitValue();
        event("NATIVE_EXIT_CODE=" + code);
        RecoveryPolicy.require(code == 0, "原生程序退出码 " + code + "，已保留现场，不会重试或重启。");
        Map<String, String> after = null;
        boolean root = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        do {
            after = state();
            RecoveryPolicy.supported(after, Supported.KERNEL, Supported.BUILD);
            RecoveryPolicy.require(before.get("boot_id").equals(after.get("boot_id")), "恢复期间手机发生了重启。");
            root = root();
            if (RecoveryPolicy.healthy(after, root)) break;
            Thread.sleep(1000);
        } while (System.nanoTime() < deadline);
        RecoveryPolicy.completed(before, after, code, root);
        verifyBootImages(after);
        String allowlist = commandOptional(10, "/system/bin/sh", "-c",
            "su -c 'sha256sum /data/adb/ksu/.allowlist'").output;
        event("Allowlist after:\n" + allowlist);
        File prior = new File(work, "allowlist-before.sha256");
        if (prior.isFile()) {
            String beforeHash = text(new FileInputStream(prior), 1024).trim().split("\\s+")[0];
            RecoveryPolicy.require(beforeHash.matches("[0-9a-f]{64}") && allowlist.startsWith(beforeHash + " "),
                "root 已可用，但授权文件哈希发生变化，请保留日志核对。");
        }
        verified = true;
        publish("restored", "临时 root 已恢复，原有应用授权已保留。", after, false);
    }

    private void cleanupPayloads() {
        if (work != null) {
            // Keep small evidence files. Never recursively remove a directory or touch /data/adb.
            for (String name : new String[] {"ghostlock", "profile.bin", "ksud", "handoff.sh", "launch.sh",
                                             ".ghostlock_root.sh"}) {
                File file = new File(work, name);
                if (file.exists() && !file.delete()) event("Retained temporary file: " + file.getPath());
            }
        }
        if (ownLock && bootLock != null) {
            if (bootLock.delete()) ownLock = false;
            else event("Retained boot lock: " + bootLock.getPath());
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static void copy(InputStream input, File target) throws IOException {
        try (OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }
    private static void write(File file, byte[] bytes) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }
    private static String text(InputStream input, int limit) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Output exceeds limit");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
    private static String sha(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream source = input) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = source.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static String readTail(File file, int limit) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(Math.max(0, input.length() - limit));
            byte[] bytes = new byte[(int)(input.length() - input.getFilePointer())];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    private static final class CommandResult {
        final int code; final String output;
        CommandResult(int code, String output) { this.code = code; this.output = output; }
    }
    private static String command(int timeout, String... argv) throws Exception {
        CommandResult result = commandOptional(timeout, argv);
        RecoveryPolicy.require(result.code == 0, "检查命令失败：" + result.output.trim());
        return result.output;
    }
    private static CommandResult commandOptional(int timeout, String... argv) throws Exception {
        java.lang.Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (output) { if (output.size() < 64000) output.write(buffer, 0, count); }
                }
            } catch (IOException ignored) { }
        }, "root-check-output");
        reader.setDaemon(true); reader.start();
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("检查命令超时；未自动重启。");
        }
        reader.join(1500);
        synchronized (output) { return new CommandResult(process.exitValue(), output.toString("UTF-8")); }
    }
}
