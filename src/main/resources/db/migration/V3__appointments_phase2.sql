-- Phase 2.2-3.2: Appointment Scheduling and Holds
ALTER TABLE appointments ADD COLUMN appointment_type VARCHAR(20) NOT NULL DEFAULT 'IN_PERSON';
ALTER TABLE appointments ADD COLUMN confirmation_code VARCHAR(20) UNIQUE;
ALTER TABLE appointments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Optimize queries for daily schedules
ALTER TABLE appointments ADD INDEX idx_appt_doc_sched (doctor_id, scheduled_at);
