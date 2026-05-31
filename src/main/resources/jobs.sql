CREATE SEQUENCE IF NOT EXISTS globalid;

CREATE TABLE IF NOT EXISTS job_records (
    id BIGINT DEFAULT nextval('globalid') PRIMARY KEY,
    "type" VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    priority INT NOT NULL,
    parent_job_id BIGINT,
    minimum_start_at BIGINT,
    attempt INT NOT NULL,
    estimated_run_time_seconds INT,
    state INT NOT NULL,
    job_details JSONB,
    system_identifier VARCHAR(255),
    started_at BIGINT,
    last_checkin_at BIGINT,
    ended_at BIGINT,
    failure_details TEXT,
    scheduled_job BOOLEAN NOT NULL DEFAULT FALSE,
    lock_key VARCHAR(255),
    max_concurrent_jobs INT,
    concurrency_key VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS job_records_running_type_lock_key_uq
    ON job_records (type, lock_key)
    WHERE state = 2 AND lock_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS job_records_running_concurrency_scope_idx
    ON job_records (type, concurrency_key)
    WHERE state = 2 AND max_concurrent_jobs IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS job_records_scheduled_chain_type_uq
    ON job_records (type)
    WHERE scheduled_job = TRUE AND state IN (1, 2);

CREATE TABLE IF NOT EXISTS job_record_logs (
    id BIGINT DEFAULT nextval('globalid') PRIMARY KEY,
    job_record_id BIGINT NOT NULL REFERENCES job_records(id),
    created_at BIGINT NOT NULL,
    level INT NOT NULL,
    message TEXT NOT NULL,
    added_at BIGINT NOT NULL
);
