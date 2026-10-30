package io.github.mrbolz.utilityapp.tuya;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * One frame of the Tuya LAN protocol, as in tinytuya's message_helper.py.
 *
 * <p>0x55AA frames (3.1 to 3.4): prefix, seqno, cmd, length, [retcode], payload, CRC32 (or
 * HMAC-SHA256 on 3.4), suffix. 0x6699 frames (3.5): prefix, 2 zero bytes, seqno, cmd, length, IV,
 * AES-GCM([retcode] + payload) with the header after the prefix as associated data, tag, suffix.
 * Frames from the device carry a return code, frames to it do not.
 */
final class TuyaMessage {
    static final int PREFIX_55AA = 0x000055AA;
    static final int SUFFIX_55AA = 0x0000AA55;
    static final int PREFIX_6699 = 0x00006699;
    static final int SUFFIX_6699 = 0x00009966;
    static final int HEADER_55AA = 16;
    static final int HEADER_6699 = 18;
    /** Larger frames mean a corrupt or desynchronised stream (tinytuya's limit is 1440 bytes). */
    static final int MAX_LENGTH = 16 * 1024;

    final int prefix;
    final int seqno;
    final int cmd;
    /** The device's return code, or null when the frame has none. */
    final Integer retcode;
    final byte[] payload;

    TuyaMessage(int prefix, int seqno, int cmd, Integer retcode, byte[] payload) {
        this.prefix = prefix;
        this.seqno = seqno;
        this.cmd = cmd;
        this.retcode = retcode;
        this.payload = payload;
    }

    /**
     * Packs a frame. {@code key} is the HMAC key for 0x55AA frames (null for CRC32) and the AES-GCM
     * key for 0x6699 frames, which also need a 12-byte {@code iv}.
     */
    static byte[] pack(int prefix, int seqno, int cmd, Integer retcode, byte[] payload, byte[] key, byte[] iv) {
        int retcodeLength = retcode == null ? 0 : 4;
        if (prefix == PREFIX_55AA) {
            int endLength = (key == null ? 4 : 32) + 4;
            int length = retcodeLength + payload.length + endLength;
            byte[] frame = new byte[HEADER_55AA + length];
            putInt(frame, 0, PREFIX_55AA);
            putInt(frame, 4, seqno);
            putInt(frame, 8, cmd);
            putInt(frame, 12, length);
            if (retcode != null) putInt(frame, HEADER_55AA, retcode);
            System.arraycopy(payload, 0, frame, HEADER_55AA + retcodeLength, payload.length);
            int checked = frame.length - endLength;
            if (key == null) {
                putInt(frame, checked, (int) TuyaCrypto.crc32(frame, 0, checked));
            } else {
                System.arraycopy(TuyaCrypto.hmacSha256(key, frame, 0, checked), 0, frame, checked, 32);
            }
            putInt(frame, frame.length - 4, SUFFIX_55AA);
            return frame;
        }
        if (prefix == PREFIX_6699) {
            byte[] raw = new byte[retcodeLength + payload.length];
            if (retcode != null) putInt(raw, 0, retcode);
            System.arraycopy(payload, 0, raw, retcodeLength, payload.length);
            int length = 12 + raw.length + 16;
            byte[] frame = new byte[HEADER_6699 + length + 4];
            putInt(frame, 0, PREFIX_6699);
            putInt(frame, 6, seqno);
            putInt(frame, 10, cmd);
            putInt(frame, 14, length);
            byte[] encrypted = TuyaCrypto.gcmEncrypt(key, iv, Arrays.copyOfRange(frame, 4, HEADER_6699), raw);
            System.arraycopy(iv, 0, frame, HEADER_6699, 12);
            System.arraycopy(encrypted, 0, frame, HEADER_6699 + 12, encrypted.length);
            putInt(frame, frame.length - 4, SUFFIX_6699);
            return frame;
        }
        throw new IllegalArgumentException("Unknown prefix");
    }

    /** Length of the header that starts with {@code prefix}, or -1 if it is not a Tuya prefix. */
    static int headerLength(int prefix) {
        if (prefix == PREFIX_55AA) return HEADER_55AA;
        if (prefix == PREFIX_6699) return HEADER_6699;
        return -1;
    }

    /** Total length of the frame whose header starts {@code data}, or -1 if the header is invalid. */
    static int frameLength(byte[] data) {
        int prefix = getInt(data, 0);
        int length;
        if (prefix == PREFIX_55AA) {
            length = getInt(data, 12);
            if (length < 8 || length > MAX_LENGTH) return -1;
            return HEADER_55AA + length;
        }
        if (prefix == PREFIX_6699) {
            length = getInt(data, 14);
            if (length < 28 || length > MAX_LENGTH) return -1;
            return HEADER_6699 + length + 4;
        }
        return -1;
    }

    /**
     * Unpacks the frame at the start of {@code data}. {@code key} as for {@link #pack}. A wrong
     * HMAC or GCM tag means the key is wrong; like tinytuya, a wrong CRC32 is tolerated.
     */
    static TuyaMessage unpack(byte[] data, int available, byte[] key, boolean withRetcode) throws TuyaException {
        if (available < HEADER_55AA) throw bad("Frame too short");
        int prefix = getInt(data, 0);
        if (prefix == PREFIX_6699 && available < HEADER_6699) throw bad("Frame too short");
        int total = frameLength(data);
        if (total < 0) throw bad("Not a Tuya frame");
        if (total > available) throw bad("Frame truncated");
        if (prefix == PREFIX_55AA) {
            int seqno = getInt(data, 4);
            int cmd = getInt(data, 8);
            int endLength = (key == null ? 4 : 32) + 4;
            int retcodeLength = withRetcode ? 4 : 0;
            if (total - HEADER_55AA < retcodeLength + endLength) throw bad("Frame too short");
            int checked = total - endLength;
            if (key != null) {
                byte[] expected = TuyaCrypto.hmacSha256(key, data, 0, checked);
                if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(data, checked, checked + 32))) {
                    throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The message HMAC does not match: wrong local key?");
                }
            }
            Integer retcode = withRetcode ? getInt(data, HEADER_55AA) : null;
            return new TuyaMessage(prefix, seqno, cmd, retcode, Arrays.copyOfRange(data, HEADER_55AA + retcodeLength, checked));
        }
        int seqno = getInt(data, 6);
        int cmd = getInt(data, 10);
        if (key == null) throw new IllegalArgumentException("0x6699 frames need a key");
        byte[] iv = Arrays.copyOfRange(data, HEADER_6699, HEADER_6699 + 12);
        byte[] content;
        try {
            content = TuyaCrypto.gcmDecrypt(key, iv, Arrays.copyOfRange(data, 4, HEADER_6699), Arrays.copyOfRange(data, HEADER_6699 + 12, total - 4));
        } catch (GeneralSecurityException e) {
            throw new TuyaException(TuyaException.Reason.KEY_REJECTED, "The message does not decrypt: wrong local key?", e);
        }
        if (!withRetcode || content.length < 4) return new TuyaMessage(prefix, seqno, cmd, null, content);
        return new TuyaMessage(prefix, seqno, cmd, getInt(content, 0), Arrays.copyOfRange(content, 4, content.length));
    }

    static int getInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    static void putInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static TuyaException bad(String message) {
        return new TuyaException(TuyaException.Reason.BAD_RESPONSE, message);
    }
}
