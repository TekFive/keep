# Background jobs

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

The job subsystem stores work in PostgreSQL and dispatches registered `JobSpec` implementations through `JobCoordinator`. It records attempts, states, start/end timestamps, failure details, and job logs.

On this page:

- [Install the job tables](#install-the-job-tables)
- [Define and launch a job](#define-and-launch-a-job)
- [Triggers and reporting](#triggers-and-reporting)
- [Schedule work and limit overlap](#schedule-work-and-limit-overlap)
- [Job state history](#job-state-history)
- [Canceling jobs](#canceling-jobs)
- [Resilience](#resilience)
- [Job record retention](#job-record-retention)
- [Upgrading existing job tables](#upgrading-existing-job-tables)
- [Next steps](#next-steps)

## Install the job tables

Initialize `DbConnection` before starting workers. Include all four job tables in
the application schema so attempts, independent heartbeats, logs, and history are
available together:

```kotlin
import org.tekfive.keep.job.db.JobHeartbeatsTable
import org.tekfive.keep.job.db.JobRecordLogsTable
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.job.db.JobStateTransitionsTable
import org.tekfive.keep.schema.AppSchema

object WorkerSchema : AppSchema() {
    override val tables = listOf(
        JobRecordsTable,
        JobHeartbeatsTable,
        JobRecordLogsTable,
        JobStateTransitionsTable,
    )
}
```

Use `db { WorkerSchema.create() }` for a fresh development database, generated
[installation SQL](schema.md) for a declared schema, or the bundled
[jobs.sql](../src/main/resources/jobs.sql). For an existing installation, use the
[upgrade sequence](#upgrading-existing-job-tables) below.

## Define and launch a job

The following examples use application functions such as `sendReminder` and
variables such as `patientId`; provide those in your application. Public job APIs
are in `org.tekfive.keep.job`, job tables in `org.tekfive.keep.job.db`, and the
`toJsonObject` extension in `org.tekfive.jfk`.

Every `JobSpec` implementation must provide a `jobDescription` describing its work.

```kotlin
class SendReminderJob : Job {
    override fun execute(context: JobContext): JobResult {
        context.log.info("sending reminder for job ${context.jobId}")
        sendReminder(context.details)
        return JobCompleted()
    }

    companion object : JobSpec {
        override val jobTypeIdentifier = "send-reminder"
        override val jobDescription = "Sends a patient reminder."
        override val maxRetriesOnFailure = 3
        override val minSecondsBetweenRetries = 300
        override fun createJob(): Job = SendReminderJob()
    }
}
```

Jobs are inserted into `JobRecordsTable`, then a coordinator polls for runnable work and dispatches it.

```kotlin
val registry = JobRegistry().apply {
    register(SendReminderJob)
}

val coordinator = JobCoordinator(
    systemIdentifier = "worker-1",
    registry = registry,
    configuration = DefaultJobConfiguration(),
)

coordinator.start()

JobRecordsTable.insertJob(
    spec = SendReminderJob,
    details = mapOf("patientId" to patientId).toJsonObject(),
)
```

Job records support priority ordering, minimum start times, retry policies, lock keys, concurrency limits, scheduled jobs, timeout detection, and average-runtime estimation using JSON path filters over previous job details.

Calling `coordinator.wakeUp()` after inserting a job ends the current poll wait early, so the new
job is dispatched without waiting for `pollSeconds` to elapse.

## Triggers and reporting

For work initiated by a user, pass a trigger and optional reporting context:

```kotlin
JobRecordsTable.insertJob(
    spec = SendReminderJob,
    details = mapOf("patientId" to patientId).toJsonObject(),
    trigger = JobTrigger.user(currentUser.id),
    subject = JobSubject(subjectType = "patient", subjectId = patientId.toString()),
    tenantId = tenantId,
)
```

`JobRecord.trigger` replaces `scheduledJob` and `userId`. A `JobTrigger` contains a `source`
(`SYSTEM`, `USER`, `SCHEDULE`, or `EVENT`) and an optional `userId`. Use `JobTrigger.system()`,
`user(id)`, `scheduled()`, or `event()`; user triggers require an ID. Scheduled and event triggers
may also retain a user ID. Automatic scheduled runs use `SCHEDULE`, and ordinary inserts default
to `SYSTEM` (or `SCHEDULE` for a scheduled spec). An explicit user trigger on a scheduled spec
records a manual run. Scheduled-chain uniqueness and schedule history use the trigger source.

`subject: JobSubject?` groups `subjectType` and `subjectId`; `tenantId: String?` identifies the
organization. Trigger and subject use ordinary SQL columns (`trigger_source`, `trigger_user_id`,
`subject_type`, `subject_id`) so reports can filter them directly, including through
`JobRecordsTable.trigger.source` and `JobRecordsTable.subject.subjectId`.

Every independent job gets a UUID `runId`. Retries and `launchCopy` retain the run ID, trigger,
subject, and tenant. `insertJob(parentJobContext = context)` inherits these values unless supplied
explicitly. Pass `JobTrigger.system()` to clear inherited user attribution, explicit `null` to
clear subject or tenant, or a new UUID to start another logical run. Each automatic scheduled run
gets a new run ID. The same values are available through `JobContext` during execution.

`applicationVersion` records the executing application's version when a worker captures an attempt:

```kotlin
val configuration = object : DefaultJobConfiguration() {
    override val applicationVersion = "my-app-2.4.0"
}
```

Pass this configuration to `JobCoordinator`, or set `JOB_APPLICATION_VERSION` when using
`AckJobConfiguration`. The version is nullable when unconfigured. A retry or recaptured job records
the current worker's version; it does not inherit the previous attempt's version.

`processedCount`, `failedCount`, and `totalCount` are nullable, nonnegative `Long` values per attempt.
Processed means items attempted, including failures; null means unknown and zero means none.
Use absolute counts when reporting progress, or supply final counts in a result:

```kotlin
context.updateProgress(processedCount = 25, failedCount = 2, totalCount = 100)
return JobCompleted("Imported 98 records; 2 failed", processedCount = 100, failedCount = 2, totalCount = 100)
```

`updateProgress` retains omitted counts and clears explicitly null counts. Its writes participate in
the caller's database transaction and only update a running attempt. `JobCompleted`, `JobWaiting`,
and `JobFailed` accept final counts; omitted/null final counts preserve the last saved progress.
Their existing info/error message becomes `resultSummary`. Summary and final counts commit with the
terminal state. Retries, copies, and recaptures reset outcome fields and counts. Timeouts preserve
saved progress and store `timeoutReason` (`HEARTBEAT` or `MAX_RUNTIME`) along with the timeout summary.

Existing installations must apply [the reporting migration](../src/main/resources/migrations/job-reporting.sql)
once in a transaction, with the job schema on the search path, before deploying the updated code.
Stop old workers first because the migration replaces their `scheduled_job` and `user_id` columns.
It preserves initiating users and scheduled-chain membership and groups surviving linked retry
attempts under one run ID. Missing parents and copies without an identifiable retry link cannot
recover their original run grouping. Other new historical values remain null, except failure
summaries copied from `failure_details`. A generated schema diff alone cannot perform this backfill.
Fresh installations can use `jobs.sql` or the schema generators. Rebuild consuming applications
and update calls that previously supplied `userId` or `scheduledJob`.

## Schedule work and limit overlap

Implement `FixedIntervalJobSpec` for a delay measured from the previous scheduled
attempt's end, or `CronJobSpec` for cron-based timing. Register the spec in the same
registry before starting the coordinator:

```kotlin
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec

class RefreshJob : Job {
    override fun execute(context: JobContext): JobResult {
        context.checkIn()
        // Refresh application data here; check in periodically during longer work.
        return JobCompleted("Refresh completed")
    }

    companion object : FixedIntervalJobSpec {
        override val jobTypeIdentifier = "refresh"
        override val jobDescription = "Refreshes application data every hour."
        override val intervalSeconds = 3_600L
        override val exclusiveExecution = true
        override fun createJob(): Job = RefreshJob()
    }
}
```

`runImmediatelyOnFirstSchedule` defaults to `false`; override it when the first
scheduled run should happen immediately. `exclusiveExecution = true` permits one
active execution of the type. `maxConcurrentJobs` allows a larger limit, optionally
restricted by a `concurrencyKey` on the record. A `lockKey` excludes another active
execution with the same type and lock key.

A job with a future `minimumStartAt` does not reserve a concurrency slot. A manual
job can run first when a worker is available; explicitly use `JobTrigger.user(userId)`
when inserting a manual occurrence of a scheduled spec. Ready jobs are selected by
priority, then creation time. `CANCEL_REQUESTED` executions keep their slot until they end.

## Job state history

Include `JobStateTransitionsTable` alongside `JobRecordsTable`, `JobHeartbeatsTable`, and `JobRecordLogsTable` in
`KeepSchema.tables`. Its PostgreSQL trigger records the initial state on insertion and every
subsequent state change, including direct SQL changes. Updates that leave the state unchanged
(such as check-ins or progress updates) do not add entries. The state update and history insert
share a transaction: rollbacks leave neither change committed, and a failed history insert
prevents the state change from committing.

Each `JobStateTransition` contains the job record ID, nullable `fromState`, `toState`, and
`transitionedAt` in epoch milliseconds, plus the worker, application version, result summary,
timeout reason, counts, and cancellation requester/time at that transition. Initial entries
have a null `fromState`.
Requeues retain the worker and counts from the interrupted execution in history even when a
later capture resets the job record's progress. Each retry has its own initial entry.

```kotlin
val attemptHistory = JobStateTransitionsTable.getForJob(jobId)
val runHistory = JobStateTransitionsTable.getForRun(runId)
```

Both return entries ordered by transition time and then history ID. Fresh `jobs.sql` installs
history automatically. Existing installations must apply
[the state-history migration](../src/main/resources/migrations/job-state-transitions.sql) after the
reporting migration, in a transaction with the job schema on the search path. Schema generators
also install the table and trigger when `JobStateTransitionsTable` is declared. History starts at
installation; previous transitions cannot be reconstructed. Deleting a job cascades to its
history, so longer-term reporting still requires a retention policy or archive.

## Canceling jobs

Request cancellation of one job attempt:

```kotlin
val requested = JobRecordsTable.requestCancellation(jobId, userId = "operator-42")
```

A pending job becomes `CANCELLED` immediately. A running job becomes
`CANCEL_REQUESTED` and keeps its concurrency slot and lock key until execution ends.
On its next database check-in, `context.checkIn()` throws a termination exception;
let that exception propagate so the dispatcher records `CANCELLED` and `endedAt`.
Check-ins still use the configured minimum check-in interval. Cancellation is
cooperative: it does not forcibly interrupt the worker or undo external effects.

If the job finishes before observing the request, its normal result wins:
`COMPLETED`, `FAILED`, or `WAITING`, including its usual failure retry policy.
Progress and details can still be saved while cancellation is requested. Timeouts
also remain active. Shutdown and restart recovery finish outstanding cancellation
requests instead of requeueing those executions.

`cancelRequestedByUserId` and `cancelRequestedAt` record the first requester and
request time, including in state history. Omit `userId` for a system request. These
fields remain available even if the job finishes normally; `state` tells whether
it was actually canceled. Copies and retries start with no cancellation request.
The method returns `false` for a missing, terminal, or already-requested job and
does not replace the first request's attribution. Canceling one scheduled occurrence
does not disable its recurring schedule.

Existing installations must apply [the cancellation migration](../src/main/resources/migrations/job-cancellation.sql)
after `job-execution-safety.sql`, once in a transaction with the job schema on the
search path. Stop old workers before upgrading: they do not recognize the new state.
Fresh `jobs.sql` installations and generated schemas include these changes.

## Resilience

The coordination thread guards every polling cycle: a scheduled spec that throws while computing
its next run, a job factory that fails, or an `Error` thrown by a job is logged and the cycle is
retried on the next poll. Dispatcher threads that exit are replaced automatically. A captured job is
always moved out of `RUNNING` before its dispatcher moves on: failures in job construction or
execution become `FAILED` records, and a job interrupted by `stop()` or a dispatcher scale-down is
returned to `PENDING` so it runs again, unless cancellation was requested, in which
case it becomes `CANCELLED`.

Each capture gets a new `executionToken`. Worker writes check this token, so an older execution
cannot change the state, details, progress, or heartbeat of a recaptured job. Business operations
outside KEEP still need their own idempotency guarantees. Copies start as `PENDING`, link their
`parentJobId` to the original, and keep the same logical `runId`.

Retry requests are saved in `retryAt` in the same transaction as the failure. The dispatcher queues
the retry immediately, and coordinators recover outstanding requests after errors or restarts.
Clearing the request and inserting the retry commit together, preventing duplicate retries.
Retries inherit the last committed details and existing runtime estimate; optional runtime
estimation cannot prevent retry creation. Pending retry requests reserve their scheduled chain
and are excluded from record cleanup.

All captures of a job type coordinate their concurrency checks. Keyed and unlimited jobs also
respect any running job's type-wide limit; separate keys can still run together when no global
limit prevents it.

`systemIdentifier` must be unique to one running process. On `start()` the coordinator returns
any `RUNNING` record that carries its own identifier and started before the coordinator did back to
`PENDING`, on the assumption that the previous process died without finishing it. Set
`reclaimOrphanedJobsOnStart` to `false` on the `JobConfiguration` if several processes share an
identifier.

Heartbeat check-ins are throttled to at most half the effective timeout, even
when a job overrides the global limit. A one-second timeout permits check-ins
every 500 milliseconds. Jobs still need to call `checkIn()` often enough.

Include `JobHeartbeatsTable` in `KeepSchema.tables` when using jobs. Check-ins commit on a separate
connection against this table, even while a business transaction holds the job record locked.
`job_records.last_checkin_at` is a best-effort mirror while running and is refreshed on completion;
`getRunningJobs()` reads the current heartbeat. Timeout sweeps skip locked job records and recheck
on a later poll, so an open business transaction can delay timeout handling.

Existing installations must apply [the execution-safety migration](../src/main/resources/migrations/job-execution-safety.sql)
after the reporting and state-history migrations, in a transaction with the job schema on the
search path and old workers stopped. It adds `execution_token`, `retry_at`, heartbeat storage,
and the retry-aware scheduled-chain index. Fresh `jobs.sql` installations include these changes.

Records left `RUNNING` by another process are recovered by timeout detection.
`timeoutSeconds` limits time without a check-in. `maxRuntimeSeconds` limits elapsed
time since the current attempt started, even when check-ins continue. Queue time
is excluded; retries and requeued attempts start a new runtime clock.

Pass either limit to `JobRecordsTable.insertJob`. Explicit values override the
parent dispatch's values, then the job spec's values. Copies and retries retain
the stored limits. At timeout checks, null values fall back to the spec and then
configuration. Zero or negative disables that limit independently.

`JOB_DEFAULT_TIMEOUT_SECONDS` defaults to 300; `JOB_DEFAULT_MAX_RUNTIME_SECONDS`
defaults to 0, disabled. Both are checked each coordinator poll, so deadlines can
be exceeded by the polling interval or database delays. The new nullable
`timeout_seconds` and `max_runtime_seconds` columns must exist before deployment.

The callback overload with `JobTimeoutReason` distinguishes `HEARTBEAT` from
`MAX_RUNTIME`; runtime wins if both have expired. It delegates to the original
three-argument callback by default. A timeout marks the job `TIMED_OUT`; jobs must
check in to observe termination, or their callback must interrupt their work.
Check-ins use a separate connection so they remain visible during a
`DatabaseTransactionJob` transaction.

Timeout status and `onJobTimedOut` database changes commit together. If the callback
throws, both roll back and a later sweep retries, including after a restart.
External callback effects must be idempotent because they cannot roll back.

Call `coordinator.stop(waitForStop = true)` during application shutdown to wait for
interrupted jobs to finish cleanup and persist their state before closing dependencies.

`JobResult` and the exceptions the framework uses to end a job extend `Exception`. A job body that
catches `Exception` broadly will swallow `throw JobCompleted()` style signals and the cancellation
raised by `checkIn()`, so catch specific exception types or rethrow `JobResult` instances.

## Job record retention

`JobRecordCleaner` deletes terminal job records and their logs based on the time the job ended.
The database also deletes those records' state-transition history through its foreign key:

| Setting | Default | Applies to |
| --- | --- | --- |
| `JOB_RECORD_CLEANER_COMPLETED_KEEP_MINUTES` | `240` (4 hours) | Successfully completed jobs |
| `JOB_RECORD_CLEANER_FAILED_KEEP_HOURS` | `48` (48 hours) | Other terminal states, including failed, cancelled, and timed-out jobs |

Both settings accept nonnegative integers. They replace `JOB_RECORD_CLEANER_COMPLETED_KEEP_DAYS`
and `JOB_RECORD_CLEANER_FAILED_KEEP_DAYS`; update existing configuration keys and convert their
values to the new units. The failed-job default is independent of the successful-job setting.
Records without an end time are retained. Cleanup runs once every 24 hours by default, so expired
records are deleted on the next cleanup run.

## Upgrading existing job tables

Fresh installations already include the current schema. For older installations,
apply only the migrations not yet installed, in this order:

1. [job-reporting.sql](../src/main/resources/migrations/job-reporting.sql)
2. [job-state-transitions.sql](../src/main/resources/migrations/job-state-transitions.sql)
3. [job-execution-safety.sql](../src/main/resources/migrations/job-execution-safety.sql)
4. [job-cancellation.sql](../src/main/resources/migrations/job-cancellation.sql)

Stop old workers, set the job schema on the connection's search path, and run each
migration once in a transaction. Start the updated application after the schema is
ready. These migrations preserve existing records; they cannot reconstruct history
or attribution that was never stored.

## Next steps

See [database configuration](database.md), [schema installation](schema.md), and [development and releases](development.md).
