package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class AesGcmSensitiveDataCipherTest {

    @Test
    void failsClosedWhenEncryptionKeyIsMissingOrInvalid() {
        assertThatThrownBy(() -> new AesGcmSensitiveDataCipher(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmSensitiveDataCipher(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmSensitiveDataCipher("not-base64"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmSensitiveDataCipher("c2hvcnQ="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validKeyEncryptsAndDecryptsWithoutPersistingPlaintext() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        AesGcmSensitiveDataCipher cipher = new AesGcmSensitiveDataCipher(key);

        String encrypted = cipher.encrypt("13800138000");

        assertThat(encrypted).doesNotContain("13800138000");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("13800138000");
    }
}
