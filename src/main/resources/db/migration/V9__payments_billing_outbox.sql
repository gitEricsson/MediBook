-- V9: Payments, Invoices, Webhook Events, and Transactional Outbox

-- ─── Payments ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payments (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    appointment_id      BIGINT          NOT NULL,
    patient_id          BIGINT          NOT NULL,
    idempotency_key     VARCHAR(100)    NOT NULL UNIQUE,
    provider            VARCHAR(30)     NOT NULL COMMENT 'PAYSTACK|FLUTTERWAVE|STRIPE',
    provider_ref        VARCHAR(255)    NULL COMMENT 'Provider transaction reference',
    amount              DECIMAL(12,2)   NOT NULL,
    currency            VARCHAR(10)     NOT NULL DEFAULT 'NGN',
    status              VARCHAR(30)     NOT NULL DEFAULT 'INITIATED',
    failure_reason      TEXT            NULL,
    refund_amount       DECIMAL(12,2)   NULL,
    refunded_at         DATETIME(6)     NULL,
    refund_ref          VARCHAR(255)    NULL,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pay_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    CONSTRAINT fk_pay_patient     FOREIGN KEY (patient_id)     REFERENCES users       (id),
    INDEX idx_pay_appointment  (appointment_id),
    INDEX idx_pay_patient      (patient_id),
    INDEX idx_pay_status       (status),
    INDEX idx_pay_provider_ref (provider_ref),
    INDEX idx_pay_created_at   (created_at)
) ENGINE=InnoDB;

-- ─── Invoices ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS invoices (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    payment_id      BIGINT          NOT NULL UNIQUE,
    invoice_number  VARCHAR(50)     NOT NULL UNIQUE,
    patient_id      BIGINT          NOT NULL,
    doctor_id       BIGINT          NOT NULL,
    subtotal        DECIMAL(12,2)   NOT NULL,
    discount        DECIMAL(12,2)   NOT NULL DEFAULT 0.00,
    total           DECIMAL(12,2)   NOT NULL,
    currency        VARCHAR(10)     NOT NULL DEFAULT 'NGN',
    status          VARCHAR(20)     NOT NULL DEFAULT 'UNPAID',
    issued_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    paid_at         DATETIME(6)     NULL,
    due_date        DATE            NULL,
    notes           TEXT            NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_inv_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_inv_patient FOREIGN KEY (patient_id) REFERENCES users    (id),
    CONSTRAINT fk_inv_doctor  FOREIGN KEY (doctor_id)  REFERENCES doctors  (id),
    INDEX idx_inv_patient    (patient_id),
    INDEX idx_inv_status     (status),
    INDEX idx_inv_issued_at  (issued_at)
) ENGINE=InnoDB;

-- ─── Invoice Line Items ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS invoice_line_items (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    invoice_id  BIGINT          NOT NULL,
    description VARCHAR(255)    NOT NULL,
    quantity    INT             NOT NULL DEFAULT 1,
    unit_price  DECIMAL(12,2)   NOT NULL,
    subtotal    DECIMAL(12,2)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ili_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ─── Payment Webhook Events ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_webhook_events (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    provider        VARCHAR(30)     NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    payload         MEDIUMTEXT      NOT NULL,
    signature       VARCHAR(512)    NULL,
    processed       BOOLEAN         NOT NULL DEFAULT FALSE,
    processed_at    DATETIME(6)     NULL,
    failure_reason  TEXT            NULL,
    retry_count     INT             NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(255)    NOT NULL UNIQUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_webhook_processed  (processed),
    INDEX idx_webhook_provider   (provider),
    INDEX idx_webhook_created_at (created_at)
) ENGINE=InnoDB;

-- ─── Transactional Outbox ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    VARCHAR(100)    NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    payload         MEDIUMTEXT      NOT NULL,
    topic           VARCHAR(255)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    retry_count     INT             NOT NULL DEFAULT 0,
    last_error      TEXT            NULL,
    scheduled_after DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at    DATETIME(6)     NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_status         (status, scheduled_after),
    INDEX idx_outbox_aggregate      (aggregate_type, aggregate_id),
    INDEX idx_outbox_created_at     (created_at)
) ENGINE=InnoDB;
