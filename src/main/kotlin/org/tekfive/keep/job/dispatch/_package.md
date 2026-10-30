# Job dispatch

This package holds the worker threads that run background jobs. Each worker
takes a ready job from the coordinator's queue, claims it in the database, runs
it, and records how it ended. A claimed job is never left marked as running
when the worker moves on.

## Code

| Type (File) | Responsibility |
| --- | --- |
| `Dispatcher` (Dispatcher.kt) | A worker thread. It captures a job, adds a runtime estimate if the job has none, runs it, and finalizes it. |
| `DispatchContext` (DispatchContext.kt) | The `JobContext` given to a running job. Its writes are tied to the job's execution token. |
| `JobEndedException` and subclasses (DispatchJobExceptions.kt) | Signals that a job ended outside the worker: cancelled, ended, deleted, or claimed by another execution. |

## Architecture

### Running one job

1. `Dispatcher.run` takes a candidate and calls `JobRecordsTable.tryCaptureRunLock`.
2. `dispatch` gives the job a runtime estimate when it has none, using
   `JobConfiguration.maxEstimatedRuntimeRecords`. A failure here is logged and
   the job runs without an estimate.
3. It builds a `DispatchContext` and runs the job.
4. `finalizeCaptured` records the outcome. An interrupted job goes back to
   pending. A failed job may request a retry, which inherits the estimate.
