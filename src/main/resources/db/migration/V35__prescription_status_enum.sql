-- Hibernate's schema validator (ddl-auto=validate) maps @Enumerated(EnumType.STRING) on an
-- enum-typed field to a native MySQL ENUM column. V34 created `status` as VARCHAR which
-- mismatches the entity. Convert to ENUM so validation passes.

ALTER TABLE prescriptions
    MODIFY COLUMN status ENUM('ACTIVE','COMPLETED','CANCELLED') NOT NULL DEFAULT 'ACTIVE';
