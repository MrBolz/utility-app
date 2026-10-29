package io.github.mrbolz.utilityapp.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Discovers UPnP devices (routers, TVs, media servers, speakers) with SSDP M-SEARCH. */
final class Ssdp {
    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    private static final int MAX_DESCRIPTIONS = 32;

    static final class Info {
        String server;
        String location;
        String friendlyName;
        String manufacturer;
        String modelName;
        String modelNumber;
        String deviceType;
    }

    private Ssdp() {}

    static byte[] searchRequest() {
        String request = "M-SEARCH * HTTP/1.1\r\nHOST: " + GROUP + ":" + PORT + "\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    /** Sends M-SEARCH requests, collects answers for {@code durationMs}, then reads each device description. */
    static Map<String, Info> discover(int durationMs) throws IOException {
        Map<String, Info> devices = new LinkedHashMap<>();
        byte[] request = searchRequest();
        InetAddress group = InetAddress.getByName(GROUP);
        byte[] buffer = new byte[4096];
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(200);
            long start = System.currentTimeMillis();
            long deadline = start + durationMs;
            int sent = 0;
            while (System.currentTimeMillis() < deadline) {
                // UDP can get lost: repeat the search a couple of times.
                if (sent < 3 && System.currentTimeMillis() >= start + sent * 600L) {
                    socket.send(new DatagramPacket(request, request.length, group, PORT));
                    sent++;
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String ip = packet.getAddress().getHostAddress();
                Map<String, String> headers = parseResponse(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                if (headers == null) continue;
                Info info = devices.computeIfAbsent(ip, k -> new Info());
                if (info.server == null) info.server = headers.get("server");
                // Prefer the root device description, which names the whole device.
                String location = headers.get("location");
                if (location != null && (info.location == null || "upnp:rootdevice".equalsIgnoreCase(headers.get("st")))) info.location = location;
            }
        }
        int described = 0;
        for (Info info : devices.values()) {
            if (info.location == null || described++ >= MAX_DESCRIPTIONS) continue;
            try {
                HttpGet.Response response = HttpGet.get(URI.create(info.location), 1500, 64 * 1024);
                if (response.status == 200) describe(info, response.body);
            } catch (IOException | IllegalArgumentException ignored) {
                // The description is optional extra detail.
            }
        }
        return devices;
    }

    /** Headers of an SSDP search response, or null if it is not one. */
    static Map<String, String> parseResponse(String message) {
        String[] lines = message.split("\r?\n");
        if (lines.length == 0 || !lines[0].toUpperCase().startsWith("HTTP/1.1 200")) return null;
        return HttpGet.headers(lines, 1);
    }

    static void describe(Info info, String xml) {
        info.friendlyName = HttpGet.xmlText(xml, "friendlyName");
        info.manufacturer = HttpGet.xmlText(xml, "manufacturer");
        info.modelName = HttpGet.xmlText(xml, "modelName");
        info.modelNumber = HttpGet.xmlText(xml, "modelNumber");
        info.deviceType = HttpGet.xmlText(xml, "deviceType");
    }
}
