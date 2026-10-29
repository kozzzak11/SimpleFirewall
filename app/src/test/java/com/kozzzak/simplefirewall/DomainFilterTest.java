package com.kozzzak.simplefirewall;

import org.junit.Test;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DomainFilterTest {
    @Test public void suffixMatchDoesNotBlockTheParentOrALookalike() throws Exception {
        DomainFilter filter = DomainFilter.load(new StringReader("""
            # comment
            0.0.0.0 ads.example.com
            127.0.0.1 localhost
            doubleclick.net
            0.0.0.0 0.0.0.0
            not a host
            """));
        assertTrue(filter.blocked("ads.example.com"));
        assertTrue(filter.blocked("a.b.ads.example.com"));
        assertTrue(filter.blocked("ADS.Example.com."));
        assertFalse(filter.blocked("example.com"));
        assertFalse(filter.blocked("notads.example.com"));
        assertTrue(filter.blocked("ad.doubleclick.net"));
        assertFalse(filter.blocked("notdoubleclick.net"));
        assertFalse(filter.blocked("localhost"));
        assertFalse(filter.blocked("doubleclick.net.evil.com"));
    }

    @Test public void bundledListCoversAdSdksAndLeavesOrdinarySites() throws Exception {
        DomainFilter filter = DomainFilter.load(new FileReader(asset("adblock-extra.txt"), StandardCharsets.UTF_8),
            new FileReader(asset("adblock-hosts.txt"), StandardCharsets.UTF_8));
        assertTrue(filter.size() > 70000);
        for (String host : new String[] {
            "doubleclick.net", "ad.doubleclick.net", "zzz.doubleclick.net",
            "googlesyndication.com", "pagead2.googlesyndication.com", "googleadservices.com",
            "googleads.g.doubleclick.net", "an.yandex.ru", "yabs.yandex.ru", "sub.yabs.yandex.ru",
            "mobile.yandexadexchange.net", "report.appmetrica.yandex.net", "appmetrica.yandex.net",
            "adfox.yandex.ru", "mc.yandex.ru", "app-measurement.com", "dns.google"})
            assertTrue(host, filter.blocked(host));
        for (String host : new String[] {
            "youtube.com", "m.youtube.com", "googlevideo.com", "i.ytimg.com",
            "instagram.com", "www.instagram.com", "cdninstagram.com",
            "google.com", "fonts.gstatic.com", "play.googleapis.com", "yandex.ru", "yastatic.net",
            "connectivitycheck.gstatic.com", "example.com", "notdoubleclick.net"})
            assertFalse(host, filter.blocked(host));
    }

    @Test public void blockedDnsAnswerIsImmediateAndUseless() {
        byte[] query = query("ads.doubleclick.net", DnsMessages.A, 0x1234);
        DnsMessages.Question q = DnsMessages.question(query);
        assertEquals("ads.doubleclick.net", q.name);
        assertEquals(DnsMessages.A, q.type);
        byte[] answer = DnsMessages.blocked(query);
        assertEquals(0x1234, u16(answer, 0));
        assertEquals(1, DnsMessages.answers(answer));
        assertEquals(0, DnsMessages.rcode(answer));
        assertArrayEquals(new byte[] {0, 0, 0, 0}, java.util.Arrays.copyOfRange(answer, answer.length - 4, answer.length));
        byte[] aaaa = DnsMessages.blocked(query("ads.doubleclick.net", DnsMessages.AAAA, 7));
        assertEquals(7, u16(aaaa, 0));
        assertArrayEquals(new byte[16], java.util.Arrays.copyOfRange(aaaa, aaaa.length - 16, aaaa.length));
        byte[] other = DnsMessages.blocked(query("ads.doubleclick.net", 65, 9));
        assertEquals(3, DnsMessages.rcode(other));
        assertEquals(9, u16(other, 0));
    }

    @Test public void udpPacketRoundTripKeepsChecksum() throws Exception {
        byte[] payload = new byte[] {1, 2, 3, 4, 5};
        byte[] packet = Packets.udp(InetAddress.getByAddress(new byte[] {10, 0, 0, 1}), 53000,
            InetAddress.getByAddress(new byte[] {10, 111, (byte) 222, 2}), 53, payload);
        assertTrue(Packets.intact(packet, 0, 20));
        Packets.Ip ip = Packets.parse(packet, packet.length);
        assertNotNull(ip);
        assertEquals(Packets.UDP, ip.proto);
        assertEquals(53, ip.dstPort);
        assertArrayEquals(payload, ip.payload(packet));
        byte[] tcp = Packets.tcp(ip.dst, 80, ip.src, 40000, 100, 200, Packets.SYN | Packets.ACK, null, true);
        Packets.Ip syn = Packets.parse(tcp, tcp.length);
        assertEquals(100, syn.tcpSeq);
        assertEquals(200, syn.tcpAck);
        assertEquals(Packets.SYN | Packets.ACK, syn.tcpFlags);
        assertEquals(0, syn.payloadLen);
        assertTrue(Packets.intact(tcp, 0, 20));
    }

    private static File asset(String name) {
        File[] candidates = {new File("src/main/assets/" + name), new File("app/src/main/assets/" + name)};
        for (File f : candidates) if (f.isFile()) return f;
        fail("missing " + name + " from " + new File(".").getAbsolutePath());
        return null;
    }

    private static byte[] query(String name, int type, int id) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(id >>> 8); out.write(id);
        out.write(1); out.write(0); // RD
        out.write(0); out.write(1); // QDCOUNT
        out.write(0); out.write(0); out.write(0); out.write(0); out.write(0); out.write(0);
        for (String label : name.split("\\.")) {
            out.write(label.length());
            out.write(label.getBytes(StandardCharsets.US_ASCII), 0, label.length());
        }
        out.write(0);
        out.write(type >>> 8); out.write(type);
        out.write(0); out.write(1);
        return out.toByteArray();
    }
    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
}
