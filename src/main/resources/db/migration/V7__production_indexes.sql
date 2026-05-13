-- Production query-path indexes.

-- Appointment overlap checks, doctor schedules, reminders, and doctor/patient authorization paths.
CREATE INDEX idx_appt_doctor_time_status
    ON appointments (doctor_id, scheduled_at, end_time, status);

CREATE INDEX idx_appt_doctor_patient
    ON appointments (doctor_id, patient_id);

CREATE INDEX idx_appt_status_scheduled
    ON appointments (status, scheduled_at);

-- Patient appointment timelines page by patient and time.
CREATE INDEX idx_appt_patient_scheduled
    ON appointments (patient_id, scheduled_at);
