package com.lowaltitude.inspection.application.reservation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM keeps contact, address and note out of plaintext database columns. */
public final class AesGcmSensitiveDataCipher implements SensitiveDataCipher {
    private static final int IV_LENGTH = 12;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSensitiveDataCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("encryption key must be configured");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "encryption key must be a 256-bit Base64 value",
                    exception);
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException(
                    "encryption key must be a 256-bit Base64 value");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }
    @Override public String encrypt(String value) {
        if (value == null) throw new IllegalArgumentException("sensitive value must not be null");
        byte[] iv = new byte[IV_LENGTH]; random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + cipher.getOutputSize(value.getBytes(StandardCharsets.UTF_8).length))
                    .put(iv).put(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))).array());
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("sensitive encryption failed", exception); }
    }
    @Override public String decrypt(String value) {
        try {
            byte[] all = Base64.getDecoder().decode(value);
            if (all.length <= IV_LENGTH) throw new IllegalArgumentException("invalid encrypted value");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_LENGTH));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("sensitive decryption failed", exception); }
    }
}
