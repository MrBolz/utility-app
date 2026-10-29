package io.github.mrbolz.utilityapp.tuya;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * A client for the Tuya Cloud OpenAPI of the user's own developer project, used once to import the
 * ids, names and local keys of the devices of the app accounts linked to it. Ported from tinytuya's
 * Cloud.py: the request signature of June 2021, the token request and the device list. Not
 * thread-safe.
 */
public final class TuyaCloud {
    static final String TOKEN_PATH = "/v1.0/token";
    static final String DEVICES_PATH = "/v1.0/iot-01/associated-users/devices";
    private static final int PAGE_SIZE = 50;
    /** Stops a device list that never ends: 5000 devices. */
    private static final int MAX_PAGES = 100;
    private static final int TIMEOUT_MS = 15000;
    /** Renew the token this long before Tuya says it expires. */
    private static final long TOKEN_MARGIN_MS = 60000;

    public enum Region {
        CENTRAL_EUROPE("central-europe", "openapi.tuyaeu.com"),
        WESTERN_EUROPE("western-europe", "openapi-weaz.tuyaeu.com"),
        WESTERN_AMERICA("western-america", "openapi.tuyaus.com"),
        EASTERN_AMERICA("eastern-america", "openapi-ueaz.tuyaus.com"),
        CHINA("china", "openapi.tuyacn.com"),
        INDIA("india", "openapi.tuyain.com"),
        SINGAPORE("singapore", "openapi-sg.iotbing.com");

        public final String id;
        public final String host;

        Region(String id, String host) {
            this.id = id;
            this.host = host;
        }

        public static Region fromId(String id) {
            for (Region region : values()) {
                if (region.id.equals(id)) return region;
            }
            throw new IllegalArgumentException("Unknown Tuya data center: " + id);
        }
    }

    /** One device as the cloud lists it. Fields the cloud did not report are null. */
    public static final class CloudDevice {
        public final String id;
        /** The name from the Tuya app; the id when there is none. */
        public final String name;
        public final String localKey;
        /** Tuya category code, e.g. "cz" for sockets. */
        public final String category;
        public final String productName;
        public final String productId;
        /** The address the cloud saw the device connect from, usually the router's public IP, not the LAN address. */
        public final String ip;
        public final boolean online;

        public CloudDevice(String id, String name, String localKey, String category, String productName,
                           String productId, String ip, boolean online) {
            this.id = id;
            this.name = name;
            this.localKey = localKey;
            this.category = category;
            this.productName = productName;
            this.productId = productId;
            this.ip = ip;
            this.online = online;
        }

        /** Maps one entry of result.devices; null when it has no id. */
        static CloudDevice from(Object entry) {
            if (!(entry instanceof Map)) return null;
            Map<?, ?> device = (Map<?, ?>) entry;
            String id = text(device.get("id"));
            if (id == null) return null;
            String name = text(device.get("name"));
            return new CloudDevice(id, name == null ? id : name, text(device.get("local_key")), text(device.get("category")),
                    text(device.get("product_name")), text(device.get("product_id")), text(device.get("ip")),
                    Boolean.TRUE.equals(device.get("online")));
        }
    }

    /** Pluggable HTTP so tests need no network. */
    public interface Http {
        Response send(String method, String url, Map<String, String> headers, String body) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** The cloud refused a request. The message is readable and ends with Tuya's code and msg. */
    public static class TuyaCloudException extends Exception {
        /** Tuya's error code, e.g. "1004"; null when the answer had none. */
        public final String code;

        public TuyaCloudException(String code, String message) {
            super(message);
            this.code = code;
        }

        static TuyaCloudException from(Map<String, Object> response) {
            Object rawCode = response.get("code");
            String code = rawCode == null ? null : String.valueOf(rawCode);
            String msg = text(response.get("msg"));
            String detail = "Tuya error " + (code == null ? "without a code" : code) + (msg == null ? "" : ": " + msg);
            String hint = hint(code, msg);
            return new TuyaCloudException(code, (hint == null ? "The Tuya cloud refused the request" : hint) + " (" + detail + ")");
        }
    }

    private final Region region;
    private final String accessId;
    private final String accessSecret;
    private final Http http;
    private final LongSupplier clock;
    private String token;
    private long tokenExpiresAt;

    public TuyaCloud(Region region, String accessId, String accessSecret) {
        this(region, accessId, accessSecret, TuyaCloud::sendHttps, System::currentTimeMillis);
    }

    /** {@code clock} gives the time in milliseconds since the epoch; Tuya signs it into every request. */
    public TuyaCloud(Region region, String accessId, String accessSecret, Http http, LongSupplier clock) {
        if (region == null) throw new IllegalArgumentException("Choose a data center");
        this.accessId = accessId == null ? "" : accessId.trim();
        this.accessSecret = accessSecret == null ? "" : accessSecret.trim();
        if (this.accessId.isEmpty() || this.accessSecret.isEmpty()) throw new IllegalArgumentException("Access ID and Access Secret are required");
        this.region = region;
        this.http = http;
        this.clock = clock;
    }

    /** All devices of the app accounts linked to the cloud project, in the order the cloud lists them. */
    public List<CloudDevice> listDevices() throws IOException, TuyaCloudException {
        Map<String, CloudDevice> devices = new LinkedHashMap<>();
        Map<String, String> query = new TreeMap<>();
        query.put("size", String.valueOf(PAGE_SIZE));
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<?, ?> result = object(get(DEVICES_PATH, query).get("result"));
            Object list = result.get("devices");
            if (list instanceof List) {
                for (Object entry : (List<?>) list) {
                    CloudDevice device = CloudDevice.from(entry);
                    if (device != null) devices.putIfAbsent(device.id, device);
                }
            }
            String lastRowKey = text(result.get("last_row_key"));
            if (!Boolean.TRUE.equals(result.get("has_more")) || lastRowKey == null || lastRowKey.equals(query.get("last_row_key"))) break;
            query.put("last_row_key", lastRowKey);
        }
        return new ArrayList<>(devices.values());
    }

    /** A GET with the cached token. When Tuya calls the token invalid, gets a new one and tries once more. */
    private Map<String, Object> get(String path, Map<String, String> query) throws IOException, TuyaCloudException {
        if (token == null || clock.getAsLong() >= tokenExpiresAt) renewToken();
        Map<String, Object> response = send(path, query);
        if (tokenInvalid(response)) {
            renewToken();
            response = send(path, query);
        }
        return check(response);
    }

    private void renewToken() throws IOException, TuyaCloudException {
        token = null;
        long now = clock.getAsLong();
        Map<?, ?> result = object(check(send(TOKEN_PATH, Collections.singletonMap("grant_type", "1"))).get("result"));
        String accessToken = text(result.get("access_token"));
        if (accessToken == null) throw new IOException("The Tuya cloud sent no access token");
        Object expiresIn = result.get("expire_time");
        // Without an expiry the token is used until Tuya calls it invalid.
        tokenExpiresAt = expiresIn instanceof Number
                ? now + Math.max(0, ((Number) expiresIn).longValue() * 1000 - TOKEN_MARGIN_MS) : Long.MAX_VALUE;
        token = accessToken;
    }

    /** Signs and sends a GET, with the token when there is one (the token request itself has none). */
    private Map<String, Object> send(String path, Map<String, String> query) throws IOException {
        // Tuya signs the path with the query sorted by key and not URL-encoded.
        StringBuilder signUrl = new StringBuilder(path);
        StringBuilder url = new StringBuilder("https://").append(region.host).append(path);
        char separator = '?';
        for (Map.Entry<String, String> parameter : new TreeMap<>(query).entrySet()) {
            signUrl.append(separator).append(parameter.getKey()).append('=').append(parameter.getValue());
            url.append(separator).append(URLEncoder.encode(parameter.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(parameter.getValue(), "UTF-8"));
            separator = '&';
        }
        long t = clock.getAsLong();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("client_id", accessId);
        headers.put("sign", sign(accessId, accessSecret, token, t, "GET", signUrl.toString(), ""));
        headers.put("t", Long.toString(t));
        headers.put("sign_method", "HMAC-SHA256");
        // Like tinytuya, the token request also carries the secret.
        if (token == null) headers.put("secret", accessSecret);
        else headers.put("access_token", token);
        Response response = http.send("GET", url.toString(), headers, null);
        try {
            return Json.parseObject(response.body == null ? "" : response.body);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unexpected answer from the Tuya cloud (HTTP " + response.status + ")", e);
        }
    }

    /**
     * The request signature: upper-case hex HMAC-SHA256, keyed with the secret, of client_id + access
     * token (none for the token request) + t + stringToSign, where stringToSign is the method, the
     * SHA-256 of the body, the signed headers (none) and the path with its sorted query, one per line.
     */
    static String sign(String accessId, String accessSecret, String token, long t, String method, String signUrl, String body) {
        String stringToSign = method + "\n" + hex(sha256(body.getBytes(StandardCharsets.UTF_8))) + "\n\n" + signUrl;
        String payload = accessId + (token == null ? "" : token) + t + stringToSign;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(accessSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Advice for the errors people run into when setting up a cloud project, or null. */
    static String hint(String code, String msg) {
        String text = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        if ("1004".equals(code) || text.contains("sign invalid")) {
            return "Wrong Access Secret, or the phone's clock is off";
        }
        if (text.contains("clientid invalid")) {
            return "Wrong Access ID";
        }
        if (text.contains("request time")) {
            return "The phone's clock is off: set date and time automatically";
        }
        if ((code != null && code.startsWith("28841")) || text.contains("no permission") || text.contains("subscri")) {
            return "The IoT Core service of the cloud project is not subscribed or its free trial expired: extend it on"
                    + " iot.tuya.com (Cloud → Project Management → IoT Core → Extend trial period)";
        }
        if ("1106".equals(code) || text.contains("permission deny")) {
            return "No access to the devices: link your Smart Life / Tuya app account to the cloud project (Devices →"
                    + " Link Tuya App Account), pick the data center of that account, and check that the IoT Core API is"
                    + " authorized and its trial has not expired";
        }
        if ("1010".equals(code) || text.contains("token invalid")) {
            return "The Tuya cloud keeps rejecting the access token: check that the IoT Core trial has not expired";
        }
        return null;
    }

    private static boolean tokenInvalid(Map<String, Object> response) {
        if (Boolean.TRUE.equals(response.get("success"))) return false;
        String msg = text(response.get("msg"));
        return "1010".equals(String.valueOf(response.get("code"))) || (msg != null && msg.toLowerCase(Locale.ROOT).contains("token invalid"));
    }

    private static Map<String, Object> check(Map<String, Object> response) throws TuyaCloudException {
        if (Boolean.TRUE.equals(response.get("success"))) return response;
        throw TuyaCloudException.from(response);
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
    }

    /** A non-empty string value, or null. */
    private static String text(Object value) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return out.toString();
    }

    /** The default {@link Http}: HttpsURLConnection, which works on Android and on the JVM. */
    private static Response sendHttps(String method, String url, Map<String, String> headers, String body) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod(method);
            for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (in == null) return new Response(status, "");
            try (InputStream stream = in) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) data.write(buffer, 0, read);
                return new Response(status, new String(data.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
