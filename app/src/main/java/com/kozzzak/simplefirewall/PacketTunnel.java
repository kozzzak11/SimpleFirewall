package com.kozzzak.simplefirewall;

import android.content.pm.PackageManager;
import android.net.*;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.OsConstants;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads the TUN only while ad blocking is on.
 * Firewall off: the VPN routes just the virtual DNS address, so this loop sees DNS and nothing else.
 * Firewall on: blocked apps are dropped; allowed apps are relayed on the same VPN so their DNS can be filtered.
 */
final class PacketTunnel {
    static final AtomicLong blockedQueries = new AtomicLong();
    static volatile String lastError = "";
    static volatile int uidMisses, forwardedTcp;
    private static final InetAddress DNS4 = addr(new byte[] {10, 111, (byte) 222, 2});
    private static final InetAddress DNS6 = addr(new byte[] {
        (byte) 0xfd, 0x42, 0x73, 0x66, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2});

    private final FirewallService vpn;
    private final ConnectivityManager cm;
    private final PackageManager pm;
    private final boolean firewall;
    private final FileInputStream in;
    private final FileOutputStream out;
    private final Object writeLock = new Object();
    private final Map<String, TcpSession> tcp = new ConcurrentHashMap<>();
    private final Map<String, UdpSession> udp = new ConcurrentHashMap<>();
    private final Map<String, Integer> flowUid = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> uidAllowed = new ConcurrentHashMap<>();
    private final ExecutorService relay = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sf-relay"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService sweep = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sf-sweep"); t.setDaemon(true); return t;
    });
    private final DnsCache cache = new DnsCache();
    private volatile Set<String> allowed = Set.of();
    private volatile List<InetAddress> upstream = List.of();
    private volatile boolean stopped;
    private Thread thread;

    PacketTunnel(FirewallService vpn, ParcelFileDescriptor tun, boolean firewall) {
        this.vpn = vpn;
        this.firewall = firewall;
        this.cm = vpn.getSystemService(ConnectivityManager.class);
        this.pm = vpn.getPackageManager();
        FileDescriptor fd = tun.getFileDescriptor();
        in = new FileInputStream(fd);
        out = new FileOutputStream(fd);
    }

    void allow(Set<String> packages) {
        allowed = Set.copyOf(packages);
        uidAllowed.clear();
        flowUid.clear();
        // A revoked app must not keep a relay that was opened while it was allowed.
        if (firewall) {
            for (TcpSession s : new ArrayList<>(tcp.values())) s.close();
            for (UdpSession s : new ArrayList<>(udp.values())) s.close();
        }
    }

    void start() {
        upstream = physicalDns();
        thread = new Thread(this::loop, "sf-dns");
        thread.start();
        sweep.scheduleWithFixedDelay(this::sweepIdle, 15, 15, TimeUnit.SECONDS);
    }

    void stop() {
        stopped = true;
        for (TcpSession s : tcp.values()) s.close();
        for (UdpSession s : udp.values()) s.close();
        tcp.clear(); udp.clear();
        relay.shutdownNow();
        sweep.shutdownNow();
    }

    private void loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
        Blocklist.ensure(vpn);
        byte[] buf = new byte[32767];
        try {
            while (!stopped) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                try { onPacket(buf, n); }
                catch (RuntimeException e) { lastError = e.getClass().getSimpleName(); }
            }
        } catch (IOException e) {
            if (!stopped) lastError = "tun";
        } finally { stop(); }
    }

    private void onPacket(byte[] raw, int len) {
        Packets.Ip ip = Packets.parse(raw, len);
        if (ip == null) return;
        boolean dns = ip.dstPort == 53 || virtual(ip.dst);
        if (ip.proto == Packets.UDP && dns) { onDns(ip, raw); return; }
        if (!firewall) {
            if (ip.proto == Packets.TCP && dns) onTcp(ip, raw, true);
            return;
        }
        // A SYN can be visible to the UID lookup only after the socket is registered.
        // Retry off the reader thread so DNS answers are not delayed.
        if (ip.proto == Packets.TCP && (ip.tcpFlags & Packets.SYN) != 0 && (ip.tcpFlags & Packets.ACK) == 0) {
            byte[] copy = java.util.Arrays.copyOf(raw, len);
            boolean toDns = dns;
            try { relay.execute(() -> openTcp(copy, toDns)); }
            catch (RejectedExecutionException e) { /* stopping */ }
            return;
        }
        int uid = uidOf(ip);
        if (uid < 0 || !allowedUid(uid)) return;
        if (ip.proto == Packets.TCP) onTcp(ip, raw, dns);
        else if (ip.proto == Packets.UDP) onUdp(ip, raw);
    }

    private void openTcp(byte[] raw, boolean dns) {
        Packets.Ip ip = Packets.parse(raw, raw.length);
        if (ip == null || stopped) return;
        int uid = -1;
        for (int i = 0; i < 6 && uid < 0 && !stopped; i++) {
            uid = uidOf(ip);
            if (uid < 0) try { Thread.sleep(30); } catch (InterruptedException e) { return; }
        }
        if (uid >= 0 && allowedUid(uid)) onTcp(ip, raw, dns);
    }

    private void onDns(Packets.Ip ip, byte[] raw) {
        byte[] query = ip.payload(raw);
        DnsMessages.Question q = DnsMessages.question(query);
        if (q == null) return;
        int uid = firewall ? uidOf(ip) : Process.myUid();
        if (firewall && uid >= 0 && !allowedUid(uid)) return;
        if (Blocklist.current.blocked(q.name)) {
            blockedQueries.incrementAndGet();
            byte[] answer = DnsMessages.blocked(query);
            if (answer != null) tunWrite(Packets.udp(ip.dst, ip.dstPort, ip.src, ip.srcPort, answer));
            return;
        }
        byte[] hit = cache.get(q);
        if (hit != null) {
            tunWrite(Packets.udp(ip.dst, ip.dstPort, ip.src, ip.srcPort, hit));
            return;
        }
        InetAddress src = ip.src, dst = ip.dst;
        int sp = ip.srcPort, dp = ip.dstPort;
        relay.execute(() -> {
            byte[] resp = resolve(query, q);
            if (resp != null && !stopped) tunWrite(Packets.udp(dst, dp, src, sp, resp));
        });
    }

    private byte[] resolve(byte[] query, DnsMessages.Question q) {
        List<InetAddress> servers = upstream.isEmpty() ? physicalDns() : upstream;
        for (int i = 0; i < servers.size() && i < 2; i++) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                vpn.protect(socket);
                socket.connect(new InetSocketAddress(servers.get(i), 53));
                socket.setSoTimeout(1000);
                socket.send(new DatagramPacket(query, query.length));
                byte[] buf = new byte[1500];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                if (packet.getLength() < 12 || buf[0] != query[0] || buf[1] != query[1]) continue;
                byte[] resp = Arrays.copyOf(buf, packet.getLength());
                cache.put(q, resp);
                return resp;
            } catch (IOException e) {
                lastError = "dns";
            } finally { if (socket != null) socket.close(); }
        }
        return null;
    }

    private void onTcp(Packets.Ip ip, byte[] raw, boolean dns) {
        String key = key(ip);
        TcpSession session = tcp.get(key);
        int flags = ip.tcpFlags;
        if ((flags & Packets.SYN) != 0 && (flags & Packets.ACK) == 0) {
            if (session != null) { session.resendSynAck(); return; }
            if (tcp.size() >= 256) return;
            TcpSession created = new TcpSession(ip, dns);
            tcp.put(key, created);
            try { relay.execute(created::connect); }
            catch (RejectedExecutionException e) { created.close(); }
            return;
        }
        if (session == null) return;
        byte[] payload = ip.payloadLen == 0 ? new byte[0] : ip.payload(raw);
        session.fromClient(ip, payload);
    }

    private void onUdp(Packets.Ip ip, byte[] raw) {
        String key = key(ip);
        UdpSession session = udp.get(key);
        if (session == null) {
            if (udp.size() >= 128) return;
            session = new UdpSession(ip);
            UdpSession prev = udp.putIfAbsent(key, session);
            if (prev != null) session = prev;
            else relay.execute(session::open);
        }
        session.send(ip.payload(raw));
    }

    private int uidOf(Packets.Ip ip) {
        String key = key(ip);
        Integer cached = flowUid.get(key);
        if (cached != null) return cached;
        int uid = lookupUid(ip);
        if (uid >= 0) {
            if (flowUid.size() > 4096) flowUid.clear();
            flowUid.put(key, uid);
        } else uidMisses++;
        return uid;
    }

    private int lookupUid(Packets.Ip ip) {
        int proto = ip.proto == Packets.TCP ? OsConstants.IPPROTO_TCP : OsConstants.IPPROTO_UDP;
        try {
            int uid = cm.getConnectionOwnerUid(proto,
                new InetSocketAddress(ip.src, ip.srcPort), new InetSocketAddress(ip.dst, ip.dstPort));
            if (uid != Process.INVALID_UID) return uid;
        } catch (RuntimeException e) { lastError = "uid"; }
        return uidFromProc(ip);
    }

    private int uidFromProc(Packets.Ip ip) {
        String path = ip.proto == Packets.TCP ? "/proc/net/tcp" : "/proc/net/udp";
        if (ip.src.getAddress().length == 16) path += "6";
        String local = hex(ip.src) + ":" + String.format(Locale.ROOT, "%04X", ip.srcPort);
        String remote = hex(ip.dst) + ":" + String.format(Locale.ROOT, "%04X", ip.dstPort);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(path), java.nio.charset.StandardCharsets.US_ASCII))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length > 7 && p[1].equalsIgnoreCase(local) && p[2].equalsIgnoreCase(remote))
                    return Integer.parseInt(p[7]);
            }
        } catch (IOException | NumberFormatException ignored) { /* proc is hidden on many devices */ }
        return -1;
    }

    private boolean allowedUid(int uid) {
        if (!firewall || uid == vpn.getApplicationInfo().uid) return true;
        Boolean cached = uidAllowed.get(uid);
        if (cached != null) return cached;
        String[] pkgs = pm.getPackagesForUid(uid);
        boolean ok = false;
        if (pkgs != null) for (String p : pkgs) if (allowed.contains(p)) { ok = true; break; }
        uidAllowed.put(uid, ok);
        return ok;
    }

    private List<InetAddress> physicalDns() {
        LinkedHashSet<InetAddress> dns = new LinkedHashSet<>();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            LinkProperties links = cm.getLinkProperties(n);
            if (caps == null || links == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            for (InetAddress server : links.getDnsServers()) if (!virtual(server)) dns.add(server);
        }
        if (dns.isEmpty()) dns.add(addr(new byte[] {8, 8, 8, 8}));
        upstream = List.copyOf(dns);
        return upstream;
    }

    private InetAddress upstreamV4() {
        for (InetAddress a : upstream) if (a.getAddress().length == 4) return a;
        return addr(new byte[] {8, 8, 8, 8});
    }

    private void tunWrite(byte[] packet) {
        if (stopped || packet == null) return;
        synchronized (writeLock) {
            try { out.write(packet); }
            catch (IOException e) { stopped = true; }
        }
    }

    private void sweepIdle() {
        long now = System.nanoTime();
        tcp.values().removeIf(s -> { boolean old = now - s.touched > 300_000_000_000L; if (old) s.close(); return old; });
        udp.values().removeIf(s -> { boolean old = now - s.touched > 30_000_000_000L; if (old) s.close(); return old; });
    }

    private static String key(Packets.Ip ip) {
        return ip.proto + " " + ip.src.getHostAddress() + " " + ip.srcPort + " " + ip.dst.getHostAddress() + " " + ip.dstPort;
    }
    private static boolean virtual(InetAddress a) {
        return a.equals(DNS4) || a.equals(DNS6);
    }
    private static InetAddress addr(byte[] b) {
        try { return InetAddress.getByAddress(b); }
        catch (UnknownHostException e) { throw new IllegalStateException(e); }
    }
    private static String hex(InetAddress a) {
        byte[] b = a.getAddress();
        StringBuilder sb = new StringBuilder();
        if (b.length == 4) return String.format(Locale.ROOT, "%02X%02X%02X%02X", b[3], b[2], b[1], b[0]);
        for (int i = 0; i < 16; i += 4)
            sb.append(String.format(Locale.ROOT, "%02X%02X%02X%02X", b[i + 3], b[i + 2], b[i + 1], b[i]));
        return sb.toString();
    }

    private final class TcpSession {
        final Packets.Ip origin;
        final boolean dns;
        final Object lock = new Object();
        Socket socket;
        int clientNext, ourSeq, state;
        boolean synSent, clientFin, serverFin;
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        volatile long touched = System.nanoTime();
        volatile boolean dead;

        TcpSession(Packets.Ip ip, boolean dns) { origin = ip; this.dns = dns; clientNext = ip.tcpSeq + 1; }

        void connect() {
            InetAddress target = origin.dst;
            int port = origin.dstPort;
            if (dns && virtual(origin.dst)) { target = upstreamV4(); port = 53; }
            Socket s = new Socket();
            try {
                vpn.protect(s);
                s.connect(new InetSocketAddress(target, port), 3000);
                s.setTcpNoDelay(true);
                synchronized (lock) {
                    if (dead) { s.close(); return; }
                    socket = s;
                    ourSeq = ThreadLocalRandom.current().nextInt() & 0x7fffffff;
                    send(Packets.SYN | Packets.ACK, ourSeq, clientNext, null, true);
                    ourSeq++;
                    synSent = true;
                    state = 1;
                }
                relay.execute(this::pump);
            } catch (IOException e) {
                lastError = "tcp";
                reset();
            }
        }

        void resendSynAck() {
            synchronized (lock) {
                if (synSent && state == 1) send(Packets.SYN | Packets.ACK, ourSeq - 1, clientNext, null, true);
            }
        }

        void fromClient(Packets.Ip ip, byte[] payload) {
            synchronized (lock) {
                if (dead) return;
                touched = System.nanoTime();
                int flags = ip.tcpFlags;
                if ((flags & Packets.RST) != 0) { close(); return; }
                if ((flags & Packets.ACK) != 0 && state == 1 && ip.tcpAck == ourSeq) {
                    state = 2;
                    forwardedTcp++;
                    if (pending.size() > 0) { sendData(pending.toByteArray()); pending.reset(); }
                }
                int seq = ip.tcpSeq, len = payload.length;
                if (len > 0 && state == 2 && seq == clientNext) {
                    if (dns && !toUpstream(payload)) return;
                    writeServer(payload);
                    clientNext += len;
                    send(Packets.ACK, ourSeq, clientNext, null, false);
                } else if (len > 0 && state == 2 && seq - clientNext < 0) {
                    send(Packets.ACK, ourSeq, clientNext, null, false);
                }
                if ((flags & Packets.FIN) != 0 && seq + len == clientNext && state == 2) {
                    clientNext++;
                    clientFin = true;
                    shutdownOut();
                    send(Packets.ACK, ourSeq, clientNext, null, false);
                }
            }
        }

        /** @return false when the query was blocked and already answered. */
        private boolean toUpstream(byte[] payload) {
            if (payload.length < 14) return true;
            int n = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
            if (n <= 0 || n > payload.length - 2) return true;
            byte[] msg = Arrays.copyOfRange(payload, 2, 2 + n);
            DnsMessages.Question q = DnsMessages.question(msg);
            if (q == null || !Blocklist.current.blocked(q.name)) return true;
            blockedQueries.incrementAndGet();
            byte[] ans = DnsMessages.blocked(msg);
            if (ans != null) {
                byte[] framed = new byte[ans.length + 2];
                framed[0] = (byte) (ans.length >>> 8); framed[1] = (byte) ans.length;
                System.arraycopy(ans, 0, framed, 2, ans.length);
                sendData(framed);
                sendFin();
            }
            close();
            return false;
        }

        void pump() {
            byte[] buf = new byte[1200];
            try {
                InputStream input = socket.getInputStream();
                int n;
                while (!dead && (n = input.read(buf)) >= 0) {
                    if (n == 0) continue;
                    byte[] copy = Arrays.copyOf(buf, n);
                    synchronized (lock) {
                        touched = System.nanoTime();
                        if (state == 2) sendData(copy);
                        else if (pending.size() < 65536) pending.write(copy, 0, copy.length);
                    }
                }
                synchronized (lock) { sendFin(); }
            } catch (IOException e) { if (!dead) reset(); }
        }

        private void sendData(byte[] data) {
            send(Packets.ACK | Packets.PSH, ourSeq, clientNext, data, false);
            ourSeq += data.length;
        }
        private void sendFin() {
            if (serverFin || dead) return;
            serverFin = true;
            send(Packets.ACK | Packets.FIN, ourSeq, clientNext, null, false);
            ourSeq++;
        }
        private void writeServer(byte[] payload) {
            try { if (socket != null) socket.getOutputStream().write(payload); }
            catch (IOException e) { reset(); }
        }
        private void shutdownOut() {
            try { if (socket != null) socket.shutdownOutput(); }
            catch (IOException ignored) { /* already closed */ }
        }
        private void send(int flags, int seq, int ack, byte[] payload, boolean mss) {
            tunWrite(Packets.tcp(origin.dst, origin.dstPort, origin.src, origin.srcPort, seq, ack, flags, payload, mss));
        }
        void reset() {
            synchronized (lock) {
                if (dead) return;
                if (synSent && !serverFin) send(Packets.RST | Packets.ACK, ourSeq, clientNext, null, false);
                close();
            }
        }
        void close() {
            dead = true;
            tcp.remove(key(origin), this);
            if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private final class UdpSession {
        final Packets.Ip origin;
        DatagramSocket socket;
        volatile long touched = System.nanoTime();
        volatile boolean dead;
        final ArrayDeque<byte[]> queued = new ArrayDeque<>();

        UdpSession(Packets.Ip ip) { origin = ip; }
        void open() {
            try {
                DatagramSocket s = new DatagramSocket();
                vpn.protect(s);
                s.connect(new InetSocketAddress(origin.dst, origin.dstPort));
                s.setSoTimeout(5000);
                socket = s;
                synchronized (queued) {
                    for (byte[] p : queued) sendNow(p);
                    queued.clear();
                }
                byte[] buf = new byte[1500];
                while (!dead) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try { s.receive(packet); }
                    catch (SocketTimeoutException e) { if (System.nanoTime() - touched > 30_000_000_000L) break; continue; }
                    touched = System.nanoTime();
                    tunWrite(Packets.udp(origin.dst, origin.dstPort, origin.src, origin.srcPort,
                        Arrays.copyOf(packet.getData(), packet.getLength())));
                }
            } catch (IOException e) { lastError = "udp"; }
            finally { close(); }
        }
        void send(byte[] payload) {
            touched = System.nanoTime();
            DatagramSocket s = socket;
            if (s == null) { synchronized (queued) { if (socket == null) { queued.add(payload); return; } } }
            sendNow(payload);
        }
        private void sendNow(byte[] payload) {
            try { if (socket != null) socket.send(new DatagramPacket(payload, payload.length)); }
            catch (IOException e) { close(); }
        }
        void close() {
            dead = true;
            udp.remove(key(origin), this);
            if (socket != null) socket.close();
        }
    }

    private static final class DnsCache {
        static final class Slot { final byte[] packet; final long until; Slot(byte[] p, long u) { packet = p; until = u; } }
        private final LinkedHashMap<String, Slot> map = new LinkedHashMap<>(128, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, Slot> eldest) { return size() > 512; }
        };
        byte[] get(DnsMessages.Question q) {
            synchronized (map) {
                Slot slot = map.get(q.name + " " + q.type);
                if (slot == null || slot.until < System.currentTimeMillis()) return null;
                return DnsMessages.withId(slot.packet, q.id);
            }
        }
        void put(DnsMessages.Question q, byte[] packet) {
            synchronized (map) { map.put(q.name + " " + q.type, new Slot(packet.clone(), System.currentTimeMillis() + 120_000)); }
        }
    }
}
