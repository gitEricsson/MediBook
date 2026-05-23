-- Private post-consultation survey table.
-- One survey per appointment (unique constraint on appointment_id).
-- Survey data is internal — never exposed to patients or public endpoints.
CREATE TABLE consultation_surveys (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id       BIGINT       NOT NULL UNIQUE,
    patient_id           BIGINT       NOT NULL,
    doctor_id            BIGINT       NOT NULL,
    overall_satisfaction TINYINT      NOT NULL,
    communication_quality TINYINT     NOT NULL,
    wait_time_experience TINYINT      NOT NULL,
    recommend_likelihood TINYINT      NOT NULL,
    private_comments     TEXT,
    submitted_at         DATETIME(6)  NOT NULL,

    INDEX idx_survey_doctor  (doctor_id),
    INDEX idx_survey_patient (patient_id),

    CONSTRAINT fk_survey_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    CONSTRAINT fk_survey_patient     FOREIGN KEY (patient_id)     REFERENCES users (id),
    CONSTRAINT fk_survey_doctor      FOREIGN KEY (doctor_id)      REFERENCES doctors (id)
);
