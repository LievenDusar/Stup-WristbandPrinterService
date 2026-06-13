-- Dynamic printer registry (docs/superpowers/specs/2026-06-13-dynamic-printer-registry-design.md).
-- Printers become first-class rows; print_jobs.printer_name is normalized into printers.display_name.
-- hidden / is_default columns are created now but only used in parts 2-3.

CREATE TABLE printers (
    id            VARCHAR(255) PRIMARY KEY,
    display_name  VARCHAR(255) NOT NULL,
    base_url      VARCHAR(512) NOT NULL DEFAULT '',
    online        BOOLEAN      NOT NULL DEFAULT FALSE,
    hidden        BOOLEAN      NOT NULL DEFAULT FALSE,
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    last_seen_at  TIMESTAMPTZ,
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- At most one default printer (partial unique index over the TRUE rows only).
CREATE UNIQUE INDEX printers_one_default ON printers (is_default) WHERE is_default;

-- Backfill from the printers referenced by historical jobs so the FK is valid and
-- their names still resolve. One row per printer_id; display_name falls back to the id.
INSERT INTO printers (id, display_name)
SELECT printer_id, COALESCE(MAX(printer_name), printer_id)
FROM print_jobs
WHERE printer_id IS NOT NULL
GROUP BY printer_id;

-- Jobs now reference a printer by id (FK). The duplicated printer_name column is
-- dropped later in V10 (a separate task), together with the matching entity change,
-- so each migration leaves the JPA entity model and the schema consistent.
ALTER TABLE print_jobs
    ADD CONSTRAINT fk_print_jobs_printer
    FOREIGN KEY (printer_id) REFERENCES printers (id);
