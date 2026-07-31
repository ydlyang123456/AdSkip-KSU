package com.adskip.vpn;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.HashSet;
import java.util.Set;

/**
 * 拦截统计：总次数 + 按域 + 按 UID（UID→包名展示）。持久化到 SharedPreferences。
 */
public final class BlockStats {
    private BlockStats() {
    }

    public static synchronized void incBlocked(Context ctx, String domain, int uid) {
        android.content.SharedPreferences p = Prefs.get(ctx);
        android.content.SharedPreferences.Editor e = p.edit();
        int total = p.getInt(Prefs.KEY_STATS_TOTAL, 0) + 1;
        e.putInt(Prefs.KEY_STATS_TOTAL, total);
        e.putInt(Prefs.PREFIX_STATS_DOMAIN + domain,
                p.getInt(Prefs.PREFIX_STATS_DOMAIN + domain, 0) + 1);
        if (uid >= 0) {
            e.putInt(Prefs.PREFIX_STATS_UID + uid,
                    p.getInt(Prefs.PREFIX_STATS_UID + uid, 0) + 1);
        }
        e.apply();
    }

    public static int getTotal(Context ctx) {
        return Prefs.get(ctx).getInt(Prefs.KEY_STATS_TOTAL, 0);
    }

    public static int getDomainCount(Context ctx, String domain) {
        return Prefs.get(ctx).getInt(Prefs.PREFIX_STATS_DOMAIN + domain, 0);
    }

    public static int getUidCount(Context ctx, int uid) {
        return Prefs.get(ctx).getInt(Prefs.PREFIX_STATS_UID + uid, 0);
    }

    /** 把 UID 映射为可读包名（未知/系统返回「未知应用」）。 */
    public static String formatUid(Context ctx, int uid) {
        if (uid < 0) {
            return "未知应用";
        }
        PackageManager pm = ctx.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs != null && pkgs.length > 0) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
                return pm.getApplicationLabel(ai).toString() + " (" + pkgs[0] + ")";
            } catch (Exception e) {
                return pkgs[0];
            }
        }
        return "未知应用(uid=" + uid + ")";
    }

    public static Set<String> getBlockedDomains(Context ctx) {
        Set<String> result = new HashSet<>();
        for (String k : Prefs.get(ctx).getAll().keySet()) {
            if (k.startsWith(Prefs.PREFIX_STATS_DOMAIN)) {
                result.add(k.substring(Prefs.PREFIX_STATS_DOMAIN.length()));
            }
        }
        return result;
    }

    public static synchronized void reset(Context ctx) {
        android.content.SharedPreferences p = Prefs.get(ctx);
        android.content.SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            if (k.startsWith(Prefs.PREFIX_STATS_DOMAIN)
                    || k.startsWith(Prefs.PREFIX_STATS_UID)
                    || Prefs.KEY_STATS_TOTAL.equals(k)) {
                e.remove(k);
            }
        }
        e.apply();
    }
}
