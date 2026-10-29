package dev.ghostlock.honor.shizuku;

import java.util.Map;
import java.util.regex.Pattern;

public final class RecoveryPolicy {
    private static final Pattern UUID = Pattern.compile(
        "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    private RecoveryPolicy() { }

    public static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    public static void supported(Map<String, String> state, String kernel, String build) {
        require(kernel.equals(state.get("release")), "内核版本不匹配，未启动恢复。");
        require(build.equals(state.get("build")), "系统版本不匹配，未启动恢复。");
        require("1".equals(state.get("boot_completed")), "系统尚未完成启动。");
        require("1".equals(state.get("locked")) && "green".equals(state.get("verified")),
                "启动锁定/验证状态不符合已验证环境。");
        String slot = state.get("slot");
        require("_a".equals(slot) || "_b".equals(slot), "无法识别当前槽位。");
        require(UUID.matcher(state.getOrDefault("boot_id", "")).matches(), "无法识别本次开机。");
    }

    public static boolean rootIdentity(String text) {
        return Pattern.compile("(?m)^uid=0\\(root\\)(?: |$)").matcher(text).find();
    }

    public static boolean healthy(Map<String, String> state, boolean root) {
        return root && "1".equals(state.get("module"))
            && "Enforcing".equals(state.get("selinux"));
    }

    public static void completed(Map<String, String> before, Map<String, String> after,
                                 int exitCode, boolean root) {
        require(exitCode == 0, "原生程序未成功退出，不会重复尝试。");
        require(before.get("boot_id").equals(after.get("boot_id")), "恢复期间发生重启，不会重复尝试。");
        require(healthy(after, root), "尚未独立确认 root、KernelSU 和 Enforcing。");
    }
}
