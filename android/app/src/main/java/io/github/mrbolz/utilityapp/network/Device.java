package io.github.mrbolz.utilityapp.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Everything learned about one host. Written by several scan threads, so access is synchronized. */
final class Device {
    final String ip;
    boolean self;
    boolean gateway;
    /** How the device was found: ping, tcp, mdns, ssdp, self, gateway. */
    final Set<String> sources = new TreeSet<>();
    Long latencyMs;
    /** Name from the network's DNS (usually the router). */
    String hostname;
    /** Name the device announces itself via mDNS, e.g. "Living-Room.local". */
    String mdnsName;
    final List<MdnsBrowser.Service> services = new ArrayList<>();
    final Set<Integer> openPorts = new TreeSet<>();
    Ssdp.Info upnp;
    String httpTitle;
    String httpServer;

    Device(String ip) {
        this.ip = ip;
    }

    synchronized void found(String source, Long latencyMs) {
        sources.add(source);
        if (latencyMs != null && (this.latencyMs == null || latencyMs < this.latencyMs)) this.latencyMs = latencyMs;
    }
}
