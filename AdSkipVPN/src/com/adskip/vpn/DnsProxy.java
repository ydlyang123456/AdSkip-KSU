package com.adskip.vpn;

import android.content.Context;
import android.net.VpnService;

/**
 * DNS 过滤决策核心：解析查询域名 → 命中黑名单/DoH 端点则伪造应答，否则转发上游。
 * uid 来自 tun 层（raw 包无法取得，统一传 -1；不影响 MVP 拦截统计语义）。
 */
public final class DnsProxy {
    private DnsProxy() {
    }

    public static byte[] handle(Context ctx, byte[] query, int uid) {
        DnsPacket pkt = DnsPacket.parse(query);
        if (pkt == null || pkt.qname == null || pkt.qname.isEmpty()) {
            return null;
        }

        boolean doh = Prefs.isDohEnabled(ctx) && DohBlocklist.isDohEndpoint(pkt.qname);
        boolean blocked = doh || Blocklist.match(pkt.qname);

        if (blocked) {
            BlockStats.incBlocked(ctx, pkt.qname, uid);
            // DoH 端点与广告域一律返回 0.0.0.0（或 ::），强制阻断/回退
            return DnsPacket.forgeBlocked(pkt, false);
        }

        // 未命中：转发上游明文 DNS
        VpnService svc = (ctx instanceof VpnService) ? (VpnService) ctx : null;
        return DnsResolver.resolve(svc, query, 5000);
    }
}
