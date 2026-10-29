package dev.ghostlock.honor;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class SessionStore {
    private final SharedPreferences prefs;
    private final SharedPreferences setup;
    private final File records;
    private final File files;
    private final HistoryFiles historyFiles;

    SessionStore(Context context) {
        prefs = context.getSharedPreferences("unified-recovery", Context.MODE_PRIVATE);
        setup = context.getSharedPreferences("verified-setup", Context.MODE_PRIVATE);
        files = context.getFilesDir(); records = new File(files, "unified-records"); historyFiles = new HistoryFiles(files);
    }
    boolean pending() { return prefs.getBoolean("pending", false); }
    long attemptedBoot() { return prefs.getLong("attemptedBoot", -1); }
    long reviewedBoot() { return prefs.getLong("reviewedBoot", -1); }
    String selected() { return prefs.getString("selected", "direct"); }
    void selected(String method) { prefs.edit().putString("selected", method).apply(); }
    String rawHistory() { return prefs.getString("history", "[]"); }
    String storageIssue() {
        try { new JSONArray(rawHistory()); return ""; }
        catch (Exception error) { return "操作记录无法读取，已暂停恢复；请先导出诊断日志。"; }
    }
    String historyMutationIssue(boolean busy) {
        return HistoryFiles.deletionIssue(busy, pending() || new File(files,"native-attempt.pending").exists(), !storageIssue().isEmpty());
    }
    String removeHistory(String name) throws Exception {
        synchronized (SessionStore.class) {
        String issue = historyMutationIssue(false);
        if (!issue.isEmpty()) throw new IllegalStateException(issue);
        JSONArray before = new JSONArray(rawHistory()), kept = new JSONArray();
        String[] logs;
        if (name == null) logs = historyFiles.names();
        else {
            historyFiles.record(name);
            boolean found = false;
            for (int i=0;i<before.length();i++) {
                JSONObject row=before.getJSONObject(i);
                if (name.equals(row.optString("log_file"))) found=true; else kept.put(row);
            }
            if (!found) throw new IllegalStateException("记录已变化，请刷新后重试。");
            logs = new String[]{name};
        }
        // Update only the display index. Attempt/review/setup keys are never cleared.
        if (!prefs.edit().putString("history", kept.toString()).commit())
            throw new IllegalStateException("记录索引保存失败，未删除日志。");
        boolean complete = true;
        for (String file : logs) try { complete &= historyFiles.remove(file); }
        catch (Exception error) { complete=false; }
        if (!complete) return "记录已移除，部分诊断副本未能删除，可再次清空重试。";
        return name == null ? "操作记录已清空" : "记录已删除";
        }
    }
    String legacyIssue(JSONObject state, long boot) {
        File[] entries = files.listFiles();
        if (entries == null) return "无法核对本机历史记录，未开放恢复。";
        if (new File(files,"attempt-boot-"+boot).exists())
            return "旧版本次开机已有恢复尝试，请保留现场，不切换方式重试。";
        boolean runs = false;
        for (File entry : entries) if (entry.isDirectory() && entry.getName().matches("direct-run-[0-9a-f]{32}")) runs = true;
        boolean registered = state != null && "true".equals(state.optString("previous_run_registered"));
        runs |= registered;
        boolean audited = state != null && state.has("previous_unresolved_runs");
        boolean unresolved = !audited || state.optInt("previous_unresolved_runs", -1) != 0
            || registered && !"true".equals(state.optString("previous_run_terminal"));
        return SessionPolicy.legacyBlocked(new File(files,"native-attempt.pending").exists(),runs,audited,unresolved);
    }

    void begin(String method, long boot) {
        synchronized (SessionStore.class) {
        if (!storageIssue().isEmpty()) throw new IllegalStateException(storageIssue());
        String blocked=SessionPolicy.blocked(false,pending(),attemptedBoot(),reviewedBoot(),boot);
        if(!blocked.isEmpty()) throw new IllegalStateException(blocked);
        boolean ok = prefs.edit().putBoolean("pending", true).putLong("attemptedBoot", boot)
            .putLong("reviewedBoot", -1).putString("attemptedMethod", method)
            .putLong("startedAt", System.currentTimeMillis()).commit();
        if (!ok) throw new IllegalStateException("无法保存恢复记录，未启动恢复。");
        }
    }

    void finish(boolean success) {
        // Failures deliberately remain pending across process death and reboot.
        if (success && !prefs.edit().putBoolean("pending", false).commit())
            throw new IllegalStateException("恢复结果未能落盘，请保留记录。");
    }

    void review(long boot) {
        if (!prefs.edit().putBoolean("pending", false).putLong("reviewedBoot", boot).commit())
            throw new IllegalStateException("无法保存复核结果。");
    }

    void resetMarkers() throws Exception {
        synchronized(SessionStore.class) {
            File archive=MarkerFiles.archive(files);
            JSONObject saved=new JSONObject().put("pending",pending()).put("attemptedBoot",attemptedBoot())
                .put("reviewedBoot",reviewedBoot()).put("attemptedMethod",prefs.getString("attemptedMethod",""))
                .put("last_run",setup.getString("last_run","")).put("resetAt",System.currentTimeMillis());
            try(FileOutputStream output=new FileOutputStream(new File(archive,"marker-state.json"))) {
                output.write(saved.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();
            }
            if(!setup.edit().remove("last_run").commit()) throw new IOException("无法更新运行目录登记，标记状态尚未重置。");
            if(!prefs.edit().putBoolean("pending",false).putLong("attemptedBoot",-1).putLong("reviewedBoot",-1)
                .remove("attemptedMethod").remove("startedAt").commit()) throw new IOException("无法重置恢复标记。");
        }
    }

    JSONArray history() {
        try { return new JSONArray(rawHistory()); }
        catch (Exception error) {
            JSONArray failed = new JSONArray();
            try { failed.put(new JSONObject().put("action","read").put("method","direct")
                .put("at",0).put("result",new JSONObject().put("phase","failed").put("message",storageIssue()))); }
            catch (Exception ignored) { }
            return failed;
        }
    }

    void record(String method, String action, JSONObject result, String log) throws Exception {
        synchronized (SessionStore.class) {
        JSONArray entries = new JSONArray(rawHistory()), kept = new JSONArray();
        if (!records.isDirectory() && !records.mkdirs()) throw new IllegalStateException("无法创建日志目录。");
        File file = File.createTempFile("record-", ".txt", records);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write((result.toString()+"\n\n"+(log==null?"未能取得完整日志。":log)).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        kept.put(new JSONObject().put("at", System.currentTimeMillis()).put("method", method)
            .put("action", action).put("result", new JSONObject(result.toString())).put("log_file",file.getName()));
        for (int i = 0; i < Math.min(39, entries.length()); i++) kept.put(entries.get(i));
        if (!prefs.edit().putString("history", kept.toString()).commit())
            throw new IllegalStateException("无法保存操作记录。");
        }
    }

    String readLog(String name) throws Exception {
        if (!name.matches("record-[0-9A-Za-z_-]+\\.txt")) return "本条记录没有保存的诊断日志。";
        File file = historyFiles.record(name);
        if (!file.isFile()) return "诊断日志文件缺失，请保留原始记录进行核对。";
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int length;
            while ((length=in.read(buffer))!=-1) {
                if (out.size()+length>1048576) throw new IllegalStateException("本条日志超过导出上限，原文件已保留。");
                out.write(buffer,0,length);
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    String latestLog(String method) {
        JSONArray entries = history();
        for (int i=0;i<entries.length();i++) {
            JSONObject item=entries.optJSONObject(i);
            if(item!=null && method.equals(item.optString("method"))) try { return readLog(item.optString("log_file")); }
            catch(Exception error) { return "读取已保存日志失败："+error.getMessage(); }
        }
        return "尚无已保存日志。";
    }
}
