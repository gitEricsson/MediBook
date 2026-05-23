-- V43: Capture a time-bounded access cutoff on patient_access_grants.
-- When a patient consents to a FOLLOW_UP booking, the auto-grant for that
-- doctor stores the day-of-booking here so the doctor only sees consultation
-- notes/records created on or before that date.
-- NULL = no cutoff (full historical access — the legacy approval flow).

ALTER TABLE patient_access_grants
    ADD COLUMN access_up_to_date DATE NULL
        COMMENT 'Inclusive upper bound on patient records this doctor may view';
