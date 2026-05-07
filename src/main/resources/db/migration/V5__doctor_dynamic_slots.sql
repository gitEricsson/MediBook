-- Phase 2 Refinement: Dynamic slot duration for doctors
ALTER TABLE doctors
ADD COLUMN slot_duration_mins INT NOT NULL DEFAULT 30;

-- Optional: add index for faster searches if needed, though this is a detail field
