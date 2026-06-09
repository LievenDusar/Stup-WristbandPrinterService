-- V6: extend print_jobs for permit wristband support.
--
-- Adds a wristband_type discriminator plus permit-specific fields.
-- Existing CREW rows get the default 'CREW' value automatically.
-- first_name / last_name / barcode_value were already nullable in the schema
-- (no NOT NULL constraint in V1), so no column alteration is required for them.

ALTER TABLE print_jobs
    ADD COLUMN IF NOT EXISTS wristband_type   VARCHAR(20)  NOT NULL DEFAULT 'CREW',
    ADD COLUMN IF NOT EXISTS permit_label     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS icon_name        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS stock_color_code INTEGER,
    ADD COLUMN IF NOT EXISTS code_value       VARCHAR(512),
    ADD COLUMN IF NOT EXISTS code_symbology   VARCHAR(20);
