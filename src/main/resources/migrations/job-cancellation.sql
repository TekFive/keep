-- Apply once in a transaction after job-execution-safety.sql.
-- Stop old workers before upgrading; they do not recognize CANCEL_REQUESTED (9).
ALTER TABLE job_records
    ADD COLUMN cancel_requested_by_user_id TEXT,
    ADD COLUMN cancel_requested_at BIGINT;
ALTER TABLE job_state_transitions
    ADD COLUMN cancel_requested_by_user_id TEXT,
    ADD COLUMN cancel_requested_at BIGINT;

DROP INDEX job_records_running_type_lock_key_uq;
CREATE UNIQUE INDEX job_records_running_type_lock_key_uq
    ON job_records (type, lock_key) WHERE state IN (2, 9) AND lock_key IS NOT NULL;
DROP INDEX job_records_running_concurrency_scope_idx;
CREATE INDEX job_records_running_concurrency_scope_idx
    ON job_records (type, concurrency_key) WHERE state IN (2, 9) AND max_concurrent_jobs IS NOT NULL;
DROP INDEX job_records_scheduled_chain_type_uq;
CREATE UNIQUE INDEX job_records_scheduled_chain_type_uq
    ON job_records (type) WHERE trigger_source = 3 AND (state IN (1, 2, 9) OR retry_at IS NOT NULL);

CREATE OR REPLACE FUNCTION record_job_state_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        EXECUTE format(
            'INSERT INTO %I.job_state_transitions (id, job_record_id, from_state, to_state, transitioned_at, system_identifier, application_version, result_summary, timeout_reason, processed_count, failed_count, total_count, cancel_requested_by_user_id, cancel_requested_at) VALUES (nextval(%L::regclass), $1, $2, $3, floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint, $4, $5, $6, $7, $8, $9, $10, $11, $12)',
            TG_TABLE_SCHEMA, format('%I.globalid', TG_TABLE_SCHEMA)
        ) USING NEW.id, NULL::integer, NEW.state, NEW.system_identifier, NEW.application_version, NEW.result_summary, NEW.timeout_reason, NEW.processed_count, NEW.failed_count, NEW.total_count, NEW.cancel_requested_by_user_id, NEW.cancel_requested_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.state IS DISTINCT FROM NEW.state THEN
            EXECUTE format(
                'INSERT INTO %I.job_state_transitions (id, job_record_id, from_state, to_state, transitioned_at, system_identifier, application_version, result_summary, timeout_reason, processed_count, failed_count, total_count, cancel_requested_by_user_id, cancel_requested_at) VALUES (nextval(%L::regclass), $1, $2, $3, floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint, $4, $5, $6, $7, $8, $9, $10, $11, $12)',
                TG_TABLE_SCHEMA, format('%I.globalid', TG_TABLE_SCHEMA)
            ) USING NEW.id, OLD.state, NEW.state, COALESCE(NEW.system_identifier, OLD.system_identifier), NEW.application_version, NEW.result_summary, NEW.timeout_reason, NEW.processed_count, NEW.failed_count, NEW.total_count, NEW.cancel_requested_by_user_id, NEW.cancel_requested_at;
        END IF;
        RETURN NEW;
    END IF;
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS job_records_record_state_transition ON job_records;
CREATE TRIGGER job_records_record_state_transition AFTER INSERT OR UPDATE ON job_records
    FOR EACH ROW EXECUTE FUNCTION record_job_state_transition();
