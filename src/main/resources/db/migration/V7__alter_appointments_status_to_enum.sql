-- V7__alter_appointments_status_to_enum.sql
-- Hibernate schema validation requires the DB column type to match the entity's
-- @Enumerated(EnumType.STRING) declaration. The original migration created this
-- column as VARCHAR; this alters it to the expected MySQL ENUM type.
ALTER TABLE appointments
    MODIFY COLUMN status ENUM('PENDING','CONFIRMED','CANCELLED','COMPLETED','NO_SHOW') NOT NULL,
    MODIFY COLUMN appointment_type ENUM('IN_PERSON','TELEHEALTH') NOT NULL DEFAULT 'IN_PERSON';
