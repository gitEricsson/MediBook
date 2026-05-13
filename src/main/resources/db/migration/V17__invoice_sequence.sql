-- V17: Invoice sequence table for persistent numbering
CREATE TABLE IF NOT EXISTS sequences (
    name VARCHAR(50) PRIMARY KEY,
    current_value BIGINT NOT NULL DEFAULT 0,
    last_date DATE
) ENGINE=InnoDB;

-- Initialize the invoice sequence
INSERT INTO sequences (name, current_value, last_date) VALUES ('invoice', 0, CURRENT_DATE);
