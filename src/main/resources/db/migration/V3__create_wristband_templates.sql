CREATE TABLE wristband_template (
    id                    UUID PRIMARY KEY,
    slug                  VARCHAR(255) NOT NULL UNIQUE,
    name                  VARCHAR(255) NOT NULL,
    project_type          VARCHAR(255),
    default_preview_color VARCHAR(255) NOT NULL DEFAULT 'white',
    definition            JSONB NOT NULL,
    generated_zpl         TEXT,
    created_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    deleted               BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_wristband_template_project_type
    ON wristband_template (project_type) WHERE deleted = FALSE;
