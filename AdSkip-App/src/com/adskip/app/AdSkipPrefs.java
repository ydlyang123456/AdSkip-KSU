package com.adskip.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍线 SharedPreferences 契约封装。
 *
 * <p>服务（{@link AdSkipAccessibilityService}）与桥（{@link AdSkipBridge}）共用同一份文件
 * （{@code adskip_prefs}，{@code MODE_PRIVATE}），保证两端读取一致。
 *
 * <p><b>集中管理约束：</b>键名 / 默认值一律以本类常量定义，禁止业务代码内联硬编码导致拼写漂移。
 * 所有键名严格对应设计文档第 3 节。
 */
public final class AdSkipPrefs {

    /** 文件名（MODE_PRIVATE）。 */
    public static final String PREFS_NAME = "adskip_prefs";

    // ---- 键名常量（集中管理，禁止拼写漂移） ----
    public static final String KEY_ENABLE_SKIP = "ENABLE_SKIP";
    public static final String KEY_ENABLED_APPS = "ENABLED_APPS";
    public static final String KEY_SKIP_KEYWORDS = "SKIP_KEYWORDS";
    public static final String KEY_EXCLUDE_KEYWORDS = "EXCLUDE_KEYWORDS";
    public static final String KEY_SKIP_DELAY_MS = "SKIP_DELAY_MS";
    public static final String KEY_SUBWINDOW_EXCLUDE = "SUBWINDOW_EXCLUDE";
    public static final String KEY_WIFI_ONLY = "WIFI_ONLY";
    /** v1.2：滑动关闭（全屏广告遮罩手势关闭），默认关。 */
    public static final String KEY_ENABLE_SLIDE_CLOSE = "ENABLE_SLIDE_CLOSE";
    public static final String KEY_STATS_DATE = "STATS_DATE";
    public static final String KEY_STATS_COUNT = "STATS_COUNT";

    /** 模块 config.sh 路径（root 侧，仅 {@link #getConfigFromShell(String)} 使用）。 */
    public static final String MODULE_CONFIG = "/data/adb/modules/adskip_ksu/config.sh";

    // ---- 默认值 ----
    public static final boolean DEF_ENABLE_SKIP = false;
    public static final int DEF_SKIP_DELAY_MS = 300;
    public static final boolean DEF_SUBWINDOW_EXCLUDE = true;
    public static final boolean DEF_WIFI_ONLY = false;
    /** v1.2：滑动关闭默认关（需用户在系统无障碍授予手势权限才生效）。 */
    public static final boolean DEF_ENABLE_SLIDE_CLOSE = false;

    private static final Set<String> DEF_ENABLED_APPS;
    private static final Set<String> DEF_SKIP_KEYWORDS;
    private static final Set<String> DEF_EXCLUDE_KEYWORDS;

    static {
        DEF_ENABLED_APPS = new LinkedHashSet<>();
        DEF_ENABLED_APPS.add("com.netease.cloudmusic");
        DEF_ENABLED_APPS.add("com.tencent.qqmusic");
        DEF_ENABLED_APPS.add("com.kugou.fan");
        DEF_ENABLED_APPS.add("cn.kuwo.player");

        DEF_SKIP_KEYWORDS = new LinkedHashSet<>();
        DEF_SKIP_KEYWORDS.add("跳过");
        DEF_SKIP_KEYWORDS.add("跳过广告");
        DEF_SKIP_KEYWORDS.add("跳过\\s*\\d+\\s*s?");
        DEF_SKIP_KEYWORDS.add("关闭");
        DEF_SKIP_KEYWORDS.add("close");
        DEF_SKIP_KEYWORDS.add("×");
        DEF_SKIP_KEYWORDS.add("X");
        // v1.2：倒计时跳过按钮（如「跳过(5)」「跳过(5s)」「跳过广告5s」）
        DEF_SKIP_KEYWORDS.add("跳过\\s*\\(\\d+\\)");
        DEF_SKIP_KEYWORDS.add("跳过\\s*\\(\\d+\\s*s?\\)");
        DEF_SKIP_KEYWORDS.add("跳过广告\\s*\\d+\\s*s?");
        // v1.2：安全点击候选（纯关闭/无副作用的弹窗关闭按钮，受整窗排除保护）
        //   设计 Q4 裁定：仅「知道了 / 稍后」作安全点击候选；「了解详情 / 立即体验」仅入排除词（绝不点）。
        DEF_SKIP_KEYWORDS.add("知道了");
        DEF_SKIP_KEYWORDS.add("稍后");

        DEF_EXCLUDE_KEYWORDS = new LinkedHashSet<>();
        DEF_EXCLUDE_KEYWORDS.add("确认支付");
        DEF_EXCLUDE_KEYWORDS.add("立即支付");
        DEF_EXCLUDE_KEYWORDS.add("开通会员");
        DEF_EXCLUDE_KEYWORDS.add("同意并继续");
        DEF_EXCLUDE_KEYWORDS.add("领取");
        DEF_EXCLUDE_KEYWORDS.add("开通");
        // v1.2：业务转化按钮，绝不自动点（防误触）
        DEF_EXCLUDE_KEYWORDS.add("了解详情");
        DEF_EXCLUDE_KEYWORDS.add("立即体验");
    }

    private final SharedPreferences sp;

    /** 构造。使用 ApplicationContext 避免 Activity / Service 泄漏。 */
    public AdSkipPrefs(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- 通用读写 ----
    public boolean getBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public void putBool(String key, boolean v) {
        sp.edit().putBoolean(key, v).apply();
    }

    public Set<String> getStringSet(String key, Set<String> def) {
        Set<String> v = sp.getStringSet(key, null);
        if (v == null || v.isEmpty()) {
            return def == null ? Collections.<String>emptySet() : new LinkedHashSet<>(def);
        }
        // 防御性拷贝，避免外部修改 SharedPreferences 内部集合（Android 已知坑）
        return new LinkedHashSet<>(v);
    }

    public void putStringSet(String key, Set<String> set) {
        sp.edit().putStringSet(key, set == null ? null : new LinkedHashSet<>(set)).apply();
    }

    public int getInt(String key, int def) {
        return sp.getInt(key, def);
    }

    public void putInt(String key, int v) {
        sp.edit().putInt(key, v).apply();
    }

    public String getString(String key, String def) {
        return sp.getString(key, def);
    }

    public void putString(String key, String v) {
        sp.edit().putString(key, v).apply();
    }

    // ---- 业务便捷读取（带默认） ----
    public boolean isSkipEnabled() {
        return getBool(KEY_ENABLE_SKIP, DEF_ENABLE_SKIP);
    }

    public Set<String> getEnabledApps() {
        return getStringSet(KEY_ENABLED_APPS, DEF_ENABLED_APPS);
    }

    public Set<String> getSkipKeywords() {
        return getStringSet(KEY_SKIP_KEYWORDS, DEF_SKIP_KEYWORDS);
    }

    public Set<String> getExcludeKeywords() {
        return getStringSet(KEY_EXCLUDE_KEYWORDS, DEF_EXCLUDE_KEYWORDS);
    }

    public int getSkipDelayMs() {
        return getInt(KEY_SKIP_DELAY_MS, DEF_SKIP_DELAY_MS);
    }

    public boolean isSubwindowExclude() {
        return getBool(KEY_SUBWINDOW_EXCLUDE, DEF_SUBWINDOW_EXCLUDE);
    }

    public boolean isWifiOnly() {
        return getBool(KEY_WIFI_ONLY, DEF_WIFI_ONLY);
    }

    /** v1.2：滑动关闭是否开启（默认关）。 */
    public boolean isSlideCloseEnabled() {
        return getBool(KEY_ENABLE_SLIDE_CLOSE, DEF_ENABLE_SLIDE_CLOSE);
    }

    // ---- 今日计数（跨天自动清零） ----
    public int getTodayCount() {
        String today = todayStr();
        String d = sp.getString(KEY_STATS_DATE, "");
        if (!today.equals(d)) {
            // 跨天：清零并重置归属日期
            sp.edit().putString(KEY_STATS_DATE, today).putInt(KEY_STATS_COUNT, 0).apply();
            return 0;
        }
        return sp.getInt(KEY_STATS_COUNT, 0);
    }

    public void incTodayCount() {
        String today = todayStr();
        String d = sp.getString(KEY_STATS_DATE, "");
        SharedPreferences.Editor ed = sp.edit();
        if (!today.equals(d)) {
            ed.putString(KEY_STATS_DATE, today).putInt(KEY_STATS_COUNT, 1);
        } else {
            ed.putInt(KEY_STATS_COUNT, sp.getInt(KEY_STATS_COUNT, 0) + 1);
        }
        ed.apply();
    }

    private static String todayStr() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return sdf.format(new Date());
        } catch (Exception e) {
            return "1970-01-01";
        }
    }

    // ---- 模块开关读取（root 侧 config.sh，best-effort） ----
    /**
     * 经 su 读取模块 config.sh 中的变量值（如 SKIP_ADSDK）。
     * 仅用于只读展示；调用方必须先在白名单内校验 key。失败返回 null。
     */
    public String getConfigFromShell(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        // 仅允许白名单字符，杜绝注入
        if (!key.matches("^[A-Za-z0-9_]+$")) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c",
                    "grep '^" + key + "=' " + MODULE_CONFIG
                            + " | head -n1 | cut -d= -f2- | tr -d '\"'");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            try {
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            r.close();
            String out = sb.toString().trim();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
}
