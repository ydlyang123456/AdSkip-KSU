package com.adskip.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * AdSkip 管理器主入口 Activity。
 *
 * <p>通过 WebView 加载本地 {@code assets/index.html}，并注入 {@link AdSkipBridge}
 * 供页面 JS 调用 root 命令（KernelSU / Magisk / APatch 通用）。
 * App 自身不声明任何网络权限，所有模块管理动作都经由 {@code su} 调用模块脚本完成。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private AdSkipBridge bridge;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();

        // 启用 JS 与本地文件访问（加载 file:///android_asset）
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        // HTML 仅加载内置 assets，无需跨源文件访问，关闭以降低理论攻击面
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient());
        bridge = new AdSkipBridge(this);
        // 暴露桥对象给 JS，名称 AdSkipBridge
        webView.addJavascriptInterface(bridge, "AdSkipBridge");
        webView.loadUrl("file:///android_asset/index.html");

        setContentView(webView);

        // 启动即在子线程预热 root 检测，提前弹出 su 授权并缓存结果，供 JS 读取。
        new Thread(new Runnable() {
            @Override
            public void run() {
                bridge.warmUpRoot();
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统无障碍设置返回后，即时刷新「开屏跳过」页权限状态（页面已加载时生效）
        if (webView != null) {
            webView.evaluateJavascript("if(window.refreshSkip)window.refreshSkip();", null);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
