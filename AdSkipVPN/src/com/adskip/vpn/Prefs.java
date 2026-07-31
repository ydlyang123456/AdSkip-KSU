package com.adskip.vpn;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 集中管理 VPN 应用的 SharedPreferences 契约：开关 / DoH 开关 / 按 App 策略 / 统计键。
 * 所有键名只在本文定义，禁止业务代码内联（参见 v1.2 设计 §十 共享知识）。
 */
public final class Prefs {
    private Prefs() {
    }

    public static final String NAME = "adskip_vpn_prefs";

    /** 总开关：VPN 是否启用（持久化，用于 BootReceiver 自启判断）。 */
    public static final String KEY_ENABLED = "enabled";
    /** DoH 端点阻断开关（默认开）。 */
    public static final String KEY_DOH_ENABLED = "doh_enabled";
    /** 按 App 放行集合（逗号分隔的包名，绕过 VPN 走真实 DNS）。 */
    public static final String KEY_ALLOWED_APPS = "allowed_apps";
    /** 总拦截次数。 */
    public static final String KEY_STATS_TOTAL = "stats_total";
    /** 按域统计键前缀：stats_domain_<domain>。 */
    public static final String PREFIX_STATS_DOMAIN = "stats_domain_";
    /** 按 UID 统计键前缀：stats_uid_<uid>。 */
    public static final String PREFIX_STATS_UID = "stats_uid_";

    public static final boolean DEF_ENABLED = false;
    public static final boolean DEF_DOH_ENABLED = true;

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return get(c).getBoolean(KEY_ENABLED, DEF_ENABLED);
    }

    public static void setEnabled(Context c, boolean v) {
        get(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static boolean isDohEnabled(Context c) {
        return get(c).getBoolean(KEY_DOH_ENABLED, DEF_DOH_ENABLED);
    }

    public static void setDohEnabled(Context c, boolean v) {
        get(c).edit().putBoolean(KEY_DOH_ENABLED, v).apply();
    }
}
