package dev.ghostlock.honor;

public final class PreparationPolicy {
    private PreparationPolicy() {}
    public static boolean prepared(boolean verified, int savedUid, int uid, long setupBoot,
                                   String kernel, String expectedKernel, String nativeHash,
                                   String expectedNative, String profile, String expectedProfile) {
        return verified && savedUid == uid && setupBoot >= 0
            && expectedKernel.equals(kernel) && expectedNative.equals(nativeHash)
            && expectedProfile.equals(profile);
    }
    public static boolean authorizationPending(boolean systemVerified, boolean ownRoot, int suCode) {
        return systemVerified && !ownRoot && (suCode == 1 || suCode == 13 || suCode == 124);
    }
}
