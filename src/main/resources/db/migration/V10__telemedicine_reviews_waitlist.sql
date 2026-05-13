-- V10: Telemedicine sessions, chat messages, doctor reviews, and waitlist

-- ─── Telemedicine Sessions ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS telemedicine_sessions (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    appointment_id      BIGINT          NOT NULL UNIQUE,
    status              VARCHAR(30)     NOT NULL DEFAULT 'SCHEDULED',
    room_id             VARCHAR(255)    NULL COMMENT 'Video provider room identifier',
    join_url_patient    VARCHAR(1024)   NULL,
    join_url_doctor     VARCHAR(1024)   NULL,
    started_at          DATETIME(6)     NULL,
    ended_at            DATETIME(6)     NULL,
    duration_seconds    INT             NULL,
    call_note_draft     MEDIUMTEXT      NULL COMMENT 'AI-assisted or auto summary; doctor must review',
    ai_assisted         BOOLEAN         NOT NULL DEFAULT FALSE,
    doctor_reviewed     BOOLEAN         NOT NULL DEFAULT FALSE,
    patient_consent     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ts_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    INDEX idx_ts_status (status)
) ENGINE=InnoDB;

-- ─── Chat Messages ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    session_id      BIGINT          NOT NULL,
    sender_id       BIGINT          NOT NULL,
    sender_role     VARCHAR(30)     NOT NULL,
    message         TEXT            NOT NULL,
    sent_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    is_system       BOOLEAN         NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_cm_session FOREIGN KEY (session_id) REFERENCES telemedicine_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_cm_sender  FOREIGN KEY (sender_id)  REFERENCES users                (id),
    INDEX idx_cm_session_time (session_id, sent_at)
) ENGINE=InnoDB;

-- ─── Doctor Reviews ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS doctor_reviews (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    appointment_id  BIGINT          NOT NULL UNIQUE,
    patient_id      BIGINT          NOT NULL,
    doctor_id       BIGINT          NOT NULL,
    rating          TINYINT         NOT NULL COMMENT '1-5',
    comment         TEXT,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING_MODERATION',
    moderated_by    BIGINT          NULL,
    moderated_at    DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_rev_appointment FOREIGN KEY (appointment_id) REFERENCES appointments  (id),
    CONSTRAINT fk_rev_patient     FOREIGN KEY (patient_id)     REFERENCES users          (id),
    CONSTRAINT fk_rev_doctor      FOREIGN KEY (doctor_id)      REFERENCES doctors        (id),
    CONSTRAINT fk_rev_moderator   FOREIGN KEY (moderated_by)   REFERENCES users          (id),
    INDEX idx_rev_doctor_status (doctor_id, status),
    INDEX idx_rev_patient       (patient_id),
    CONSTRAINT chk_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB;

-- ─── Waitlist ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS waitlist_entries (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    patient_id      BIGINT          NOT NULL,
    doctor_id       BIGINT          NULL COMMENT 'Specific doctor or NULL for any in specialty',
    department_id   BIGINT          NULL,
    specialization  VARCHAR(150)    NULL,
    preferred_date  DATE            NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'WAITING',
    promoted_at     DATETIME(6)     NULL,
    promoted_appointment_id BIGINT  NULL,
    expires_at      DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_wl_patient   FOREIGN KEY (patient_id)  REFERENCES users       (id),
    CONSTRAINT fk_wl_doctor    FOREIGN KEY (doctor_id)   REFERENCES doctors     (id),
    CONSTRAINT fk_wl_dept      FOREIGN KEY (department_id) REFERENCES departments (id),
    INDEX idx_wl_status_created (status, created_at),
    INDEX idx_wl_doctor_status  (doctor_id, status),
    INDEX idx_wl_patient        (patient_id)
) ENGINE=InnoDB;
