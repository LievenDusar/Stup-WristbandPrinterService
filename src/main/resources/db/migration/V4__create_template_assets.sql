CREATE TABLE template_asset (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    png        BYTEA NOT NULL,
    width      INTEGER NOT NULL,
    height     INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
