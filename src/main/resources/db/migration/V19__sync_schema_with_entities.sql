-- V8__sync_schema_with_entities.sql
-- Synchronizes the database schema with JPA entity definitions to resolve Hibernate validation errors.

-- 1. Departments: Add missing 'code' and 'version' columns found in Department entity
ALTER TABLE departments
    ADD COLUMN code VARCHAR(50) NOT NULL UNIQUE AFTER name,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER is_active;

-- 2. Appointments: Adjust cancelled_at from TIMESTAMP to DATETIME(6) 
-- to match Hibernate's default mapping for LocalDateTime in MySQL 8.
ALTER TABLE appointments
    MODIFY COLUMN cancelled_at DATETIME(6) NULL;

-- 3. Idempotency & Config: Adjust remaining TIMESTAMP columns to DATETIME(6)
ALTER TABLE processed_events
    MODIFY COLUMN processed_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6);

ALTER TABLE system_configs
    MODIFY COLUMN updated_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);

-- 4. Consultation Notes: Note on consistency
-- The current DB has it as NOT NULL, while the entity was implicitly nullable.
-- We will keep the DB as NOT NULL (with default) and update the entity to match.
-- (No DB change needed here if we update the entity, but including for clarity if we wanted to change DB instead).
