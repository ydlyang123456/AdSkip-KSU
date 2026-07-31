package com.adskip.vpn;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * tun 报文循环：从 VpnService 的 fd 读取 IP 包，最小解析 IPv4+UDP，
 * 取出 DNS payload 交给 DnsProxy，再把伪造/上游应答写回 tun。
 */
public final class VpnTunnel implements Runnable {
    private final VpnService svc;
    private final ParcelFileDescriptor pfd;
    private volatile boolean running = true;

    public VpnTunnel(VpnService svc, ParcelFileDescriptor pfd) {
        this.svc = svc;
        this.pfd = pfd;
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        FileDescriptor fd = pfd.getFileDescriptor();
        try (FileInputStream in = new FileInputStream(fd);
             FileOutputStream out = new FileOutputStream(fd)) {
            byte[] buf = new byte[65536];
            while (running) {
                int n = in.read(buf);
                if (n <= 0) {
                    break; // tun 已关闭
                }
                byte[] pkt = new byte[n];
                System.arraycopy(buf, 0, pkt, 0, n);
                handlePacket(pkt, out);
            }
        } catch (IOException e) {
            // 循环结束
        }
    }

    private void handlePacket(byte[] pkt, FileOutputStream out) {
        if (pkt.length < 20) {
            return;
        }
        int version = (pkt[0] & 0xf0) >> 4;
        if (version != 4) {
            return; // 仅支持 IPv4
        }
        int ihl = (pkt[0] & 0x0f) * 4;
        if (ihl < 20 || ihl > pkt.length) {
            return;
        }
        if ((pkt[9] & 0xff) != 17) {
            return; // 仅 UDP
        }

        int dstPort = ((pkt[ihl + 2] & 0xff) << 8) | (pkt[ihl + 3] & 0xff);
        if (dstPort != 53) {
            return; // 仅处理 DNS(53)
        }

        int udpLen = ((pkt[ihl + 4] & 0xff) << 8) | (pkt[ihl + 5] & 0xff);
        int dataOff = ihl + 8;
        int dataLen = udpLen - 8;
        if (dataOff + dataLen > pkt.length) {
            dataLen = pkt.length - dataOff;
        }
        if (dataLen < 12) {
            return;
        }

        byte[] dns = new byte[dataLen];
        System.arraycopy(pkt, dataOff, dns, 0, dataLen);

        byte[] resp = DnsProxy.handle(svc, dns, -1);
        if (resp == null || resp.length == 0) {
            return;
        }

        byte[] outPkt = buildUdpResponse(pkt, ihl, resp);
        try {
            out.write(outPkt);
        } catch (IOException e) {
            // 忽略单次写出失败
        }
    }

    /** 构造 UDP/DNS 应答包：交换 IP/端口，src=10.0.0.1（本虚拟 DNS 服务器）。 */
    private static byte[] buildUdpResponse(byte[] pkt, int ihl, byte[] dnsResp) {
        final int IP_HDR = 20;
        int udpLen = 8 + dnsResp.length;
        int total = IP_HDR + udpLen;
        byte[] out = new byte[total];

        // IPv4 头（固定 20 字节，不复制原选项）
        out[0] = 0x45;                 // version=4, IHL=5
        out[1] = 0;                    // TOS
        out[2] = (byte) ((total >> 8) & 0xff);
        out[3] = (byte) (total & 0xff);
        out[4] = pkt[4];
        out[5] = pkt[5];               // ID 沿用
        out[6] = 0;
        out[7] = 0;                    // flags/frag
        out[8] = 64;                   // TTL
        out[9] = 17;                   // protocol UDP
        out[10] = 0;
        out[11] = 0;                   // checksum 占位
        // src = 10.0.0.1（本 VPN 虚拟 DNS 服务器）
        out[12] = 10;
        out[13] = 0;
        out[14] = 0;
        out[15] = 1;
        // dst = 原查询的源 IP（回给发起方）
        out[16] = pkt[12];
        out[17] = pkt[13];
        out[18] = pkt[14];
        out[19] = pkt[15];
        int ipSum = checksum(out, 0, IP_HDR);
        out[10] = (byte) ((ipSum >> 8) & 0xff);
        out[11] = (byte) (ipSum & 0xff);

        // UDP 头
        int u = IP_HDR;
        out[u] = 0;
        out[u + 1] = 53;                                   // sport = 53
        out[u + 2] = pkt[ihl];
        out[u + 3] = pkt[ihl + 1];                         // dport = 原 sport
        out[u + 4] = (byte) ((udpLen >> 8) & 0xff);
        out[u + 5] = (byte) (udpLen & 0xff);
        out[u + 6] = 0;
        out[u + 7] = 0;                                    // UDP checksum=0（IPv4 允许）
        System.arraycopy(dnsResp, 0, out, u + 8, dnsResp.length);
        return out;
    }

    /** IPv4 首部校验和（16 位反码求和取反）。 */
    private static int checksum(byte[] buf, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len; i += 2) {
            int hi = buf[i] & 0xff;
            int lo = (i + 1 < off + len) ? (buf[i + 1] & 0xff) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (~sum) & 0xffff;
    }
}
