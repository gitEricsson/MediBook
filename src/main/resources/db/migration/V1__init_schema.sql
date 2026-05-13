-- MediBook: Consolidated Initial Schema
-- ============================================================

-- 1. Roles
CREATE TABLE IF NOT EXISTS roles (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50)  NOT NULL UNIQUE,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

INSERT IGNORE INTO roles (name) VALUES ('ROLE_PATIENT'), ('ROLE_DOCTOR'), ('ROLE_ADMIN');

-- 2. Users
CREATE TABLE IF NOT EXISTS users (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    email               VARCHAR(255)    NOT NULL UNIQUE,
    password            VARCHAR(255)    NOT NULL,
    first_name          VARCHAR(100)    NOT NULL,
    last_name           VARCHAR(100)    NOT NULL,
    phone               VARCHAR(20),
    date_of_birth       DATE,
    role                VARCHAR(50)     NOT NULL DEFAULT 'ROLE_PATIENT',
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    is_enabled          BOOLEAN         NOT NULL DEFAULT TRUE,
    two_factor_enabled  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_users_email (email),
    INDEX idx_users_role  (role)
) ENGINE=InnoDB;

-- 3. Refresh Tokens (Redis used in prod, but SQL for fallback/audit)
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    user_id     BIGINT          NOT NULL,
    token       VARCHAR(512)    NOT NULL UNIQUE,
    expires_at  DATETIME(6)     NOT NULL,
    revoked     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_rt_token   (token),
    INDEX idx_rt_user_id (user_id)
) ENGINE=InnoDB;

-- 4. Departments
CREATE TABLE IF NOT EXISTS departments (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(150)    NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- 5. Doctors
CREATE TABLE IF NOT EXISTS doctors (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL UNIQUE,
    department_id   BIGINT          NOT NULL,
    specialization  VARCHAR(150),
    license_number  VARCHAR(100)    NOT NULL UNIQUE,
    bio             TEXT,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_doc_user       FOREIGN KEY (user_id)       REFERENCES users       (id) ON DELETE CASCADE,
    CONSTRAINT fk_doc_department FOREIGN KEY (department_id) REFERENCES departments (id),
    INDEX idx_doctors_department (department_id)
) ENGINE=InnoDB;

-- 6. Doctor Working Hours
CREATE TABLE IF NOT EXISTS doctor_working_hours (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    doctor_id       BIGINT      NOT NULL,
    day_of_week     INT         NOT NULL COMMENT '1=Mon..7=Sun',
    start_time      TIME        NOT NULL,
    end_time        TIME        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_work_doctor FOREIGN KEY (doctor_id) REFERENCES doctors (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 7. Appointments
CREATE TABLE IF NOT EXISTS appointments (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    patient_id          BIGINT          NOT NULL,
    doctor_id           BIGINT          NOT NULL,
    department_id       BIGINT,
    scheduled_at        DATETIME(6)     NOT NULL,
    end_time            DATETIME(6),
    duration_mins       INT             NOT NULL DEFAULT 30,
    status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    reason              TEXT,
    notes               TEXT,
    cancelled_at        DATETIME(6)     NULL,
    cancelled_by        BIGINT          NULL,
    cancellation_reason TEXT,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    -- Generated Virtual Slot Key for conflict prevention
    slot_key            VARCHAR(100)    GENERATED ALWAYS AS (
        IF(status NOT IN ('CANCELLED', 'NO_SHOW'), 
           CONCAT(doctor_id, ':', scheduled_at), 
           NULL)
    ) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE INDEX idx_appt_slot_key (slot_key),
    CONSTRAINT fk_appt_patient    FOREIGN KEY (patient_id)    REFERENCES users       (id),
    CONSTRAINT fk_appt_doctor     FOREIGN KEY (doctor_id)     REFERENCES doctors     (id),
    CONSTRAINT fk_appt_dept       FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_appt_cancel_user FOREIGN KEY (cancelled_by) REFERENCES users       (id),
    INDEX idx_appt_patient     (patient_id),
    INDEX idx_appt_doctor      (doctor_id),
    INDEX idx_appt_status      (status),
    INDEX idx_appt_scheduled   (scheduled_at)
) ENGINE=InnoDB;

-- 8. Consultation Notes
CREATE TABLE IF NOT EXISTS consultation_notes (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    appointment_id  BIGINT      NOT NULL UNIQUE,
    doctor_id       BIGINT,
    diagnosis       MEDIUMTEXT,        -- PHI encrypted at app layer
    treatment_plan  MEDIUMTEXT,        -- PHI encrypted at app layer
    prescriptions   TEXT,
    phi_version     VARCHAR(10) NOT NULL DEFAULT 'v1',
    follow_up_date  DATE,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_cn_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id) ON DELETE CASCADE,
    CONSTRAINT fk_cn_doctor      FOREIGN KEY (doctor_id)      REFERENCES doctors      (id)
) ENGINE=InnoDB;

-- 9. Patient Profiles (Legacy/Detailed Metadata)
CREATE TABLE IF NOT EXISTS patient_profiles (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL UNIQUE,
    date_of_birth_enc   TEXT,           -- AES-256-GCM encrypted PHI
    ssn_enc             TEXT,           -- AES-256-GCM encrypted PHI
    blood_group         VARCHAR(10),
    allergies_enc       TEXT,           -- AES-256-GCM encrypted PHI
    medical_history_enc TEXT,           -- AES-256-GCM encrypted PHI
    emergency_contact   VARCHAR(255),
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pp_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 10. Processed Events (Idempotency for Consumers)
CREATE TABLE IF NOT EXISTS processed_events (
    event_id        VARCHAR(36)  PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 11. System Config
CREATE TABLE IF NOT EXISTS system_configs (
    config_key      VARCHAR(100) PRIMARY KEY,
    config_value    TEXT,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      BIGINT,
    CONSTRAINT fk_cfg_user FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE=InnoDB;
