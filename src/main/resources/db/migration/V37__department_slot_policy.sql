-- V37: Add per-department slot duration and buffer policy
-- slot_duration_mins: default appointment length for all doctors in this department
-- buffer_mins: cleanup/prep time added between consecutive slots

ALTER TABLE departments
    ADD COLUMN slot_duration_mins INT NOT NULL DEFAULT 30
        COMMENT 'Default slot length for doctors in this department (minutes)',
    ADD COLUMN buffer_mins INT NOT NULL DEFAULT 0
        COMMENT 'Buffer between consecutive slots for prep/cleaning (minutes)';

-- Seed sensible per-department defaults based on clinical practice
UPDATE departments SET slot_duration_mins = 15, buffer_mins = 5  WHERE code = 'PEDIATRICS';
UPDATE departments SET slot_duration_mins = 45, buffer_mins = 10 WHERE code = 'DENTISTRY';
UPDATE departments SET slot_duration_mins = 20, buffer_mins = 5  WHERE code = 'DERMATOLOGY';
UPDATE departments SET slot_duration_mins = 30, buffer_mins = 5  WHERE code = 'GENERAL';
UPDATE departments SET slot_duration_mins = 45, buffer_mins = 15 WHERE code = 'SURGERY';
UPDATE departments SET slot_duration_mins = 60, buffer_mins = 10 WHERE code = 'PSYCHIATRY';
