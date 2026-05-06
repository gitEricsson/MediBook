package com.medibook.common.encryption;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter that transparently encrypts PHI fields on write
 * and decrypts on read using AES-256-GCM.
 *
 * Usage on entity field:
 * {@code @Convert(converter = PhiAttributeConverter.class)}
 * {@code private String diagnosis;}
 */
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
        return encryptionService.decrypt(dbData);
    }
}
