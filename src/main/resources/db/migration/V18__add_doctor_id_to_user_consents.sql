-- V18__add_doctor_id_to_user_consents.sql

-- Add doctor_id column to user_consents for doctor-specific consent records
ALTER TABLE user_consents
    ADD COLUMN doctor_id BIGINT NULL;

-- Add foreign key constraint for doctor_id
ALTER TABLE user_consents
    ADD CONSTRAINT fk_uc_doctor FOREIGN KEY (doctor_id) REFERENCES doctors (id) ON DELETE CASCADE;

-- Note: idx_uc_user_type is used by FK constraints so we keep it for now
-- The new index will coexist and be used for doctor-specific queries
CREATE UNIQUE INDEX idx_uc_user_doctor_type ON user_consents (user_id, doctor_id, consent_type);
