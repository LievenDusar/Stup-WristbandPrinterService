-- Number of physical copies a job prints (Zebra ^PQ). Existing rows default to 1.
ALTER TABLE print_jobs ADD COLUMN copies integer NOT NULL DEFAULT 1;
