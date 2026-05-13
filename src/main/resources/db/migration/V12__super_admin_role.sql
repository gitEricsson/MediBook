-- V12: Super admin role and supporting indexes for admin management queries

-- Add SUPER_ADMIN to roles reference table
INSERT IGNORE INTO roles (name) VALUES ('ROLE_SUPER_ADMIN');

-- Composite index for admin listing queries that filter by role + enabled state
CREATE INDEX idx_users_role_enabled ON users (role, is_enabled);

-- Composite index for capacity report date-range + department joins
CREATE INDEX idx_appt_dept_scheduled ON appointments (department_id, scheduled_at);
