package com.adskip.vpn;

import java.io.ByteArrayOutputStream;

/**
 * 最小 DNS 报文解析与伪造（仅覆盖 DNS 查询/应答所需字段，不做完整 RFC 实现）。
 * 支持：header(id/flags) + 单个 question 域名解码 + 伪造 A=0.0.0.0 / AAAA=:: / NXDOMAIN 应答。
 */
public final class DnsPacket {
    public int id;
    public int flags;
    public String qname;
    public int qtype;   // 1=A, 28=AAAA
    public int qclass;  // 1=IN
    public byte[] raw;
    public int qnameOffset;   // 域名在 raw 中的起始偏移（=12）
    public int questionEnd;   // question 段结束偏移（含 qclass）

    private DnsPacket() {
    }

    public static DnsPacket parse(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        DnsPacket p = new DnsPacket();
        p.raw = data;
        p.id = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
        p.flags = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        int qd = ((data[4] & 0xff) << 8) | (data[5] & 0xff);
        if (qd < 1) {
            return null;
        }

        int pos = 12;
        StringBuilder sb = new StringBuilder();
        while (pos < data.length) {
            int len = data[pos] & 0xff;
            if (len == 0) {
                pos++;
                break;
            }
            if ((len & 0xc0) == 0xc0) {
                // 压缩指针：查询报文不应出现，直接停止
                break;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            if (pos + 1 + len > data.length) {
                return null;
            }
            sb.append(new String(data, pos + 1, len));
            pos += 1 + len;
        }
        p.qname = sb.toString();
        p.qnameOffset = 12;
        if (pos + 4 > data.length) {
            return null;
        }
        p.qtype = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
        p.qclass = ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff);
        p.questionEnd = pos + 4;
        return p;
    }

    /**
     * 伪造应答。
     *
     * @param useNxdomain true→返回 NXDOMAIN（无应答）；false→返回 A=0.0.0.0（或 AAAA=::）。
     */
    public static byte[] forgeBlocked(DnsPacket p, boolean useNxdomain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // header: id
        out.write((p.id >> 8) & 0xff);
        out.write(p.id & 0xff);
        // flags: QR=1, AA=1, 复制 RD, RA=1, RCODE
        int qr = 1 << 15;
        int aa = 1 << 10;
        int rd = p.flags & (1 << 8);
        int ra = 1 << 7;
        int rcode = useNxdomain ? 3 : 0;
        int flags = qr | aa | rd | ra | rcode;
        out.write((flags >> 8) & 0xff);
        out.write(flags & 0xff);
        // QDCOUNT = 1
        out.write(0);
        out.write(1);
        if (useNxdomain) {
            out.write(0);
            out.write(0); // ANCOUNT
            out.write(0);
            out.write(0); // NSCOUNT
            out.write(0);
            out.write(0); // ARCOUNT
        } else {
            out.write(0);
            out.write(1); // ANCOUNT = 1
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(0);
        }
        // Question 段原样拷贝
        out.write(p.raw, p.qnameOffset, p.questionEnd - p.qnameOffset);
        if (!useNxdomain) {
            // Answer: name 压缩指针 → offset 12
            out.write(0xc0);
            out.write(0x0c);
            int atype = (p.qtype == 28) ? 28 : 1; // 28=AAAA, 1=A
            out.write((atype >> 8) & 0xff);
            out.write(atype & 0xff);
            out.write(0);
            out.write(1); // CLASS IN
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(60); // TTL = 60
            if (atype == 28) {
                out.write(0);
                out.write(16); // RDLENGTH = 16
                for (int i = 0; i < 16; i++) {
                    out.write(0); // ::（全零）
                }
            } else {
                out.write(0);
                out.write(4); // RDLENGTH = 4
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(0); // 0.0.0.0
            }
        }
        return out.toByteArray();
    }
}
