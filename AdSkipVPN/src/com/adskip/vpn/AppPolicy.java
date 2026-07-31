package com.adskip.vpn;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

/**
 * 按 App 放行/拦截策略：allowedApps 中的包名经 builder.addDisallowedApplication 绕过 VPN，
 * 使用真实 DNS（即「放行」）；其余 App 的 DNS 全部经过滤（即「拦截」）。持久化到 SharedPreferences。
 */
public final class AppPolicy {
    private AppPolicy() {
    }

    public static Set<String> getAllowedApps(Context c) {
        String s = Prefs.get(c).getString(Prefs.KEY_ALLOWED_APPS, "");
        Set<String> set = new HashSet<>();
        if (s != null && !s.isEmpty()) {
            for (String p : s.split(",")) {
                if (!p.isEmpty()) {
                    set.add(p);
                }
            }
        }
        return set;
    }

    public static void setAllowedApps(Context c, Set<String> pkgs) {
        Prefs.get(c).edit().putString(Prefs.KEY_ALLOWED_APPS, join(pkgs)).apply();
    }

    public static boolean isAllowed(Context c, String pkg) {
        return getAllowedApps(c).contains(pkg);
    }

    public static void setAllowed(Context c, String pkg, boolean allowed) {
        Set<String> s = getAllowedApps(c);
        if (allowed) {
            s.add(pkg);
        } else {
            s.remove(pkg);
        }
        setAllowedApps(c, s);
    }

    private static String join(Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
