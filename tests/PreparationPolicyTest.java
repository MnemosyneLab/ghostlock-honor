package dev.ghostlock.honor;

public final class PreparationPolicyTest {
    private static int count;
    private static void check(boolean ok) { count++; if(!ok) throw new AssertionError("preparation check "+count); }
    private static boolean prepared(boolean v,int saved,int uid,long boot,String k,String n,String p) {
        return PreparationPolicy.prepared(v,saved,uid,boot,k,"kernel",n,"native",p,"profile");
    }
    public static void main(String[] args) {
        check(prepared(true,10332,10332,42,"kernel","native","profile"));
        check(!prepared(false,10332,10332,42,"kernel","native","profile"));
        check(!prepared(true,10332,10333,42,"kernel","native","profile"));
        check(!prepared(true,10332,10332,-1,"kernel","native","profile"));
        check(!prepared(true,10332,10332,42,"old-kernel","native","profile"));
        check(!prepared(true,10332,10332,42,"kernel","old-native","profile"));
        check(!prepared(true,10332,10332,42,"kernel","native","old-profile"));
        check(PreparationPolicy.authorizationPending(true,false,1));
        check(PreparationPolicy.authorizationPending(true,false,124));
        check(!PreparationPolicy.authorizationPending(false,false,1));
        check(!PreparationPolicy.authorizationPending(true,true,1));
        check(!PreparationPolicy.authorizationPending(true,false,127));
        check(!PreparationPolicy.authorizationPending(true,false,0));
        check(!PreparationPolicy.authorizationPending(true,false,42));
        System.out.println(count+" preparation state checks passed");
    }
}
