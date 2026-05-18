-- Add review tracking columns to doctor_leaves.
-- Columns added: reviewed_by (FK to users), reviewed_at.
-- Note: safe to re-run as pure DDL.
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doctor_leaves' AND COLUMN_NAME = 'reviewed_by');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE doctor_leaves ADD COLUMN reviewed_by BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists2 = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doctor_leaves' AND COLUMN_NAME = 'reviewed_at');
SET @sql2 = IF(@col_exists2 = 0, 'ALTER TABLE doctor_leaves ADD COLUMN reviewed_at DATETIME(6) NULL', 'SELECT 1');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
