-- Apply once in a transaction after the reporting/state-history migrations.
-- Stop old workers before upgrading; old code does not enforce execution ownership.
ALTER TABLE job_records
    ADD COLUMN execution_token UUID,
    ADD COLUMN retry_at BIGINT;

CREATE TABLE IF NOT EXISTS job_heartbeats (
    job_record_id BIGINT PRIMARY KEY CONSTRAINT job_heartbeats_job_record_id_fk
        REFERENCES job_records(id) ON DELETE CASCADE ON UPDATE RESTRICT,
    execution_token UUID NOT NULL,
    last_checkin_at BIGINT
);

-- Preserve existing running records until reclaimed or timed out by the upgraded workers.
UPDATE job_records SET execution_token = gen_random_uuid() WHERE state = 2;
INSERT INTO job_heartbeats (job_record_id, execution_token, last_checkin_at)
SELECT id, execution_token, last_checkin_at FROM job_records WHERE state = 2;

DROP INDEX job_records_scheduled_chain_type_uq;
CREATE UNIQUE INDEX job_records_scheduled_chain_type_uq
    ON job_records (type) WHERE trigger_source = 3 AND (state IN (1, 2) OR retry_at IS NOT NULL);
CREATE INDEX job_records_retry_pending_idx ON job_records (type) WHERE retry_at IS NOT NULL;
