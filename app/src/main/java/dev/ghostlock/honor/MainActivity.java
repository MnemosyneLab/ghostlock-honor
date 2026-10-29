package dev.ghostlock.honor;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    private static final int INK = 0xff20352e, MUTED = 0xff65776f, GREEN = 0xff08765b;
    private static final int BG = 0xfff5f7f4, LINE = 0xffdce6df, WHITE = 0xffffffff;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DirectService service;
    private boolean bound, destroyed;
    private String tab = "home", method = "direct", exportText = "", lastHistory = "";
    private LinearLayout body, navigation;
    private LinearLayout actionDock;
    private String lastRender = "";
    private ScrollView scroll;
    private TextView heroTitle, heroBody, heroTag, condition, directSubtitle, shizukuSubtitle, setupStatus;
    private TextView nextStep;
    private TextView settingsResult;
    private TextView connectionStatus;
    private LinearLayout preparationCard;
    private TextView preparationDescription, historyStatus;
    private Button enableIndependent, clearHistory;
    private final java.util.ArrayList<Button> historyDeleteButtons = new java.util.ArrayList<>();
    private LinearLayout directCard, shizukuCard;
    private Button primary, check, initialize, review;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed) return;
            service = ((DirectService.LocalBinder) binder).service(); method = service.store.selected();
            if (!service.busy()) service.inspect("direct");
            showTab(tab); render();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; render(); }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() { if (!destroyed) { render(); handler.postDelayed(this, 700); } }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) tab = state.getString("tab", "home");
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        LinearLayout brand = row(); brand.setPadding(dp(24), dp(16), dp(24), dp(10));
        ImageView icon = new ImageView(this); icon.setImageDrawable(getDrawable(getApplicationInfo().icon));
        brand.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView wordmark = text("GhostLock HONOR", 19, INK, true); wordmark.setPadding(dp(10),0,0,0);
        brand.addView(wordmark, new LinearLayout.LayoutParams(0,-2,1));
        TextView badge = text("HONOR 400", 11, GREEN, true); badge.setPadding(dp(10),dp(6),dp(10),dp(6));
        badge.setBackground(shape(0xffe7eee8, 20, 0)); brand.addView(badge);
        root.addView(brand);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = column(); body.setPadding(dp(24),dp(14),dp(24),dp(24)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        actionDock = column(); actionDock.setPadding(dp(24),dp(10),dp(24),dp(10));
        actionDock.setBackgroundColor(BG); root.addView(actionDock);
        navigation = row(); navigation.setPadding(dp(14),dp(10),dp(14),dp(10));
        navigation.setBackground(shape(WHITE,0,0)); root.addView(navigation);
        setContentView(root); showTab(tab);
        bound = bindService(new Intent(this,DirectService.class),connection,BIND_AUTO_CREATE);
        handler.post(poll);
    }

    private void showTab(String page) {
        tab = page; lastRender = ""; body.removeAllViews(); actionDock.removeAllViews();
        actionDock.setVisibility(page.equals("home")?View.VISIBLE:View.GONE);
        heroTitle = heroBody = heroTag = condition = directSubtitle = shizukuSubtitle = setupStatus = nextStep = null;
        settingsResult = null;
        connectionStatus = null;
        preparationCard=null; preparationDescription=null; historyStatus=null;
        enableIndependent=null; clearHistory=null; historyDeleteButtons.clear();
        primary = check = initialize = review = null;
        directCard = shizukuCard = null;
        if (page.equals("history")) history(); else if (page.equals("settings")) settings(); else home();
        navigation.removeAllViews();
        nav("home", "恢复", android.R.drawable.ic_lock_lock);
        nav("history", "记录", android.R.drawable.ic_menu_recent_history);
        nav("settings", "设置", android.R.drawable.ic_menu_manage);
        scroll.scrollTo(0,0); render();
    }

    private void home() {
        heading("恢复", "选择恢复方式并检查设备状态。");
        LinearLayout hero = column(); hero.setPadding(dp(22),dp(22),dp(22),dp(22));
        hero.setBackground(shape(0xff123e32,24,0));
        heroTag = text("设备权限",12,0xffb2d8c7,false); hero.addView(heroTag);
        heroTitle = text("正在连接",30,WHITE,true); hero.addView(heroTitle,space(10));
        heroBody = text("正在读取设备状态，请稍候。",14,0xffd1e5da,false); hero.addView(heroBody,space(10));
        hero.addView(text("临时 Root  ·  重启后需重新恢复",11,0xffa8ccbb,false),space(20));
        body.addView(hero,space(22));
        preparationCard=card();
        preparationCard.addView(text("启用独立恢复",18,INK,true));
        preparationDescription=text("首次先通过 Shizuku 或电脑恢复 Root，再完成本 App 的授权与初始化。",13,MUTED,false);
        preparationCard.addView(preparationDescription,space(8));
        enableIndependent=button("启用独立恢复",true,this::prepareIndependent);
        preparationCard.addView(enableIndependent,space(12)); body.addView(preparationCard,space(16));
        section("恢复方式", "按当前条件选择，失败后不会自动切换。");
        directCard = modeCard("direct", "独立恢复", "无需电脑，也无需无线调试", android.R.drawable.ic_menu_rotate);
        shizukuCard = modeCard("shizuku", "Shizuku 恢复", "通过已授权的 Shizuku 服务恢复", android.R.drawable.ic_menu_share);
        nextStep = text("正在检查准备条件…",13,MUTED,false); body.addView(nextStep,space(18));
        primary = button("检查设备", true, () -> primaryAction()); actionDock.addView(primary,new LinearLayout.LayoutParams(-1,-2));
        check = button("重新检查状态", false, () -> inspect()); body.addView(check,space(8));
        LinearLayout hint = card();
        hint.addView(text("恢复前请保存正在进行的工作",14,INK,true));
        hint.addView(text("恢复过程仍可能失败或导致手机重启。每次开机最多提交一次恢复请求；两种方式共享保护记录。",12,MUTED,false),space(7));
        body.addView(hint,space(20));
        condition = text("仅适用于已核对的 HONOR 400 固件与管理器。",11,MUTED,false);
        body.addView(condition,space(16));
    }

    private LinearLayout modeCard(String id, String title, String subtitle, int resource) {
        LinearLayout box = row(); box.setPadding(dp(16),dp(17),dp(16),dp(17));
        ImageView image = new ImageView(this); image.setImageResource(resource); image.setImageTintList(ColorStateList.valueOf(GREEN));
        box.addView(image,new LinearLayout.LayoutParams(dp(26),dp(26)));
        LinearLayout labels = column(); labels.setPadding(dp(13),0,dp(4),0);
        labels.addView(text(title,16,INK,true)); labels.addView(text(subtitle,11,MUTED,false),space(4));
        TextView status = text("等待检查",11,GREEN,true); labels.addView(status,space(9));
        box.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        RadioButton radio = new RadioButton(this); radio.setChecked(id.equals(method)); radio.setClickable(false);
        radio.setFocusable(false); radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        radio.setButtonTintList(ColorStateList.valueOf(GREEN)); box.addView(radio);
        box.setFocusable(true); box.setContentDescription(title+"，"+subtitle); box.setOnClickListener(v -> {
            if (service != null && service.busy()) { toast("请等待当前操作完成。"); return; }
            method = id; if (service != null) service.select(id); showTab("home");
        });
        if (id.equals("direct")) directSubtitle = status; else shizukuSubtitle = status;
        body.addView(box,space(10)); return box;
    }

    private void history() {
        heading("操作记录", "检查、恢复和复核结果都保存在本机。");
        Button save = button("导出诊断日志", false, this::export); body.addView(save,space(22));
        clearHistory=destructiveButton("清空操作记录",()->confirmHistoryRemoval(null));
        clearHistory.setEnabled(service!=null && service.store.historyMutationIssue(service.busy()).isEmpty());
        body.addView(clearHistory,space(8));
        historyStatus=text("",12,MUTED,false); body.addView(historyStatus,space(10));
        JSONArray rows = service == null ? new JSONArray() : service.store.history(); lastHistory = rows.toString();
        if (rows.length() == 0) {
            LinearLayout empty = card(); empty.addView(text("还没有操作记录",20,INK,true));
            empty.addView(text("后续检查或恢复会产生新记录。恢复保护标记与原始运行证据仍会保留。",14,MUTED,false),space(10));
            body.addView(empty,space(20)); return;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i); if (item == null) continue;
            JSONObject result = item.optJSONObject("result"); if (result == null) continue;
            String phase = result.optString("phase");
            LinearLayout entry = card();
            entry.addView(text(actionName(item.optString("action"))+" · "+methodName(item.optString("method")),14,INK,true));
            entry.addView(text(phaseName(phase),19,SessionPolicy.success(phase)?GREEN:INK,true),space(10));
            entry.addView(text(friendly(result.optString("message")),13,MUTED,false),space(6));
            entry.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(item.optLong("at"))),11,MUTED,false),space(12));
            entry.addView(button("查看技术详情",false,()->details(item)),space(10));
            Button remove=destructiveButton("删除记录",()->confirmHistoryRemoval(item.optString("log_file")));
            remove.setTag(item.optString("log_file")); historyDeleteButtons.add(remove); entry.addView(remove,space(8));
            body.addView(entry,space(14));
        }
    }

    private void settings() {
        heading("设置", "准备、授权与设备信息。");
        settingsResult = text("",13,MUTED,false); body.addView(settingsResult,space(12));
        LinearLayout prep = card(); prep.addView(text("独立恢复准备",18,INK,true));
        setupStatus = text("正在读取初始化状态…",13,MUTED,false); prep.addView(setupStatus,space(8));
        prep.addView(text("首次使用时，先取得正常 Root，在 KernelSU 中授权 GhostLock HONOR，再保存初始化凭证。覆盖升级会保留原独立版数据。",13,MUTED,false),space(10));
        prep.addView(button("打开 KernelSU",false,()->openPackage(Supported.MANAGER)),space(14));
        prep.addView(button("检查已有 Root",false,()->{ if(service!=null && !service.busy()) service.inspect("direct"); }),space(8));
        initialize = button("启用独立恢复",true,this::prepareIndependent);
        prep.addView(initialize,space(8)); body.addView(prep,space(22));
        LinearLayout bridge = card(); bridge.addView(text("Shizuku 连接",18,INK,true));
        connectionStatus=text("尚未连接",12,GREEN,true); bridge.addView(connectionStatus,space(8));
        bridge.addView(text("重启后，在 Shizuku 中通过无线调试启动服务，然后在这里连接并授权。连接本身不会启动恢复。",13,MUTED,false),space(8));
        bridge.addView(button("连接 / 授权 Shizuku",false,this::authorize),space(12));
        bridge.addView(button("打开 Shizuku",false,()->openPackage("moe.shizuku.privileged.api")),space(8));
        bridge.addView(button("开发者选项",false,()->{
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch(Exception error) { toast("请手动打开系统开发者选项。"); }
        }),space(8)); body.addView(bridge,space(14));
        LinearLayout device = card(); device.addView(text("设备与版本",18,INK,true));
        device.addView(text("HONOR 400 · DNY-NX9\n"+Supported.BUILD+"\nKernelSU 官方管理器 v3.3.1\nGhostLock HONOR "+Supported.VERSION_NAME+" · "+Supported.VERSION_CODE,13,MUTED,false),space(10));
        device.addView(text("固件、内核或管理器升级后需重新验证。恢复可能失败或导致重启。",12,MUTED,false),space(12));
        body.addView(device,space(14));
        LinearLayout safety = card(); safety.addView(text("中断与安全",18,INK,true));
        safety.addView(text("独立恢复失败并重启后，可改用 Shizuku 恢复。也可手动强制清除恢复标记，不要求 Root 或复核；初始化与授权保留，原始日志会封存。",13,MUTED,false),space(8));
        safety.addView(button("导出诊断日志",false,this::export),space(12));
        review = button("强制清除恢复标记",false,this::confirmResetMarkers);
        safety.addView(review,space(8)); body.addView(safety,space(14));
        TextView licenses = text("临时 Root，不提供刷写、Bootloader 解锁或开机自动恢复。\nGhostLock / AndroidX：Apache-2.0 · Shizuku API：MIT",11,MUTED,false);
        body.addView(licenses,space(20));
    }

    private void render() {
        boolean running = service != null && service.busy();
        String renderKey=tab+"|"+method+"|"+(service==null?"disconnected":running+"|"+service.healthy()+"|"
            +service.liveState("direct")+"|"+service.liveState("shizuku")+"|"+service.notice()+"|"
            +service.shizuku.message()+"|"+service.store.pending()+"|"+service.independentPrepared()+"|"+service.store.rawHistory());
        if(renderKey.equals(lastRender)) return;
        lastRender=renderKey;
        if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (tab.equals("home") && heroTitle != null) {
            JSONObject value = service == null ? new JSONObject() : service.liveState(method);
            String phase = value.optString("phase"); boolean active = service != null && service.healthy();
            boolean interrupted=service!=null && service.needsReview();
            boolean prepared=service!=null && service.independentPrepared();
            preparationCard.setVisibility(prepared?View.GONE:View.VISIBLE);
            enableIndependent.setEnabled(service!=null && !running);
            preparationDescription.setText(active?"系统 Root 已就绪。点击下方按钮，完成 GhostLock HONOR 的授权核对和初始化；下次开机即可独立恢复。"
                :"首次先通过 Shizuku 或电脑 USB 恢复 Root，再点击此按钮，在 KernelSU 允许 GhostLock HONOR 并完成初始化。");
            String block = service == null ? "正在连接本机服务" : service.block();
            heroTitle.setText(running ? "正在处理" : interrupted ? "需要核对记录" : active ? "Root 已就绪" : service != null && service.eligible(method) ? "可以恢复" : "等待准备");
            heroTag.setText(active ? "已验证 · 本次开机有效" : running ? "操作进行中" : "设备权限");
            String message = running ? service.operationLabel() : active ? (prepared?"现在可以在 KernelSU 中管理其他应用的 Root 授权。":"系统 Root 已恢复，还需要启用独立恢复，才能在下次开机使用这个方式。")
                : phase.equals("ready") ? (method.equals("shizuku") ? "Shizuku 连接和设备检查已通过。恢复前还会进一步校验受保护状态。" : "系统和初始化凭证已匹配。恢复前还会进一步校验受保护状态。")
                : friendly(value.optString("message","检查设备后，查看当前方式是否可用。"));
            heroBody.setText(interrupted && !running ? "可以改用 Shizuku 恢复，或手动清除恢复标记。初始化仍保留，无需卸载或清空数据。" : message);
            nextStep.setText(service != null && !service.notice().isEmpty() ? service.notice() : !block.isEmpty() && !active ? block : active ? (prepared?"已有正常 Root，无需重复恢复。":"下一步：点击“启用独立恢复”，核对授权并完成一次准备。") : phase.equals("ready") ? "准备条件已通过，确认后仅执行一次恢复。" : "下一步：检查设备，或先通过 Shizuku / 电脑恢复 Root。");
            primary.setText(interrupted && method.equals("direct") ? "处理上次中断" : active ? (prepared?"管理应用授权":"启用独立恢复") : method.equals("shizuku") && service != null && !service.shizuku.connected() ? "连接 Shizuku" : service != null && (service.eligible(method)||method.equals("shizuku")&&service.eligibleRescue()) ? "恢复 Root" : "检查设备");
            primary.setEnabled(service != null && !running);
            check.setEnabled(service != null && !running);
            directCard.setBackground(shape(method.equals("direct")?0xffeaf3ed:WHITE,18,method.equals("direct")?GREEN:LINE));
            shizukuCard.setBackground(shape(method.equals("shizuku")?0xffeaf3ed:WHITE,18,method.equals("shizuku")?GREEN:LINE));
            directSubtitle.setText(service == null ? "等待检查" : !prepared?"尚未启用 · 需要一次初始化":"已启用 · "+modeStatus(service.state("direct")));
            shizukuSubtitle.setText(service == null ? "尚未连接" : service.shizuku.connected() ? "已连接 · "+modeStatus(service.state("shizuku")) : service.shizuku.message());
            condition.setText("适用固件 10.0.0.213 (C636) · "+(Supported.EXPERIMENTAL?"按需恢复，不自动运行":"当前安装包仅开放检查"));
        } else if (tab.equals("settings") && initialize != null) {
            connectionStatus.setText(service==null?"尚未连接":service.shizuku.message());
            settingsResult.setText(service == null ? "正在连接本机服务…" : running ? service.operationLabel()
                : !service.notice().isEmpty() ? service.notice() : friendly(service.state("direct").optString("message")));
            boolean ready = service != null && service.independentPrepared();
            setupStatus.setText(ready ? "独立恢复已启用。保留 KernelSU 授权，普通升级会保留准备数据。" : "独立恢复尚未启用。先恢复系统 Root，再点击下方按钮核对授权并初始化。");
            initialize.setEnabled(service != null && !running);
            review.setEnabled(service != null && !running);
        } else if (tab.equals("history") && service != null) {
            if(!lastHistory.equals(service.store.history().toString())) { showTab("history"); return; }
            String issue=service.store.historyMutationIssue(running);
            clearHistory.setEnabled(issue.isEmpty());
            historyStatus.setText(issue.isEmpty()?"可删除单条或清空操作记录，恢复保护与原始证据会保留。":issue);
            for(Button b:historyDeleteButtons) b.setEnabled(issue.isEmpty() && HistoryFiles.allowedName((String)b.getTag()));
        }
    }

    private String modeStatus(JSONObject value) {
        if (value.optBoolean("busy")) return "正在检查 / 执行";
        String phase = value.optString("phase");
        if (DirectService.healthy(value)) return "已验证 Root";
        if (phase.equals("ready")) return "准备条件通过";
        if (phase.equals("stopped") || phase.equals("failed")) return "需要检查准备条件";
        return "等待检查";
    }
    private void primaryAction() {
        if (service == null || service.busy()) return;
        if (service.needsReview() && !method.equals("shizuku")) { interruptionHelp(); return; }
        if (service.healthy()) { if(!service.independentPrepared()) prepareIndependent(); else openPackage(Supported.MANAGER); return; }
        if (method.equals("shizuku") && !service.shizuku.connected()) { authorize(); return; }
        if (method.equals("shizuku") && service.eligibleRescue()) {
            confirm("通过 Shizuku 重新恢复", "将保留上次失败的原始日志，重置旧标记，然后通过 Shizuku 执行一次恢复。恢复仍可能失败或重启，不会自动重试。成功后可正常删除操作记录。", "rescue","shizuku"); return;
        }
        if (service.eligible(method)) confirm("恢复 Root", "将使用“"+methodName(method)+"”执行一次临时恢复。恢复过程可能失败或导致重启，请先保存工作。中断后不会自动切换另一种方式。", "restore",method);
        else inspect();
    }
    private void inspect() {
        if (service == null || service.busy()) return;
        if (method.equals("shizuku") && !service.shizuku.connected()) { authorize(); return; }
        service.inspect(method); render();
    }
    private void authorize() {
        if (service == null || service.busy()) return;
        if (!service.shizuku.running()) { openPackage("moe.shizuku.privileged.api"); return; }
        service.shizuku.authorize(); render();
    }
    private void confirm(String title, String message, String action, String selected) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null)
            .setPositiveButton(action.equals("restore")||action.equals("rescue")?"确认恢复":"继续",(dialog,which)->{
                if (service == null || service.busy()) return;
                try { startForegroundService(new Intent(this,DirectService.class).setAction(action).putExtra("method",selected)); }
                catch (Exception error) { toast("无法启动操作："+error.getMessage()); }
            }).show();
    }
    private void prepareIndependent() {
        if(service==null || service.busy()) return;
        confirm("启用独立恢复","请先通过 Shizuku 或电脑恢复系统 Root。接下来会请求 GhostLock HONOR 的 KernelSU 授权，并核对设备、资源和启动分区后保存初始化凭证。\n\n此操作不执行原生恢复。完成后从下次正常开机起可使用独立恢复。","initialize","direct");
    }
    private void interruptionHelp() {
        if(service==null || service.busy()) return;
        new AlertDialog.Builder(this).setTitle("处理中断记录")
            .setMessage("独立恢复失败并重启后，可以直接改用 Shizuku。连接、检查、确认恢复即可；成功后可删除操作记录。\n\n也可强制清除恢复标记，不要求 Root 或复核。初始化与授权保留，原始日志只封存。清除标记不会自动执行恢复，也不会修复原生恢复的重启原因。")
            .setNegativeButton("取消",null).setNeutralButton("强制清除",(d,w)->confirmResetMarkers())
            .setPositiveButton("使用 Shizuku",(d,w)->{
                method="shizuku";service.select(method);showTab("home");
                if(!service.shizuku.connected()) authorize();else service.inspect("shizuku");
            })
            .show();
    }
    private void confirmResetMarkers() {
        if(service==null || service.busy()) return;
        new AlertDialog.Builder(this).setTitle("强制清除恢复标记？")
            .setMessage("不要求 Root 或复核，将重置失败和本次开机尝试标记。初始化、授权和操作记录保留，原始运行日志会封存。\n\n这不会修复重启原因，也不会自动开始恢复。")
            .setNegativeButton("取消",null).setPositiveButton("强制清除",(d,w)->service.resetMarkers(message->{
                if(!destroyed){toast(message);showTab("home");}
            })).show();
    }
    private void confirmHistoryRemoval(String id) {
        if(service==null) return;
        String issue=service.store.historyMutationIssue(service.busy());
        if(!issue.isEmpty()) { toast(issue); return; }
        new AlertDialog.Builder(this).setTitle(id==null?"清空操作记录？":"删除这条记录？")
            .setMessage("将删除操作历史及对应诊断副本，无法撤销。恢复保护标记、初始化数据与原始运行证据会保留。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->{
                if(service==null) return;
                service.removeHistory(id,message->{if(!destroyed){toast(message);showTab("history");}});
                render();
            }).show();
    }
    private void export() {
        if (service == null) { toast("本机服务尚未连接。"); return; }
        if (service.busy()) { toast("请等待当前操作结束后导出。"); return; }
        service.collectLog(log -> {
            if (destroyed) return;
            exportText = log+"\n\n=== 原始操作记录 ===\n"+service.store.rawHistory();
            saveDocument();
        });
    }
    private void details(JSONObject item) {
        TextView details = text(item.toString(),12,MUTED,false); details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true); details.setPadding(dp(20),dp(16),dp(20),dp(16));
        ScrollView wrapper = new ScrollView(this); wrapper.addView(details);
        new AlertDialog.Builder(this).setTitle("技术详情").setView(wrapper).setPositiveButton("关闭",null)
            .setNeutralButton("导出此条日志",(dialog,which)->{
                if(service==null || service.busy()) { toast("请等待当前操作结束。"); return; }
                service.collectRecord(item,log->{ if(!destroyed) { exportText=log; saveDocument(); } });
            }).show();
    }
    private void saveDocument() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"GhostLockHonor-diagnostics.txt"),41);
    }
    private void openPackage(String name) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(name);
        if (launch == null) toast("未安装相应应用，请先安装已验证的官方版本。"); else startActivity(launch);
    }
    private void nav(String id, String label, int icon) {
        LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setPadding(0,dp(6),0,dp(6));
        ImageView image = new ImageView(this); image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(tab.equals(id)?GREEN:MUTED));
        item.addView(image,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView caption=text(label,11,tab.equals(id)?GREEN:MUTED,tab.equals(id)); caption.setGravity(Gravity.CENTER);
        item.addView(caption,space(4));
        if (tab.equals(id)) item.setBackground(shape(0xffeaf3ed,18,0));
        item.setMinimumHeight(dp(56)); item.setFocusable(true); item.setContentDescription(label);
        item.setOnClickListener(v -> showTab(id)); navigation.addView(item,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void heading(String title, String subtitle) {
        body.addView(text(title,30,INK,true)); body.addView(text(subtitle,13,MUTED,false),space(6));
    }
    private void section(String title, String subtitle) {
        body.addView(text(title,18,INK,true),space(26)); body.addView(text(subtitle,12,MUTED,false),space(5));
    }
    private LinearLayout card() {
        LinearLayout c = column(); c.setPadding(dp(18),dp(18),dp(18),dp(18)); c.setBackground(shape(WHITE,18,LINE)); return c;
    }
    private Button button(String label, boolean filled, Runnable click) {
        Button button = new Button(this); button.setText(label); button.setTextSize(14); button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD); button.setMinHeight(dp(52)); button.setMinimumHeight(dp(52));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff9aaa9f,filled?WHITE:GREEN}));
        GradientDrawable normal = shape(filled?GREEN:WHITE,14,filled?0:LINE);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{-android.R.attr.state_enabled},shape(0xffe7ece6,14,0)); bg.addState(new int[]{},normal);
        button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(filled?0x33ffffff:0x2208765b),bg,shape(WHITE,14,0)));
        button.setPadding(dp(12),dp(12),dp(12),dp(12)); button.setOnClickListener(v -> click.run()); return button;
    }
    private Button destructiveButton(String label, Runnable click) {
        Button b=button(label,false,click);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff9aaa9f,0xff9b3b2e}));
        return b;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); view.setLineSpacing(dp(3),1);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return view;
    }
    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1),border); return drawable;
    }
    private LinearLayout.LayoutParams space(int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(margin); return p; }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private String friendly(String text) { return text.replace("GhostLock HONOR","GhostLock HONOR").replace("独立实验版","独立恢复"); }
    private String methodName(String id) { return id.equals("shizuku")?"Shizuku 恢复":"独立恢复"; }
    private String actionName(String id) { return id.equals("restore")||id.equals("rescue")?"恢复":id.equals("initialize")?"初始化":id.equals("review")?"复核":"检查"; }
    private String phaseName(String id) {
        return SessionPolicy.success(id)?"Root 已验证":id.equals("ready")?"准备条件通过":id.equals("initialized")?"初始化已完成":id.equals("reviewed")?"复核已保存":id.equals("failed")||id.equals("stopped")?"操作已停止":"状态检查";
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("tab",tab); super.onSaveInstanceState(out); }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==41 && result==RESULT_OK && data!=null && data.getData()!=null) try(OutputStream out=getContentResolver().openOutputStream(data.getData())) {
            if (out==null) throw new IllegalStateException("无法打开保存位置"); out.write(exportText.getBytes(StandardCharsets.UTF_8)); toast("诊断日志已保存");
        } catch(Exception error) { toast("保存失败："+error.getMessage()); }
    }
    @Override protected void onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null); if(bound) unbindService(connection); super.onDestroy();
    }
}
