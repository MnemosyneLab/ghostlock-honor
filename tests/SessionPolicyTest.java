package dev.ghostlock.honor;

public final class SessionPolicyTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        check(SessionPolicy.blocked(false, false, -1, -1, 46).isEmpty());
        check(!SessionPolicy.blocked(true, false, -1, -1, 46).isEmpty());
        check(!SessionPolicy.blocked(false, false, -1, -1, -1).isEmpty());
        check(!SessionPolicy.blocked(false, true, 46, -1, 46).isEmpty());
        check(!SessionPolicy.blocked(false, true, 46, -1, 47).isEmpty());
        check(!SessionPolicy.blocked(false, false, 46, -1, 46).isEmpty());
        check(!SessionPolicy.blocked(false, false, 47, -1, 46).isEmpty());
        check(SessionPolicy.blocked(false, false, 46, -1, 47).isEmpty());
        check(!SessionPolicy.blocked(false, false, 46, 47, 47).isEmpty());
        check(SessionPolicy.blocked(false, false, 46, 47, 48).isEmpty());
        check(!SessionPolicy.reviewAllowed(46,46,true));
        check(!SessionPolicy.reviewAllowed(46,47,false));
        check(!SessionPolicy.reviewAllowed(-1,47,true));
        check(SessionPolicy.reviewAllowed(46,47,true));
        check(SessionPolicy.success("restored"));
        check(SessionPolicy.success("active"));
        check(!SessionPolicy.success("ready"));
        check(!SessionPolicy.success("failed"));
        check(!SessionPolicy.terminal("restoring"));
        check(SessionPolicy.terminal("failed"));
        check(SessionPolicy.legacyBlocked(false,false,false,false).isEmpty());
        check(!SessionPolicy.legacyBlocked(true,false,true,false).isEmpty());
        check(!SessionPolicy.legacyBlocked(false,true,false,false).isEmpty());
        check(!SessionPolicy.legacyBlocked(false,true,true,true).isEmpty());
        check(SessionPolicy.legacyBlocked(false,true,true,false).isEmpty());
        check(SessionPolicy.rescueAllowed(false,true,54,55,false));
        check(!SessionPolicy.rescueAllowed(true,true,54,55,false));
        check(!SessionPolicy.rescueAllowed(false,false,54,55,false));
        check(!SessionPolicy.rescueAllowed(false,true,54,54,false));
        check(!SessionPolicy.rescueAllowed(false,true,55,54,false));
        check(!SessionPolicy.rescueAllowed(false,true,-1,55,false));
        check(!SessionPolicy.rescueAllowed(false,true,54,55,true));
        System.out.println(checks + " unified session checks passed");
    }
}
