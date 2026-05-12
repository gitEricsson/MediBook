-- ─── V15: AI-Assisted Chat (Phase 1) ────────────────────────────────────────
-- Adds tables for:
--   • Twilio Conversations mirror (chat_conversations, chat_messages)
--   • Doctor draft approval workflow (ai_draft_responses)
--   • Medical urgency detection (urgency_alerts)
--   • Patient AI consent (ai_consent_records)
--   • Immutable AI audit trail (ai_message_audit)

-- ── chat_conversations ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_conversations (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    twilio_conversation_sid VARCHAR(64)     NOT NULL,
    appointment_id          BIGINT          NOT NULL,
    patient_id              BIGINT          NOT NULL,
    doctor_id               BIGINT          NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    ai_enabled              BOOLEAN         NOT NULL DEFAULT FALSE,
    intake_completed        BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at              DATETIME(6)     NOT NULL,
    updated_at              DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_twilio_sid (twilio_conversation_sid),
    INDEX idx_cc_appointment    (appointment_id),
    INDEX idx_cc_patient        (patient_id),
    INDEX idx_cc_doctor         (doctor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── chat_messages ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_messages (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id     BIGINT      NOT NULL,
    twilio_message_sid  VARCHAR(64) NOT NULL,
    sender_id           BIGINT,
    sender_role         VARCHAR(20) NOT NULL,
    body                TEXT        NOT NULL,
    ai_generated        BOOLEAN     NOT NULL DEFAULT FALSE,
    safety_label        VARCHAR(30),
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cm_twilio_sid (twilio_message_sid),
    INDEX idx_cm_conversation   (conversation_id),
    INDEX idx_cm_sender         (sender_id),
    INDEX idx_cm_safety         (safety_label),
    CONSTRAINT fk_cm_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── ai_draft_responses ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai_draft_responses (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT          NOT NULL,
    appointment_id  BIGINT,
    doctor_id       BIGINT          NOT NULL,
    draft_body      TEXT            NOT NULL,
    edited_body     TEXT,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    prompt_version  VARCHAR(20)     NOT NULL,
    model_used      VARCHAR(60)     NOT NULL,
    approved_by     BIGINT,
    approved_at     DATETIME(6),
    created_at      DATETIME(6)     NOT NULL,
    updated_at      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_adr_conversation (conversation_id),
    INDEX idx_adr_doctor       (doctor_id),
    INDEX idx_adr_status       (status),
    CONSTRAINT fk_adr_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── urgency_alerts ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS urgency_alerts (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id     BIGINT      NOT NULL,
    message_id          BIGINT,
    patient_id          BIGINT      NOT NULL,
    doctor_id           BIGINT      NOT NULL,
    urgency_keywords    TEXT        NOT NULL,
    alert_message       TEXT        NOT NULL,
    escalated           BOOLEAN     NOT NULL DEFAULT TRUE,
    acknowledged_by     BIGINT,
    acknowledged_at     DATETIME(6),
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ua_conversation (conversation_id),
    INDEX idx_ua_patient      (patient_id),
    INDEX idx_ua_acknowledged (acknowledged_at),
    CONSTRAINT fk_ua_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── ai_consent_records ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai_consent_records (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    patient_id       BIGINT      NOT NULL,
    conversation_id  BIGINT      NOT NULL,
    consent_granted  BOOLEAN     NOT NULL,
    ip_address       VARCHAR(45),
    user_agent       VARCHAR(512),
    consent_text     TEXT        NOT NULL,
    consent_version  VARCHAR(10) NOT NULL,
    granted_at       DATETIME(6),
    revoked_at       DATETIME(6),
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_acr_patient_conversation (patient_id, conversation_id),
    INDEX idx_acr_patient      (patient_id),
    INDEX idx_acr_conversation (conversation_id),
    CONSTRAINT fk_acr_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── ai_message_audit ─────────────────────────────────────────────────────────
-- Append-only table. Never delete or update rows.
CREATE TABLE IF NOT EXISTS ai_message_audit (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    event_id              CHAR(36)    NOT NULL,
    conversation_id       BIGINT,
    appointment_id        BIGINT,
    actor_id              BIGINT      NOT NULL,
    actor_role            VARCHAR(20) NOT NULL,
    operation             VARCHAR(30) NOT NULL,
    model_used            VARCHAR(60) NOT NULL,
    prompt_version        VARCHAR(20) NOT NULL,
    input_token_count     INT,
    output_token_count    INT,
    safety_label          VARCHAR(30) NOT NULL,
    escalation_triggered  BOOLEAN     NOT NULL DEFAULT FALSE,
    latency_ms            BIGINT,
    created_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ama_event_id    (event_id),
    INDEX idx_ama_conversation    (conversation_id),
    INDEX idx_ama_actor           (actor_id),
    INDEX idx_ama_operation       (operation),
    INDEX idx_ama_created         (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── Kafka topics reference (comment only — topics created by Kafka admin) ──────
-- chat.events
-- ai.events
-- urgency.events
