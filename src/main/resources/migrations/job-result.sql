-- Apply once in a transaction after job-cancellation.sql.
ALTER TABLE job_records
    ADD COLUMN result JSONB;
