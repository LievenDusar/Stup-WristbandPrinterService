CREATE TABLE print_jobs (
    job_id           UUID PRIMARY KEY,
    status           VARCHAR(255),
    event_name       VARCHAR(255),
    first_name       VARCHAR(255),
    last_name        VARCHAR(255),
    association_name VARCHAR(255),
    barcode_value    VARCHAR(255),
    submitted_at     TIMESTAMP(6) WITH TIME ZONE,
    completed_at     TIMESTAMP(6) WITH TIME ZONE,
    error            VARCHAR(2000)
);
