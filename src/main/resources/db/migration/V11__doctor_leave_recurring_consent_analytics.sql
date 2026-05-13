-- V11: Doctor leaves, hospital holidays, recurring appointments, consent management

-- ─── Doctor Leaves ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS doctor_leaves (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    doctor_id       BIGINT          NOT NULL,
    start_date      DATE            NOT NULL,
    end_date        DATE            NOT NULL,
    reason          VARCHAR(255)    NULL,
    leave_type      VARCHAR(30)     NOT NULL DEFAULT 'PERSONAL' COMMENT 'PERSONAL|SICK|CONFERENCE|HOLIDAY',
    status          VARCHAR(20)     NOT NULL DEFAULT 'APPROVED',
    created_by      BIGINT          NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_dl_doctor     FOREIGN KEY (doctor_id)  REFERENCES doctors (id) ON DELETE CASCADE,
    CONSTRAINT fk_dl_created_by FOREIGN KEY (created_by) REFERENCES users   (id),
    INDEX idx_dl_doctor_dates (doctor_id, start_date, end_date),
    CONSTRAINT chk_leave_dates CHECK (end_date >= start_date)
) ENGINE=InnoDB;

-- ─── Hospital Holidays ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS hospital_holidays (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    holiday_date    DATE            NOT NULL UNIQUE,
    name            VARCHAR(150)    NOT NULL,
    department_id   BIGINT          NULL COMMENT 'NULL = affects all departments',
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_hh_department FOREIGN KEY (department_id) REFERENCES departments (id),
    INDEX idx_hh_date (holiday_date)
) ENGINE=InnoDB;

-- ─── Appointment Series (Recurring) ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS appointment_series (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    patient_id          BIGINT          NOT NULL,
    doctor_id           BIGINT          NOT NULL,
    recurrence_type     VARCHAR(20)     NOT NULL COMMENT 'DAILY|WEEKLY|MONTHLY',
    recurrence_interval INT             NOT NULL DEFAULT 1,
    start_date          DATE            NOT NULL,
    end_date            DATE            NULL,
    max_occurrences     INT             NULL,
    time_of_day         TIME            NOT NULL,
    duration_mins       INT             NOT NULL DEFAULT 30,
    appointment_type    VARCHAR(20)     NOT NULL DEFAULT 'IN_PERSON',
    reason              TEXT            NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_as_patient FOREIGN KEY (patient_id) REFERENCES users    (id),
    CONSTRAINT fk_as_doctor  FOREIGN KEY (doctor_id)  REFERENCES doctors  (id),
    INDEX idx_as_patient (patient_id),
    INDEX idx_as_doctor  (doctor_id)
) ENGINE=InnoDB;

-- Link appointments back to their series
ALTER TABLE appointments
    ADD COLUMN series_id BIGINT NULL,
    ADD CONSTRAINT fk_appt_series FOREIGN KEY (series_id) REFERENCES appointment_series (id);

-- ─── Consultation Note Templates ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS note_templates (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100)    NOT NULL,
    template_type   VARCHAR(30)     NOT NULL COMMENT 'SOAP|DIAGNOSIS|FOLLOWUP|CUSTOM',
    content         MEDIUMTEXT      NOT NULL,
    doctor_id       BIGINT          NULL COMMENT 'NULL = system-wide template',
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_nt_doctor FOREIGN KEY (doctor_id) REFERENCES doctors (id),
    INDEX idx_nt_doctor_type (doctor_id, template_type)
) ENGINE=InnoDB;

-- ─── User Consents ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_consents (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    consent_type    VARCHAR(50)     NOT NULL COMMENT 'TELEMEDICINE|DATA_PROCESSING|MARKETING|PHI_SHARING',
    granted         BOOLEAN         NOT NULL,
    ip_address      VARCHAR(45)     NULL,
    granted_at      DATETIME(6)     NULL,
    revoked_at      DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_uc_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    UNIQUE INDEX idx_uc_user_type (user_id, consent_type),
    INDEX idx_uc_type (consent_type)
) ENGINE=InnoDB;
