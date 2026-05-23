-- V44: Per-slot, doctor-driven ad-hoc unavailability.
-- Distinct from doctor_leaves (which is a full day or multi-day block):
-- a slot_block carves a specific time range out of a single calendar day with
-- a free-text reason (e.g. "operating on patient X").
-- The availability grid renders any overlapping slot as BLOCKED so patients
-- can't book it. Admins can audit these via /api/v1/admin/slot-blocks.

CREATE TABLE IF NOT EXISTS doctor_slot_blocks (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    doctor_id    BIGINT NOT NULL,
    block_date   DATE   NOT NULL,
    start_time   TIME   NOT NULL,
    end_time     TIME   NOT NULL,
    reason       VARCHAR(500) NOT NULL,
    created_by   BIGINT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_slot_block_doctor  FOREIGN KEY (doctor_id)  REFERENCES doctors(id) ON DELETE CASCADE,
    CONSTRAINT fk_slot_block_creator FOREIGN KEY (created_by) REFERENCES users(id)   ON DELETE SET NULL,
    KEY idx_slot_block_doctor_date (doctor_id, block_date)
);
