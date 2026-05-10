-- Department API fields added after the initial schema.
-- Kept separate because V1 predates Department.code and optimistic locking.

ALTER TABLE departments
    ADD COLUMN code VARCHAR(50) NULL UNIQUE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
