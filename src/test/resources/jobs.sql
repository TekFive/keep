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
    trigger_source INT NOT NULL DEFAULT 1,
    trigger_user_id TEXT,
    lock_key VARCHAR(255),
    max_concurrent_jobs INT,
    concurrency_key VARCHAR(255),
    timeout_seconds INT,
    max_runtime_seconds INT,
    subject_type TEXT,
    subject_id TEXT,
    result_summary TEXT,
    application_version TEXT,
    run_id UUID NOT NULL DEFAULT gen_random_uuid(),
    timeout_reason INT,
    tenant_id TEXT,
    processed_count BIGINT,
    failed_count BIGINT,
    total_count BIGINT,
    execution_token UUID,
    retry_at BIGINT
);

CREATE UNIQUE INDEX IF NOT EXISTS job_records_running_type_lock_key_uq
    ON job_records (type, lock_key)
    WHERE state = 2 AND lock_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS job_records_running_concurrency_scope_idx
    ON job_records (type, concurrency_key)
    WHERE state = 2 AND max_concurrent_jobs IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS job_records_scheduled_chain_type_uq
    ON job_records (type)
    WHERE trigger_source = 3 AND (state IN (1, 2) OR retry_at IS NOT NULL);

CREATE INDEX IF NOT EXISTS job_records_pending_priority_created_idx
    ON job_records (priority DESC, created_at)
    WHERE state = 1;

CREATE INDEX IF NOT EXISTS job_records_type_state_idx
    ON job_records (type, state);

CREATE INDEX IF NOT EXISTS job_records_run_id_idx
    ON job_records (run_id);

CREATE INDEX IF NOT EXISTS job_records_retry_pending_idx
    ON job_records (type) WHERE retry_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS job_heartbeats (
    job_record_id BIGINT PRIMARY KEY CONSTRAINT job_heartbeats_job_record_id_fk
        REFERENCES job_records(id) ON DELETE CASCADE ON UPDATE RESTRICT,
    execution_token UUID NOT NULL,
    last_checkin_at BIGINT
);

CREATE TABLE IF NOT EXISTS job_record_logs (
    id BIGINT DEFAULT nextval('globalid') PRIMARY KEY,
    job_record_id BIGINT NOT NULL CONSTRAINT job_record_logs_job_record_id_fk REFERENCES job_records(id) ON DELETE NO ACTION,
    level INT NOT NULL,
    message TEXT NOT NULL,
    added_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS job_record_logs_job_record_id_ix
    ON job_record_logs (job_record_id);

-- State history for inserts and state changes after installation.
CREATE TABLE IF NOT EXISTS job_state_transitions (
    id BIGINT DEFAULT nextval('globalid') PRIMARY KEY,
    job_record_id BIGINT NOT NULL CONSTRAINT job_state_transitions_job_record_id_fk
        REFERENCES job_records(id) ON DELETE CASCADE ON UPDATE RESTRICT,
    from_state INT,
    to_state INT NOT NULL,
    transitioned_at BIGINT NOT NULL,
    system_identifier VARCHAR(255),
    application_version TEXT,
    result_summary TEXT,
    timeout_reason INT,
    processed_count BIGINT,
    failed_count BIGINT,
    total_count BIGINT
);

CREATE INDEX IF NOT EXISTS job_state_transitions_job_record_id_ix
    ON job_state_transitions (job_record_id);

CREATE OR REPLACE FUNCTION record_job_state_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        EXECUTE format(
            'INSERT INTO %I.job_state_transitions (id, job_record_id, from_state, to_state, transitioned_at, system_identifier, application_version, result_summary, timeout_reason, processed_count, failed_count, total_count) VALUES (nextval(%L::regclass), $1, $2, $3, floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint, $4, $5, $6, $7, $8, $9, $10)',
            TG_TABLE_SCHEMA, format('%I.globalid', TG_TABLE_SCHEMA)
        ) USING NEW.id, NULL::integer, NEW.state, NEW.system_identifier, NEW.application_version, NEW.result_summary, NEW.timeout_reason, NEW.processed_count, NEW.failed_count, NEW.total_count;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.state IS DISTINCT FROM NEW.state THEN
            EXECUTE format(
                'INSERT INTO %I.job_state_transitions (id, job_record_id, from_state, to_state, transitioned_at, system_identifier, application_version, result_summary, timeout_reason, processed_count, failed_count, total_count) VALUES (nextval(%L::regclass), $1, $2, $3, floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint, $4, $5, $6, $7, $8, $9, $10)',
                TG_TABLE_SCHEMA, format('%I.globalid', TG_TABLE_SCHEMA)
            ) USING NEW.id, OLD.state, NEW.state, COALESCE(NEW.system_identifier, OLD.system_identifier), NEW.application_version, NEW.result_summary, NEW.timeout_reason, NEW.processed_count, NEW.failed_count, NEW.total_count;
        END IF;
        RETURN NEW;
    END IF;
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS job_records_record_state_transition ON job_records;
CREATE TRIGGER job_records_record_state_transition AFTER INSERT OR UPDATE ON job_records
    FOR EACH ROW EXECUTE FUNCTION record_job_state_transition();
