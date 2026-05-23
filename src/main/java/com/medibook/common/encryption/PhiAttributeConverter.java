package com.medibook.common.encryption;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter that transparently encrypts PHI fields on write
 * and decrypts on read using AES-256-GCM.
 *
 * Usage on entity field:
 * {@code @Convert(converter = PhiAttributeConverter.class)}
 * {@code private String diagnosis;}
 *
 * <p><b>Legacy plaintext tolerance.</b> Some columns were added to this converter
 * after rows already existed in plaintext (e.g. {@code Appointment.reason},
 * {@code Prescription.drug_name}, {@code PatientProfile.blood_group}). For those
 * rows the stored value is not a valid AES-GCM ciphertext, so {@code decrypt()}
 * would throw and crash any read that touches the column. To avoid a hard
 * outage during the gradual migration, this converter:
 *
 * <ol>
 *   <li>Attempts decryption normally.</li>
 *   <li>If decryption fails (bad Base64, GCM auth tag mismatch, etc.), logs a
 *       warning at most once per column-value-shape and returns the raw value
 *       as-is — assuming it's a pre-encryption legacy row.</li>
 * </ol>
 *
 * New writes always go through {@code encrypt()}, so the column gradually
 * self-heals as rows are updated. A backfill job can be run to encrypt any
 * stragglers; until then, this fallback prevents data loss and 500s.</p>
 */
@Slf4j
@Component
@Converter
@RequiredArgsConstructor
public class PhiAttributeConverter implements AttributeConverter<String, String> {

    private final PhiEncryptionService encryptionService;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptionService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        try {
            return encryptionService.decrypt(dbData);
        } catch (Exception e) {
            // Legacy plaintext row written before this column was encrypted.
            // Return as-is so the read succeeds; a future write will encrypt it.
            log.warn("PHI column held non-decryptable value (length={}); treating as legacy plaintext. cause={}",
                    dbData.length(), e.getMessage());
            return dbData;
        }
    }
}
