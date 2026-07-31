package com.adskip.vpn;

import android.app.Application;

/**
 * Application：初始化时在后台线程加载黑名单（仅一次），避免主线程 IO 阻塞/ANR。
 */
public final class AdSkipVpnApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Blocklist.load(getApplicationContext());
            }
        }, "blocklist-loader").start();
    }
}
