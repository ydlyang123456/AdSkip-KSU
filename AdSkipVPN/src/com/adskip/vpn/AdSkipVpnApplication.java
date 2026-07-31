package com.adskip.vpn;

import android.app.Application;

/**
 * Application：初始化时加载黑名单（仅一次）。
 */
public final class AdSkipVpnApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Blocklist.load(getApplicationContext());
    }
}
