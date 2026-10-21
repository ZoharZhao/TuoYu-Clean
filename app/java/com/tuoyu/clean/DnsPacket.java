package com.tuoyu.clean;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Minimal DNS-over-UDP handling for the local VPN.
 *
 * Only what the blocker needs: read the QNAME out of a query, synth a reply for
 * blocked hosts, and forward everything else to an upstream resolver. Anything
 * that is not plain IPv4 + UDP/53 is refused, so the tun carries DNS and nothing
 * else.
 */
final class DnsPacket {

    /** upstream resolver used for hosts we do not block */
    private static final String UPSTREAM = "223.5.5.5";
    private static final int UPSTREAM_PORT = 53;

    private DnsPacket() {
    }

    /**
     * Handles one packet read from the tun device.
     *
     * @return the bytes to write back, or null when the packet should be dropped
     */
    static byte[] handle(byte[] buf, int len, DatagramSocket socket) {
        if (len < 28) {
            return null;
        }
        int version = (buf[0] >> 4) & 0xF;
        if (version != 4) {
            return null;                    // IPv6: not routed here anyway
        }
        int ihl = (buf[0] & 0xF) * 4;
        if (ihl < 20 || len < ihl + 8) {
            return null;
        }
        if (buf[9] != 17) {
            return null;                    // not UDP
        }
        int dstPort = ((buf[ihl + 2] & 0xFF) << 8) | (buf[ihl + 3] & 0xFF);
        if (dstPort != 53) {
            return null;
        }
        int srcPort = ((buf[ihl] & 0xFF) << 8) | (buf[ihl + 1] & 0xFF);
        int dnsOffset = ihl + 8;
        int dnsLen = len - dnsOffset;
        if (dnsLen < 12) {
            return null;
        }
        String qname = readQName(buf, dnsOffset, dnsLen);
        if (qname == null) {
            return null;
        }

        byte[] dns;
        if (AdHosts.isBlocked(qname)) {
            dns = blockedResponse(buf, dnsOffset);
        } else {
            dns = forward(buf, dnsOffset, dnsLen, socket);
            if (dns == null) {
                return null;
            }
        }
        return buildReply(buf, ihl, len, srcPort, dns);
    }

    /** reads the queried name; returns null when the packet is malformed */
    private static String readQName(byte[] buf, int offset, int dnsLen) {
        StringBuilder sb = new StringBuilder();
        int p = offset + 12;                // skip the DNS header
        int end = offset + dnsLen;
        while (p < end) {
            int labelLen = buf[p] & 0xFF;
            if (labelLen == 0) {
                break;
            }
            if (labelLen > 63) {
                return null;                // compression pointer in a question
            }
            p++;
            if (p + labelLen > end) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(new String(buf, p, labelLen, java.nio.charset.StandardCharsets.ISO_8859_1));
            p += labelLen;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** A response with an A record pointing at 0.0.0.0 */
    private static byte[] blockedResponse(byte[] buf, int dnsOffset) {
        int questionLen = questionLength(buf, dnsOffset);
        if (questionLen <= 0) {
            return null;
        }
        byte[] out = new byte[12 + questionLen + 16];
        System.arraycopy(buf, dnsOffset, out, 0, 12 + questionLen);
        out[2] = (byte) 0x81;               // QR=1, opcode=0, RD=1
        out[3] = (byte) 0x80;               // RA=1, rcode=0
        out[6] = 0;                         // ANCOUNT = 1
        out[7] = 1;
        int p = 12 + questionLen;
        out[p++] = (byte) 0xC0;             // name pointer to offset 12
        out[p++] = 0x0C;
        out[p++] = 0x00;                    // TYPE A
        out[p++] = 0x01;
        out[p++] = 0x00;                    // CLASS IN
        out[p++] = 0x01;
        out[p++] = 0x00;                    // TTL 60s
        out[p++] = 0x00;
        out[p++] = 0x00;
        out[p++] = 0x3C;
        out[p++] = 0x00;                    // RDLENGTH 4
        out[p++] = 0x04;
        out[p++] = 0x00;                    // 0.0.0.0
        out[p++] = 0x00;
        out[p++] = 0x00;
        out[p] = 0x00;
        return out;
    }

    /** total bytes of the question section (name + type + class) */
    private static int questionLength(byte[] buf, int dnsOffset) {
        int p = dnsOffset + 12;
        int limit = Math.min(buf.length, dnsOffset + 512);
        while (p < limit) {
            int labelLen = buf[p] & 0xFF;
            if (labelLen == 0) {
                p++;
                return p - dnsOffset + 4;
            }
            if (labelLen > 63) {
                return -1;
            }
            p += 1 + labelLen;
        }
        return -1;
    }

    /** forwards the query upstream and returns the raw DNS answer */
    private static byte[] forward(byte[] buf, int dnsOffset, int dnsLen, DatagramSocket socket) {
        try {
            InetAddress upstream = InetAddress.getByName(UPSTREAM);
            byte[] query = Arrays.copyOfRange(buf, dnsOffset, dnsOffset + dnsLen);
            socket.send(new DatagramPacket(query, query.length, upstream, UPSTREAM_PORT));
            byte[] resp = new byte[1500];
            DatagramPacket pkt = new DatagramPacket(resp, resp.length);
            socket.setSoTimeout(3000);
            socket.receive(pkt);
            return Arrays.copyOf(resp, pkt.getLength());
        } catch (Throwable e) {
            return null;
        }
    }

    /** wraps a DNS payload back into an IPv4/UDP packet addressed to the client */
    private static byte[] buildReply(byte[] req, int ihl, int reqLen, int srcPort, byte[] dns) {
        int udpLen = 8 + dns.length;
        int total = 20 + udpLen;
        byte[] out = new byte[total];
        out[0] = 0x45;                      // IPv4, IHL 5
        out[1] = 0;
        out[2] = (byte) (total >> 8);
        out[3] = (byte) total;
        // id / flags / frag left as zero
        out[8] = 64;                        // TTL
        out[9] = 17;                        // UDP
        // source = the DNS address we handed to the system
        System.arraycopy(req, 16, out, 12, 4);
        // destination = whoever asked
        System.arraycopy(req, 12, out, 16, 4);
        int u = 20;
        out[u] = (byte) (53 >> 8);          // src port 53
        out[u + 1] = 53;
        out[u + 2] = (byte) (srcPort >> 8);
        out[u + 3] = (byte) srcPort;
        out[u + 4] = (byte) (udpLen >> 8);
        out[u + 5] = (byte) udpLen;
        System.arraycopy(dns, 0, out, u + 8, dns.length);
        fillChecksums(out, total, u, udpLen);
        return out;
    }

    private static void fillChecksums(byte[] p, int total, int udpOffset, int udpLen) {
        // IP header checksum
        p[10] = 0;
        p[11] = 0;
        int sum = 0;
        for (int i = 0; i < 20; i += 2) {
            sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int ipc = ~sum & 0xFFFF;
        p[10] = (byte) (ipc >> 8);
        p[11] = (byte) ipc;

        // UDP checksum (pseudo header + UDP header + payload)
        p[udpOffset + 6] = 0;
        p[udpOffset + 7] = 0;
        int usum = 0;
        for (int i = 12; i < 20; i += 2) {  // src + dst
            usum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
        }
        usum += 17;                          // protocol
        usum += udpLen;
        for (int i = udpOffset; i < udpOffset + udpLen; i += 2) {
            int hi = p[i] & 0xFF;
            int lo = (i + 1 < total) ? (p[i + 1] & 0xFF) : 0;
            usum += (hi << 8) | lo;
        }
        while ((usum >> 16) != 0) {
            usum = (usum & 0xFFFF) + (usum >> 16);
        }
        int uc = ~usum & 0xFFFF;
        if (uc == 0) {
            uc = 0xFFFF;
        }
        p[udpOffset + 6] = (byte) (uc >> 8);
        p[udpOffset + 7] = (byte) uc;
    }
}
