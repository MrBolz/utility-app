package io.github.mrbolz.utilityapp.tuya;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.net.SocketFactory;

/**
 * One LAN connection to a Tuya device on TCP port 6668, a port of tinytuya's XenonDevice for
 * protocol versions 3.1 to 3.5. Not thread-safe.
 *
 * <p>3.1 sends JSON in the clear (only commands are encrypted), 3.2 and 3.3 encrypt it with
 * AES-ECB under the local key, 3.4 negotiates a session key and signs frames with HMAC-SHA256,
 * and 3.5 negotiates a session key and uses AES-GCM in 0x6699 frames.
 */
public final class TuyaDevice implements Closeable {
    public static final int PORT = 6668;

    // Command types, from tinytuya's command_types.py.
    static final int SESS_KEY_NEG_START = 0x03;
    static final int SESS_KEY_NEG_RESP = 0x04;
    static final int SESS_KEY_NEG_FINISH = 0x05;
    static final int CONTROL = 0x07;
    static final int STATUS = 0x08;
    static final int HEART_BEAT = 0x09;
    static final int DP_QUERY = 0x0a;
    static final int CONTROL_NEW = 0x0d;
    static final int DP_QUERY_NEW = 0x10;
    static final int UPDATEDPS = 0x12;
    static final int LAN_EXT_STREAM = 0x40;

    /** After the device acknowledged a command, how long to wait for it to report the new values. */
    private static final int REPORT_WAIT_MS = 1000;
    /** Some devices reject a session key that starts with a zero byte, so a new one is negotiated. */
    private static final int NEGOTIATION_ATTEMPTS = 3;

    /** A command and its JSON payload, like tinytuya's MessagePayload. */
    static final class Request {
        final int cmd;
        final byte[] payload;

        Request(int cmd, Object json) {
            this.cmd = cmd;
            this.payload = Json.stringify(json).getBytes(StandardCharsets.UTF_8);
        }
    }

    private final String id;
    private final byte[] realKey;
    private final String address;
    private final int port;
    /** "3.1" to "3.5", or null when unsupported. */
    private final String version;
    private final String requestedVersion;
    /** The digit after "3.", 0 when unsupported. */
    private final int minor;
    private final SocketFactory socketFactory;
    private final int timeoutMs;
    private final Random random;
    /** Seconds since the epoch, for the "t" field. */
    private final LongSupplier clock;

    private Socket socket;
    /** The local key, or the session key once negotiated (3.4+). */
    private byte[] key;
    private int seqno = 1;
    /** Devices that need CONTROL_NEW with a DP list to report their status (tinytuya's "device22"). */
    private boolean device22;
    private boolean negotiating;
    /** True once the device sent a frame under the current key on this connection. */
    private boolean confirmed;
    private byte[] localNonce;

    /**
     * @param version "3.1", "3.2", "3.3", "3.4" or "3.5"
     * @param socketFactory creates the sockets (Android passes Network.getSocketFactory() to force Wi-Fi); null for the default
     */
    public TuyaDevice(String id, String localKey, String address, String version, SocketFactory socketFactory, int timeoutMs) {
        this(id, localKey, address, PORT, version, socketFactory, timeoutMs, new SecureRandom(), () -> System.currentTimeMillis() / 1000L);
    }

    TuyaDevice(String id, String localKey, String address, int port, String version, SocketFactory socketFactory, int timeoutMs,
               Random random, LongSupplier clock) {
        this.id = id;
        this.realKey = (localKey == null ? "" : localKey).getBytes(StandardCharsets.ISO_8859_1);
        this.address = address;
        this.port = port;
        this.requestedVersion = version;
        this.version = normalizeVersion(version);
        this.minor = this.version == null ? 0 : this.version.charAt(2) - '0';
        this.socketFactory = socketFactory != null ? socketFactory : SocketFactory.getDefault();
        this.timeoutMs = timeoutMs;
        this.random = random;
        this.clock = clock;
        this.key = realKey;
        // 3.2 behaves like 3.3 with device22.
        this.device22 = minor == 2;
    }

    /** "3.3" for "3.3", "3.30" or "v3.3"; null when it is not a supported version. */
    static String normalizeVersion(String version) {
        if (version == null) return null;
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        try {
            double number = Double.parseDouble(text);
            long tenths = Math.round(number * 10);
            if (Math.abs(number * 10 - tenths) < 1e-6 && tenths >= 31 && tenths <= 35) return "3." + (tenths - 30);
        } catch (NumberFormatException ignored) {
            // Not a number.
        }
        return null;
    }

    /** Opens the connection, including the session key negotiation of 3.4 and 3.5. */
    public void connect() throws IOException, TuyaException {
        close();
        if (minor == 0) throw new TuyaException(TuyaException.Reason.UNSUPPORTED_VERSION, "Unsupported protocol version " + requestedVersion);
        if (minor > 1 && realKey.length != 16) throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The local key must be 16 characters long");
        if (address == null || address.isEmpty()) throw new TuyaException(TuyaException.Reason.UNREACHABLE, "The device has no IP address");
        for (int attempt = 1; ; attempt++) {
            open();
            if (minor < 4) return;
            try {
                if (negotiate(attempt == NEGOTIATION_ATTEMPTS)) return;
            } catch (IOException e) {
                throw failed(e);
            } catch (TuyaException e) {
                close();
                throw e;
            }
            close();
        }
    }

    /** The current values of the device's data points (DP id → value). */
    public Map<String, Object> status() throws IOException, TuyaException {
        boolean wasDevice22 = device22;
        Map<String, Object> dps = exchange(this::queryRequest);
        // tinytuya resends the query when the device asked for the device22 form.
        if (dps == null && device22 && !wasDevice22) dps = exchange(this::queryRequest);
        if (dps == null) throw new TuyaException(TuyaException.Reason.DEVICE_ERROR, "The device does not accept the status query");
        return dps;
    }

    /** Writes DPs, e.g. {"1": true}. Returns the DPs the device reported back (may be empty). */
    public Map<String, Object> setDps(Map<String, Object> dps) throws IOException, TuyaException {
        if (minor == 1 && realKey.length != 16) throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The local key must be 16 characters long");
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : dps.entrySet()) values.put(String.valueOf(entry.getKey()), entry.getValue());
        Map<String, Object> reported = exchange(() -> controlRequest(values));
        if (reported == null) throw new TuyaException(TuyaException.Reason.DEVICE_ERROR, "The device does not accept the command");
        return reported;
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            socket = null;
        }
        key = realKey;
        negotiating = false;
        confirmed = false;
    }

    /** Sends a request, connecting first if needed; reconnects once if the device dropped an idle connection. */
    private Map<String, Object> exchange(Supplier<Request> request) throws IOException, TuyaException {
        boolean reused = socket != null;
        if (!reused) connect();
        try {
            return transact(request.get());
        } catch (TuyaException e) {
            if (!reused || e.reason() != TuyaException.Reason.BUSY) throw e;
            connect();
            return transact(request.get());
        }
    }

    /**
     * Sends one request and reads frames until its answer, skipping heartbeats and other frames.
     * STATUS reports are collected: devices often acknowledge a command with an empty frame and
     * then report the new values. Returns null when the device asks for the device22 form.
     */
    private Map<String, Object> transact(Request request) throws IOException, TuyaException {
        try {
            byte[] frame = encode(request.cmd, request.payload);
            int sentSeqno = seqno - 1;
            socket.getOutputStream().write(frame);
            long deadline = now() + timeoutMs;
            Map<String, Object> reported = new LinkedHashMap<>();
            boolean acknowledged = false;
            while (true) {
                TuyaMessage reply;
                try {
                    reply = receive(deadline);
                } catch (SocketTimeoutException e) {
                    if (acknowledged || !reported.isEmpty()) return reported;
                    throw e;
                }
                // Before 3.5 the device echoes the sequence number; 3.5 devices use their own counter.
                boolean response = reply.cmd == request.cmd && (minor >= 5 || reply.seqno == sentSeqno);
                if (!response && reply.cmd != STATUS) continue;
                int retcode = reply.retcode == null ? 0 : reply.retcode;
                if (response && retcode != 0) throw rejected(reply);
                Map<String, Object> dps = null;
                if (reply.payload.length > 0) {
                    Map<String, Object> json = decodePayload(reply.payload);
                    if (json == null) {
                        if (response) return null;
                        continue;
                    }
                    dps = dpsOf(json);
                }
                if (dps != null) reported.putAll(dps);
                if (response && dps != null) return reported;
                if (response) {
                    // An acknowledgement: the values usually follow in a STATUS report.
                    acknowledged = true;
                    deadline = Math.min(deadline, now() + REPORT_WAIT_MS);
                }
                if (acknowledged && !reported.isEmpty()) return reported;
            }
        } catch (IOException e) {
            throw failed(e);
        } catch (TuyaException e) {
            close();
            throw e;
        }
    }

    /** A response with an error code: the device could not read the request or refused it. */
    private TuyaException rejected(TuyaMessage reply) {
        String detail = "error code " + reply.retcode;
        if (reply.payload.length > 0) {
            try {
                Map<String, Object> json = decodePayload(reply.payload);
                if (json == null) return new TuyaException(TuyaException.Reason.DEVICE_ERROR, "The device rejected the request (data unvalid)");
                detail += ": " + Json.stringify(json);
            } catch (TuyaException e) {
                return new TuyaException(TuyaException.Reason.KEY_REJECTED, "The device rejected the request (" + detail + "): wrong local key or protocol version?");
            }
        }
        return new TuyaException(TuyaException.Reason.DEVICE_ERROR, "The device rejected the request (" + detail + ")");
    }

    private void open() throws TuyaException {
        Socket s = null;
        try {
            s = socketFactory.createSocket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(address, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Nothing to do.
                }
            }
            throw connectError(address, e);
        }
        socket = s;
        key = realKey;
        confirmed = false;
    }

    /** Explains a failed connection attempt: refused or reset means busy, anything else unreachable. */
    static TuyaException connectError(String address, Exception e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("refused") || message.contains("reset")) {
            return new TuyaException(TuyaException.Reason.BUSY, address + " refused the connection: another app may be connected to the device", e);
        }
        if (e instanceof SocketTimeoutException) {
            return new TuyaException(TuyaException.Reason.UNREACHABLE, "No answer from " + address, e);
        }
        return new TuyaException(TuyaException.Reason.UNREACHABLE, address + " is not reachable (" + e.getMessage() + ")", e);
    }

    /**
     * Closes the connection after an I/O error, whose state is unknown now, and throws the error
     * as a TuyaException when it has a known reason; otherwise returns it to be rethrown.
     */
    private IOException failed(IOException e) throws TuyaException {
        boolean wasNegotiating = negotiating;
        boolean wasConfirmed = confirmed;
        close();
        if (e instanceof SocketTimeoutException) {
            throw new TuyaException(TuyaException.Reason.TIMEOUT, "The device did not answer within " + timeoutMs + " ms", e);
        }
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        boolean lost = e instanceof EOFException
            || (e instanceof SocketException && (message.contains("reset") || message.contains("broken pipe") || message.contains("abort")));
        if (!lost) return e;
        // Devices drop the connection when the handshake, the session key or the protocol version is
        // wrong; a busy device refuses the connection instead (see connectError).
        if (wasNegotiating) {
            throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The device closed the connection during key negotiation: wrong local key or protocol version?", e);
        }
        if (!wasConfirmed) {
            String when = minor >= 4 ? "right after key negotiation" : "without answering";
            throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The device closed the connection " + when + ": wrong local key or protocol version?", e);
        }
        throw new TuyaException(TuyaException.Reason.BUSY, "The device closed the connection: another app may be connected to it", e);
    }

    /** Runs the 3.4/3.5 handshake; false when the session key should not be used and a new one is needed. */
    private boolean negotiate(boolean acceptAnyKey) throws IOException, TuyaException {
        negotiating = true;
        socket.getOutputStream().write(negotiationStart());
        long deadline = now() + timeoutMs;
        TuyaMessage response;
        do {
            response = receive(deadline);
        } while (response.cmd != SESS_KEY_NEG_RESP);
        byte[] remoteNonce = negotiationRemoteNonce(response);
        byte[] sessionKey = sessionKey(minor, realKey, localNonce, remoteNonce);
        if (sessionKey[0] == 0 && !acceptAnyKey) return false;
        socket.getOutputStream().write(negotiationFinish(remoteNonce));
        key = sessionKey;
        negotiating = false;
        return true;
    }

    /** Step 1: a random nonce, encrypted and signed with the local key. */
    byte[] negotiationStart() {
        localNonce = new byte[16];
        random.nextBytes(localNonce);
        key = realKey;
        return encode(SESS_KEY_NEG_START, localNonce);
    }

    /** Step 2: checks that the device proved it knows the local key and returns its nonce. */
    byte[] negotiationRemoteNonce(TuyaMessage response) throws TuyaException {
        byte[] payload = response.payload;
        if (minor == 4) {
            try {
                payload = TuyaCrypto.ecbDecrypt(realKey, payload, true);
            } catch (GeneralSecurityException e) {
                throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The key negotiation answer does not decrypt: wrong local key?", e);
            }
        }
        if (payload.length < 48) throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The key negotiation answer is too short");
        byte[] expected = TuyaCrypto.hmacSha256(realKey, localNonce);
        if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(payload, 16, 48))) {
            throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The device does not know this local key");
        }
        return Arrays.copyOf(payload, 16);
    }

    /** Step 3: proves to the device that we know the local key. */
    byte[] negotiationFinish(byte[] remoteNonce) {
        return encode(SESS_KEY_NEG_FINISH, TuyaCrypto.hmacSha256(realKey, remoteNonce));
    }

    /** The key of the session: the XOR of both nonces encrypted with the local key. */
    static byte[] sessionKey(int minor, byte[] realKey, byte[] localNonce, byte[] remoteNonce) {
        byte[] mixed = new byte[16];
        for (int i = 0; i < 16; i++) mixed[i] = (byte) (localNonce[i] ^ remoteNonce[i]);
        if (minor == 4) return TuyaCrypto.ecbEncrypt(realKey, mixed, false);
        // 3.5: AES-GCM with the first 12 bytes of the local nonce as IV; the key is the ciphertext.
        return Arrays.copyOf(TuyaCrypto.gcmEncrypt(realKey, Arrays.copyOf(localNonce, 12), null, mixed), 16);
    }

    /** Uses a known session key (for tests). */
    void useKey(byte[] key) {
        this.key = key;
    }

    /** The status query (tinytuya's DP_QUERY payload for this version and device type). */
    Request queryRequest() {
        Map<String, Object> json = new LinkedHashMap<>();
        if (device22) {
            json.put("devId", id);
            json.put("uid", id);
            json.put("t", String.valueOf(clock.getAsLong()));
            Map<String, Object> dps = new LinkedHashMap<>();
            dps.put("1", null);
            json.put("dps", dps);
            return new Request(CONTROL_NEW, json);
        }
        if (minor >= 4) return new Request(DP_QUERY_NEW, json);
        json.put("gwId", id);
        json.put("devId", id);
        json.put("uid", id);
        json.put("t", String.valueOf(clock.getAsLong()));
        return new Request(DP_QUERY, json);
    }

    /** The command that writes {@code dps} (tinytuya's CONTROL payload for this version). */
    Request controlRequest(Map<String, Object> dps) {
        Map<String, Object> json = new LinkedHashMap<>();
        if (minor >= 4) {
            json.put("protocol", 5L);
            json.put("t", clock.getAsLong());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dps", dps);
            json.put("data", data);
            return new Request(CONTROL_NEW, json);
        }
        json.put("devId", id);
        json.put("uid", id);
        json.put("t", String.valueOf(clock.getAsLong()));
        json.put("dps", dps);
        return new Request(CONTROL, json);
    }

    /** Adds the version header if needed, encrypts and frames a message (tinytuya's _encode_message). */
    byte[] encode(int cmd, byte[] payload) {
        byte[] data = payload;
        boolean header = !noProtocolHeader(cmd);
        int sequence = seqno++;
        if (minor >= 4) {
            if (header) data = concat(versionHeader(), data);
            if (minor >= 5) {
                byte[] iv = new byte[12];
                random.nextBytes(iv);
                return TuyaMessage.pack(TuyaMessage.PREFIX_6699, sequence, cmd, null, data, key, iv);
            }
            data = TuyaCrypto.ecbEncrypt(key, data, true);
            return TuyaMessage.pack(TuyaMessage.PREFIX_55AA, sequence, cmd, null, data, key, null);
        }
        if (minor >= 2) {
            data = TuyaCrypto.ecbEncrypt(key, data, true);
            if (header) data = concat(versionHeader(), data);
        } else if (cmd == CONTROL) {
            String encrypted = TuyaCrypto.base64Encode(TuyaCrypto.ecbEncrypt(key, data, true));
            byte[] signed = concat(("data=" + encrypted + "||lpv=3.1||").getBytes(StandardCharsets.ISO_8859_1), key);
            String md5 = TuyaCrypto.hex(TuyaCrypto.md5(signed));
            data = ("3.1" + md5.substring(8, 24) + encrypted).getBytes(StandardCharsets.ISO_8859_1);
        }
        return TuyaMessage.pack(TuyaMessage.PREFIX_55AA, sequence, cmd, null, data, null, null);
    }

    /** Decrypts a device payload and parses its JSON (tinytuya's _decode_payload); null for "data unvalid". */
    Map<String, Object> decodePayload(byte[] payload) throws TuyaException {
        byte[] data = payload;
        try {
            // 3.4 encrypts the version header together with the payload.
            if (minor == 4) data = TuyaCrypto.ecbDecrypt(key, data, true);
            if (startsWith(data, "3.1".getBytes(StandardCharsets.US_ASCII))) {
                // 3.1: version, 16 characters of an MD5 digest, base64 of the encrypted JSON.
                if (data.length < 19) throw new IllegalArgumentException("Too short");
                data = TuyaCrypto.ecbDecrypt(key, TuyaCrypto.base64Decode(data, 19, data.length - 19), true);
            } else if (minor >= 2) {
                byte[] versionBytes = version.getBytes(StandardCharsets.US_ASCII);
                if (startsWith(data, versionBytes) || (device22 && (data.length & 0x0F) != 0 && data.length >= 15)) {
                    data = Arrays.copyOfRange(data, 15, data.length);
                }
                if (minor < 4) data = TuyaCrypto.ecbDecrypt(key, data, true);
                if ((minor == 3 || minor == 4) && indexOf(data, "data unvalid".getBytes(StandardCharsets.US_ASCII)) >= 0) {
                    device22 = true;
                    return null;
                }
            } else if (!startsWith(data, new byte[] {'{'})) {
                throw new TuyaException(TuyaException.Reason.BAD_RESPONSE, "Unexpected payload from the device");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The device's answer does not decrypt: wrong local key?", e);
        }
        // Only a version header, as some acknowledgements carry.
        if (data.length == 0) return new LinkedHashMap<>();
        Object json;
        try {
            json = Json.parse(new String(data, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            // Before 3.4 there is no signature, so garbage usually means the wrong key.
            if (minor < 4) throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The device's answer is unreadable: wrong local key?", e);
            throw new TuyaException(TuyaException.Reason.BAD_RESPONSE, "The device's answer is not JSON", e);
        }
        if (!(json instanceof Map)) throw new TuyaException(TuyaException.Reason.BAD_RESPONSE, "The device's answer is not a JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) json;
        return map;
    }

    /** The "dps" of a device message; 3.4+ nests them in "data". */
    @SuppressWarnings("unchecked")
    static Map<String, Object> dpsOf(Map<String, Object> json) {
        Object dps = json.get("dps");
        if (!(dps instanceof Map) && json.get("data") instanceof Map) dps = ((Map<String, Object>) json.get("data")).get("dps");
        return dps instanceof Map ? (Map<String, Object>) dps : null;
    }

    /** Reads and unpacks the next frame from the device. */
    private TuyaMessage receive(long deadline) throws IOException, TuyaException {
        byte[] frame = readFrame(deadline);
        boolean signed = minor >= 4 || TuyaMessage.getInt(frame, 0) == TuyaMessage.PREFIX_6699;
        TuyaMessage message = TuyaMessage.unpack(frame, frame.length, signed ? key : null, true);
        if (!negotiating) confirmed = true;
        return message;
    }

    /** Reads one frame, skipping anything before a frame prefix like tinytuya does. */
    private byte[] readFrame(long deadline) throws IOException, TuyaException {
        byte[] header = new byte[TuyaMessage.HEADER_6699];
        readFully(header, 0, 4, deadline);
        int skipped = 0;
        int headerLength;
        while ((headerLength = TuyaMessage.headerLength(TuyaMessage.getInt(header, 0))) < 0) {
            if (++skipped > TuyaMessage.MAX_LENGTH) throw new TuyaException(TuyaException.Reason.BAD_RESPONSE, "The device sends no Tuya frames");
            System.arraycopy(header, 1, header, 0, 3);
            readFully(header, 3, 1, deadline);
        }
        readFully(header, 4, headerLength - 4, deadline);
        int total = TuyaMessage.frameLength(header);
        if (total < 0) throw new TuyaException(TuyaException.Reason.BAD_RESPONSE, "Invalid frame header from the device");
        byte[] frame = Arrays.copyOf(header, total);
        readFully(frame, headerLength, total - headerLength, deadline);
        return frame;
    }

    private void readFully(byte[] buffer, int offset, int length, long deadline) throws IOException {
        InputStream in = socket.getInputStream();
        while (length > 0) {
            long remaining = deadline - now();
            if (remaining <= 0) throw new SocketTimeoutException("Read timed out");
            socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
            int read = in.read(buffer, offset, length);
            if (read < 0) throw new EOFException("Connection closed by the device");
            offset += read;
            length -= read;
        }
    }

    private byte[] versionHeader() {
        // The version followed by 12 zero bytes.
        return Arrays.copyOf(version.getBytes(StandardCharsets.US_ASCII), 15);
    }

    /** tinytuya's NO_PROTOCOL_HEADER_CMDS. */
    private static boolean noProtocolHeader(int cmd) {
        return cmd == DP_QUERY || cmd == DP_QUERY_NEW || cmd == UPDATEDPS || cmd == HEART_BEAT || cmd == SESS_KEY_NEG_START
            || cmd == SESS_KEY_NEG_RESP || cmd == SESS_KEY_NEG_FINISH || cmd == LAN_EXT_STREAM;
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] part) {
        outer:
        for (int i = 0; i + part.length <= data.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
