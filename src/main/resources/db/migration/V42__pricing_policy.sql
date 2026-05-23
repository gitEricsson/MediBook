-- V42: Hospital-wide pricing policy persisted in DB so admins can RUD it
-- via the admin console without redeploying. Singleton row (id=1).

CREATE TABLE IF NOT EXISTS pricing_policy (
    id                          BIGINT       NOT NULL PRIMARY KEY,
    emergency_multiplier_pct    INT          NOT NULL DEFAULT 150
        COMMENT 'Percent surcharge on base fee for EMERGENCY consultations',
    follow_up_discount_pct      INT          NOT NULL DEFAULT 20
        COMMENT 'Percent discount on base fee for FOLLOW_UP consultations',
    experience_premium_pct      INT          NOT NULL DEFAULT 20
        COMMENT 'Percent surcharge for senior consultants',
    experience_threshold_years  INT          NOT NULL DEFAULT 20
        COMMENT 'Years of experience that qualify as senior consultant (exclusive)',
    medium_surcharge_pct        INT          NOT NULL DEFAULT 10
        COMMENT 'Percent surcharge for AUDIO/VIDEO consultations',
    updated_at                  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by                  BIGINT       NULL
        COMMENT 'User ID of the admin who last edited the policy'
);

-- Seed the singleton with the existing yaml defaults so a fresh DB matches the
-- pre-V42 behaviour exactly. INSERT IGNORE keeps repeated runs idempotent.
INSERT IGNORE INTO pricing_policy
    (id, emergency_multiplier_pct, follow_up_discount_pct,
     experience_premium_pct, experience_threshold_years, medium_surcharge_pct)
VALUES
    (1, 150, 20, 20, 20, 10);
