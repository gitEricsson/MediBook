-- V8__sync_schema_with_entities.sql
-- Synchronizes the database schema with JPA entity definitions to resolve Hibernate validation errors.

-- 1. Departments: code and version columns already added in V2, skipping

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
