package dev.ghostlock.honor;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
import dev.ghostlock.honor.shizuku.IRestoreService;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DirectService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DirectEngine direct;
    ShizukuConnection shizuku;
    SessionStore store;
    private volatile boolean operating;
    private volatile String operatingMethod = "direct";
    private volatile String operation = "";
    private volatile JSONObject directState = empty("等待检查设备");
    private volatile JSONObject shizukuState = empty("先连接 Shizuku，再检查状态");
    private volatile String remoteLog = "尚无 Shizuku 执行日志。";
    private volatile String notice = "";
    private volatile boolean verifiedHealthy;

    public final class LocalBinder extends Binder { DirectService service() { return DirectService.this; } }
    @Override public void onCreate() {
        super.onCreate(); direct = new DirectEngine(this); store = new SessionStore(this);
        shizuku = new ShizukuConnection(this);
    }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction(), method = intent.getStringExtra("method");
        if (!("restore".equals(action) || "rescue".equals(action) || "initialize".equals(action) || "review".equals(action))) return START_NOT_STICKY;
        foreground();
        submit(action, "shizuku".equals(method) ? "shizuku" : "direct");
        return START_NOT_STICKY;
    }
    boolean busy() { return operating || direct.busy() || direct.nativeAlive(); }
    long boot() { return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    JSONObject state(String method) { return method.equals("shizuku") ? shizukuState : directState; }
    String notice() { return notice; }
    void select(String method) { if (!busy()) store.selected(method); }
    String block() {
        if (!store.storageIssue().isEmpty()) return store.storageIssue();
        String current = SessionPolicy.blocked(busy(), store.pending(), store.attemptedBoot(), store.reviewedBoot(), boot());
        if (!current.isEmpty()) return current;
        return store.legacyIssue(directState.optJSONObject("state"),boot());
    }
    boolean healthy() { return !busy() && verifiedHealthy; }
    boolean ownHealthy() { return healthy(directState); }
    boolean needsReview() { return store.pending() || new java.io.File(getFilesDir(),"native-attempt.pending").exists()
        || directState.optJSONObject("state")!=null && directState.optJSONObject("state").optInt("previous_unresolved_runs",0)>0; }
    boolean independentPrepared() {
        android.content.SharedPreferences p=getSharedPreferences("verified-setup",MODE_PRIVATE);
        return PreparationPolicy.prepared(p.getBoolean("verified",false),p.getInt("uid",-1),android.os.Process.myUid(),
            p.getLong("boot",-1),p.getString("kernel",""),Supported.KERNEL,p.getString("native",""),Supported.NATIVE_SHA,
            p.getString("profile",""),Supported.PROFILE_SHA);
    }
    boolean eligible(String method) { return !healthy() && block().isEmpty() && state(method).optBoolean("canRestore")
        && (!method.equals("shizuku") || shizuku.connected()) && Supported.EXPERIMENTAL; }
    boolean eligibleRescue() { return !healthy() && store.storageIssue().isEmpty() && Supported.EXPERIMENTAL
        && shizuku.connected() && shizukuState.optBoolean("canRestore")
        && SessionPolicy.rescueAllowed(busy(),needsReview(),store.attemptedBoot(),boot(),
            new java.io.File(getFilesDir(),"attempt-boot-"+boot()).exists()); }
    String operationLabel() { return operating ? ((operation.equals("restore") || operation.equals("rescue")) ? "正在恢复，请保持应用打开"
        : operation.equals("initialize") ? "正在核对本 App 的 Root 授权并启用独立恢复"
        : operation.equals("history") ? "正在整理操作记录" : "正在检查，请稍候") : ""; }

    static boolean healthy(JSONObject value) {
        if (value.optBoolean("busy")) return false;
        String phase = value.optString("phase");
        return SessionPolicy.success(phase) || phase.equals("initialized") || phase.equals("reviewed");
    }
    private static JSONObject empty(String message) {
        try { return new JSONObject().put("phase", "idle").put("message", message).put("busy", false); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    void inspect(String method) { submit("inspect", method); }

    private synchronized void submit(String action, String method) {
        if (busy()) { notice = "已有操作进行中，请等待完成。"; return; }
        if (!method.equals("direct") && !method.equals("shizuku")) return;
        boolean rescue=action.equals("rescue"), restore=action.equals("restore")||rescue;
        if (rescue && !method.equals("shizuku")) return;
        if (restore && !(rescue?eligibleRescue():eligible(method))) { notice = block().isEmpty() ? "请先检查当前恢复方式的准备条件。" : block(); stopWhenSafe(); return; }
        if (!action.equals("inspect") && !restore && !method.equals("direct")) return;
        if (action.equals("review") && store.pending()
            && !SessionPolicy.reviewAllowed(store.attemptedBoot(), boot(), ownHealthy())) {
            notice = "需在后续开机中先验证本 App 的正常 root，才可复核。"; return;
        }
        try {
            if(rescue) {
                store.resetMarkers();
                direct=new DirectEngine(this);directState=empty("已封存上次中断，正在通过 Shizuku 恢复。");
            }
            if (restore) store.begin(method, boot());
            operating = true; operatingMethod = method; operation = action; notice = ""; verifiedHealthy = false;
            if (method.equals("direct")) {
                direct.execute(action, () -> handler.post(() -> {
                    try { finish(action, method, new JSONObject(direct.snapshot()), null); }
                    catch (Exception error) { fail(action, method, error); }
                }));
            } else {
                worker.execute(() -> {
                    try {
                        IRestoreService remote = shizuku.require();
                        if (restore) remote.restore(); else remote.inspect();
                        long until = SystemClock.elapsedRealtime() + 300000;
                        while (true) {
                            JSONObject value = new JSONObject(remote.snapshot());
                            shizukuState = value;
                            if (!value.optBoolean("busy") && SessionPolicy.terminal(value.optString("phase"))) {
                                String log = remote.readLog();
                                handler.post(() -> finish(action, method, value, log)); return;
                            }
                            if (SystemClock.elapsedRealtime() > until) throw new IllegalStateException("状态等待超时，未自动重试，请保留现场。");
                            Thread.sleep(500);
                        }
                    } catch (Exception error) { handler.post(() -> fail(action, method, error)); }
                });
            }
        } catch (Exception error) { fail(action, method, error); }
    }

    private void finish(String action, String method, JSONObject value, String log) {
        try {
            JSONObject observed=value.optJSONObject("state");
            boolean authorizationPending=method.equals("direct") && observed!=null
                && PreparationPolicy.authorizationPending(healthy(shizukuState),"true".equals(observed.optString("own_su_root")),observed.optInt("own_su_probe_rc",-999));
            verifiedHealthy = healthy(value) || authorizationPending;
            if(authorizationPending) notice="系统 Root 已可用，但 GhostLock HONOR 的授权尚未完成。请在 KernelSU 允许本 App 后，再点击“启用独立恢复”。";
            else if(action.equals("initialize") && value.optString("phase").equals("initialized"))
                notice="独立恢复已启用。保留本 App 的 KernelSU 授权，下次正常开机后即可使用。";
            else if(method.equals("shizuku") && healthy(value) && !independentPrepared())
                notice="系统 Root 已恢复。还需点击“启用独立恢复”，核对本 App 授权并完成一次初始化。";
            if (method.equals("direct")) directState = value; else { shizukuState = value; remoteLog = log==null?"未能取得远端完整日志。":log; }
            if (action.equals("restore") || action.equals("rescue")) store.finish(SessionPolicy.success(value.optString("phase")));
            if (action.equals("review") && value.optString("phase").equals("reviewed") && store.pending()) {
                if (!SessionPolicy.reviewAllowed(store.attemptedBoot(), boot(), healthy(value)))
                    throw new IllegalStateException("当前复核不满足后续开机和完整 root 验证条件。");
                store.review(boot());
            }
            store.record(method, action, value, method.equals("direct") ? direct.log() : log);
        } catch (Exception error) { notice = "记录需核对：" + error.getMessage(); }
        finally { operating = false; stopWhenSafe(); }
    }

    private void fail(String action, String method, Exception error) {
        JSONObject value = empty(error.getMessage() == null ? error.toString() : error.getMessage());
        try { value.put("phase", "failed"); } catch (Exception ignored) {}
        notice = value.optString("message"); finish(action, method, value, null);
    }

    JSONObject liveState(String method) {
        if (operating && operatingMethod.equals("direct") && method.equals("direct")) {
            try { return new JSONObject(direct.snapshot()); } catch (Exception ignored) {}
        }
        return state(method);
    }
    void collectLog(java.util.function.Consumer<String> result) {
        worker.execute(() -> {
            String text = "GhostLock HONOR " + Supported.VERSION_NAME + "\n" + directState + "\n" + shizukuState
                + "\n\n=== 独立恢复（当前） ===\n" + direct.log() + "\n\n=== Shizuku（当前） ===\n" + remoteLog
                + "\n\n=== 最近已保存独立日志 ===\n" + store.latestLog("direct")
                + "\n\n=== 最近已保存 Shizuku 日志 ===\n" + store.latestLog("shizuku");
            handler.post(() -> result.accept(text));
        });
    }
    void collectRecord(JSONObject item, java.util.function.Consumer<String> result) {
        worker.execute(() -> {
            String text;
            try { text = item+"\n\n"+store.readLog(item.optString("log_file")); }
            catch (Exception error) { text = item+"\n读取失败："+error.getMessage(); }
            final String captured = text; handler.post(() -> result.accept(captured));
        });
    }
    synchronized void removeHistory(String name, java.util.function.Consumer<String> result) {
        String issue=store.historyMutationIssue(busy());
        if(!issue.isEmpty()) { result.accept(issue); return; }
        operating=true; operation="history"; operatingMethod=store.selected();
        worker.execute(()->{
            String message;
            try { message=store.removeHistory(name); }
            catch(Exception error) { message="无法删除记录："+error.getMessage(); }
            final String captured=message;
            handler.post(()->{ operating=false; stopWhenSafe(); result.accept(captured); });
        });
    }
    synchronized void resetMarkers(java.util.function.Consumer<String> result) {
        if(busy()) { result.accept("当前操作仍在执行，请等待结束。"); return; }
        operating=true;operation="history";operatingMethod=store.selected();
        worker.execute(()->{
            String message;
            try {
                store.resetMarkers();
                direct=new DirectEngine(this);directState=empty("标记已清除，请重新检查设备。");
                shizukuState=empty("标记已清除，请重新检查 Shizuku 状态。");verifiedHealthy=false;
                message="恢复标记已清除，初始化与授权保留，原始日志已封存。请重新检查设备。";
            } catch(Exception error) { message="未能完成标记清除："+error.getMessage(); }
            final String captured=message;
            handler.post(()->{ operating=false;notice=captured;stopWhenSafe();result.accept(captured); });
        });
    }
    private void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("ghostlock-honor", "恢复进度", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "ghostlock-honor").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("GhostLock HONOR").setContentText("正在处理恢复请求，请勿强行停止应用")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }
    private void stopWhenSafe() {
        // An interrupted remote operation may still own a native worker. Keep it untouched.
        if (busy() || store.pending()) return;
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() { shizuku.close(); worker.shutdown(); super.onDestroy(); }
}
