package com.adskip.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * P1：开机 / 应用更新后自启保活服务。
 *
 * <p>仅在 {@code ENABLE_SKIP=true} 时启动（设计 §八 待明确事项 #3：默认不强制）。
 * 无障碍服务本身无法由 App 自动启用，故此处仅启动前台保活服务，并经由通知/App 引导用户回到系统设置开启。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!"android.intent.action.BOOT_COMPLETED".equals(action)
                && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            return;
        }
        AdSkipPrefs prefs = new AdSkipPrefs(context);
        if (!prefs.isSkipEnabled()) {
            return; // 默认不强制启动
        }
        Intent svc = new Intent(context, AdSkipKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {
            // 自启为兜底，失败不影响其他逻辑
        }
    }
}
