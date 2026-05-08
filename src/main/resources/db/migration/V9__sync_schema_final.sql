-- V9__sync_schema_final.sql
-- Revert appointment enums to VARCHAR to satisfy standard Hibernate EnumType.STRING validation.
-- (V7 previously changed these to ENUM, which causes Types#VARCHAR vs Types#OTHER schema validation failures)

ALTER TABLE appointments
    MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    MODIFY COLUMN appointment_type VARCHAR(20) NOT NULL DEFAULT 'IN_PERSON';
