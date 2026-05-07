-- PERF-3: Full-text doctor search
-- Adds a denormalized search_vector column so MATCH AGAINST can replace leading-wildcard LIKE scans.

ALTER TABLE doctors ADD COLUMN search_vector TEXT;

-- Backfill existing rows using the joined user name + specialization
UPDATE doctors d
JOIN users u ON d.user_id = u.id
SET d.search_vector = CONCAT_WS(' ', u.first_name, u.last_name, d.specialization);

-- FULLTEXT index used by MATCH(search_vector) AGAINST(:q IN BOOLEAN MODE)
ALTER TABLE doctors ADD FULLTEXT INDEX ft_doctor_search (search_vector);

-- B-tree index for the exact-match specialization filter path
ALTER TABLE doctors ADD INDEX idx_doctors_specialization (specialization(100));

-- Composite index for doctor_working_hours — every availability query filters by doctor_id + day_of_week
ALTER TABLE doctor_working_hours ADD INDEX idx_dwh_doctor_day (doctor_id, day_of_week);
