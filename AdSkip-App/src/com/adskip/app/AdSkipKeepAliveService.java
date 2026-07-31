package com.adskip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * P1：前台保活服务（specialUse）。
 *
 * <p>仅在 API>=31 且用户已开启 {@code ENABLE_SKIP} 时，由 {@link AdSkipAccessibilityService}
 * 或 {@link BootReceiver} 启动，持常驻通知以提示用户并兜底保活。
 * <b>默认不强制启动</b>（{@code ENABLE_SKIP=false} 时不会启动），符合设计 §八 待明确事项 #3。
 */
public class AdSkipKeepAliveService extends Service {

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "adskip_keepalive";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    private Notification buildNotification() {
        // 注：本类不引用 R（保持 build.py 无需改动——其 javac 阶段不生成 R.java）。
        // 文案与 strings.xml 保持一致；图标经 getIdentifier 运行时解析。
        String title = "AdSkip 开屏跳过运行中";
        String text = "正在守护无障碍跳过服务（点击打开设置）";
        String channelName = "AdSkip 保活";

        int iconId = getResources().getIdentifier("ic_adskip_notify", "drawable", getPackageName());
        if (iconId == 0) {
            iconId = android.R.drawable.ic_dialog_info;
        }

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        channelName, NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setAction("com.adskip.app.OPEN_SKIP");
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(iconId)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
