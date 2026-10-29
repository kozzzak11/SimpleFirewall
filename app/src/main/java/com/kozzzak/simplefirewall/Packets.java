package com.kozzzak.simplefirewall;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** IPv4/IPv6 UDP and TCP packets as they appear on the TUN (no Ethernet header). */
final class Packets {
    static final int TCP = 6, UDP = 17;
    static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;
    private static final int MSS = 1240;

    private Packets() {}

    static final class Ip {
        int version, proto, srcPort, dstPort, payloadOff, payloadLen;
        int tcpFlags, tcpSeq, tcpAck, tcpHeaderLen;
        InetAddress src, dst;
        byte[] payload(byte[] raw) {
            byte[] out = new byte[payloadLen];
            System.arraycopy(raw, payloadOff, out, 0, payloadLen);
            return out;
        }
    }

    static Ip parse(byte[] raw, int len) {
        if (raw == null || len < 20) return null;
        int version = (raw[0] & 0xf0) >> 4;
        Ip ip = new Ip();
        ip.version = version;
        try {
            if (version == 4) {
                int ihl = (raw[0] & 0x0f) * 4;
                int total = u16(raw, 2);
                if (ihl < 20 || total > len || total < ihl) return null;
                ip.proto = raw[9] & 0xff;
                ip.src = InetAddress.getByAddress(slice(raw, 12, 4));
                ip.dst = InetAddress.getByAddress(slice(raw, 16, 4));
                return transport(ip, raw, ihl, total);
            }
            if (version == 6) {
                if (len < 40) return null;
                int payload = u16(raw, 4);
                int total = 40 + payload;
                if (total > len) return null;
                ip.proto = raw[6] & 0xff;
                if (ip.proto != TCP && ip.proto != UDP) return null;
                ip.src = InetAddress.getByAddress(slice(raw, 8, 16));
                ip.dst = InetAddress.getByAddress(slice(raw, 24, 16));
                return transport(ip, raw, 40, total);
            }
        } catch (UnknownHostException impossible) { /* fixed-length addresses */ }
        return null;
    }

    private static Ip transport(Ip ip, byte[] raw, int off, int total) {
        if (ip.proto == UDP) {
            if (off + 8 > total) return null;
            ip.srcPort = u16(raw, off);
            ip.dstPort = u16(raw, off + 2);
            int udpLen = u16(raw, off + 4);
            if (udpLen < 8 || off + udpLen > total) return null;
            ip.payloadOff = off + 8;
            ip.payloadLen = udpLen - 8;
            return ip;
        }
        if (ip.proto == TCP) {
            if (off + 20 > total) return null;
            ip.srcPort = u16(raw, off);
            ip.dstPort = u16(raw, off + 2);
            ip.tcpSeq = u32(raw, off + 4);
            ip.tcpAck = u32(raw, off + 8);
            ip.tcpHeaderLen = ((raw[off + 12] & 0xf0) >> 4) * 4;
            if (ip.tcpHeaderLen < 20 || off + ip.tcpHeaderLen > total) return null;
            ip.tcpFlags = raw[off + 13] & 0xff;
            ip.payloadOff = off + ip.tcpHeaderLen;
            ip.payloadLen = total - ip.payloadOff;
            return ip;
        }
        return null;
    }

    static byte[] udp(InetAddress src, int srcPort, InetAddress dst, int dstPort, byte[] payload) {
        byte[] segment = new byte[8 + payload.length];
        put16(segment, 0, srcPort);
        put16(segment, 2, dstPort);
        put16(segment, 4, segment.length);
        System.arraycopy(payload, 0, segment, 8, payload.length);
        int sum = checksum(src, dst, UDP, segment);
        if (sum == 0) sum = 0xffff;
        put16(segment, 6, sum);
        return ip(src, dst, UDP, segment);
    }

    static byte[] tcp(InetAddress src, int srcPort, InetAddress dst, int dstPort,
                       int seq, int ack, int flags, byte[] payload, boolean mss) {
        if (payload == null) payload = new byte[0];
        boolean syn = (flags & SYN) != 0 && mss;
        int header = syn ? 24 : 20;
        byte[] segment = new byte[header + payload.length];
        put16(segment, 0, srcPort);
        put16(segment, 2, dstPort);
        put32(segment, 4, seq);
        put32(segment, 8, ack);
        segment[12] = (byte) ((header / 4) << 4);
        segment[13] = (byte) flags;
        put16(segment, 14, 65535);
        if (syn) {
            segment[20] = 2; segment[21] = 4;
            put16(segment, 22, MSS);
        }
        System.arraycopy(payload, 0, segment, header, payload.length);
        put16(segment, 16, checksum(src, dst, TCP, segment));
        return ip(src, dst, TCP, segment);
    }

    private static byte[] ip(InetAddress src, InetAddress dst, int proto, byte[] segment) {
        byte[] s = src.getAddress(), d = dst.getAddress();
        if (s.length != d.length) throw new IllegalArgumentException("address family");
        if (s.length == 4) {
            byte[] out = new byte[20 + segment.length];
            out[0] = 0x45;
            put16(out, 2, out.length);
            out[6] = 0x40; // DF
            out[8] = 64;
            out[9] = (byte) proto;
            System.arraycopy(s, 0, out, 12, 4);
            System.arraycopy(d, 0, out, 16, 4);
            put16(out, 10, checksum(out, 0, 20));
            System.arraycopy(segment, 0, out, 20, segment.length);
            return out;
        }
        byte[] out = new byte[40 + segment.length];
        out[0] = 0x60;
        put16(out, 4, segment.length);
        out[6] = (byte) proto;
        out[7] = 64;
        System.arraycopy(s, 0, out, 8, 16);
        System.arraycopy(d, 0, out, 24, 16);
        System.arraycopy(segment, 0, out, 40, segment.length);
        return out;
    }

    /** Internet checksum of a header whose checksum field is zero. */
    static int checksum(byte[] data, int off, int len) {
        return fold(sum(data, off, len));
    }

    static int checksum(InetAddress src, InetAddress dst, int proto, byte[] segment) {
        byte[] s = src.getAddress(), d = dst.getAddress();
        long acc = sum(s, 0, s.length) + sum(d, 0, d.length) + sum(segment, 0, segment.length);
        if (s.length == 4) acc += proto + segment.length;
        else acc += (segment.length >>> 16) + (segment.length & 0xffff) + proto;
        return fold(acc);
    }

    /** True when the stored checksum makes the one's-complement sum zero. */
    static boolean intact(byte[] header, int off, int len) {
        long acc = sum(header, off, len);
        while ((acc >>> 16) != 0) acc = (acc & 0xffff) + (acc >>> 16);
        return acc == 0xffff || acc == 0;
    }

    private static long sum(byte[] data, int off, int len) {
        long acc = 0;
        int i = off, end = off + len;
        while (i + 1 < end) {
            acc += ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
            i += 2;
        }
        if (i < end) acc += (data[i] & 0xff) << 8;
        return acc;
    }

    private static int fold(long acc) {
        while ((acc >>> 16) != 0) acc = (acc & 0xffff) + (acc >>> 16);
        return (~(int) acc) & 0xffff;
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static int u32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
    private static void put16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }
    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }
    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }
}
