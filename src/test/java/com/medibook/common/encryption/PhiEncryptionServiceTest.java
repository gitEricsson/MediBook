package com.medibook.common.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PhiEncryptionService — AES-256-GCM")
class PhiEncryptionServiceTest {

    private PhiEncryptionService service;

    @BeforeEach
    void setUp() {
        // 32-char key (padded to 32 bytes internally)
        service = new PhiEncryptionService("medibook-phi-aes256-encryption-key!");
    }

    @Test
    @DisplayName("encrypt then decrypt returns original plaintext")
    void roundTrip() {
        String plaintext = "Patient has Type 2 Diabetes. Prescribed Metformin 500mg.";
        String encrypted = service.encrypt(plaintext);
        String decrypted = service.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("two encryptions of the same plaintext produce different ciphertext (IV randomness)")
    void differentCiphertextEachEncryption() {
        String plaintext = "Same diagnosis text";
        String enc1 = service.encrypt(plaintext);
        String enc2 = service.encrypt(plaintext);
        assertThat(enc1).isNotEqualTo(enc2);
    }

    @Test
    @DisplayName("null input returns null — no NPE")
    void nullHandling() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("decrypt with tampered ciphertext throws IllegalStateException")
    void tamperDetection() {
        String encrypted = service.encrypt("Sensitive PHI");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "XXXX";
        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    @DisplayName("empty string encrypts and decrypts correctly")
    void emptyString() {
        assertThat(service.decrypt(service.encrypt(""))).isEmpty();
    }

    @Test
    @DisplayName("unicode PHI characters round-trip correctly")
    void unicodeRoundTrip() {
        String unicode = "诊断：II型糖尿病。治疗方案：二甲双胍。";
        assertThat(service.decrypt(service.encrypt(unicode))).isEqualTo(unicode);
    }
}
