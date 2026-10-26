-- Apply after job-reporting.sql, in a transaction with the job schema on the search path.
-- History starts at installation; earlier state transitions are not reconstructed.
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
