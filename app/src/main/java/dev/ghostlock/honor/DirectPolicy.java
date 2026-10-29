package dev.ghostlock.honor;

import java.util.Map;
import java.util.regex.Pattern;

public final class DirectPolicy {
    private DirectPolicy() { }
    public static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
    public static boolean root(String text) {
        return Pattern.compile("(?m)^uid=0\\(root\\)(?: |$)").matcher(text).find();
    }
    // A zero/error response is inconclusive. In particular Seccomp can hide the driver.
    public static boolean positiveKernelVersion(int code, String output) {
        if(code!=0 || output.contains("blocked by seccomp")) return false;
        java.util.regex.Matcher match=Pattern.compile("(?m)^Kernel Version: ([0-9]{1,9})\\r?$").matcher(output);
        if(!match.find())return false;
        int version=Integer.parseInt(match.group(1));
        return version>0 && !match.find();
    }
    public static void environment(Map<String, String> state, String kernel, String build) {
        publicEnvironment(state,kernel,build);
        require("Enforcing".equals(state.get("selinux")), "尚未确认 SELinux 为 Enforcing，未判为成功。");
    }
    public static void publicEnvironment(Map<String, String> state, String kernel, String build) {
        require(kernel.equals(state.get("release")) && build.equals(state.get("build")), "系统或内核不匹配。");
        require("1".equals(state.get("locked")) && "green".equals(state.get("verified")), "启动验证状态不匹配。");
        require("_a".equals(state.get("slot")) || "_b".equals(state.get("slot")), "无法读取启动槽位。");
        require("1".equals(state.get("boot_completed")), "系统尚未完成启动。");
        require(state.getOrDefault("boot_count", "").matches("[0-9]+"), "无法读取系统开机次数。");
    }
    public static void coldEnvironment(Map<String,String> state,String kernel,String build) {
        publicEnvironment(state,kernel,build);
        require("Enforcing".equals(state.get("selinux")) || ("unknown".equals(state.get("selinux"))
            && "unavailable".equals(state.get("selinux_source")) && "true".equals(state.get("selinux_query_denied"))),
            "SELinux 为 Permissive 或检查出现未验证的错误，未启动恢复。");
        require("false".equals(state.get("own_su_root")) && "127".equals(state.get("own_su_probe_rc")),
            "su 并非明确不可用，先核对现有授权；未重复恢复。");
    }
    public static boolean canDeferModuleCheck(Map<String,String> state) {
        // Only the exact denied-query combination observed on this pinned device is supported.
        // This permits a later privileged check; it does not assert that a module is absent.
        return "false".equals(state.get("module_query_known"))
            && "false".equals(state.get("module_query_loaded"))
            && "false".equals(state.get("modules_readable")) && "true".equals(state.get("modules_query_denied"))
            && "unavailable".equals(state.get("module_query_source"))
            && "0".equals(state.get("loader_probe_rc")) && "true".equals(state.get("loader_probe_seccomp_blocked"))
            && "KernelSU driver install syscall was blocked by seccomp\nKernel Version: 0".equals(state.get("loader_probe_output"));
    }
    public static void sandbox(int uid, int seccomp) {
        require(uid >= 10000 && uid < 20000, "要求主用户的普通 App 身份，拒绝 shell/root 执行环境。");
        require(seccomp == 2, "普通 App 的 Seccomp 状态未匹配，未运行新路径。");
    }
    public static void previousAttemptClosed(boolean registered,boolean terminalReceipt) {
        require(!registered || terminalReceipt,
            "上次原生尝试缺少结束记录；已锁定实验入口，跨重启也不会重试。请保留并导出日志进行核对。");
    }
    public static void reviewAllowed(int attemptUid,int uid,long attemptBoot,long reviewBoot,boolean rootVerified) {
        require(rootVerified && attemptUid==uid && attemptBoot>=0 && reviewBoot>attemptBoot,
            "复核需要同一 App 在后续开机中独立验证已有 root、模块、Enforcing 和启动分区。");
    }
    public static boolean reviewedFailure(int attemptUid,int uid,long attemptBoot,long reviewBoot,long currentBoot,
                                           boolean hashesMatch,boolean rootVerified,boolean futureRequired) {
        return attemptUid==uid && attemptBoot>=0 && reviewBoot>attemptBoot && hashesMatch && rootVerified
            && (futureRequired?currentBoot>reviewBoot:currentBoot>=reviewBoot);
    }
    public static boolean sameEvidenceFingerprint(String recorded,String observed) {
        return recorded!=null && recorded.equals(observed)
            && (recorded.equals("missing") || recorded.matches("[0-9a-f]{64}"));
    }
    public static void eligible(boolean setup, int storedUid, int uid, long setupBoot, long boot,
                                boolean attempted, boolean moduleKnown, boolean moduleLoaded) {
        eligible(setup,storedUid,uid,setupBoot,boot,attempted,moduleKnown,moduleLoaded,false);
    }
    public static void eligible(boolean setup, int storedUid, int uid, long setupBoot, long boot,
                                boolean attempted, boolean moduleKnown, boolean moduleLoaded, boolean deferred) {
        require(setup && storedUid == uid, "请先在已有正常 root 时初始化这个 App。");
        require(boot > setupBoot && setupBoot >= 0, "初始化后需要一次新的正常开机。");
        require(moduleKnown || deferred, "模块状态未知且不符合已验证的受限查询情形，未启动恢复。");
        require(!moduleLoaded, "KernelSU 已加载；先检查本 App 是否仍被授权，未重复恢复。");
        require(!attempted, "本次开机已尝试过，保留现场，不会重试。");
    }
    public static void completed(int code, long before, long after, boolean root,
                                 boolean module, String selinux, boolean hashes, boolean allowlist) {
        require(code == 0 && before == after && root && module && "Enforcing".equals(selinux)
                && hashes && allowlist, "尚未完整验证 root、模块、SELinux、开机次数及哈希；请保留现场。");
    }
    public static boolean preloadReceipt(String receipt,long parentBoot,String slot,String kernelBootId) {
        return parentBoot>=0 && ("_a".equals(slot)||"_b".equals(slot))
            && kernelBootId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            && receipt.equals("uid=0\nseccomp=0\nmodule_absent=1\nparent_boot_count="+parentBoot
                +"\nslot="+slot+"\nkernel_boot_id="+kernelBootId+"\n");
    }
}
