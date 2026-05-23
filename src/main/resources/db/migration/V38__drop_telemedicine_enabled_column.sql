-- Every doctor is now assignable to any consultation medium (PHYSICAL / AUDIO / VIDEO).
-- The telemedicine_enabled column and its index are no longer needed.

ALTER TABLE doctors DROP INDEX idx_doctors_telemedicine;
ALTER TABLE doctors DROP COLUMN telemedicine_enabled;
