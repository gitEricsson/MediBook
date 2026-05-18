-- Structured prescriptions, separate from the free-text consultation_notes.prescriptions field.
--
-- One appointment can have many prescription lines. The drug/dose/route/frequency/duration
-- fields are dispense-ready; instructions is for patient-facing guidance. Status moves
-- ACTIVE → COMPLETED on the configured duration, or CANCELLED if the doctor revokes.
--
-- PHI columns reuse MEDIUMTEXT so the existing PhiAttributeConverter (AES) works.

CREATE TABLE prescriptions (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    appointment_id   BIGINT      NOT NULL,
    doctor_id        BIGINT      NOT NULL,
    patient_id       BIGINT      NOT NULL,

    drug_name        VARCHAR(255) NOT NULL,
    dosage           VARCHAR(120) NOT NULL,
    route            VARCHAR(60)  NULL,
    frequency        VARCHAR(120) NOT NULL,
    duration_days    INT          NULL,
    instructions     MEDIUMTEXT   NULL,

    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    issued_at        DATETIME(6)  NOT NULL,
    expires_at       DATETIME(6)  NULL,
    cancelled_at     DATETIME(6)  NULL,
    cancelled_reason VARCHAR(255) NULL,

    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    deleted_at       DATETIME(6)  NULL,
    deleted_by       BIGINT       NULL,

    PRIMARY KEY (id),
    KEY idx_rx_appt    (appointment_id),
    KEY idx_rx_doctor  (doctor_id),
    KEY idx_rx_patient (patient_id, status),
    CONSTRAINT fk_rx_appt    FOREIGN KEY (appointment_id) REFERENCES appointments(id),
    CONSTRAINT fk_rx_doctor  FOREIGN KEY (doctor_id)      REFERENCES doctors(id),
    CONSTRAINT fk_rx_patient FOREIGN KEY (patient_id)     REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
