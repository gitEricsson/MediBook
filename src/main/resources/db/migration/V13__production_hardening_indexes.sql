-- Production hardening indexes for the current MySQL schema.
-- Note: MySQL does not support PostgreSQL-style partial indexes.
-- Active-slot uniqueness remains enforced by appointments.slot_key.

ALTER TABLE appointments
    ADD INDEX idx_appt_doctor_scheduled_status (doctor_id, scheduled_at, status);

ALTER TABLE appointments
    ADD INDEX idx_appt_patient_scheduled_status (patient_id, scheduled_at, status);

ALTER TABLE doctors
    ADD INDEX idx_doctors_department_specialization_active (department_id, specialization, is_active);

ALTER TABLE processed_events
    ADD INDEX idx_processed_events_type_processed_at (event_type, processed_at);
