# Job storage

This package stores background jobs in PostgreSQL. It saves new jobs, lets one
worker at a time claim a job, records progress and check-ins, and records how
each job ended. It also keeps a history of state changes and cleans up old jobs.

## Code

| Type (File) | Responsibility |
| --- | --- |
| `JobRecordsTable` (JobRecordsTable.kt), `JobRecord` (JobRecord.kt) | One row per job attempt, and the queries that queue, capture, end, requeue, retry, and estimate jobs. |
| `JobHeartbeatsTable` (JobHeartbeatsTable.kt) | The latest check-in of each captured job, kept apart so a job can check in while its own transaction locks the job row. |
| `JobRecordLogsTable` (JobRecordLogsTable.kt), `JobRecordLog`, `JobRecordLogLevel` | Log lines a job saves to the database. |
| `JobStateTransitionsTable` (JobStateTransitionsTable.kt), `JobStateTransition` | State history, written by a database trigger. |
| `JobTriggerColumns`, `JobSubjectColumns` (JobReportingColumns.kt) | Column groups for a job's trigger and subject. |
| `JobRecordCleaner` (JobRecordCleaner.kt) | A scheduled job that deletes old ended jobs and their logs. |
| `DatabaseGatekeeper` (DatabaseGatekeeper.kt), `DatabaseRetryPolicy` (DatabaseRetryPolicy.kt) | Back off from the database after recoverable SQL errors. |

## Database Tables

| Table Name | Code | Description |
| --- | --- | --- |
| `job_records` | JobRecordsTable.kt | Each job attempt, its state, details, limits, estimate, and outcome. |
| `job_heartbeats` | JobHeartbeatsTable.kt | The latest check-in of each captured job. |
| `job_record_logs` | JobRecordLogsTable.kt | Saved job log lines. |
| `job_state_transitions` | JobStateTransitionsTable.kt | Each state change of each job. |

## Architecture

### Capturing a job

`tryCaptureRunLock` moves a pending job to running. It takes an advisory lock for
the job type, checks exclusive execution, concurrency limits, and lock keys, and
gives the job a new execution token. Later writes from the worker check that
token, so an older execution cannot change a job that was captured again.

### Runtime estimates

`getAverageRuntimeSecs` averages the runtime of the last N completed jobs of a
type, optionally filtered by a query over job details. The private
`estimateRuntimeSeconds` tries the spec's detail queries first when the job has
details. Without details, or when no query matches, it averages all completed
jobs of the type.

`insertJob` uses it when its `maxEstimatedRuntimeRecords` argument is positive,
unless the parent job already has an estimate. `tryEstimateRuntime` uses it
after capture for a job that has no estimate yet. That update only applies to
the captured execution and only while the estimate is still empty.
