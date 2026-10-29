package dev.ghostlock.honor.shizuku;

import java.util.HashMap;
import java.util.Map;

public final class RecoveryPolicyTest {
    private static int checks;
    private static Map<String, String> state() {
        Map<String, String> s = new HashMap<>();
        s.put("release", "kernel"); s.put("build", "build"); s.put("boot_completed", "1");
        s.put("locked", "1"); s.put("verified", "green"); s.put("slot", "_a");
        s.put("boot_id", "12345678-1234-1234-1234-123456789abc");
        s.put("module", "1"); s.put("selinux", "Enforcing");
        return s;
    }
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
        checks++;
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Unsafe state was accepted");
    }
    public static void main(String[] args) {
        RecoveryPolicy.supported(state(), "kernel", "build"); checks++;
        for (String key : new String[] {"release", "build", "boot_completed", "locked", "verified", "slot", "boot_id"}) {
            Map<String, String> s = state(); s.put(key, "unexpected");
            rejected(() -> RecoveryPolicy.supported(s, "kernel", "build"));
        }
        check(RecoveryPolicy.rootIdentity("uid=0(root) gid=0(root) context=u:r:ksu:s0\n"));
        check(!RecoveryPolicy.rootIdentity("child uid = 0\nKernelSU ready\n"));
        check(!RecoveryPolicy.rootIdentity("uid=2000(shell) gid=2000(shell)\n"));
        check(!RecoveryPolicy.healthy(state(), false));
        Map<String, String> permissive = state(); permissive.put("selinux", "Permissive");
        check(!RecoveryPolicy.healthy(permissive, true));
        Map<String, String> unloaded = state(); unloaded.put("module", "0");
        check(!RecoveryPolicy.healthy(unloaded, true));
        RecoveryPolicy.completed(state(), state(), 0, true); checks++;
        rejected(() -> RecoveryPolicy.completed(state(), state(), 1, true));
        rejected(() -> RecoveryPolicy.completed(state(), unloaded, 0, true));
        rejected(() -> RecoveryPolicy.completed(state(), permissive, 0, true));
        Map<String, String> rebooted = state(); rebooted.put("boot_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        rejected(() -> RecoveryPolicy.completed(state(), rebooted, 0, true));
        System.out.println("Recovery policy checks passed: " + checks);
    }
}
