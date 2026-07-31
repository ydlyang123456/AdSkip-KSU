package com.adskip.vpn;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 已知 DoH（DNS over HTTPS）端点域名集。
 * 机制：在 DNS 层把这些端点域名解析为 0.0.0.0/NXDOMAIN，迫使 App 无法连上 DoH，
 * 回退到系统明文 DNS（即被本应用过滤的 tun DNS）。无需 TLS MITM。
 */
public final class DohBlocklist {
    private DohBlocklist() {
    }

    private static final Set<String> ENDPOINTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "dns.google",
            "dns.cloudflare.com",
            "dns.quad9.net",
            "dns.alidns.com",
            "doh.opendns.com",
            "doh.pub",
            "rubyfish.cn"
    )));

    public static Set<String> getEndpoints() {
        return ENDPOINTS;
    }

    /** 判断给定域名是否为已知 DoH 端点（支持子域）。 */
    public static boolean isDohEndpoint(String domain) {
        if (domain == null) {
            return false;
        }
        String d = domain.toLowerCase();
        while (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        for (String e : ENDPOINTS) {
            if (d.equals(e) || d.endsWith("." + e)) {
                return true;
            }
        }
        return false;
    }
}
