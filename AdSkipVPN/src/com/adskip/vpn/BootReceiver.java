package com.adskip.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/**
 * 开机 / 应用更新后自启：仅当用户上次已启用 VPN 且已授予 VPN 授权（prepare()==null）时启动服务。
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            if (Prefs.isEnabled(c) && VpnService.prepare(c) == null) {
                c.startService(new Intent(c, AdSkipVpnService.class));
            }
        }
    }
}
