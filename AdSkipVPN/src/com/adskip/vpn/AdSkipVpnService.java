package com.adskip.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

/**
 * 核心 VpnService（foreground）。建立 DNS-only 分流隧道，启动 VpnTunnel 读包循环，
 * 并维持常驻通知（显示已拦截次数）。
 */
public final class AdSkipVpnService extends VpnService {
    private static final String CHANNEL_ID = "adskip_vpn_channel";
    private static final int NOTIF_ID = 1;
    private static volatile boolean sRunning = false;

    private ParcelFileDescriptor pfd;
    private VpnTunnel tunnel;
    private Thread tunnelThread;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (sRunning && tunnel != null && tunnel.isRunning()) {
                updateNotification();
                uiHandler.postDelayed(this, 2000);
            }
        }
    };

    public static boolean isRunning() {
        return sRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Blocklist.load(getApplicationContext());
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        Builder b = new Builder();
        b.addAddress("10.0.0.1", 32);
        // 仅把 DNS 服务器 IP 路由进 tun（分流，不捕获 0.0.0.0/0）
        b.addRoute("10.0.0.1", 32);
        // 系统解析器把 DNS 查询发往 tun
        b.addDnsServer("10.0.0.1");
        // 对每个「放行 App」调用 addDisallowedApplication（绕过 VPN 用真实 DNS）
        for (String pkg : AppPolicy.getAllowedApps(this)) {
            try {
                b.addDisallowedApplication(pkg);
            } catch (Exception e) {
                // 包已卸载等：忽略
            }
        }
        b.setSession("AdSkipVPN");
        pfd = b.establish();
        if (pfd == null) {
            Prefs.setEnabled(this, false);
            stopSelf();
            return;
        }
        sRunning = true;
        startForeground(NOTIF_ID, buildNotification());
        tunnel = new VpnTunnel(this, pfd);
        tunnelThread = new Thread(tunnel, "AdSkipVpnTunnel");
        tunnelThread.start();
        uiHandler.postDelayed(refreshTask, 2000);
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        uiHandler.removeCallbacks(refreshTask);
        if (tunnel != null) {
            tunnel.stop();
        }
        if (tunnelThread != null) {
            tunnelThread.interrupt();
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception e) {
                // ignore
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("AdSkip VPN 拦截通知");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        int piflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piflags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piflags);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.vpn_notif_title))
                .setContentText(getString(R.string.vpn_notif_text, BlockStats.getTotal(this)))
                .setSmallIcon(R.drawable.ic_vpn_notify)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
    }
}
