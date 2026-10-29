package io.github.mrbolz.utilityapp.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny HTTP/1.0 client over a raw socket, for reading device web pages and UPnP descriptions
 * on the local network. Raw sockets are not subject to Android's cleartext HTTP policy, so the
 * app does not need to allow cleartext traffic everywhere.
 */
final class HttpGet {
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);

    static final class Response {
        final int status;
        final Map<String, String> headers;
        final String body;

        Response(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private HttpGet() {}

    static Response get(URI uri, int timeoutMs, int maxBytes) throws IOException {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IOException("Unsupported URL: " + uri);
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            String request = "GET " + path + " HTTP/1.0\r\nHost: " + uri.getHost() + (port == 80 ? "" : ":" + port)
                    + "\r\nUser-Agent: Brutus\r\nAccept: */*\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while (raw.size() < maxBytes && (read = in.read(buffer)) != -1) raw.write(buffer, 0, read);
            return parse(raw.toString(StandardCharsets.UTF_8.name()));
        }
    }

    static Response parse(String raw) throws IOException {
        int headerEnd = raw.indexOf("\r\n\r\n");
        String head = headerEnd < 0 ? raw : raw.substring(0, headerEnd);
        String body = headerEnd < 0 ? "" : raw.substring(headerEnd + 4);
        String[] lines = head.split("\r\n");
        String[] statusLine = lines[0].split(" ");
        if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/")) throw new IOException("Not an HTTP response");
        int status;
        try {
            status = Integer.parseInt(statusLine[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Bad status line: " + lines[0]);
        }
        return new Response(status, headers(lines, 1), body);
    }

    /** Parses "Name: value" lines from {@code start} on into a map with lower-case names. */
    static Map<String, String> headers(String[] lines, int start) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = start; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.putIfAbsent(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
        }
        return headers;
    }

    static String title(String html) {
        Matcher matcher = TITLE.matcher(html);
        if (!matcher.find()) return null;
        String title = decodeEntities(matcher.group(1)).replaceAll("\\s+", " ").trim();
        return title.isEmpty() ? null : title;
    }

    /** The text of the first {@code <tag>} element, or null. Good enough for UPnP descriptions. */
    static String xmlText(String xml, String tag) {
        Matcher matcher = Pattern.compile("<(?:\\w+:)?" + Pattern.quote(tag) + ">([^<]*)</", Pattern.CASE_INSENSITIVE).matcher(xml);
        if (!matcher.find()) return null;
        String text = decodeEntities(matcher.group(1)).trim();
        return text.isEmpty() ? null : text;
    }

    private static String decodeEntities(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }
}
