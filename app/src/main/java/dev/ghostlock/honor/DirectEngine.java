package dev.ghostlock.honor;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.provider.Settings;
import android.system.Os;
import android.system.ErrnoException;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

final class DirectEngine {
    private final Context context;
    private final SharedPreferences setup;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String snapshot = "{\"phase\":\"idle\",\"message\":\"请检查设备状态。\",\"busy\":false}";
    private volatile java.lang.Process nativeProcess;
    private Map<String,String> observed;
    private long inspectionNumber;
    private int lastSuCode = -1;
    private File work;
    private File attemptedWork;
    private final Map<String,Long> forwarded = new HashMap<>();
    private final StringBuilder events = new StringBuilder();

    DirectEngine(Context context) {
        this.context = context.getApplicationContext();
        setup = context.getSharedPreferences("verified-setup", Context.MODE_PRIVATE);
        String previous=setup.getString("last_run","");
        if(previous.matches("direct-run-[0-9a-f]{32}")){
            File saved=new File(context.getFilesDir(),previous);
            if(saved.isDirectory())work=saved;
        }
    }
    String snapshot() { return snapshot; }
    boolean nativeAlive() { return nativeProcess != null && nativeProcess.isAlive(); }
    boolean busy() { return busy.get(); }

    void execute(String action, Runnable complete) {
        if (nativeAlive()) { complete.run(); return; }
        if (!busy.compareAndSet(false, true)) { complete.run(); return; }
        observed = null;
        attemptedWork = null;
        inspectionNumber++;
        publish("checking", "正在检查普通 App 环境…", null, false);
        new Thread(() -> {
            try { check(action); }
            catch (Exception error) {
                event("ERROR: " + error);
                publish("stopped", error.getMessage() == null ? error.toString() : error.getMessage(), observed, false);
            } finally {
                try {
                    JSONObject value = new JSONObject(snapshot); value.put("busy", false);
                    value.put("nativeAlive",nativeAlive());
                    snapshot = value.toString();
                    android.util.Log.i("GhostLockDiag","STATE_JSON="+snapshot);
                    forwardNativeLogs();
                    write(new File(context.getFilesDir(), "last-state.json"), snapshot);
                    write(new File(context.getFilesDir(), "last-log.txt"), log());
                    // A rejected request must never turn an older interrupted run into a closed run.
                    if (attemptedWork != null) write(new File(attemptedWork, "result.json"), snapshot);
                } catch (Exception error) { event("Save result: " + error); }
                busy.set(false);
                complete.run();
            }
        }, "direct-root-worker").start();
    }

    private void check(String action) throws Exception {
        Map<String,String> state = state();
        observed = state;
        state.put("setup_verified",String.valueOf(setup.getBoolean("verified",false)));
        state.put("setup_uid_matches",String.valueOf(setup.getInt("uid",-1)==Process.myUid()));
        state.put("setup_boot",String.valueOf(setup.getLong("boot",-1)));
        String bootValue=state.get("boot_count");
        if(bootValue.matches("[0-9]+"))state.put("attempt_marker_exists",String.valueOf(
            new File(context.getFilesDir(),"attempt-boot-"+bootValue).exists()));
        event("Observed state: " + new JSONObject(state));
        DirectPolicy.publicEnvironment(state,Supported.KERNEL,Supported.BUILD);
        String self = read(new File("/proc/self/status"), 32000);
        int seccomp = -1;
        for (String line : self.split("\n")) if (line.startsWith("Seccomp:")) seccomp = Integer.parseInt(line.substring(8).trim());
        DirectPolicy.sandbox(Process.myUid(), seccomp);
        state.put("app_uid",String.valueOf(Process.myUid()));state.put("seccomp",String.valueOf(seccomp));
        event("App uid=" + Process.myUid() + " Seccomp=" + seccomp + " boot_count=" + state.get("boot_count"));
        // The fixed multicast route uses AF_INET. This opens no connection and sends no data.
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket((java.net.SocketAddress)null)) {
            event("Unbound UDP socket creation passed.");
        }
        File nativeFile = new File(context.getApplicationInfo().nativeLibraryDir, "libghostlock.so");
        DirectPolicy.require(nativeFile.isFile() && nativeFile.canExecute() && sha(nativeFile).equals(Supported.NATIVE_SHA),
            "只读原生程序不存在、不可执行或哈希不匹配。");
        // --help exits before loading a profile.
        Result usage=run(10,nativeFile.getPath(),"--help");
        event("Read-only ELF usage probe rc="+usage.code+": "+usage.output.trim());
        DirectPolicy.require((usage.code==1||usage.code==255) && usage.output.contains("usage:")
                && usage.output.contains("--load-prebuilt-profile"),
            "普通 App 无法执行原生入口，未进入恢复。");
        verifyAssets();
        File loader = loader();
        Result version=run(10,loader.getPath(),"debug","version");
        event("KernelSU read-only version probe rc="+version.code+": "+version.output.trim());
        state.put("loader_probe_rc",String.valueOf(version.code));
        state.put("loader_probe_output",version.output.trim());
        state.put("loader_probe_seccomp_blocked",String.valueOf(version.output.contains("blocked by seccomp")));
        if(DirectPolicy.positiveKernelVersion(version.code,version.output)){
            state.put("module_query_known","true");state.put("module_query_loaded","true");
            state.put("module_query_source","official-loader-version");
        }
        boolean root = root();
        state.put("own_su_root",String.valueOf(root));
        state.put("own_su_probe_rc",String.valueOf(lastSuCode));
        if (root) fillRootState(state);
        if (root) {
            DirectPolicy.environment(state, Supported.KERNEL, Supported.BUILD);
            verifyRoot(state);
            if (action.equals("initialize")) {
                boolean saved = setup.edit().putBoolean("verified", true).putInt("uid", Process.myUid())
                    .putLong("boot", Long.parseLong(state.get("boot_count")))
                    .putString("kernel", Supported.KERNEL).putString("native", Supported.NATIVE_SHA)
                    .putString("profile", Supported.PROFILE_SHA).commit();
                DirectPolicy.require(saved, "初始化凭证保存失败。");
                event("Initialized with existing root; no native recovery launched.");
                publish("initialized", "初始化已完成。保留本 App 的 KernelSU 授权；恢复实测需要下一次开机。", state, false);
            } else if(action.equals("review")) {
                int count=reviewInterruptedRuns(state);
                state.put("reviewed_runs",String.valueOf(count));
                publish("reviewed","中断记录已复核并保留原始日志。只有下一次开机才可进行新的单次实验。",state,false);
            } else {
                publish("active", "本 App 的 root 已可用，无需重复恢复。首次使用可点击初始化。", state, false);
            }
            return;
        }
        if (action.equals("initialize")) throw new IllegalStateException(
            "请先用已验证的方法恢复 root，在 KernelSU 中手动授权“GhostLock HONOR”，再初始化。");
        DirectPolicy.require(!action.equals("review"),"当前没有本 App 已获授权的 root，不能复核中断记录。");
        DirectPolicy.coldEnvironment(state,Supported.KERNEL,Supported.BUILD);
        boolean previousRegistered=!setup.getString("last_run","").isEmpty();
        boolean previousTerminal=previousTerminalReceipt(work);
        File[] entries=context.getFilesDir().listFiles();
        DirectPolicy.require(entries!=null,"无法核对历史运行目录，未开放实验。");
        int runCount=0,unresolved=0;
        for(File entry:entries)if(entry.isDirectory()&&entry.getName().matches("direct-run-[0-9a-f]{32}")){
            runCount++;
            boolean terminal=previousTerminalReceipt(entry);
            event("Prior run "+entry.getName()+" terminal="+terminal);
            if(!terminal){unresolved++;work=entry;}
        }
        boolean pending=new File(context.getFilesDir(),"native-attempt.pending").exists();
        state.put("previous_run_registered",String.valueOf(previousRegistered));
        state.put("previous_run_terminal",String.valueOf(previousTerminal));
        state.put("previous_run_count",String.valueOf(runCount));
        state.put("previous_unresolved_runs",String.valueOf(unresolved));
        state.put("pending_attempt_journal",String.valueOf(pending));
        DirectPolicy.previousAttemptClosed(previousRegistered,previousTerminal);
        // SharedPreferences may roll back after an abrupt reset. Audit every run directory as well.
        DirectPolicy.previousAttemptClosed(pending||unresolved>0,false);
        boolean configured = setup.getBoolean("verified", false)
            && Supported.KERNEL.equals(setup.getString("kernel", ""))
            && Supported.NATIVE_SHA.equals(setup.getString("native", ""))
            && Supported.PROFILE_SHA.equals(setup.getString("profile", ""));
        long boot = Long.parseLong(state.get("boot_count"));
        File marker = new File(context.getFilesDir(), "attempt-boot-" + boot);
        boolean moduleKnown=Boolean.parseBoolean(state.get("module_query_known"));
        boolean moduleLoaded=Boolean.parseBoolean(state.get("module_query_loaded"));
        boolean deferred=DirectPolicy.canDeferModuleCheck(state);
        DirectPolicy.eligible(configured, setup.getInt("uid", -1), Process.myUid(),
            setup.getLong("boot", -1), boot, marker.exists(), moduleKnown, moduleLoaded,deferred);
        state.put("cold_contract","ordinary-app-two-phase-v1");
        state.put("module_check_deferred",String.valueOf(!moduleKnown && deferred));
        state.put("selinux_check_deferred",String.valueOf("unknown".equals(state.get("selinux"))));
        event("Cold preflight: protected observations remain unknown; privileged pre-load and strict post-root checks are required.");
        if (!action.equals("restore")) {
            publish("ready", Supported.EXPERIMENTAL
                ? "公开状态及初始化凭证通过；受保护状态仍待加载前/恢复后核对。可进行单次实验，可能失败或重启。"
                : "分阶段前置检查通过，受保护状态仍未知。本检查版未开放实验恢复。", state, Supported.EXPERIMENTAL);
            return;
        }
        DirectPolicy.require(Supported.EXPERIMENTAL, "此安装包只开放检查与初始化，实验恢复开关尚未启用。");
        DirectPolicy.require(marker.createNewFile(), "本次开机已有尝试标记，未重复恢复。");
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(("native-launch-reserved\n" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        work = new File(context.getFilesDir(), "direct-run-" + UUID.randomUUID().toString().replace("-", ""));
        DirectPolicy.require(work.mkdir(), "无法创建私有恢复目录，已保留尝试标记。");
        attemptedWork=work;
        DirectPolicy.require(setup.edit().putString("last_run",work.getName()).commit(),"运行证据目录登记失败，未启动原生程序。");
        for (String name : new String[]{"profile.bin", "handoff.sh"}) {
            try (InputStream input = context.getAssets().open(name); FileOutputStream output = new FileOutputStream(new File(work,name))) {
                transfer(input, output);
                output.getFD().sync();
            }
        }
        File handoff = new File(work, "handoff.sh");
        DirectPolicy.require(handoff.setExecutable(true, true), "无法设置私有脚本权限。");
        syncDirectory(work);
        publish("restoring", "正在执行独立恢复。请保持 App 打开，不要强行停止或并行运行其他恢复工具。", state, false);
        write(new File(work,"preflight.json"),snapshot);
        write(new File(context.getFilesDir(),"last-state.json"),snapshot);
        event("NATIVE_LAUNCH_RESERVED boot_count="+boot+" uid="+Process.myUid());
        File pendingJournal=new File(context.getFilesDir(),"native-attempt.pending");
        DirectPolicy.require(pendingJournal.createNewFile(),"存在尚未核对的原生尝试记录，未启动恢复。");
        write(pendingJournal,snapshot);
        syncDirectory(context.getFilesDir());
        ProcessBuilder builder = new ProcessBuilder(nativeFile.getPath(), "--load-prebuilt-profile", new File(work,"profile.bin").getPath())
            .directory(work).redirectErrorStream(true).redirectOutput(new File(work,"native.log"));
        builder.environment().put("GHOSTLOCK_HOME", work.getPath());
        builder.environment().put("TMPDIR", work.getPath());
        builder.environment().put("GHOSTLOCK_KSUD", handoff.getPath());
        builder.environment().put("GHOSTLOCK_KSU_LOG", new File(work,"root-handoff.log").getPath());
        builder.environment().put("DIRECT_KSUD", loader.getPath());
        builder.environment().put("DIRECT_BOOT_COUNT",String.valueOf(boot));
        builder.environment().put("DIRECT_SLOT",state.get("slot"));
        nativeProcess = builder.start();
        event("NATIVE_STARTED");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(600);
        while(!nativeProcess.waitFor(1,TimeUnit.SECONDS)){
            forwardNativeLogs();
            DirectPolicy.require(System.nanoTime()<deadline,"原生程序超时，保持现场；不会杀进程、重试或重启。");
        }
        forwardNativeLogs();
        int code = nativeProcess.exitValue(); event("NATIVE_EXIT_CODE=" + code);
        DirectPolicy.require(code == 0, "原生退出码 " + code + "。保留现场，不会重试。");
        Map<String,String> after = state();
        observed = after;
        boolean rootAfter = waitOwnRoot(boot);
        after.put("own_su_root",String.valueOf(rootAfter));
        after.put("own_su_probe_rc",String.valueOf(lastSuCode));
        boolean verified = false;
        if (rootAfter) {
            fillRootState(after);
            DirectPolicy.environment(after, Supported.KERNEL, Supported.BUILD);
            verifyRoot(after); verified = true;
        }
        if(!rootAfter){
            File handoffLog=new File(work,"handoff.log");
            String detail=handoffLog.isFile()?tail(handoffLog,2000).trim():"没有加载入口日志";
            throw new IllegalStateException("原生进程已结束，但本 App 的 su 仍不可用，未判为成功。加载入口："+detail);
        }
        String preload=read(new File(work,"preload-verified.txt"),1024);
        Result bootIdentity=run(10,"/system/bin/sh","-c","su -c 'cat /proc/sys/kernel/random/boot_id'");
        DirectPolicy.require(bootIdentity.code==0 && DirectPolicy.preloadReceipt(preload,boot,state.get("slot"),bootIdentity.output.trim()),
            "缺少匹配本次开机的加载前校验证据，未判为成功。");
        event("Privileged pre-load receipt:\n"+preload);
        String prior = read(new File(work,"allowlist-before.sha256"), 1024).trim().split("\\s+")[0];
        DirectPolicy.require(prior.matches("[0-9a-f]{64}"), "缺少原有授权文件的哈希，未判为成功。");
        String current = rootAfter ? run(15,"/system/bin/sh","-c","su -c 'sha256sum /data/adb/ksu/.allowlist'").output : "";
        boolean allowlist = current.startsWith(prior + " ");
        DirectPolicy.completed(code, boot, Long.parseLong(after.get("boot_count")), rootAfter, verified,
            after.get("selinux"), verified, allowlist);
        DirectPolicy.require(pendingJournal.delete(),"root 已核对，但待核对标记无法关闭；保留日志。");
        syncDirectory(context.getFilesDir());
        publish("restored", "独立恢复已通过身份、模块、SELinux 和哈希验证。", after, false);
    }

    private void verifyRoot(Map<String,String> state) throws Exception {
        DirectPolicy.require(root(), "本 App 尚未获得 KernelSU 授权。");
        String script = "id; getenforce; grep '^kernelsu ' /proc/modules; sha256sum";
        for (String name : Supported.BOOT_NAMES) script += " /dev/block/by-name/" + name + state.get("slot");
        Result result = run(45,"/system/bin/sh","-c","su -c " + quote(script));
        DirectPolicy.require(result.code == 0 && DirectPolicy.root(result.output)
            && result.output.contains("\nEnforcing\n") && result.output.matches("(?s).*\nkernelsu [^\n]*.*"),
            "未独立确认 root、KernelSU 模块与 Enforcing。");
        for(int i=0;i<Supported.BOOT_NAMES.length;i++) DirectPolicy.require(result.output.contains(
            Supported.BOOT_SHA[i]+"  /dev/block/by-name/"+Supported.BOOT_NAMES[i]+state.get("slot")), "启动分区哈希不匹配。");
        state.put("module_query_known","true");state.put("module_query_loaded","true");
        state.put("module_query_source","this-app-authorized-su");state.put("boot_hashes_verified","true");
        event("Independent root verification:\n" + result.output);
    }
    private boolean root() throws Exception {
        Result result = run(12,"/system/bin/sh","-c","su -c id");
        lastSuCode=result.code;
        event("Own su identity probe rc="+result.code+": "+result.output.trim());
        return result.code == 0 && DirectPolicy.root(result.output);
    }
    private boolean waitOwnRoot(long boot)throws Exception {
        // KernelSU late-load daemonizes. Wait only for read-only verification, never relaunch native.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        do{
            DirectPolicy.require(Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT)==boot,
                "验证期间开机次数变化，未重复恢复。");
            if(root())return true;
            Thread.sleep(1000);
        }while(System.nanoTime()<deadline);
        return false;
    }
    private Map<String,String> state() throws Exception {
        Map<String,String> state = new LinkedHashMap<>();
        observed = state;
        state.put("release",System.getProperty("os.version",""));
        state.put("build",android.os.Build.DISPLAY);
        try {
            state.put("boot_count",String.valueOf(Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT)));
        } catch (Exception unavailable) {
            state.put("boot_count","unknown");event("BOOT_COUNT: "+unavailable);
        }
        Result enforce=run(10,"/system/bin/getenforce");
        state.put("selinux",enforce.code==0?enforce.output.trim():"unknown");
        state.put("selinux_source",enforce.code==0?"ordinary-app-command":"unavailable");
        state.put("selinux_query_denied",String.valueOf(enforce.code!=0 && enforce.output.contains("Permission denied")));
        if(enforce.code!=0)event("Ordinary App getenforce: "+enforce.output.trim());
        // A readable status page is an independent public read; do not infer Enforcing from a denial.
        if(enforce.code!=0)try{
            byte[] first=statusPage(),second=statusPage();
            java.nio.ByteBuffer one=java.nio.ByteBuffer.wrap(first).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            java.nio.ByteBuffer two=java.nio.ByteBuffer.wrap(second).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int version=one.getInt(0),sequence=one.getInt(4),mode=one.getInt(8);
            if(version==1&&sequence%2==0&&sequence==two.getInt(4)&&(mode==0||mode==1)){
                state.put("selinux",mode==1?"Enforcing":"Permissive");
                state.put("selinux_source","ordinary-app-status-page");
            }
        }catch(Exception unavailable){event("Ordinary App SELinux status page: "+unavailable);}
        for(String[] pair:new String[][]{{"locked","ro.boot.flash.locked"},{"verified","ro.boot.verifiedbootstate"},
                {"slot","ro.boot.slot_suffix"},{"boot_completed","sys.boot_completed"}}){
            Result result=run(10,"/system/bin/getprop",pair[1]);
            state.put(pair[0],result.code==0?result.output.trim():"unknown");
            if(result.code!=0)event("Ordinary App property "+pair[1]+": "+result.output.trim());
        }
        boolean known=false,loaded=false;
        String source="unavailable";
        try{
            String modules=read(new File("/proc/modules"),512000);
            state.put("modules_readable","true");known=true;source="proc-modules";
            loaded=modules.matches("(?s).*\\bkernelsu [^\n]*.*");
        }catch(IOException denied){
            state.put("modules_readable","false");
            state.put("modules_query_denied",String.valueOf(denied.toString().contains("EACCES")));
            event("Ordinary App /proc/modules: "+denied);
        }
        try{
            Os.stat("/sys/module");
            try{
                Os.stat("/sys/module/kernelsu");known=true;loaded=true;source="sysfs-module-present";
            }catch(ErrnoException absent){
                if(absent.errno==OsConstants.ENOENT){
                    // This device hides the module's sysfs directory even when loaded.
                    // Record the missing path; never treat it as proof of module absence.
                    state.put("sysfs_module_path","missing");
                }else event("Ordinary App module stat: errno="+absent.errno);
            }
        }catch(ErrnoException denied){event("Ordinary App sysfs parent stat: errno="+denied.errno);}
        state.put("module_query_known",String.valueOf(known));state.put("module_query_loaded",String.valueOf(loaded));
        state.put("module_query_source",source);
        return state;
    }
    private static byte[] statusPage() throws IOException {
        byte[] bytes=new byte[20];
        try(DataInputStream input=new DataInputStream(new FileInputStream("/sys/fs/selinux/status"))){input.readFully(bytes);}
        return bytes;
    }
    private void fillRootState(Map<String,String> state) throws Exception {
        // Only after this App's own su request succeeded. No shell bridge or global grant is used.
        String script="printf 'selinux=%s\\n' \"$(getenforce)\"; "
            +"printf 'locked=%s\\n' \"$(getprop ro.boot.flash.locked)\"; "
            +"printf 'verified=%s\\n' \"$(getprop ro.boot.verifiedbootstate)\"; "
            +"printf 'slot=%s\\n' \"$(getprop ro.boot.slot_suffix)\"; "
            +"printf 'boot_completed=%s\\n' \"$(getprop sys.boot_completed)\"";
        Result result=run(15,"/system/bin/sh","-c","su -c "+quote(script));
        DirectPolicy.require(result.code==0,"无法用本 App 已获授权的 su 核对状态。");
        for(String line:result.output.split("\n")){
            int index=line.indexOf('=');if(index>0)state.put(line.substring(0,index),line.substring(index+1));
        }
        state.put("selinux_source","this-app-authorized-su");
        event("Existing/post-load root state: "+new JSONObject(state));
    }
    private File loader() throws Exception {
        ApplicationInfo manager = context.getPackageManager().getApplicationInfo(Supported.MANAGER,0);
        DirectPolicy.require(sha(new File(manager.sourceDir)).equals(Supported.MANAGER_SHA), "KernelSU 官方管理器 APK 不匹配。");
        File loader = new File(manager.nativeLibraryDir,"libksud.so");
        DirectPolicy.require(loader.isFile() && loader.canExecute() && sha(loader).equals(Supported.LOADER_SHA),
            "管理器的只读加载器无法访问或哈希不匹配。");
        return loader;
    }
    private boolean originalTerminalReceipt(File run) {
        if(run==null)return false;
        try{
            JSONObject value=new JSONObject(read(new File(run,"result.json"),32000));
            String phase=value.optString("phase");
            return (phase.equals("stopped")||phase.equals("restored"))
                && value.has("busy") && !value.optBoolean("busy",true) && !value.optBoolean("nativeAlive",false);
        }catch(Exception unavailable){event("Previous result receipt unavailable: "+unavailable);return false;}
    }
    private boolean previousTerminalReceipt(File run) {
        if(originalTerminalReceipt(run))return true;
        return reviewedReceipt(run,Long.parseLong(observed.get("boot_count")),true);
    }
    private boolean reviewedReceipt(File run,long currentBoot,boolean futureRequired) {
        if(run==null||!new File(run,"review.json").isFile())return false;
        try{
            JSONObject review=new JSONObject(read(new File(run,"review.json"),32000));
            JSONObject prior=new JSONObject(read(new File(run,"preflight.json"),32000)).getJSONObject("state");
            boolean hashes=review.getInt("schema")==1 && review.getString("outcome").equals("reviewed-interruption")
                && review.getString("kernel").equals(Supported.KERNEL)
                && review.getString("native_sha256").equals(Supported.NATIVE_SHA)
                && review.getString("profile_sha256").equals(Supported.PROFILE_SHA)
                && DirectPolicy.sameEvidenceFingerprint(review.getString("recorded_profile_sha256"),evidenceFingerprint(new File(run,"profile.bin")))
                && review.getString("preflight_sha256").equals(sha(new File(run,"preflight.json")))
                && review.getString("native_log_sha256").equals(sha(new File(run,"native.log")))
                && review.getLong("attempt_boot")==Long.parseLong(prior.getString("boot_count"))
                && review.getInt("uid")==Integer.parseInt(prior.getString("app_uid"));
            return DirectPolicy.reviewedFailure(review.getInt("uid"),Process.myUid(),review.getLong("attempt_boot"),
                review.getLong("review_boot"),currentBoot,hashes,review.getBoolean("root_verified"),futureRequired);
        }catch(Exception invalid){event("Review receipt invalid: "+invalid);return false;}
    }
    private int reviewInterruptedRuns(Map<String,String> state)throws Exception {
        DirectPolicy.require(!new File(context.getFilesDir(),"native-attempt.pending").exists(),
            "存在新式待核对日志，本次历史记录复核流程不清除它。");
        File[] runs=context.getFilesDir().listFiles();DirectPolicy.require(runs!=null,"无法读取历史运行目录。");
        int count=0;long boot=Long.parseLong(state.get("boot_count"));
        for(File run:runs)if(run.isDirectory()&&run.getName().matches("direct-run-[0-9a-f]{32}")&&!originalTerminalReceipt(run)){
            if(reviewedReceipt(run,boot,false)){count++;continue;}
            File receipt=new File(run,"review.json");DirectPolicy.require(!receipt.exists(),"已有复核文件不匹配，保留现场，不覆盖。");
            JSONObject prior=new JSONObject(read(new File(run,"preflight.json"),32000)).getJSONObject("state");
            long attemptBoot=Long.parseLong(prior.getString("boot_count"));int uid=Integer.parseInt(prior.getString("app_uid"));
            DirectPolicy.reviewAllowed(uid,Process.myUid(),attemptBoot,boot,
                "true".equals(state.get("boot_hashes_verified"))&&"Enforcing".equals(state.get("selinux")));
            DirectPolicy.require(Supported.KERNEL.equals(prior.getString("release"))&&Supported.BUILD.equals(prior.getString("build")),
                "中断记录的系统不匹配。");
            JSONObject review=new JSONObject().put("schema",1).put("outcome","reviewed-interruption")
                .put("uid",uid).put("attempt_boot",attemptBoot).put("review_boot",boot).put("root_verified",true)
                .put("kernel",Supported.KERNEL).put("native_sha256",Supported.NATIVE_SHA).put("profile_sha256",Supported.PROFILE_SHA)
                .put("recorded_profile_sha256",evidenceFingerprint(new File(run,"profile.bin")))
                .put("preflight_sha256",sha(new File(run,"preflight.json"))).put("native_log_sha256",sha(new File(run,"native.log")))
                .put("reviewed_at_ms",System.currentTimeMillis());
            write(receipt,review.toString());syncDirectory(run);
            DirectPolicy.require(reviewedReceipt(run,boot,false),"复核记录读回验证失败。");
            event("REVIEW_SAVED attempt_boot="+attemptBoot+" review_boot="+boot+"; original result/logs preserved.");count++;
            work=run;
        }
        return count;
    }
    private static String evidenceFingerprint(File file)throws Exception {
        try{Os.stat(file.getPath());}
        catch(ErrnoException unavailable){if(unavailable.errno==OsConstants.ENOENT)return "missing";throw unavailable;}
        return sha(file);
    }
    private void verifyAssets() throws Exception {
        for(int i=0;i<Supported.ASSETS.length;i++) {
            try(InputStream input=context.getAssets().open(Supported.ASSETS[i])) {
                DirectPolicy.require(sha(input).equals(Supported.ASSET_SHA[i]), "内置资源校验失败。");
            }
        }
    }
    String log() {
        StringBuilder text = new StringBuilder(snapshot).append('\n');
        synchronized(events) { text.append(events); }
        if(work!=null) for(String name:new String[]{"preflight.json","result.json","review.json","preload-verified.txt","native.log","handoff.log","root-handoff.log","allowlist-before.sha256","boot-before.sha256"}) {
            File file=new File(work,name);
            if(file.isFile()) try { text.append("\n--- ").append(name).append(" ---\n").append(tail(file,50000)); }
            catch(IOException error) { text.append(error); }
        }
        return text.toString();
    }
    private void forwardNativeLogs() {
        if(work==null)return;
        for(String name:new String[]{"native.log","handoff.log","root-handoff.log"}){
            File file=new File(work,name);long size=file.length(),offset=forwarded.getOrDefault(file.getPath(),0L);
            if(size==offset)continue;
            if(size<offset)offset=0;
            try(RandomAccessFile input=new RandomAccessFile(file,"r")){
                int length=(int)Math.min(16000,size-offset);byte[] bytes=new byte[length];
                input.seek(offset);input.readFully(bytes);
                event("RUN_LOG "+work.getName()+"/"+name+" offset="+offset+" bytes="+size+" data:\n"+new String(bytes,StandardCharsets.UTF_8));
                forwarded.put(file.getPath(),offset+length);
            }
            catch(IOException error){event("Cannot forward "+name+": "+error);}
        }
    }
    private void event(String value) {
        synchronized(events) { events.append(value).append('\n'); if(events.length()>32000) events.delete(0,events.length()-32000); }
        for(int offset=0;offset<value.length();offset+=3000)
            android.util.Log.i("GhostLockDiag",value.substring(offset,Math.min(value.length(),offset+3000)));
    }
    private void publish(String phase,String message,Map<String,String> state,boolean ready) {
        JSONObject value=new JSONObject();
        try {
            value.put("phase",phase).put("message",message).put("busy",busy.get()).put("canRestore",ready)
                .put("inspectionNumber",inspectionNumber)
                .put("experimentalEnabled",Supported.EXPERIMENTAL).put("noShizuku",true).put("bootPartitionsWritten",false);
            if(state!=null)value.put("state",new JSONObject(state));
        } catch(Exception error) { throw new IllegalStateException(error); }
        snapshot=value.toString();
    }
    private static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    private static void transfer(InputStream in,OutputStream out)throws IOException {
        byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
    }
    private static void write(File file,String value)throws IOException {
        try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }
    private static void syncDirectory(File directory)throws ErrnoException {
        FileDescriptor descriptor=Os.open(directory.getPath(),OsConstants.O_RDONLY,0);
        try{Os.fsync(descriptor);}finally{Os.close(descriptor);}
    }
    private static String read(File file,int cap)throws IOException {
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>cap)throw new IOException("File too large");out.write(b,0,n);}
            return out.toString("UTF-8");
        }
    }
    private static String tail(File file,int cap)throws IOException {
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            long end=in.length();in.seek(Math.max(0,end-cap));byte[] b=new byte[(int)(end-in.getFilePointer())];in.readFully(b);
            return new String(b,StandardCharsets.UTF_8);
        }
    }
    private static String sha(File file)throws Exception { return sha(new FileInputStream(file)); }
    private static String sha(InputStream input)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=input){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private static final class Result { final int code;final String output;Result(int code,String output){this.code=code;this.output=output;} }
    private static String checked(int timeout,String... args)throws Exception {
        Result r=run(timeout,args);DirectPolicy.require(r.code==0,"检查命令失败："+r.output);return r.output;
    }
    private static Result run(int timeout,String... args)throws Exception {
        java.lang.Process child=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{
            try(InputStream in=child.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)
                synchronized(bytes){if(bytes.size()<128000)bytes.write(b,0,n);}}
            catch(IOException ignored){}
        },"direct-check-output");drain.setDaemon(true);drain.start();
        if(!child.waitFor(timeout,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("检查命令超时；未重启。");}
        drain.join(1500);synchronized(bytes){return new Result(child.exitValue(),bytes.toString("UTF-8"));}
    }
}
