package dev.ghostlock.honor;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Process;
import dev.ghostlock.honor.shizuku.IRestoreService;
import dev.ghostlock.honor.shizuku.RestoreService;
import rikka.shizuku.Shizuku;

final class ShizukuConnection {
    private final Context context;
    private final Shizuku.UserServiceArgs args;
    private volatile IRestoreService remote;
    private volatile String message = "尚未连接";
    private volatile boolean binding;
    private final Shizuku.OnBinderDeadListener dead = () -> { remote = null; binding = false; message = "服务已断开"; };
    private final Shizuku.OnRequestPermissionResultListener permission = (request, result) -> {
        if (result == PackageManager.PERMISSION_GRANTED) connect(); else message = "尚未获得授权";
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IRestoreService.Stub.asInterface(binder); binding = false; message = "已连接 · 请检查状态";
        }
        @Override public void onServiceDisconnected(ComponentName name) { dead.onBinderDead(); }
    };

    ShizukuConnection(Context context) {
        this.context = context;
        args = new Shizuku.UserServiceArgs(new ComponentName(context, RestoreService.class))
            .daemon(true).processNameSuffix("restore").debuggable(false)
            .tag("ghostlock-honor").version(Supported.VERSION_CODE);
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
    }
    boolean connected() { return remote != null; }
    String message() { return message; }
    boolean running() { try { return Shizuku.pingBinder(); } catch (Exception error) { return false; } }

    void authorize() {
        try {
            if (!running()) { message = "请先在 Shizuku 中启动服务"; return; }
            if (Shizuku.getUid() != Process.SHELL_UID) { message = "需要通过无线调试启动的 shell 模式"; return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(31);
            else connect();
        } catch (Exception error) { message = "连接失败：" + error.getMessage(); }
    }

    private void connect() {
        if (remote != null || binding) return;
        try {
            if (!running() || Shizuku.getUid() != Process.SHELL_UID
                || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            binding = true; message = "正在连接…";
            Shizuku.bindUserService(args, connection);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (remote == null && binding) { binding = false; message = "连接超时，请重试连接"; }
            }, 15000);
        } catch (Exception error) { binding = false; message = "连接失败：" + error.getMessage(); }
    }

    IRestoreService require() {
        IRestoreService current = remote;
        if (current == null) throw new IllegalStateException("Shizuku 尚未连接，请先连接并授权。");
        return current;
    }

    void close() {
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        // remove=false disconnects this client, without terminating the daemon or native worker.
        try { Shizuku.unbindUserService(args, connection, false); } catch (Exception ignored) {}
    }
}
