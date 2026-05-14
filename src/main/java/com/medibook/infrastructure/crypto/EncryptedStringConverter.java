package com.medibook.infrastructure.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter for field-level encryption using AES-256-GCM.
 * Encrypts/decrypts String entity fields transparently.
 * Use with @Convert(converter = EncryptedStringConverter.class) on entity fields.
 */
@Component
@Converter(autoApply = false)
@RequiredArgsConstructor
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldEncryptor fieldEncryptor;

    /**
     * Converts entity attribute (plaintext) to database column (encrypted).
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        return fieldEncryptor.encrypt(attribute);
    }

    /**
     * Converts database column (encrypted) to entity attribute (plaintext).
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        return fieldEncryptor.decrypt(dbData);
    }
}
