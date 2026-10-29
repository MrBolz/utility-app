package io.github.mrbolz.utilityapp.network;

import java.util.ArrayList;
import java.util.List;

/** IPv4 address arithmetic on plain ints. */
final class Ipv4 {
    private Ipv4() {}

    static int parse(String address) {
        String[] parts = address.split("\\.");
        if (parts.length != 4) throw new IllegalArgumentException("Not an IPv4 address: " + address);
        int value = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) throw new IllegalArgumentException("Not an IPv4 address: " + address);
            value = (value << 8) | octet;
        }
        return value;
    }

    static String format(int address) {
        return ((address >>> 24) & 0xff) + "." + ((address >>> 16) & 0xff) + "." + ((address >>> 8) & 0xff) + "." + (address & 0xff);
    }

    static boolean isIpv4(String address) {
        try {
            parse(address);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The host addresses to scan for a device at {@code address}/{@code prefixLength}. Networks
     * larger than a /24 are limited to the /24 around the device, so a scan stays quick.
     */
    static List<String> scanRange(String address, int prefixLength) {
        List<String> hosts = new ArrayList<>();
        if (prefixLength < 0 || prefixLength > 30) return hosts;
        int prefix = Math.max(prefixLength, 24);
        int mask = (int) (0xffffffffL << (32 - prefix));
        int network = parse(address) & mask;
        int broadcast = network | ~mask;
        for (int host = network + 1; host < broadcast; host++) hosts.add(format(host));
        return hosts;
    }

    /** Sort key that orders addresses numerically (10.0.0.9 before 10.0.0.10). */
    static long sortKey(String address) {
        return isIpv4(address) ? parse(address) & 0xffffffffL : Long.MAX_VALUE;
    }

    /** The reverse-lookup name for an address, e.g. 4.3.2.1.in-addr.arpa for 1.2.3.4. */
    static String reverseName(String address) {
        String[] parts = address.split("\\.");
        return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0] + ".in-addr.arpa";
    }
}
