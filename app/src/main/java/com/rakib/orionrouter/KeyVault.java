package com.rakib.orionrouter;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts API keys at rest with an AES/GCM key held in the Android Keystore.
 * Ciphertext stored in SharedPreferences is useless without this device's
 * Keystore key, and the app never sends a key anywhere except to the
 * provider it belongs to, as a Bearer token over HTTPS.
 *
 * If the Keystore key is invalidated (e.g. lock-screen change on some
 * devices), decryption fails: the caller must clear stored keys and ask
 * the user to re-enter them. That failure is surfaced, never silent.
 */
public class KeyVault {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "orion_router_keys_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    /** Thrown when the Keystore key is gone/invalidated and stored keys cannot be read. */
    public static class VaultResetException extends Exception {
        public VaultResetException(Throwable cause) { super(cause); }
    }

    public static String encrypt(String plain) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[IV_BYTES + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, IV_BYTES);
        System.arraycopy(cipherText, 0, combined, IV_BYTES, cipherText.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    public static String decrypt(String encoded) throws VaultResetException {
        try {
            byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
            if (combined.length <= IV_BYTES) throw new IllegalArgumentException("short ciphertext");
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherText = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, cipherText, 0, cipherText.length);
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Any decrypt failure here means the stored value cannot be trusted;
            // treat it as a vault reset so the UI clears keys and asks again.
            throw new VaultResetException(e);
        }
    }

    /** Deletes the Keystore key so the next encrypt() creates a fresh one. */
    public static void reset() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception ignored) {}
    }

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            SecretKey existing = (SecretKey) ks.getKey(ALIAS, null);
            if (existing != null) return existing;
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }
}
