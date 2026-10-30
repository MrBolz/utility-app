package io.github.mrbolz.utilityapp.tuya;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The primitives of the Tuya LAN protocol, as in tinytuya's crypto_helper.py. */
final class TuyaCrypto {
    /** Key of the UDP announcements of 3.3+ devices: md5("yGAdlopoPVldABfn"), from tuya-convert. */
    static final byte[] UDP_KEY = md5("yGAdlopoPVldABfn".getBytes(StandardCharsets.US_ASCII));

    private static final char[] BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TuyaCrypto() {}

    /** AES-128-ECB, optionally with PKCS#7 padding. */
    static byte[] ecbEncrypt(byte[] key, byte[] data, boolean pad) {
        byte[] input = data;
        if (pad) {
            int padding = 16 - data.length % 16;
            input = Arrays.copyOf(data, data.length + padding);
            Arrays.fill(input, data.length, input.length, (byte) padding);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * AES-128-ECB decryption. Like tinytuya, unpadding only checks that the last byte is a padding
     * length of 1 to 16; a wrong key is caught later because the result is not JSON.
     */
    static byte[] ecbDecrypt(byte[] key, byte[] data, boolean unpad) throws GeneralSecurityException {
        if (data.length == 0 || data.length % 16 != 0) throw new BadPaddingException("Invalid length " + data.length);
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] plain = cipher.doFinal(data);
        if (!unpad) return plain;
        int padding = plain[plain.length - 1] & 0xFF;
        if (padding < 1 || padding > 16) throw new BadPaddingException("Invalid padding");
        return Arrays.copyOf(plain, plain.length - padding);
    }

    /** AES-128-GCM with a 12-byte IV; returns the ciphertext followed by the 16-byte tag. */
    static byte[] gcmEncrypt(byte[] key, byte[] iv, byte[] aad, byte[] plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) cipher.updateAAD(aad);
            return cipher.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decrypts ciphertext followed by its tag; throws AEADBadTagException when it does not authenticate. */
    static byte[] gcmDecrypt(byte[] key, byte[] iv, byte[] aad, byte[] encryptedAndTag) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(encryptedAndTag);
    }

    static byte[] hmacSha256(byte[] key, byte[] data, int offset, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(data, offset, length);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmacSha256(byte[] key, byte[] data) {
        return hmacSha256(key, data, 0, data.length);
    }

    static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static long crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = HEX[(data[i] >> 4) & 0x0F];
            out[i * 2 + 1] = HEX[data[i] & 0x0F];
        }
        return new String(out);
    }

    /** Base64 with padding (java.util.Base64 needs API 26). */
    static String base64Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            out.append(BASE64[b0 >> 2]).append(BASE64[((b0 & 0x03) << 4) | (b1 >> 4)]);
            out.append(i + 1 < data.length ? BASE64[((b1 & 0x0F) << 2) | (b2 >> 6)] : '=');
            out.append(i + 2 < data.length ? BASE64[b2 & 0x3F] : '=');
        }
        return out.toString();
    }

    static byte[] base64Decode(byte[] text, int offset, int length) {
        byte[] out = new byte[length * 3 / 4];
        int size = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = offset; i < offset + length; i++) {
            int c = text[i];
            if (c == '=') break;
            int value;
            if (c >= 'A' && c <= 'Z') value = c - 'A';
            else if (c >= 'a' && c <= 'z') value = c - 'a' + 26;
            else if (c >= '0' && c <= '9') value = c - '0' + 52;
            else if (c == '+') value = 62;
            else if (c == '/') value = 63;
            else throw new IllegalArgumentException("Not base64");
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[size++] = (byte) (buffer >> bits);
            }
        }
        return Arrays.copyOf(out, size);
    }
}
