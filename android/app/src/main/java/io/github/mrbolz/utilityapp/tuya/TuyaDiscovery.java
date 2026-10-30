package io.github.mrbolz.utilityapp.tuya;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds Tuya devices by their UDP announcements, as in tinytuya's scanner.py and udp_helper.py.
 * Devices broadcast every few seconds: 3.1 in plain JSON to port 6666, 3.3 and 3.4 encrypted to
 * port 6667, and 3.5 in 0x6699 frames to port 7000, where they also answer a discovery request.
 */
public final class TuyaDiscovery {
    static final int PORT_PLAIN = 6666;
    static final int PORT_ENCRYPTED = 6667;
    static final int PORT_APP = 7000;
    /** Asks 3.5 devices to announce themselves (tinytuya's REQ_DEVINFO). */
    static final int REQ_DEVINFO = 0x25;
    private static final int RESEND_MS = 2000;
    private static final int POLL_MS = 50;

    public static final class Found {
        public final String id;
        public final String ip;
        public final String version;
        public final String productKey;

        public Found(String id, String ip, String version, String productKey) {
            this.id = id;
            this.ip = ip;
            this.version = version;
            this.productKey = productKey;
        }

        @Override
        public String toString() {
            return "Found{id=" + id + ", ip=" + ip + ", version=" + version + ", productKey=" + productKey + "}";
        }
    }

    /** Binds a socket to a network before use (Android: Network.bindSocket to force Wi-Fi). */
    public interface SocketBinder {
        void bind(DatagramSocket socket) throws IOException;
    }

    private TuyaDiscovery() {}

    /**
     * Listens on 6666, 6667 and 7000 for {@code durationMs} (and sends the 3.5 discovery request);
     * deduplicates by id. Ports that are in use are skipped; fails only if none can be opened.
     */
    public static List<Found> listen(int durationMs, SocketBinder binder) throws IOException {
        int[] ports = {PORT_PLAIN, PORT_ENCRYPTED, PORT_APP};
        List<DatagramSocket> sockets = new ArrayList<>();
        List<Integer> socketPorts = new ArrayList<>();
        Map<String, Found> found = new LinkedHashMap<>();
        IOException failure = null;
        try {
            for (int port : ports) {
                try {
                    sockets.add(open(port, binder));
                    socketPorts.add(port);
                } catch (IOException e) {
                    if (failure == null) failure = e;
                }
            }
            if (sockets.isEmpty()) throw failure;
            int appIndex = socketPorts.indexOf(PORT_APP);
            DatagramSocket app = appIndex >= 0 ? sockets.get(appIndex) : null;
            byte[] buffer = new byte[4096];
            SecureRandom random = new SecureRandom();
            long start = System.currentTimeMillis();
            long deadline = start + durationMs;
            long nextRequest = start;
            while (System.currentTimeMillis() < deadline) {
                // UDP can get lost: repeat the request now and then.
                if (app != null && System.currentTimeMillis() >= nextRequest) {
                    sendRequests(app, random);
                    nextRequest += RESEND_MS;
                }
                for (int i = 0; i < sockets.size(); i++) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        sockets.get(i).receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    Found device = decode(packet.getData(), packet.getLength(), socketPorts.get(i));
                    if (device == null) continue;
                    // Like tinytuya, trust the sender's address over the one in the message.
                    String ip = packet.getAddress().getHostAddress();
                    found.put(device.id, new Found(device.id, ip, device.version, device.productKey));
                }
            }
        } finally {
            for (DatagramSocket socket : sockets) socket.close();
        }
        return new ArrayList<>(found.values());
    }

    private static DatagramSocket open(int port, SocketBinder binder) throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        try {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            if (binder != null) binder.bind(socket);
            socket.bind(new InetSocketAddress(port));
            socket.setSoTimeout(POLL_MS);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** Sends the 3.5 discovery request to the broadcast address of each IPv4 network. */
    private static void sendRequests(DatagramSocket socket, SecureRandom random) {
        for (InterfaceAddress address : broadcastAddresses()) {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            byte[] request = discoveryRequest(address.getAddress().getHostAddress(), iv);
            try {
                socket.send(new DatagramPacket(request, request.length, address.getBroadcast(), PORT_APP));
            } catch (IOException ignored) {
                // Another network (e.g. mobile data) may not allow it; the passive announcements still arrive.
            }
        }
    }

    private static List<InterfaceAddress> broadcastAddresses() {
        List<InterfaceAddress> result = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null) result.add(address);
                }
            }
        } catch (IOException | NullPointerException ignored) {
            // No interface list: only listen.
        }
        return result;
    }

    /** tinytuya's discovery request: {"from": "app", "ip": ...} (with its spaces) in a 0x6699 frame under the UDP key. */
    static byte[] discoveryRequest(String localIp, byte[] iv) {
        byte[] payload = ("{\"from\": \"app\", \"ip\": \"" + localIp + "\"}").getBytes(StandardCharsets.UTF_8);
        return TuyaMessage.pack(TuyaMessage.PREFIX_6699, 0, REQ_DEVINFO, null, payload, TuyaCrypto.UDP_KEY, iv);
    }

    /** Decodes one datagram received on {@code port}; null when it is not a Tuya announcement. */
    public static Found decode(byte[] data, int length, int port) {
        Map<String, Object> json;
        try {
            json = Json.parseObject(announcement(Arrays.copyOf(data, length)));
        } catch (TuyaException | IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
        // Discovery requests of apps (including our own) are not devices.
        if ("app".equals(json.get("from"))) return null;
        Object gwId = json.get("gwId");
        if (!(gwId instanceof String) || ((String) gwId).isEmpty()) return null;
        Object ip = json.get("ip");
        String version = text(json.get("version"));
        if (version == null) version = port == PORT_APP ? "3.5" : port == PORT_ENCRYPTED ? "3.3" : "3.1";
        return new Found((String) gwId, ip instanceof String ? (String) ip : null, version, text(json.get("productKey")));
    }

    /** The JSON text of an announcement (tinytuya's decrypt_udp). */
    private static String announcement(byte[] data) throws TuyaException, GeneralSecurityException {
        int prefix = data.length >= 4 ? TuyaMessage.getInt(data, 0) : 0;
        byte[] payload;
        if (prefix == TuyaMessage.PREFIX_55AA) {
            payload = TuyaMessage.unpack(data, data.length, null, true).payload;
            boolean plain = payload.length > 0 && payload[0] == '{' && payload[payload.length - 1] == '}';
            if (!plain) payload = TuyaCrypto.ecbDecrypt(TuyaCrypto.UDP_KEY, payload, true);
        } else if (prefix == TuyaMessage.PREFIX_6699) {
            payload = TuyaMessage.unpack(data, data.length, TuyaCrypto.UDP_KEY, false).payload;
            // A return code may precede the JSON.
            if (payload.length > 4 && payload[0] != '{' && payload[4] == '{') payload = Arrays.copyOfRange(payload, 4, payload.length);
            int end = payload.length;
            while (end > 0 && payload[end - 1] == 0) end--;
            payload = Arrays.copyOf(payload, end);
        } else {
            payload = TuyaCrypto.ecbDecrypt(TuyaCrypto.UDP_KEY, data, true);
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof Number) return String.valueOf(value);
        return null;
    }
}
