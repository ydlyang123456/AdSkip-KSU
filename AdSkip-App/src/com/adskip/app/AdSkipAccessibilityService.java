package com.adskip.app;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.LinkedList;
import java.util.Set;

/**
 * AdSkip 无障碍开屏自动跳过服务。
 *
 * <p>监听目标 App 的开屏 / 弹窗窗口变化（{@code TYPE_WINDOW_STATE_CHANGED} /
 * {@code TYPE_WINDOW_CONTENT_CHANGED}），遍历可点击节点匹配「跳过」按钮文案，
 * 命中后先做整窗排除词扫描（防误触），再（P1）延迟 + 可见可点击约束后执行点击，并累计今日跳过次数。
 *
 * <p>所有配置来自 {@link AdSkipPrefs}（本地 SharedPreferences，免 root）；命中 / 排除匹配来自
 * {@link AdSkipRegex}。与 hosts 线（模块 config.sh）完全解耦、互不影响（设计文档 §一 / §七）。
 *
 * <p><b>节点回收约定：</b>每次 {@code getRootInActiveWindow()} / {@code getChild()} 返回独立 wrapper，
 * 须各回收一次。本类：
 * <ul>
 *   <li>{@link #findSkipNode} 遍历时回收所有非命中节点，仅返回命中节点（调用方回收一次）；</li>
 *   <li>整窗排除扫描在<b>独立</b>的第二次取根副本上进行，整棵回收，与命中节点互不干扰；</li>
 *   <li>命中节点最终由排除分支或 {@link #performSkipClick} 回收恰好一次。</li>
 * </ul>
 */
@SuppressLint("Registered") // 在 AndroidManifest.xml 中声明
public class AdSkipAccessibilityService extends AccessibilityService {

    private AdSkipPrefs prefs;
    private Handler mainHandler;

    @Override
    public void onServiceConnected() {
        prefs = new AdSkipPrefs(this);
        mainHandler = new Handler(Looper.getMainLooper());
        // P1：API>=31 且已开启时，启动前台保活服务（默认不强制；仅 ENABLE_SKIP=true 时）
        if (Build.VERSION.SDK_INT >= 31 && prefs.isSkipEnabled()) {
            startKeepAlive();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null || event == null) {
            return;
        }

        // 1) 总开关（默认关；未授权时 UI 禁用，此处双保险）
        if (!prefs.isSkipEnabled()) {
            return;
        }

        // 2) 包名过滤（运行时以 ENABLED_APPS 为准；清单 packageNames 仅作提示）
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        Set<String> enabledApps = prefs.getEnabledApps();
        if (enabledApps == null || enabledApps.isEmpty()) {
            // 启用列表为空 = 等同关闭，对所有 App 不生效（设计 §八 待明确事项 #4）
            return;
        }
        if (!enabledApps.contains(pkg.toString())) {
            return;
        }

        // 3) P2：仅 WiFi 下跳过（best-effort，无网络权限时 fail-open 保持默认行为）
        if (!isWifiOk()) {
            return;
        }

        // 4) 编译正则（来自当前配置，支持运行时编辑）
        AdSkipRegex regex = new AdSkipRegex(prefs.getSkipKeywords(), prefs.getExcludeKeywords());

        // 5) 第一次取根：找跳过按钮（findSkipNode 内部回收所有遍历到的节点，仅返回命中节点）
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo skipNode = findSkipNode(root, regex);
        if (skipNode == null) {
            // root 已在 findSkipNode 内回收
            return;
        }

        // 6) 第二次取根（独立副本）：整窗排除词扫描，完全回收此副本，不影响 skipNode
        boolean excluded = false;
        AccessibilityNodeInfo exRoot = getRootInActiveWindow();
        if (exRoot != null) {
            excluded = scanExclude(exRoot, regex); // scanExclude 内部回收整棵树（含 exRoot）
        }

        // 7) 防误触：命中排除词则不点
        if (excluded) {
            skipNode.recycle();
            return;
        }

        // 8) 延迟 + 可见可点击约束后点击（内部回收 skipNode）
        performSkipClick(skipNode, prefs.getSkipDelayMs());
    }

    /**
     * 遍历可点击节点，返回首个命中跳过正则的节点（调用方负责 recycle 恰好一次）。
     * 遍历过程中所有非命中节点立即回收；命中节点作为唯一未回收者返回。
     */
    private AccessibilityNodeInfo findSkipNode(AccessibilityNodeInfo root, AdSkipRegex regex) {
        if (root == null) {
            return null;
        }
        LinkedList<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.pollFirst();
            if (node == null) {
                continue;
            }
            boolean matched = false;
            if (node.isClickable()) {
                CharSequence txt = node.getText();
                if (txt != null && regex.matchesSkip(txt.toString())) {
                    matched = true;
                } else {
                    CharSequence cd = node.getContentDescription();
                    if (cd != null && regex.matchesSkip(cd.toString())) {
                        matched = true;
                    }
                }
            }
            int cnt = node.getChildCount();
            for (int i = 0; i < cnt; i++) {
                queue.add(node.getChild(i));
            }
            if (matched) {
                return node; // 调用方 recycle
            }
            node.recycle();
        }
        return null;
    }

    /**
     * 整窗排除词扫描：遍历收集所有节点文本 + contentDescription，命中排除正则则返回 true。
     * 回收遍历到的整棵节点（含传入的 root 副本），调用方不应再回收该副本。
     */
    private boolean scanExclude(AccessibilityNodeInfo root, AdSkipRegex regex) {
        if (root == null) {
            return false;
        }
        if (!prefs.isSubwindowExclude()) {
            // 仅做局部（根自身）扫描
            CharSequence txt = root.getText();
            if (txt != null && regex.containsExclude(txt.toString())) {
                root.recycle();
                return true;
            }
            CharSequence cd = root.getContentDescription();
            boolean hit = cd != null && regex.containsExclude(cd.toString());
            root.recycle();
            return hit;
        }
        LinkedList<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.add(root);
        StringBuilder sb = new StringBuilder();
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.pollFirst();
            if (node == null) {
                continue;
            }
            CharSequence txt = node.getText();
            if (txt != null) {
                sb.append(txt).append('\n');
            }
            CharSequence cd = node.getContentDescription();
            if (cd != null) {
                sb.append(cd).append('\n');
            }
            int cnt = node.getChildCount();
            for (int i = 0; i < cnt; i++) {
                queue.add(node.getChild(i));
            }
            node.recycle(); // 回收每一个访问过的节点（含传入 root 副本）
        }
        return regex.containsExclude(sb.toString());
    }

    /** 延迟后执行点击（P1：延迟 + 可见可点击约束）。 */
    private void performSkipClick(final AccessibilityNodeInfo node, final int delayMs) {
        if (node == null) {
            return;
        }
        if (delayMs <= 0) {
            doClick(node);
            return;
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                doClick(node);
            }
        }, delayMs);
    }

    private void doClick(AccessibilityNodeInfo node) {
        try {
            // P1 约束：延迟后再次确认节点仍可见且可点击，避免点到错误位置
            if (node.isVisibleToUser() && node.isClickable()) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    prefs.incTodayCount();
                }
            }
        } catch (Exception ignored) {
            // 节点可能已失效（窗口关闭），忽略
        } finally {
            node.recycle(); // 恰好回收一次
        }
    }

    @SuppressLint("MissingPermission")
    private boolean isWifiOk() {
        if (!prefs.isWifiOnly()) {
            return true;
        }
        // 需要 ACCESS_NETWORK_STATE；若无可声明（硬约束：App 无网络权限），fail-open 允许跳过。
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) {
                    return true;
                }
                android.net.NetworkCapabilities cap = cm.getNetworkCapabilities(n);
                if (cap == null) {
                    return true;
                }
                return cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
            }
        } catch (SecurityException e) {
            return true; // 无权限时 fail-open，保持默认行为（不新增网络权限）
        } catch (Exception e) {
            return true;
        }
    }

    private void startKeepAlive() {
        try {
            Intent i = new Intent(this, AdSkipKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception ignored) {
            // 保活为兜底，失败不影响跳过逻辑
        }
    }

    @Override
    public void onInterrupt() {
        // 服务被系统中断；无需特殊处理
    }
}
