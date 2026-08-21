-- V11: extend print_jobs for free-text wristband support.
--
-- Adds the free_text column. Existing rows (CREW/PERMIT) get NULL, same pattern as V6's
-- permit_label column.

ALTER TABLE print_jobs
    ADD COLUMN IF NOT EXISTS free_text VARCHAR(2000);
