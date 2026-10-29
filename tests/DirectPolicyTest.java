package dev.ghostlock.honor;
import java.util.*;

public final class DirectPolicyTest {
    private static int count;
    private static void yes(boolean b) { if (!b) throw new AssertionError(); count++; }
    private static void no(Runnable r) {
        try { r.run(); } catch (IllegalStateException expected) { count++; return; }
        throw new AssertionError("Unsafe condition accepted");
    }
    private static Map<String,String> state() {
        Map<String,String> s = new HashMap<>();
        s.put("release","k");s.put("build","b");s.put("selinux","Enforcing");s.put("locked","1");
        s.put("verified","green");s.put("slot","_a");s.put("boot_completed","1");s.put("boot_count","42");
        return s;
    }
    private static Map<String,String> cold() {
        Map<String,String> s=state();s.put("selinux","unknown");s.put("selinux_source","unavailable");
        s.put("selinux_query_denied","true");s.put("own_su_root","false");s.put("own_su_probe_rc","127");
        s.put("module_query_known","false");s.put("module_query_loaded","false");s.put("modules_readable","false");
        s.put("modules_query_denied","true");s.put("module_query_source","unavailable");s.put("loader_probe_rc","0");
        s.put("loader_probe_seccomp_blocked","true");
        s.put("loader_probe_output","KernelSU driver install syscall was blocked by seccomp\nKernel Version: 0");
        return s;
    }
    public static void main(String[] args) {
        DirectPolicy.environment(state(),"k","b");count++;
        for(String key:state().keySet()) {
            Map<String,String> s=state();s.remove(key);no(()->DirectPolicy.environment(s,"k","b"));
        }
        DirectPolicy.sandbox(10290,2);count++;
        for(int uid:new int[]{0,1000,2000,20000,1010290}) no(()->DirectPolicy.sandbox(uid,2));
        no(()->DirectPolicy.sandbox(10290,0));no(()->DirectPolicy.sandbox(10290,1));
        DirectPolicy.previousAttemptClosed(false,false);count++;
        DirectPolicy.previousAttemptClosed(true,true);count++;
        // This condition has no boot-number exception: an interrupted run stays blocked after reboot.
        no(()->DirectPolicy.previousAttemptClosed(true,false));
        DirectPolicy.reviewAllowed(10290,10290,44,45,true);count++;
        no(()->DirectPolicy.reviewAllowed(10290,10290,44,44,true));
        no(()->DirectPolicy.reviewAllowed(10291,10290,44,45,true));
        no(()->DirectPolicy.reviewAllowed(10290,10290,44,45,false));
        yes(DirectPolicy.reviewedFailure(10290,10290,44,45,46,true,true,true));
        yes(!DirectPolicy.reviewedFailure(10290,10290,44,45,45,true,true,true));
        yes(DirectPolicy.reviewedFailure(10290,10290,44,45,45,true,true,false));
        yes(!DirectPolicy.reviewedFailure(10290,10290,44,45,46,false,true,true));
        yes(!DirectPolicy.reviewedFailure(10290,10290,44,45,46,true,false,true));
        yes(!DirectPolicy.reviewedFailure(10291,10290,44,45,46,true,true,true));
        yes(!DirectPolicy.reviewedFailure(10290,10290,44,44,46,true,true,true));
        String zeroHash=String.join("",Collections.nCopies(64,"0"));
        yes(DirectPolicy.sameEvidenceFingerprint("missing","missing"));
        yes(!DirectPolicy.sameEvidenceFingerprint("missing",zeroHash));
        yes(DirectPolicy.sameEvidenceFingerprint(zeroHash,zeroHash));
        yes(!DirectPolicy.sameEvidenceFingerprint("Permission denied","Permission denied"));
        yes(!DirectPolicy.sameEvidenceFingerprint(zeroHash,"1"+zeroHash.substring(1)));
        DirectPolicy.eligible(true,10290,10290,41,42,false,true,false);count++;
        no(()->DirectPolicy.eligible(false,10290,10290,41,42,false,true,false));
        no(()->DirectPolicy.eligible(true,10291,10290,41,42,false,true,false));
        no(()->DirectPolicy.eligible(true,10290,10290,42,42,false,true,false));
        no(()->DirectPolicy.eligible(true,10290,10290,-1,42,false,true,false));
        no(()->DirectPolicy.eligible(true,10290,10290,43,42,false,true,false));
        no(()->DirectPolicy.eligible(true,10290,10290,41,42,true,true,false));
        no(()->DirectPolicy.eligible(true,10290,10290,41,42,false,false,false));
        no(()->DirectPolicy.eligible(true,10290,10290,41,42,false,true,true));
        yes(DirectPolicy.root("uid=0(root) gid=0(root)\n"));yes(!DirectPolicy.root("child uid = 0\nKernelSU ready\n"));
        yes(DirectPolicy.positiveKernelVersion(0,"Kernel Version: 32680\n"));
        yes(!DirectPolicy.positiveKernelVersion(0,"Kernel Version: 0\n"));
        yes(!DirectPolicy.positiveKernelVersion(0,"KernelSU driver install syscall was blocked by seccomp\nKernel Version: 0\n"));
        yes(!DirectPolicy.positiveKernelVersion(0,"KernelSU driver install syscall was blocked by seccomp\nKernel Version: 32680\n"));
        yes(!DirectPolicy.positiveKernelVersion(1,"Kernel Version: 32680\n"));
        yes(!DirectPolicy.positiveKernelVersion(0,"Kernel Version: 99999999999999999999\n"));
        yes(!DirectPolicy.positiveKernelVersion(0,"Kernel Version: 32680\nKernel Version: 0\n"));
        Map<String,String> unknown=state();unknown.put("selinux","unknown");
        no(()->DirectPolicy.environment(unknown,"k","b"));
        Map<String,String> cold=cold();
        DirectPolicy.coldEnvironment(cold,"k","b");count++;
        yes(DirectPolicy.canDeferModuleCheck(cold));
        yes(cold.get("selinux").equals("unknown") && cold.get("module_query_known").equals("false"));
        no(()->DirectPolicy.environment(cold,"k","b"));
        for(String key:new String[]{"release","build","locked","verified","slot","boot_completed","boot_count",
                "selinux","selinux_source","selinux_query_denied","own_su_root","own_su_probe_rc"}){
            Map<String,String> missing=cold();missing.remove(key);
            no(()->DirectPolicy.coldEnvironment(missing,"k","b"));
        }
        for(String[] bad:new String[][]{{"selinux","Permissive"},{"selinux_query_denied","false"},
                {"own_su_probe_rc","1"},{"own_su_root","true"},{"locked","0"},{"verified","orange"}}){
            Map<String,String> s=cold();s.put(bad[0],bad[1]);no(()->DirectPolicy.coldEnvironment(s,"k","b"));
        }
        for(String key:new String[]{"module_query_known","module_query_loaded","modules_readable","modules_query_denied",
                "module_query_source","loader_probe_rc","loader_probe_seccomp_blocked","loader_probe_output"}){
            Map<String,String> missing=cold();missing.remove(key);yes(!DirectPolicy.canDeferModuleCheck(missing));
        }
        for(String[] bad:new String[][]{{"module_query_known","true"},{"module_query_loaded","true"},
                {"modules_query_denied","false"},{"loader_probe_output","Kernel Version: 0"},{"loader_probe_rc","1"},
                {"loader_probe_output","KernelSU driver install syscall was blocked by seccomp\nKernel Version: 32680"}}){
            Map<String,String> s=cold();s.put(bad[0],bad[1]);yes(!DirectPolicy.canDeferModuleCheck(s));
        }
        DirectPolicy.eligible(true,10290,10290,41,42,false,false,false,true);count++;
        no(()->DirectPolicy.eligible(false,10290,10290,41,42,false,false,false,true));
        no(()->DirectPolicy.eligible(true,10291,10290,41,42,false,false,false,true));
        no(()->DirectPolicy.eligible(true,10290,10290,42,42,false,false,false,true));
        no(()->DirectPolicy.eligible(true,10290,10290,41,42,true,false,false,true));
        no(()->DirectPolicy.eligible(true,10290,10290,41,42,false,true,true,true));
        DirectPolicy.completed(0,42,42,true,true,"Enforcing",true,true);count++;
        no(()->DirectPolicy.completed(1,42,42,true,true,"Enforcing",true,true));
        no(()->DirectPolicy.completed(0,42,43,true,true,"Enforcing",true,true));
        no(()->DirectPolicy.completed(0,42,42,false,true,"Enforcing",true,true));
        no(()->DirectPolicy.completed(0,42,42,true,false,"Enforcing",true,true));
        no(()->DirectPolicy.completed(0,42,42,true,true,"Permissive",true,true));
        no(()->DirectPolicy.completed(0,42,42,true,true,"Enforcing",false,true));
        no(()->DirectPolicy.completed(0,42,42,true,true,"Enforcing",true,false));
        String identity="12345678-1234-1234-1234-123456789abc";
        String receipt="uid=0\nseccomp=0\nmodule_absent=1\nparent_boot_count=42\nslot=_a\nkernel_boot_id="+identity+"\n";
        yes(DirectPolicy.preloadReceipt(receipt,42,"_a",identity));
        yes(!DirectPolicy.preloadReceipt(receipt,43,"_a",identity));
        yes(!DirectPolicy.preloadReceipt(receipt,42,"_b",identity));
        yes(!DirectPolicy.preloadReceipt(receipt,42,"_a","00000000-1234-1234-1234-123456789abc"));
        yes(!DirectPolicy.preloadReceipt(receipt.replace("module_absent=1","module_absent=0"),42,"_a",identity));
        yes(!DirectPolicy.preloadReceipt(receipt,42,"_a","unknown"));
        yes(!DirectPolicy.preloadReceipt(receipt+"extra\n",42,"_a",identity));
        System.out.println("Direct policy checks passed: "+count);
    }
}
