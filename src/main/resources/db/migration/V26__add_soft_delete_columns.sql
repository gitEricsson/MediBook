-- Add soft delete columns to medical records for HIPAA/GDPR compliance

-- Appointments soft delete
ALTER TABLE appointments ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE appointments ADD COLUMN deleted_by BIGINT NULL;
CREATE INDEX idx_appointments_deleted ON appointments(deleted_at);
CREATE INDEX idx_appointments_deleted_by ON appointments(deleted_by);

-- Consultation notes soft delete
ALTER TABLE consultation_notes ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE consultation_notes ADD COLUMN deleted_by BIGINT NULL;
CREATE INDEX idx_consultation_notes_deleted ON consultation_notes(deleted_at);

-- Payments soft delete
ALTER TABLE payments ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE payments ADD COLUMN deleted_by BIGINT NULL;
CREATE INDEX idx_payments_deleted ON payments(deleted_at);
CREATE INDEX idx_payments_deleted_by ON payments(deleted_by);

-- Invoices soft delete
ALTER TABLE invoices ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE invoices ADD COLUMN deleted_by BIGINT NULL;
CREATE INDEX idx_invoices_deleted ON invoices(deleted_at);
