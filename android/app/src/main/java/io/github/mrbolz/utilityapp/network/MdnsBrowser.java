package io.github.mrbolz.utilityapp.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers mDNS / DNS-SD services (Bonjour, Google Cast, AirPlay, printers, …) and hostnames.
 *
 * <p>Queries are sent from an ephemeral port, which makes them "legacy unicast" queries (RFC 6762
 * section 6.7): responders answer directly to that port, so no multicast lock is needed to receive.
 */
final class MdnsBrowser {
    private static final String GROUP = "224.0.0.251";
    private static final int PORT = 5353;
    private static final String SERVICE_TYPES = "_services._dns-sd._udp.local";
    /** Queried up front because not every responder answers the service type enumeration. */
    static final List<String> COMMON_TYPES = Arrays.asList(
            "_http._tcp", "_https._tcp", "_googlecast._tcp", "_airplay._tcp", "_raop._tcp",
            "_companion-link._tcp", "_hap._tcp", "_homekit._tcp", "_ipp._tcp", "_ipps._tcp",
            "_printer._tcp", "_pdl-datastream._tcp", "_uscan._tcp", "_scanner._tcp", "_smb._tcp",
            "_afpovertcp._tcp", "_adisk._tcp", "_ssh._tcp", "_sftp-ssh._tcp", "_workstation._tcp",
            "_device-info._tcp", "_spotify-connect._tcp", "_sonos._tcp", "_amzn-wplay._tcp",
            "_androidtvremote2._tcp", "_matter._tcp", "_matterc._udp", "_meshcop._udp", "_hue._tcp",
            "_home-assistant._tcp", "_esphomelib._tcp", "_mqtt._tcp", "_rfb._tcp", "_daap._tcp",
            "_sleep-proxy._udp", "_apple-mobdev2._tcp", "_nfs._tcp", "_ftp._tcp");
    /** Keeps each query packet well below a typical MTU. */
    private static final int QUESTIONS_PER_PACKET = 24;

    static final class Service {
        final String name;
        final String type;
        final int port;
        final Map<String, String> txt;

        Service(String name, String type, int port, Map<String, String> txt) {
            this.name = name;
            this.type = type;
            this.port = port;
            this.txt = txt;
        }
    }

    static final class Result {
        final Map<String, List<Service>> servicesByIp = new LinkedHashMap<>();
        final Map<String, String> hostnamesByIp = new LinkedHashMap<>();
    }

    /** Collected answers. Kept separate from the socket handling so it can be unit-tested. */
    static final class State {
        final Set<String> types = new LinkedHashSet<>();
        final Map<String, Set<String>> instancesByType = new HashMap<>();
        final Map<String, Dns.Record> srvByInstance = new HashMap<>();
        final Map<String, Map<String, String>> txtByInstance = new HashMap<>();
        final Map<String, String> ipByHost = new HashMap<>();
        final Map<String, String> hostByIp = new HashMap<>();
        private final Set<String> asked = new LinkedHashSet<>();

        void add(Dns.Record record) {
            String name = lower(record.name);
            switch (record.type) {
                case Dns.TYPE_PTR:
                    if (record.target == null) break;
                    if (name.equals(SERVICE_TYPES)) {
                        types.add(lower(record.target));
                    } else if (name.endsWith(".in-addr.arpa")) {
                        String ip = ipFromReverseName(name);
                        if (ip != null) hostByIp.put(ip, Dns.trimDot(record.target));
                    } else {
                        types.add(name);
                        instancesByType.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(record.target);
                    }
                    break;
                case Dns.TYPE_SRV:
                    srvByInstance.put(name, record);
                    break;
                case Dns.TYPE_TXT:
                    txtByInstance.put(name, record.txt);
                    break;
                case Dns.TYPE_A:
                    if (record.address != null) {
                        ipByHost.put(name, record.address);
                        hostByIp.putIfAbsent(record.address, Dns.trimDot(record.name));
                    }
                    break;
                default:
                    break;
            }
        }

        /** Questions that would fill gaps in what is known; each is only asked once. */
        List<Dns.Question> nextQuestions() {
            List<Dns.Question> questions = new ArrayList<>();
            for (String type : types) ask(questions, type, Dns.TYPE_PTR);
            for (Set<String> instances : instancesByType.values()) {
                for (String instance : instances) {
                    if (!srvByInstance.containsKey(lower(instance))) ask(questions, instance, Dns.TYPE_ANY);
                }
            }
            for (Dns.Record srv : srvByInstance.values()) {
                if (srv.target != null && !ipByHost.containsKey(lower(srv.target))) ask(questions, srv.target, Dns.TYPE_A);
            }
            return questions;
        }

        private void ask(List<Dns.Question> questions, String name, int type) {
            if (asked.add(lower(name) + "/" + type)) questions.add(new Dns.Question(name, type));
        }

        Result result() {
            Result result = new Result();
            for (Map.Entry<String, Set<String>> entry : instancesByType.entrySet()) {
                String type = entry.getKey();
                for (String instance : entry.getValue()) {
                    Dns.Record srv = srvByInstance.get(lower(instance));
                    if (srv == null || srv.target == null) continue;
                    String ip = ipByHost.get(lower(srv.target));
                    if (ip == null) continue;
                    Map<String, String> txt = txtByInstance.getOrDefault(lower(instance), new LinkedHashMap<>());
                    result.servicesByIp.computeIfAbsent(ip, k -> new ArrayList<>())
                            .add(new Service(instanceLabel(instance, type), shortType(type), srv.port, txt));
                    result.hostnamesByIp.putIfAbsent(ip, Dns.trimDot(srv.target));
                }
            }
            hostByIp.forEach(result.hostnamesByIp::putIfAbsent);
            return result;
        }
    }

    private MdnsBrowser() {}

    /**
     * Listens for {@code durationMs}. Asks for the names of {@code reverseLookupIps} and, when
     * {@code services} is set, browses for services.
     */
    static Result browse(int durationMs, Collection<String> reverseLookupIps, boolean services) throws IOException {
        State state = new State();
        List<Dns.Question> initial = new ArrayList<>();
        if (services) {
            initial.add(new Dns.Question(SERVICE_TYPES, Dns.TYPE_PTR));
            for (String type : COMMON_TYPES) state.types.add(type + ".local");
        }
        for (String ip : reverseLookupIps) initial.add(new Dns.Question(Ipv4.reverseName(ip), Dns.TYPE_PTR));

        InetAddress group = InetAddress.getByName(GROUP);
        byte[] buffer = new byte[9000];
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(200);
            long deadline = System.currentTimeMillis() + durationMs;
            List<Dns.Question> pending = initial;
            pending.addAll(state.nextQuestions());
            int id = 1;
            while (System.currentTimeMillis() < deadline) {
                for (int i = 0; i < pending.size(); i += QUESTIONS_PER_PACKET) {
                    byte[] query = Dns.query(id++, pending.subList(i, Math.min(pending.size(), i + QUESTIONS_PER_PACKET)));
                    socket.send(new DatagramPacket(query, query.length, group, PORT));
                }
                // Collect answers for a moment, then ask follow-up questions about what was found.
                long roundEnd = Math.min(deadline, System.currentTimeMillis() + 700);
                while (System.currentTimeMillis() < roundEnd) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    try {
                        for (Dns.Record record : Dns.parse(packet.getData(), packet.getLength())) state.add(record);
                    } catch (IllegalArgumentException ignored) {
                        // Malformed packet: skip it.
                    }
                }
                pending = state.nextQuestions();
            }
        }
        return state.result();
    }

    static String instanceLabel(String instance, String type) {
        String suffix = "." + type;
        return instance.toLowerCase().endsWith(suffix) ? instance.substring(0, instance.length() - suffix.length()) : instance;
    }

    /** "_googlecast._tcp.local" → "_googlecast._tcp". */
    static String shortType(String type) {
        return type.endsWith(".local") ? type.substring(0, type.length() - ".local".length()) : type;
    }

    static String ipFromReverseName(String name) {
        String[] parts = name.split("\\.");
        if (parts.length != 6) return null;
        String ip = parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0];
        return Ipv4.isIpv4(ip) ? ip : null;
    }

    private static String lower(String name) {
        return Dns.trimDot(name).toLowerCase();
    }
}
