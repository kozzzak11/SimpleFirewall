package com.kozzzak.simplefirewall;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Suffix blocklist. A listed domain blocks itself and every subdomain, not its parent. */
final class DomainFilter {
    static final DomainFilter EMPTY = new DomainFilter(Collections.emptySet());
    /** Names whose loss makes Android treat the network as captive or break clock sync. */
    private static final Set<String> NEVER = Set.of(
        "connectivitycheck.gstatic.com", "connectivitycheck.android.com", "captive.apple.com",
        "msftconnecttest.com", "www.msftconnecttest.com", "clients3.google.com",
        "time.android.com", "time.google.com", "time.gstatic.com");
    private final Set<String> domains;

    private DomainFilter(Set<String> domains) { this.domains = domains; }
    int size() { return domains.size(); }

    boolean blocked(String host) {
        host = normalize(host);
        if (host == null || NEVER.contains(host)) return false;
        String rest = host;
        while (true) {
            if (domains.contains(rest)) return true;
            int dot = rest.indexOf('.');
            if (dot <= 0) return false;
            rest = rest.substring(dot + 1);
            if (rest.indexOf('.') < 0) return false;
        }
    }

    static DomainFilter load(Reader... sources) throws IOException {
        HashSet<String> domains = new HashSet<>();
        for (Reader source : sources) {
            if (source == null) continue;
            try (BufferedReader in = source instanceof BufferedReader ? (BufferedReader) source : new BufferedReader(source)) {
                String line;
                while ((line = in.readLine()) != null) addLine(line, domains);
            }
        }
        domains.removeAll(NEVER);
        return new DomainFilter(domains);
    }

    static void addLine(String line, Set<String> into) {
        if (line == null) return;
        int hash = line.indexOf('#');
        if (hash >= 0) line = line.substring(0, hash);
        line = line.trim().toLowerCase(Locale.ROOT);
        if (line.isEmpty()) return;
        String[] parts = line.split("\\s+");
        String host = parts.length == 1 ? parts[0]
            : (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1") || parts[0].equals("::") || parts[0].equals("::1"))
                && parts.length >= 2 ? parts[1] : null;
        if (host == null) return;
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (!valid(host) || NEVER.contains(host)) return;
        into.add(host);
    }

    static String normalize(String host) {
        if (host == null) return null;
        host = host.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 253 || host.indexOf('.') < 0) return null;
        return host;
    }

    private static boolean valid(String host) {
        if (host.length() > 253 || host.indexOf('.') < 0) return false;
        // Bare IPv4 is not a domain; suffix-matching it would be meaningless.
        if (host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) return false;
        int label = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
            if (!ok) return false;
            if (c == '.') {
                if (label == 0 || label > 63) return false;
                label = 0;
            } else label++;
        }
        return label > 0 && label <= 63;
    }
}
