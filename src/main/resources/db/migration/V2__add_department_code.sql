-- Phase 2: Add Department Code
ALTER TABLE departments ADD COLUMN code VARCHAR(50);
ALTER TABLE departments ADD UNIQUE INDEX idx_dept_code (code);
-- Also adding an index on the name as it might be used in the admin search
ALTER TABLE departments ADD INDEX idx_dept_name (name);

-- Optimistic Locking
ALTER TABLE departments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Indexes on appointments for the 90-day count optimization
ALTER TABLE appointments ADD INDEX idx_appt_dept_scheduled (department_id, scheduled_at);
