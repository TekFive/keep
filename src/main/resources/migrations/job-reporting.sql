-- Apply once, in a transaction, to the schema containing the existing job tables.
-- Stop old workers before applying; their scheduled_job/user_id mappings are replaced.
ALTER TABLE job_records
    ADD COLUMN trigger_source INT NOT NULL DEFAULT 1,
    ADD COLUMN trigger_user_id TEXT,
    ADD COLUMN subject_type TEXT,
    ADD COLUMN subject_id TEXT,
    ADD COLUMN result_summary TEXT,
    ADD COLUMN application_version TEXT,
    ADD COLUMN run_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN timeout_reason INT,
    ADD COLUMN tenant_id TEXT,
    ADD COLUMN processed_count BIGINT,
    ADD COLUMN failed_count BIGINT,
    ADD COLUMN total_count BIGINT;

-- Preserve scheduled-chain membership even when a legacy row also has a user ID.
UPDATE job_records
SET trigger_source = CASE WHEN scheduled_job THEN 3 WHEN user_id IS NOT NULL THEN 2 ELSE 1 END,
    trigger_user_id = user_id,
    result_summary = failure_details;

-- Give surviving, linked retry attempts the same run ID. A missing parent starts a new group.
-- Strictly increasing attempt numbers make this traversal safe even with malformed parent cycles.
WITH RECURSIVE runs AS (
    SELECT job.id, job.run_id
    FROM job_records job
    LEFT JOIN job_records parent
        ON parent.id = job.parent_job_id AND parent.type = job.type AND job.attempt = parent.attempt + 1
    WHERE parent.id IS NULL
    UNION ALL
    SELECT child.id, runs.run_id
    FROM runs
    JOIN job_records parent ON parent.id = runs.id
    JOIN job_records child ON child.parent_job_id = parent.id
        AND child.type = parent.type AND child.attempt = parent.attempt + 1
)
UPDATE job_records job SET run_id = runs.run_id FROM runs WHERE job.id = runs.id;

DROP INDEX job_records_scheduled_chain_type_uq;
ALTER TABLE job_records DROP COLUMN scheduled_job, DROP COLUMN user_id;

CREATE UNIQUE INDEX job_records_scheduled_chain_type_uq
    ON job_records (type) WHERE trigger_source = 3 AND state IN (1, 2);
CREATE INDEX job_records_run_id_idx ON job_records (run_id);
