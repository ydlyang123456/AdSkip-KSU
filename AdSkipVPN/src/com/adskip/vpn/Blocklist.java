package com.adskip.vpn;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 域名黑名单：加载 assets/blocklist.txt（离线内置，仅广告/追踪/SDK 服务端域）。
 * 匹配采用「域名后缀」策略，支持子域（如 a.b.ad.com 命中 b.ad.com）。
 *
 * 注：DoH 端点不并入本表，由 DnsProxy 在 DoH 开关开启时单独判定（见 DohBlocklist），
 * 以便 DoH 开关可独立生效。运行时「从模块导入」的域名通过 addDomain 追加。
 */
public final class Blocklist {
    private Blocklist() {
    }

    private static final Set<String> DOMAINS = new CopyOnWriteArraySet<>();
    private static volatile boolean sLoaded = false;

    /** 加载离线黑名单（仅一次）。 */
    public static synchronized void load(Context ctx) {
        if (sLoaded) {
            return;
        }
        DOMAINS.clear();
        loadFromAssets(ctx, "blocklist.txt");
        sLoaded = true;
    }

    private static void loadFromAssets(Context ctx, String name) {
        AssetManager am = ctx.getApplicationContext().getAssets();
        try (InputStream in = am.open(name);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String d = normalize(line);
                if (!d.isEmpty()) {
                    DOMAINS.add(d);
                }
            }
        } catch (IOException e) {
            // 资源缺失时黑名单为空，不影响启动
        }
    }

    /** 运行时追加域名（「从模块导入」使用）。 */
    public static void addDomain(String domain) {
        String d = canonical(domain);
        if (!d.isEmpty()) {
            DOMAINS.add(d);
        }
    }

    private static String normalize(String line) {
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#")) {
            return "";
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h).trim();
        }
        return canonical(s);
    }

    private static String canonical(String d) {
        d = d.toLowerCase();
        while (d.length() > 1 && d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    /** 命中黑名单（含子域后缀匹配）。 */
    public static boolean match(String domain) {
        if (domain == null) {
            return false;
        }
        String d = canonical(domain);
        for (String block : DOMAINS) {
            if (d.equals(block) || d.endsWith("." + block)) {
                return true;
            }
        }
        return false;
    }

    public static int size() {
        return DOMAINS.size();
    }

    public static Set<String> domains() {
        return new HashSet<>(DOMAINS);
    }
}
