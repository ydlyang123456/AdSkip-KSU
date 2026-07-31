package com.adskip.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
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
        AccessibilityNodeInfo candidate = findSkipNode(root, regex);
        if (candidate == null) {
            // root 已在 findSkipNode 内回收；再尝试识别全屏广告遮罩的关闭按钮（X / 关闭）
            candidate = findCloseNode();
        }
        if (candidate == null) {
            return;
        }

        // 6) 第二次取根（独立副本）：整窗排除词扫描，完全回收此副本，不影响 candidate
        boolean excluded = false;
        AccessibilityNodeInfo exRoot = getRootInActiveWindow();
        if (exRoot != null) {
            excluded = scanExclude(exRoot, regex); // scanExclude 内部回收整棵树（含 exRoot）
        }

        // 7) 防误触：命中排除词则不点
        if (excluded) {
            candidate.recycle();
            return;
        }

        // 8) 点击型 / 滑动型统一处理（内部回收 candidate）
        handleCandidate(candidate, regex);
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

    /**
     * v1.2：识别全屏广告遮罩的关闭按钮（X / 关闭）。
     * 独立取根（与 {@link #findSkipNode} 的 root 副本互不干扰），遍历所有可点击节点，
     * 命中「关闭文本」且其祖先（或自身）覆盖屏幕 ≥ 90%（近似全屏遮罩）时返回该节点。
     * 返回节点由调用方回收恰好一次；遍历过程中其余节点立即回收。
     */
    private AccessibilityNodeInfo findCloseNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        Rect screen = getScreenRect();
        long screenArea = (long) screen.width() * screen.height();
        LinkedList<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.add(root);
        AccessibilityNodeInfo found = null;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.pollFirst();
            if (node == null) {
                continue;
            }
            boolean matched = false;
            if (node.isClickable()) {
                CharSequence txt = node.getText();
                if (txt != null && isCloseText(txt.toString())) {
                    matched = true;
                } else {
                    CharSequence cd = node.getContentDescription();
                    if (cd != null && isCloseText(cd.toString())) {
                        matched = true;
                    }
                }
            }
            if (matched && insideFullscreen(node, screen, screenArea)) {
                found = node;
                break;
            }
            int cnt = node.getChildCount();
            for (int i = 0; i < cnt; i++) {
                queue.add(node.getChild(i));
            }
            if (node != root) {
                node.recycle();
            }
        }
        // 回收队列中未处理的节点（found 不回收，由调用方处理）
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.pollFirst();
            if (n != null && n != found) {
                n.recycle();
            }
        }
        // 命中节点的祖先（含 root）已由 insideFullscreen 回收；仅当未命中时 root 需在此回收
        if (found == null) {
            root.recycle();
        }
        return found;
    }

    /**
     * 判断节点是否位于「近似全屏容器」内（祖先或自身 bounds 覆盖屏幕 ≥ 90%）。
     * 遍历 node 的父链，回收除 node 自身外的所有祖先 wrapper（避免泄漏）。
     */
    private boolean insideFullscreen(AccessibilityNodeInfo node, Rect screen, long screenArea) {
        AccessibilityNodeInfo cur = node;
        while (cur != null) {
            Rect b = new Rect();
            cur.getBoundsInScreen(b);
            long area = (long) b.width() * b.height();
            boolean full = screenArea > 0 && area >= screenArea * 9 / 10;
            AccessibilityNodeInfo parent = cur.getParent();
            if (cur != node) {
                cur.recycle();
            }
            cur = parent;
            if (full) {
                return true;
            }
        }
        return false;
    }

    /** 是否命中「关闭按钮」文本（关闭 / 跳过 / × / X / close）。 */
    private static boolean isCloseText(String s) {
        if (s == null) {
            return false;
        }
        if (s.contains("关闭") || s.contains("跳过")) {
            return true;
        }
        String t = s.trim();
        return t.equals("×") || t.equals("X") || t.equals("x") || t.equalsIgnoreCase("close");
    }

    /** 取屏幕尺寸（用于全屏判定与滑动手势坐标）。 */
    private Rect getScreenRect() {
        Rect r = new Rect();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                Display d = wm.getDefaultDisplay();
                Point p = new Point();
                d.getRealSize(p);
                r.set(0, 0, p.x, p.y);
            }
        } catch (Exception e) {
            r.set(0, 0, 1080, 2400);
        }
        return r;
    }

    /**
     * 统一处理命中候选：滑动型（ENABLE_SLIDE_CLOSE 开启且命中滑动提示）走手势关闭，
     * 否则延迟 + 可见可点击后点击。内部回收 candidate 恰好一次。
     */
    private void handleCandidate(final AccessibilityNodeInfo node, AdSkipRegex regex) {
        if (node == null) {
            return;
        }
        CharSequence txt = node.getText();
        String text = (txt != null) ? txt.toString() : "";
        if (text.isEmpty()) {
            CharSequence cd = node.getContentDescription();
            if (cd != null) {
                text = cd.toString();
            }
        }
        if (prefs.isSlideCloseEnabled() && regex.matchesSlideHint(text)) {
            dispatchGestureSlide();
            prefs.incTodayCount();
            node.recycle();
            return;
        }
        performSkipClick(node, prefs.getSkipDelayMs());
    }

    /** v1.2：滑动关闭全屏广告（dispatchGesture 手势；API>=24 且已授权手势权限）。 */
    @SuppressLint("NewApi")
    private void dispatchGestureSlide() {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        Point size = new Point();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealSize(size);
            } else {
                size.set(1080, 2400);
            }
        } catch (Exception e) {
            size.set(1080, 2400);
        }
        // v1.2：水平滑动（从左到右，横跨屏幕中部）
        int cy = size.y / 2;
        int x1 = (int) (size.x * 0.25f);
        int x2 = (int) (size.x * 0.75f);
        Path path = new Path();
        path.moveTo(x1, cy);
        path.lineTo(x2, cy);
        long now = System.currentTimeMillis();
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, now, 250);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(stroke);
        dispatchGesture(b.build(), null, null);
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
