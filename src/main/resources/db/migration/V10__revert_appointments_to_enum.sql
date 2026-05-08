-- V10__revert_appointments_to_enum.sql
-- Revert appointments status and appointment_type back to ENUM.
-- Hibernate 6.x actually defaults to native ENUM mapping for @Enumerated(EnumType.STRING) on MySQL.
-- This restores the schema state from V7.

ALTER TABLE appointments
    MODIFY COLUMN status ENUM('PENDING','CONFIRMED','CANCELLED','COMPLETED','NO_SHOW') NOT NULL,
    MODIFY COLUMN appointment_type ENUM('IN_PERSON','TELEHEALTH') NOT NULL DEFAULT 'IN_PERSON';
