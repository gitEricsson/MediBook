-- Visit Co-Pilot: live in-call AI assistant for doctors.
--
-- During a telemedicine call the doctor's browser streams a rolling transcript here.
-- Every refresh, Claude is asked to produce a structured brief (SOAP, red flags,
-- suggested ICDs, Rx draft). On finalize, the doctor-approved brief is converted
-- into a consultation note via the existing notes endpoint.
--
-- One row per telemedicine_session_id (a call). Encrypted PHI columns reuse the
-- same MEDIUMTEXT shape as consultation_notes so the PhiAttributeConverter works.

CREATE TABLE visit_copilot_sessions (
    id                       BIGINT          NOT NULL AUTO_INCREMENT,
    telemedicine_session_id  BIGINT          NOT NULL,
    appointment_id           BIGINT          NOT NULL,
    doctor_id                BIGINT          NOT NULL,
    patient_id               BIGINT          NOT NULL,

    -- Rolling transcript captured client-side. Encrypted at rest.
    transcript               MEDIUMTEXT      NULL,

    -- Latest Claude-generated brief as JSON (chief_complaint, hpi, red_flags,
    -- differentials, suggested_icd, rx_draft, soap). Kept as a single column so we
    -- can iterate on the schema without further migrations.
    brief_json               MEDIUMTEXT      NULL,

    -- Surfaced separately for fast UI banner rendering ("possible PE - consider D-dimer").
    red_flags                TEXT            NULL,

    -- finalized=true means the doctor approved a brief and we persisted it as a note.
    finalized                BOOLEAN         NOT NULL DEFAULT FALSE,
    finalized_at             DATETIME(6)     NULL,
    consultation_note_id     BIGINT          NULL,

    created_at               DATETIME(6)     NOT NULL,
    updated_at               DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_copilot_telemed_session (telemedicine_session_id),
    KEY idx_copilot_doctor   (doctor_id),
    KEY idx_copilot_patient  (patient_id),
    KEY idx_copilot_appt     (appointment_id),
    CONSTRAINT fk_copilot_telemed
        FOREIGN KEY (telemedicine_session_id) REFERENCES telemedicine_sessions(id),
    CONSTRAINT fk_copilot_appt
        FOREIGN KEY (appointment_id) REFERENCES appointments(id),
    CONSTRAINT fk_copilot_doctor
        FOREIGN KEY (doctor_id) REFERENCES doctors(id),
    CONSTRAINT fk_copilot_patient
        FOREIGN KEY (patient_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
