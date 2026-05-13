-- V16: Twilio Video call lifecycle and chat message compatibility

-- Extend telemedicine_sessions with Twilio Video call metadata.
ALTER TABLE telemedicine_sessions
    ADD COLUMN patient_id BIGINT NULL,
    ADD COLUMN doctor_id BIGINT NULL,
    ADD COLUMN chat_conversation_id BIGINT NULL,
    ADD COLUMN twilio_room_sid VARCHAR(64) NULL,
    ADD COLUMN twilio_room_name VARCHAR(255) NULL,
    ADD COLUMN started_by_user_id BIGINT NULL,
    ADD COLUMN accepted_at DATETIME(6) NULL,
    ADD COLUMN end_reason VARCHAR(255) NULL;

CREATE INDEX idx_ts_appointment_status ON telemedicine_sessions (appointment_id, status);
CREATE INDEX idx_ts_chat_conversation ON telemedicine_sessions (chat_conversation_id);

-- V10 created chat_messages for legacy telemedicine chat. V15 introduced the
-- Twilio Conversations mirror with the same table name and CREATE TABLE IF NOT
-- EXISTS, so existing MySQL schemas need these columns relaxed/added.
ALTER TABLE chat_messages
    MODIFY COLUMN session_id BIGINT NULL,
    MODIFY COLUMN sender_id BIGINT NULL,
    MODIFY COLUMN message TEXT NULL,
    ADD COLUMN conversation_id BIGINT NULL,
    ADD COLUMN twilio_message_sid VARCHAR(64) NULL,
    ADD COLUMN body TEXT NULL,
    ADD COLUMN ai_generated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN safety_label VARCHAR(30) NULL,
    ADD COLUMN metadata_json TEXT NULL,
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);

UPDATE chat_messages
SET body = COALESCE(body, message)
WHERE body IS NULL;

CREATE UNIQUE INDEX uk_cm_twilio_sid ON chat_messages (twilio_message_sid);
CREATE INDEX idx_cm_conversation ON chat_messages (conversation_id);
CREATE INDEX idx_cm_sender ON chat_messages (sender_id);
CREATE INDEX idx_cm_safety ON chat_messages (safety_label);

ALTER TABLE chat_messages
    ADD CONSTRAINT fk_cm_conversation
        FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS call_participants (
    id                          BIGINT      NOT NULL AUTO_INCREMENT,
    telemedicine_session_id     BIGINT      NOT NULL,
    user_id                     BIGINT      NOT NULL,
    role                        VARCHAR(20) NOT NULL,
    joined_at                   DATETIME(6) NULL,
    left_at                     DATETIME(6) NULL,
    camera_enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    microphone_enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    connection_status           VARCHAR(30) NOT NULL DEFAULT 'INVITED',
    created_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_call_participant_session_user (telemedicine_session_id, user_id),
    INDEX idx_cp_session (telemedicine_session_id),
    INDEX idx_cp_user (user_id),
    CONSTRAINT fk_cp_session FOREIGN KEY (telemedicine_session_id)
        REFERENCES telemedicine_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
