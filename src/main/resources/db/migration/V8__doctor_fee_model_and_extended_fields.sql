-- V8: Doctor fee model, experience, gender, telemedicine flag
-- Also: Department consultation fee override, Doctor average rating

ALTER TABLE doctors
    ADD COLUMN years_of_experience INT         NOT NULL DEFAULT 0,
    ADD COLUMN consultation_fee    DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN gender              VARCHAR(10)  NULL,
    ADD COLUMN telemedicine_enabled BOOLEAN    NOT NULL DEFAULT FALSE,
    ADD COLUMN average_rating      DOUBLE      NOT NULL DEFAULT 0.0,
    ADD COLUMN review_count        INT         NOT NULL DEFAULT 0;

ALTER TABLE departments
    ADD COLUMN base_consultation_fee DECIMAL(10,2) NOT NULL DEFAULT 5000.00;

-- Partial index: only active telemedicine-enabled doctors
CREATE INDEX idx_doctors_telemedicine ON doctors (telemedicine_enabled, is_active);
CREATE INDEX idx_doctors_experience   ON doctors (years_of_experience);
CREATE INDEX idx_doctors_rating       ON doctors (average_rating);
CREATE INDEX idx_doctors_fee          ON doctors (consultation_fee);
