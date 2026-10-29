package io.github.mrbolz.utilityapp.tuya;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The device side of the Tuya LAN protocol on a loopback port, for tests: session key negotiation,
 * status queries and commands for versions 3.1 to 3.5. Serves one connection at a time.
 */
final class FakeTuyaDevice implements Closeable {
    final String id;
    final byte[] key;
    final int minor;
    final Map<String, Object> dps = Collections.synchronizedMap(new LinkedHashMap<>());
    /** "cmd:seqno" of every frame received, in order. */
    final List<String> received = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger connections = new AtomicInteger();

    /** Sends junk bytes, a heartbeat and a stale response (wrong sequence number) before each answer. */
    volatile boolean noisy;
    /** Answers DP_QUERY with "data unvalid" like a device22 device. */
    volatile boolean device22;
    /** Reads requests but never answers. */
    volatile boolean silent;
    /** Closes the connection right after the key negotiation (a rejected session key). */
    volatile boolean closeAfterNegotiation;
    /** Closes the connection after answering one request (a dropped idle connection). */
    volatile boolean closeAfterAnswer;
    /** Picks its nonce so the first negotiated session key starts with a zero byte. */
    volatile boolean zeroSessionKeyOnce;
    /** Answers the negotiation even when the client's frame does not authenticate. */
    volatile boolean ignoreBadFrames;

    private final ServerSocket server;
    private final SecureRandom random = new SecureRandom();
    private final Thread thread;
    private int deviceSeqno = 0x100;

    FakeTuyaDevice(String id, String key, String version) throws IOException {
        this(id, key, version, 0);
    }

    FakeTuyaDevice(String id, String key, String version, int port) throws IOException {
        this.id = id;
        this.key = key.getBytes(StandardCharsets.ISO_8859_1);
        this.minor = TuyaDevice.normalizeVersion(version).charAt(2) - '0';
        dps.put("1", false);
        dps.put("20", 2301L);
        server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        thread = new Thread(this::run, "fake-tuya-" + version);
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void run() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                connections.incrementAndGet();
                serve(socket);
            } catch (IOException | GeneralSecurityException e) {
                // The client went away or the server was closed.
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    private void serve(Socket socket) throws IOException, GeneralSecurityException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        // Before 3.4 the local key is used throughout; 3.4+ switch to the session key.
        byte[] current = key;
        byte[] clientNonce = null;
        byte[] deviceNonce = null;
        while (true) {
            byte[] frame = readFrame(in);
            if (frame == null) return;
            TuyaMessage message;
            try {
                message = TuyaMessage.unpack(frame, frame.length, minor >= 4 ? current : null, false);
            } catch (TuyaException e) {
                if (!ignoreBadFrames) return; // Like real 3.4+ devices: drop the connection.
                // Answer with a negotiation response under our own key anyway.
                byte[] body = new byte[48];
                random.nextBytes(body);
                send(out, 1, TuyaDevice.SESS_KEY_NEG_RESP, 0, minor == 4 ? TuyaCrypto.ecbEncrypt(key, body, true) : body, key);
                continue;
            }
            received.add(message.cmd + ":" + message.seqno);
            if (silent) continue;
            if (message.cmd == TuyaDevice.SESS_KEY_NEG_START) {
                clientNonce = minor == 4 ? TuyaCrypto.ecbDecrypt(key, message.payload, true) : message.payload;
                deviceNonce = new byte[16];
                do {
                    random.nextBytes(deviceNonce);
                } while (zeroSessionKeyOnce && TuyaDevice.sessionKey(minor, key, clientNonce, deviceNonce)[0] != 0);
                zeroSessionKeyOnce = false;
                byte[] body = TuyaDevice.concat(deviceNonce, TuyaCrypto.hmacSha256(key, clientNonce));
                send(out, message.seqno, TuyaDevice.SESS_KEY_NEG_RESP, 0, minor == 4 ? TuyaCrypto.ecbEncrypt(key, body, true) : body, key);
            } else if (message.cmd == TuyaDevice.SESS_KEY_NEG_FINISH) {
                byte[] proof = minor == 4 ? TuyaCrypto.ecbDecrypt(key, message.payload, true) : message.payload;
                if (deviceNonce == null || !MessageDigest.isEqual(proof, TuyaCrypto.hmacSha256(key, deviceNonce))) return;
                current = TuyaDevice.sessionKey(minor, key, clientNonce, deviceNonce);
                if (closeAfterNegotiation) return;
            } else if (message.cmd != TuyaDevice.HEART_BEAT) {
                Map<String, Object> request = decrypt(message, current);
                if (request == null) {
                    // A 3.3 device answers what it cannot read with an encrypted rejection.
                    send(out, message.seqno, message.cmd, 1, encrypt("data format error".getBytes(StandardCharsets.US_ASCII), current, false), current);
                    continue;
                }
                answer(out, message, request, current);
                if (closeAfterAnswer) return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void answer(OutputStream out, TuyaMessage message, Map<String, Object> request, byte[] current) throws IOException {
        if (noisy) {
            out.write(new byte[] {0x00, 0x55, (byte) 0xAA});
            send(out, deviceSeqno++, TuyaDevice.HEART_BEAT, 0, new byte[0], current);
            Map<String, Object> stale = new LinkedHashMap<>();
            stale.put("1", "stale");
            // A response to an older request (3.5 does not echo sequence numbers, so another command there).
            int cmd = minor >= 5 ? TuyaDevice.LAN_EXT_STREAM : message.cmd;
            send(out, message.seqno + 1000, cmd, 0, encrypt(json(stale), current, false), current);
        }
        Object dpsField = request.get("dps");
        if (dpsField == null && request.get("data") instanceof Map) dpsField = ((Map<String, Object>) request.get("data")).get("dps");
        boolean query = message.cmd == TuyaDevice.DP_QUERY || message.cmd == TuyaDevice.DP_QUERY_NEW
            || (message.cmd == TuyaDevice.CONTROL_NEW && minor < 4);
        if (query && device22 && message.cmd == TuyaDevice.DP_QUERY) {
            send(out, message.seqno, message.cmd, 0, encrypt("json obj data unvalid".getBytes(StandardCharsets.US_ASCII), current, false), current);
            return;
        }
        if (query) {
            Map<String, Object> values = new LinkedHashMap<>();
            synchronized (dps) {
                if (message.cmd == TuyaDevice.CONTROL_NEW) {
                    // device22: only the requested DPs that exist.
                    for (String dp : ((Map<String, Object>) dpsField).keySet()) {
                        if (dps.containsKey(dp)) values.put(dp, dps.get(dp));
                    }
                } else {
                    values.putAll(dps);
                }
            }
            send(out, message.seqno, message.cmd, 0, encrypt(json(values), current, false), current);
            return;
        }
        Map<String, Object> changes = (Map<String, Object>) dpsField;
        dps.putAll(changes);
        send(out, message.seqno, message.cmd, 0, new byte[0], current);
        send(out, deviceSeqno++, TuyaDevice.STATUS, 0, encrypt(json(changes), current, true), current);
    }

    /** A device message with these DPs, in the form of the version. */
    private byte[] json(Map<String, Object> values) {
        Map<String, Object> message = new LinkedHashMap<>();
        if (minor >= 4) {
            message.put("protocol", 4L);
            message.put("t", System.currentTimeMillis() / 1000L);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dps", values);
            message.put("data", data);
        } else {
            message.put("devId", id);
            message.put("dps", values);
        }
        return Json.stringify(message).getBytes(StandardCharsets.UTF_8);
    }

    /** The request JSON, or null when it cannot be read with the key. */
    private Map<String, Object> decrypt(TuyaMessage message, byte[] current) {
        byte[] data = message.payload;
        byte[] header = versionHeader();
        try {
            if (minor == 1) {
                if (message.cmd == TuyaDevice.CONTROL) data = TuyaCrypto.ecbDecrypt(key, TuyaCrypto.base64Decode(data, 19, data.length - 19), true);
            } else if (minor <= 3) {
                if (startsWith(data, header)) data = Arrays.copyOfRange(data, header.length, data.length);
                data = TuyaCrypto.ecbDecrypt(current, data, true);
            } else {
                if (minor == 4) data = TuyaCrypto.ecbDecrypt(current, data, true);
                if (startsWith(data, header)) data = Arrays.copyOfRange(data, header.length, data.length);
            }
            return Json.parseObject(new String(data, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Encrypts a device payload the way the version does (3.5 is encrypted by the frame). */
    private byte[] encrypt(byte[] payload, byte[] current, boolean withHeader) {
        byte[] header = withHeader && minor > 1 ? versionHeader() : new byte[0];
        if (minor == 1) {
            if (!withHeader) return payload;
            String encrypted = TuyaCrypto.base64Encode(TuyaCrypto.ecbEncrypt(key, payload, true));
            String md5 = TuyaCrypto.hex(TuyaCrypto.md5(TuyaDevice.concat(("data=" + encrypted + "||lpv=3.1||").getBytes(StandardCharsets.ISO_8859_1), key)));
            return ("3.1" + md5.substring(8, 24) + encrypted).getBytes(StandardCharsets.ISO_8859_1);
        }
        if (minor <= 3) return TuyaDevice.concat(header, TuyaCrypto.ecbEncrypt(current, payload, true));
        if (minor == 4) return TuyaCrypto.ecbEncrypt(current, TuyaDevice.concat(header, payload), true);
        return TuyaDevice.concat(header, payload);
    }

    private void send(OutputStream out, int seqno, int cmd, int retcode, byte[] payload, byte[] current) throws IOException {
        byte[] frame;
        if (minor >= 5) {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            // 3.5 devices number their frames with their own counter.
            frame = TuyaMessage.pack(TuyaMessage.PREFIX_6699, deviceSeqno++, cmd, retcode, payload, current, iv);
        } else {
            frame = TuyaMessage.pack(TuyaMessage.PREFIX_55AA, seqno, cmd, retcode, payload, minor == 4 ? current : null, null);
        }
        out.write(frame);
        out.flush();
    }

    private byte[] versionHeader() {
        return Arrays.copyOf(("3." + minor).getBytes(StandardCharsets.US_ASCII), 15);
    }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        byte[] header = new byte[TuyaMessage.HEADER_6699];
        try {
            in.readFully(header, 0, 4);
            int headerLength = TuyaMessage.headerLength(TuyaMessage.getInt(header, 0));
            if (headerLength < 0) return null;
            in.readFully(header, 4, headerLength - 4);
            int total = TuyaMessage.frameLength(header);
            if (total < 0) return null;
            byte[] frame = Arrays.copyOf(header, total);
            in.readFully(frame, headerLength, total - headerLength);
            return frame;
        } catch (EOFException e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }
}
