package com.adskip.vpn;

import android.net.VpnService;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 上游明文 DNS 转发器。经 VpnService.protect() 排除自身出 tun，避免回环。
 * 依次尝试多个受控上游（223.5.5.5 / 119.29.29.29），任一成功即返回响应。
 */
public final class DnsResolver {
    private DnsResolver() {
    }

    private static final String[] UPSTREAMS = {"223.5.5.5", "119.29.29.29"};

    public static byte[] resolve(VpnService svc, byte[] query, int timeoutMs) {
        for (String up : UPSTREAMS) {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                if (svc != null) {
                    svc.protect(sock);
                }
                sock.setSoTimeout(timeoutMs);
                InetAddress addr = InetAddress.getByName(up);
                DatagramPacket req = new DatagramPacket(query, query.length, addr, 53);
                sock.send(req);
                byte[] buf = new byte[4096];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                sock.receive(resp);
                int n = resp.getLength();
                byte[] result = new byte[n];
                System.arraycopy(buf, 0, result, 0, n);
                return result;
            } catch (Exception e) {
                // 尝试下一个上游
            } finally {
                if (sock != null) {
                    sock.close();
                }
            }
        }
        return null;
    }
}
