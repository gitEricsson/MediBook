-- Phase 2: User Profile and Doctor Filtering
ALTER TABLE users 
ADD COLUMN email_notifications BOOLEAN NOT NULL DEFAULT TRUE,
ADD COLUMN sms_notifications BOOLEAN NOT NULL DEFAULT TRUE,
ADD COLUMN locale VARCHAR(10) NOT NULL DEFAULT 'en-US',
ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE doctors
ADD COLUMN languages VARCHAR(255) DEFAULT 'English',
ADD COLUMN accepting_new BOOLEAN NOT NULL DEFAULT TRUE;

-- Indexes for filtering
CREATE INDEX idx_doctors_specialization ON doctors(specialization);
CREATE INDEX idx_doctors_accepting_new ON doctors(accepting_new);
CREATE INDEX idx_doctors_is_active ON doctors(is_active);
