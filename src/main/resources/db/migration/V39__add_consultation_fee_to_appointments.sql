-- V39: Store the computed consultation fee on each appointment.
-- NULL for historical rows (pre-pricing engine); non-null for all new bookings.

ALTER TABLE appointments
    ADD COLUMN consultation_fee DECIMAL(10, 2) NULL
        COMMENT 'Fee computed at booking time: dept base + type modifier + senior surcharge + medium surcharge';
