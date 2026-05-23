-- V30: Add consultation_medium and consultation_type to appointments
-- consultation_medium: PHYSICAL | AUDIO | VIDEO (how it's delivered)
-- consultation_type:   FIRST_VISIT | FOLLOW_UP | EMERGENCY (what kind of visit)

ALTER TABLE appointments
    ADD COLUMN consultation_medium   VARCHAR(20) NOT NULL DEFAULT 'PHYSICAL'
        COMMENT 'PHYSICAL | AUDIO | VIDEO — determines telemedicine eligibility',
    ADD COLUMN consultation_type     VARCHAR(20) NOT NULL DEFAULT 'FIRST_VISIT'
        COMMENT 'FIRST_VISIT | FOLLOW_UP | EMERGENCY',
    ADD COLUMN follow_up_consent_given TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'Patient consented to share prior records for a follow-up consultation';

-- Index on consultation_type for emergency queue queries
CREATE INDEX idx_appt_consultation_type ON appointments (consultation_type);

-- Index on consultation_medium for telemedicine eligibility queries
CREATE INDEX idx_appt_medium ON appointments (consultation_medium);

-- Backfill existing TELEMEDICINE/TELEHEALTH rows to VIDEO medium
UPDATE appointments
   SET consultation_medium = 'VIDEO'
 WHERE appointment_type IN ('TELEMEDICINE', 'TELEHEALTH')
   AND consultation_medium = 'PHYSICAL';
