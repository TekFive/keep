# Background jobs

This package runs background work that is stored in the database. An application
describes each kind of work once, saves jobs to run, and starts a coordinator.
The coordinator finds jobs that are ready, runs them on worker threads, and
records how each one ended. Work survives restarts because every job lives in
the database.

## Subpackages

| Package | Responsibility |
| --- | --- |
| `ack` | Job settings read from ACK configuration, such as `JOB_POLL_SECONDS`. |
| [`db`](db/_package.md) | The job tables, the queries that capture and end jobs, and database retry handling. |
| [`dispatch`](dispatch/_package.md) | Worker threads that run one captured job at a time. |
| `schedule` | Specs for jobs that repeat on a fixed interval or a cron expression. |

## Code

| Type (File) | Responsibility |
| --- | --- |
| `Job` (Job.kt), `DatabaseTransactionJob` (DatabaseTransactionJob.kt) | The work itself. The transaction version runs the whole job in one database transaction. |
| `JobSpec` (JobSpec.kt) | Describes a kind of job: its type, retries, limits, timeouts, and runtime estimate settings. |
| `JobRegistry` (JobRegistry.kt) | The specs a coordinator is allowed to run. |
| `JobCoordinator` (JobCoordinator.kt) | The polling loop. It schedules repeating jobs, recovers retries, sweeps timeouts, and queues ready jobs for workers. |
| `JobConfiguration` (JobConfiguration.kt) | Worker count, poll interval, timeout defaults, and the number of past runs used for runtime estimates. |
| `JobContext` (JobContext.kt) | What a running job can see and do: its details, estimate, logger, progress, and check-ins. |
| `JobResult` (JobResult.kt) | How a job ends: completed, waiting for input, or failed. |
| `JobState` (JobState.kt) | The states a job record moves through. |
| `JobTrigger`, `JobSubject` (JobTrigger.kt, JobSubject.kt) | Who or what started a job, and what it is about, for reporting. |
| `JobTimeoutMonitor` (JobTimeoutMonitor.kt) | Ends running jobs that stopped checking in or ran too long. |
| `JobLogger` (JobLogger.kt) | Writes job log lines to SLF4J and, above a set level, to the database. |

## Architecture

### Runtime estimates

A spec with `estimateRuntime` on gets an estimate, in seconds, of how long a job
will run. The estimate is the average runtime of recent completed jobs of the
same type. It is made in one of two places:

- `JobRecordsTable.insertJob` makes it when the caller passes a positive
  `maxEstimatedRuntimeRecords`. A child job reuses its parent's estimate.
- A job that still has no estimate when a worker captures it gets one then. This
  covers scheduled jobs and records created without `insertJob`. The number of
  past runs comes from `JobConfiguration.maxEstimatedRuntimeRecords`.

An estimate that already exists is never replaced. The estimate stays empty
until the type has a completed run.
