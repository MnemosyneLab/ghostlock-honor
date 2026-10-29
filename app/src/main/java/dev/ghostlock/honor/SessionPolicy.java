package dev.ghostlock.honor;

public final class SessionPolicy {
    private SessionPolicy() {}

    public static String blocked(boolean busy, boolean pending, long attemptedBoot,
                                 long reviewedBoot, long boot) {
        if (busy) return "有操作正在进行，请等待完成。";
        if (boot < 0) return "无法确认本次开机，请先检查设备状态。";
        if (pending) return "上次恢复尚未结束。重启后可改用 Shizuku，或手动清除恢复标记。";
        if (attemptedBoot >= boot) return "本次开机已提交恢复请求，或开机计数回退；不会换用另一种方式重试。";
        if (reviewedBoot >= boot) return "复核已保存，请在下次正常开机后检查状态。";
        return "";
    }

    public static boolean reviewAllowed(long attempt, long boot, boolean verified) {
        return attempt >= 0 && boot > attempt && verified;
    }
    public static boolean rescueAllowed(boolean busy, boolean interrupted, long attempt, long boot, boolean sameBootMarker) {
        return !busy && interrupted && attempt>=0 && boot>attempt && !sameBootMarker;
    }

    public static String legacyBlocked(boolean journal, boolean runs, boolean audited, boolean unresolved) {
        if (journal) return "仍有中断记录，可改用 Shizuku 或手动清除恢复标记。";
        if (runs && (!audited || unresolved))
            return "旧版运行记录尚未完整核对，请在独立恢复中检查状态；不能切换方式重试。";
        return "";
    }

    public static boolean terminal(String phase) {
        return phase.equals("active") || phase.equals("restored") || phase.equals("stopped")
            || phase.equals("failed") || phase.equals("initialized") || phase.equals("reviewed")
            || phase.equals("ready");
    }

    public static boolean success(String phase) {
        return phase.equals("active") || phase.equals("restored");
    }
}
