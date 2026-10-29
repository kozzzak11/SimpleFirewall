package com.kozzzak.simplefirewall;

/** DNS codec for the question we filter and the negative answers we write back. */
final class DnsMessages {
    static final int A = 1, AAAA = 28;
    private static final byte[] V4_SINK = new byte[] {0, 0, 0, 0};
    private static final byte[] V6_SINK = new byte[16];

    private DnsMessages() {}

    static final class Question {
        final int id, type;
        final String name;
        final byte[] wire;
        Question(int id, int type, String name, byte[] wire) {
            this.id = id; this.type = type; this.name = name; this.wire = wire;
        }
    }

    /** First question, or null when the packet is not a single standard query. */
    static Question question(byte[] msg) {
        if (msg == null || msg.length < 12) return null;
        int qd = u16(msg, 4);
        if (qd < 1) return null;
        int flags = u16(msg, 2);
        if ((flags & 0x8000) != 0 || (flags & 0x7800) != 0) return null;
        int[] pos = {12};
        String name = readName(msg, pos);
        if (name == null || pos[0] + 4 > msg.length) return null;
        int type = u16(msg, pos[0]);
        int end = pos[0] + 4;
        return new Question(u16(msg, 0), type, name, copy(msg, 0, end));
    }

    /**
     * Fast negative answer. A and AAAA get an unsable address so clients stop immediately;
     * every other type (including HTTPS/SVCB) is NXDOMAIN and is not forwarded.
     */
    static byte[] blocked(byte[] query) {
        Question q = question(query);
        if (q == null) return null;
        if (q.type == A) return record(q, A, V4_SINK);
        if (q.type == AAAA) return record(q, AAAA, V6_SINK);
        return nxdomain(q);
    }

    static byte[] withId(byte[] response, int id) {
        if (response == null || response.length < 2) return response;
        byte[] copy = response.clone();
        copy[0] = (byte) (id >>> 8);
        copy[1] = (byte) id;
        return copy;
    }

    static int rcode(byte[] msg) { return msg == null || msg.length < 4 ? -1 : msg[3] & 0x0f; }
    static int answers(byte[] msg) { return msg == null || msg.length < 8 ? 0 : u16(msg, 6); }

    private static byte[] nxdomain(Question q) {
        byte[] out = new byte[q.wire.length];
        System.arraycopy(q.wire, 0, out, 0, q.wire.length);
        int flags = 0x8000 | 0x0080 | (u16(q.wire, 2) & 0x0100) | 0x0003;
        put16(out, 2, flags);
        put16(out, 4, 1);
        put16(out, 6, 0);
        put16(out, 8, 0);
        put16(out, 10, 0);
        return out;
    }

    private static byte[] record(Question q, int type, byte[] rdata) {
        byte[] out = new byte[q.wire.length + 12 + rdata.length];
        System.arraycopy(q.wire, 0, out, 0, q.wire.length);
        int flags = 0x8000 | 0x0080 | (u16(q.wire, 2) & 0x0100);
        put16(out, 2, flags);
        put16(out, 4, 1);
        put16(out, 6, 1);
        put16(out, 8, 0);
        put16(out, 10, 0);
        int p = q.wire.length;
        out[p++] = (byte) 0xc0; out[p++] = 0x0c;
        put16(out, p, type); p += 2;
        put16(out, p, 1); p += 2;
        out[p++] = 0; out[p++] = 0; out[p++] = 0x01; out[p++] = 0x2c; // TTL 300
        put16(out, p, rdata.length); p += 2;
        System.arraycopy(rdata, 0, out, p, rdata.length);
        return out;
    }

    private static String readName(byte[] msg, int[] pos) {
        int i = pos[0];
        StringBuilder name = new StringBuilder();
        boolean jumped = false;
        for (int hops = 0; hops < 64 && i < msg.length; hops++) {
            int len = msg[i] & 0xff;
            if (len == 0) {
                if (!jumped) pos[0] = i + 1;
                return name.length() == 0 ? null : name.toString();
            }
            if ((len & 0xc0) == 0xc0) {
                if (i + 1 >= msg.length) return null;
                if (!jumped) pos[0] = i + 2;
                jumped = true;
                i = ((len & 0x3f) << 8) | (msg[i + 1] & 0xff);
                continue;
            }
            if ((len & 0xc0) != 0 || i + 1 + len > msg.length) return null;
            if (name.length() > 0) name.append('.');
            for (int k = 0; k < len; k++) {
                int c = msg[i + 1 + k] & 0xff;
                if (c >= 'A' && c <= 'Z') c += 32;
                name.append((char) c);
            }
            i += len + 1;
        }
        return null;
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static void put16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }
    private static byte[] copy(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }
}
