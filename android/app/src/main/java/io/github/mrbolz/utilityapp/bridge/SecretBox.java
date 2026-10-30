package io.github.mrbolz.utilityapp.bridge;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Seals local keys and the cloud secret with AES-GCM under a key that never leaves the Android
 * Keystore. A sealed value is "base64(iv):base64(ciphertext)".
 */
final class SecretBox {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "brutus-secrets";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private SecretBox() {}

    static synchronized String seal(String plain) throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // The keystore picks a random IV.
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP);
        } catch (RuntimeException e) {
            // Some keystores fail with runtime exceptions (e.g. ProviderException).
            throw new GeneralSecurityException(e);
        }
    }

    /** The plain text; null when it cannot be decrypted (e.g. data restored onto another phone). */
    static synchronized String open(String sealed) {
        if (sealed == null) return null;
        int colon = sealed.indexOf(':');
        if (colon < 0) return null;
        try {
            SecretKey key = key(false);
            if (key == null) return null;
            byte[] iv = Base64.decode(sealed.substring(0, colon), Base64.NO_WRAP);
            byte[] data = Base64.decode(sealed.substring(colon + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /** The keystore key; created when missing if {@code create}, otherwise null then. */
    private static SecretKey key(boolean create) throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        Key existing = keyStore.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create) return null;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
