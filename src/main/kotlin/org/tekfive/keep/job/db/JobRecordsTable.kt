package org.tekfive.keep.job.db

import org.tekfive.keep.json.query.QueryNode
import org.tekfive.jfk.JsonObject
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.schema.postgresObjects
import org.tekfive.keep.schema.indexKey
import org.tekfive.keep.migration.dynamic.SqlExpression
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbConnection
import org.tekfive.keep.data.addedAt
import org.tekfive.keep.data.createdAt
import org.tekfive.keep.data.uniqueConstraint
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobSpec
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.JobTimeoutReason
import org.tekfive.keep.job.JobSubject
import org.tekfive.keep.job.JobTrigger
import org.tekfive.keep.job.JobTriggerSource
import org.tekfive.keep.job.validateJobCounts
import org.tekfive.keep.job.dispatch.JobOwnershipLostException
import org.tekfive.keep.job.dispatch.DispatchContext
import org.tekfive.keep.job.schedule.ScheduledJobSpec
import org.tekfive.keep.json.jsonObject
import org.tekfive.keep.utils.isUniqueConstraint
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

object JobRecordsTable : DataTable<JobRecord>("job_records") {
    val type = varchar("type", 255)
    val createdAt = createdAt()
    val priority = integer("priority")
    val parentJobId = long("parent_job_id").nullable()
    val minimumStartAt = long("minimum_start_at").nullable()
    val attempt = integer("attempt")
    val estimatedRuntimeSeconds = integer("estimated_run_time_seconds").nullable()
    val state = dataEnum<JobState>("state")
    val jobDetails = jsonObject("job_details").nullable()
    val systemIdentifier = varchar("system_identifier", 255).nullable()
    val startedAt = long("started_at").nullable()
    val lastCheckInAt = long("last_checkin_at").nullable()
    val endedAt = long("ended_at").nullable()
    val failureDetails = text("failure_details").nullable()
    val trigger = JobTriggerColumns(this)
    val lockKey = varchar("lock_key", 255).nullable()
    val maxConcurrentJobs = integer("max_concurrent_jobs").nullable()
    val concurrencyKey = varchar("concurrency_key", 255).nullable()
    val timeoutSeconds = integer("timeout_seconds").nullable()
    val maxRuntimeSeconds = integer("max_runtime_seconds").nullable()
    val subject = JobSubjectColumns(this)
    val resultSummary = text("result_summary").nullable()
    val applicationVersion = text("application_version").nullable()
    val runId = javaUUID("run_id").let { it.defaultExpression(CustomFunction("gen_random_uuid", it.columnType)) }
    val timeoutReason = dataEnum<JobTimeoutReason>("timeout_reason").nullable()
    val tenantId = text("tenant_id").nullable()
    val processedCount = long("processed_count").nullable()
    val failedCount = long("failed_count").nullable()
    val totalCount = long("total_count").nullable()
    val executionToken = javaUUID("execution_token").nullable()
    val retryAt = long("retry_at").nullable()

    override val postgresObjects = postgresObjects {
        index("job_records_running_type_lock_key_uq", type, lockKey, unique = true,
            predicate = SqlExpression("state = ${JobState.RUNNING.id} AND lock_key IS NOT NULL"))
        index("job_records_running_concurrency_scope_idx", type, concurrencyKey,
            predicate = SqlExpression("state = ${JobState.RUNNING.id} AND max_concurrent_jobs IS NOT NULL"))
        index("job_records_scheduled_chain_type_uq", type, unique = true,
            predicate = SqlExpression("trigger_source = ${JobTriggerSource.SCHEDULE.id} AND (state IN (${JobState.PENDING.id}, ${JobState.RUNNING.id}) OR retry_at IS NOT NULL)"))
        index("job_records_pending_priority_created_idx", listOf(priority.indexKey(SortOrder.DESC), createdAt.indexKey()),
            predicate = SqlExpression("state = ${JobState.PENDING.id}"))
        index("job_records_type_state_idx", type, state)
        index("job_records_run_id_idx", runId)
        index("job_records_retry_pending_idx", type, predicate = SqlExpression("retry_at IS NOT NULL"))
    }

    fun launchCopy(copy: JobRecord, now: Long = System.currentTimeMillis()): JobRecord =
        create(copyForExecution(copy, now))

    private fun copyForExecution(copy: JobRecord, now: Long, minimumStartAt: Long? = null): JobRecord =
        JobRecord(
            type = copy.type,
            createdAt = now,
            priority = copy.priority,
            parentJobId = copy.id,
            minimumStartAt = minimumStartAt,
            attempt = copy.attempt + 1,
            estimatedRuntimeSeconds = copy.estimatedRuntimeSeconds,
            state = JobState.PENDING,
            jobDetails = copy.jobDetails,
            systemIdentifier = null,
            startedAt = null,
            lastCheckInAt = null,
            endedAt = null,
            trigger = copy.trigger,
            lockKey = copy.lockKey,
            maxConcurrentJobs = copy.maxConcurrentJobs,
            concurrencyKey = copy.concurrencyKey,
            timeoutSeconds = copy.timeoutSeconds,
            maxRuntimeSeconds = copy.maxRuntimeSeconds,
            subject = copy.subject,
            runId = copy.runId,
            tenantId = copy.tenantId,
        )

    /**
     * Returns the ids and types of the records that are the best candidates to start now, ordered
     * by priority (descending) then creation time (ascending).
     *
     * Only records that could actually be captured are returned, so that a blocked record at the
     * head of the queue cannot starve the records behind it. A record is skipped when:
     * - it carries a [lockKey] and another record of the same [type] and [lockKey] is already
     *   [JobState.RUNNING], or
     * - its own [maxConcurrentJobs] is reached by the [JobState.RUNNING] records in its concurrency
     *   scope (records of the same [type] with the same [concurrencyKey], or every record of the
     *   type when the candidate has no [concurrencyKey]).
     *
     * Limits held by running jobs also apply when the candidate falls within their scope.
     * A candidate's null [maxConcurrentJobs] leaves its spec-level check to [tryCaptureRunLock].
     *
     * [excludeIds] removes already-known ids (for example, ids the caller has queued) from the
     * result.
     */
    fun getJobIdStartCandidates(
        maxIds: Int,
        jobTypeIds: List<String>,
        now: Long,
        excludeIds: Collection<Long> = emptyList(),
    ): List<Pair<Long, String>> {
        if (jobTypeIds.isEmpty()) {
            return emptyList()
        }

        val waitingStateIds = JobState.waitingStates.joinToString(",") { it.id.toString() }
        val typePlaceholders = jobTypeIds.joinToString(",") { "?" }
        val excludeClause = if (excludeIds.isEmpty()) "" else "AND candidate.id <> ALL (?)"
        val sql = """
            SELECT candidate.id, candidate.type
            FROM $tableName candidate
            WHERE candidate.state IN ($waitingStateIds)
            AND candidate.type IN ($typePlaceholders)
            AND (candidate.minimum_start_at IS NULL OR candidate.minimum_start_at <= ?)
            $excludeClause
            AND (
                candidate.lock_key IS NULL
                OR NOT EXISTS (
                    SELECT 1
                    FROM $tableName lock_holder
                    WHERE lock_holder.state = ${JobState.RUNNING.id}
                    AND lock_holder.type = candidate.type
                    AND lock_holder.lock_key = candidate.lock_key
                )
            )
            AND (
                candidate.max_concurrent_jobs IS NULL
                OR candidate.max_concurrent_jobs > (
                    SELECT COUNT(*)
                    FROM $tableName scope_member
                    WHERE scope_member.state = ${JobState.RUNNING.id}
                    AND scope_member.type = candidate.type
                    AND (candidate.concurrency_key IS NULL OR scope_member.concurrency_key = candidate.concurrency_key)
                )
            )
            AND NOT EXISTS (
                SELECT 1 FROM $tableName holder
                WHERE holder.type = candidate.type AND holder.state = ${JobState.RUNNING.id}
                AND holder.max_concurrent_jobs IS NOT NULL
                AND (holder.concurrency_key IS NULL OR holder.concurrency_key = candidate.concurrency_key)
                AND holder.max_concurrent_jobs <= (
                    SELECT COUNT(*) FROM $tableName member
                    WHERE member.type = candidate.type AND member.state = ${JobState.RUNNING.id}
                    AND (holder.concurrency_key IS NULL OR member.concurrency_key = holder.concurrency_key)
                )
            )
            ORDER BY candidate.priority DESC, candidate.created_at ASC
            LIMIT ?
        """.trimIndent()

        return db {
            val connection = dbConnection()
            connection.prepareStatement(sql).use { statement ->
                val currentIndex = AtomicInteger(1)
                jobTypeIds.forEach { statement.setString(currentIndex.getAndIncrement(), it) }
                statement.setLong(currentIndex.getAndIncrement(), now)
                if (excludeIds.isNotEmpty()) {
                    val excluded = connection.createArrayOf("bigint", excludeIds.toTypedArray())
                    statement.setArray(currentIndex.getAndIncrement(), excluded)
                }
                statement.setInt(currentIndex.getAndIncrement(), maxIds)

                statement.executeQuery().use { rs ->
                    val candidates = mutableListOf<Pair<Long, String>>()
                    while (rs.next()) {
                        candidates.add(rs.getLong(1) to rs.getString(2))
                    }
                    candidates
                }
            }
        }
    }

    fun getRunningJobs(jobTypeIds: List<String>): List<JobRecord> {
        if (jobTypeIds.isEmpty()) {
            return emptyList()
        }

        return db {
            leftJoin(JobHeartbeatsTable).select(columns + JobHeartbeatsTable.columns).where {
                (state eq JobState.RUNNING) and (type inList jobTypeIds)
            }.map { row ->
                map(row).also { job ->
                    if (row.getOrNull(JobHeartbeatsTable.executionToken) == job.executionToken) {
                        job.lastCheckInAt = row.getOrNull(JobHeartbeatsTable.lastCheckInAt) ?: job.lastCheckInAt
                    }
                }
            }
        }
    }

    /**
     * Queues a job, inheriting its parent's trigger, subject, tenant, and run ID unless overridden.
     * Independent jobs receive a new run ID. Use [JobTrigger.system] to clear inherited user attribution.
     */
    fun insertJob(
        spec: JobSpec,
        parentJobContext: JobContext? = null,
        parentJobId: Long? = parentJobContext?.jobId,
        details: JsonObject? = parentJobContext?.details,
        minStartAt: Long? = null,
        maxEstimatedRuntimeRecords: Int = 0,
        lockKey: String? = null,
        maxConcurrentJobs: Int? = null,
        concurrencyKey: String? = null,
        timeoutSeconds: Int? = null,
        maxRuntimeSeconds: Int? = null,
        trigger: JobTrigger = parentJobContext?.trigger
            ?: if (spec is ScheduledJobSpec) JobTrigger.scheduled() else JobTrigger.system(),
        subject: JobSubject? = parentJobContext?.subject,
        tenantId: String? = parentJobContext?.tenantId,
        runId: UUID = parentJobContext?.runId ?: UUID.randomUUID(),
    ): Long {
        var estimatedRuntimeSeconds: Int? = null
        if (spec.estimateRuntime && maxEstimatedRuntimeRecords > 0) {
            if (parentJobContext?.estimatedRuntimeSeconds != null) {
                estimatedRuntimeSeconds = parentJobContext.estimatedRuntimeSeconds
            } else if (details != null) {
                val queries = spec.getEstimatedRuntimeQueries(details)
                for (query in queries) {
                    estimatedRuntimeSeconds = getAverageRuntimeSecs(spec.jobTypeIdentifier, maxEstimatedRuntimeRecords, query)
                    if (estimatedRuntimeSeconds != null) break
                }
                if (estimatedRuntimeSeconds == null) {
                    estimatedRuntimeSeconds = getAverageRuntimeSecs(spec.jobTypeIdentifier, maxEstimatedRuntimeRecords, null)
                }
            }
        }

        val resolvedLockKey = lockKey
            ?: (parentJobContext as? DispatchContext)?.jobRecord?.lockKey
        val resolvedMaxConcurrentJobs = maxConcurrentJobs
            ?: (parentJobContext as? DispatchContext)?.jobRecord?.maxConcurrentJobs
            ?: spec.maxConcurrentJobs
        val resolvedConcurrencyKey = concurrencyKey
            ?: (parentJobContext as? DispatchContext)?.jobRecord?.concurrencyKey

        require(resolvedMaxConcurrentJobs == null || resolvedMaxConcurrentJobs > 0) {
            "Max concurrent jobs must be greater than 0."
        }
        require(resolvedMaxConcurrentJobs != null || resolvedConcurrencyKey == null) {
            "A concurrency key requires a max concurrent jobs value."
        }

        return db {
            val now = DbConnection.transactionAt
            val jobRecord = JobRecord(
                type = spec.jobTypeIdentifier,
                createdAt = now,
                priority = spec.jobPriority ?: 0,
                parentJobId = parentJobId,
                minimumStartAt = minStartAt,
                attempt = parentJobContext?.let { it.attempt + 1 } ?: 1,
                estimatedRuntimeSeconds = estimatedRuntimeSeconds,
                state = JobState.PENDING,
                jobDetails = details,
                systemIdentifier = null,
                startedAt = null,
                lastCheckInAt = null,
                endedAt = null,
                failureDetails = null,
                trigger = trigger,
                lockKey = resolvedLockKey,
                maxConcurrentJobs = resolvedMaxConcurrentJobs,
                concurrencyKey = resolvedConcurrencyKey,
                timeoutSeconds = timeoutSeconds
                    ?: (parentJobContext as? DispatchContext)?.jobRecord?.timeoutSeconds
                    ?: spec.timeoutSeconds,
                maxRuntimeSeconds = maxRuntimeSeconds
                    ?: (parentJobContext as? DispatchContext)?.jobRecord?.maxRuntimeSeconds
                    ?: spec.maxRuntimeSeconds,
                subject = subject,
                tenantId = tenantId,
                runId = runId,
            )
            create(jobRecord)
            jobRecord.id
        }
    }

    internal fun tryCaptureRunLock(jobId: Long, systemIdentifier: String, spec: JobSpec, applicationVersion: String? = null): JobRecord? {
        return db {
            // Every capture participates, even an unlimited/keyed job: its count can affect a
            // type-wide limit held by another running job. Acquire before reading the counts.
            captureConcurrencyScopeLock("job:${spec.jobTypeIdentifier}")
            val jobRecord = findById(jobId) ?: return@db null
            if (jobRecord.type != spec.jobTypeIdentifier || jobRecord.state !in JobState.waitingStates) return@db null

            val maxConcurrentJobs = jobRecord.maxConcurrentJobs ?: spec.maxConcurrentJobs
            require(maxConcurrentJobs == null || maxConcurrentJobs > 0) { "Max concurrent jobs must be greater than 0." }
            val running = select(this@JobRecordsTable.maxConcurrentJobs, concurrencyKey).where {
                (type eq jobRecord.type) and (state eq JobState.RUNNING)
            }.toList()
            val countsByKey = running.groupingBy { it[concurrencyKey] }.eachCount()
            fun countInScope(key: String?): Int = if (key == null) running.size else countsByKey[key] ?: 0
            if (spec.exclusiveExecution && running.isNotEmpty()) return@db null
            if (maxConcurrentJobs != null && countInScope(jobRecord.concurrencyKey) >= maxConcurrentJobs) return@db null
            // Respect limits already promised to jobs whose scope includes this candidate.
            if (running.any { row ->
                val limit = row[this@JobRecordsTable.maxConcurrentJobs]
                val key = row[concurrencyKey]
                limit != null && (key == null || key == jobRecord.concurrencyKey) && countInScope(key) >= limit
            }) return@db null

            val concurrencyPredicate: Op<Boolean>? = if (jobRecord.lockKey != null) {
                val lockKey = jobRecord.lockKey
                notExists(select(id).where {
                    (type eq spec.jobTypeIdentifier) and (state eq JobState.RUNNING) and (this@JobRecordsTable.lockKey eq lockKey)
                })
            } else {
                null
            }

            val basePredicate = (id eq jobId) and (state inList JobState.waitingStates)
            val fullPredicate = if (concurrencyPredicate != null) basePredicate and concurrencyPredicate else basePredicate

            val now = DbConnection.transactionAt
            val rows = try {
                updateReturning(where = { fullPredicate }) { stmt ->
                    stmt[state] = JobState.RUNNING
                    stmt[this@JobRecordsTable.systemIdentifier] = systemIdentifier
                    stmt[startedAt] = now
                    stmt[lastCheckInAt] = null
                    stmt[endedAt] = null
                    stmt[failureDetails] = null
                    stmt[executionToken] = UUID.randomUUID()
                    stmt[this@JobRecordsTable.maxConcurrentJobs] = maxConcurrentJobs
                    stmt[this@JobRecordsTable.applicationVersion] = applicationVersion
                    // A requeued record starts its reporting counters again when it is recaptured.
                    stmt[resultSummary] = null
                    stmt[timeoutReason] = null
                    stmt[processedCount] = null
                    stmt[failedCount] = null
                    stmt[totalCount] = null
                }
            } catch (e: SQLException) {
                if (e.isUniqueConstraint) {
                    return@db null
                }
                throw e
            }

            rows.singleOrNull()?.let { row ->
                map(row).also { job ->
                    dbConnection().prepareStatement("""
                        INSERT INTO ${JobHeartbeatsTable.tableName} (job_record_id, execution_token, last_checkin_at)
                        VALUES (?, ?, NULL)
                        ON CONFLICT (job_record_id) DO UPDATE
                        SET execution_token = EXCLUDED.execution_token, last_checkin_at = NULL
                    """.trimIndent()).use { statement ->
                        statement.setLong(1, job.id)
                        statement.setObject(2, job.executionToken)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun captureConcurrencyScopeLock(scope: String) {
        dbConnection().prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, scope)
            statement.execute()
        }
    }

    internal fun markEnded(jobRecordId: Long, endedAt: Long, state: JobState, failureDetails: String? = null) {
        tryMarkEnded(jobRecordId, endedAt, state, failureDetails)
    }

    internal fun tryMarkEnded(
        jobRecordId: Long,
        endedAt: Long,
        state: JobState,
        failureDetails: String? = null,
        resultSummary: String? = failureDetails,
        processedCount: Long? = null,
        failedCount: Long? = null,
        totalCount: Long? = null,
        expectedExecutionToken: UUID? = null,
        retryAt: Long? = null,
    ): Boolean {
        validateJobCounts(processedCount, failedCount, totalCount)
        return db {
            val heartbeatAt = JobHeartbeatsTable.innerJoin(this@JobRecordsTable)
                .select(JobHeartbeatsTable.lastCheckInAt).where {
                    (id eq jobRecordId) and (JobHeartbeatsTable.executionToken eq executionToken)
                }.singleOrNull()?.get(JobHeartbeatsTable.lastCheckInAt)
            update({
                runningExecution(jobRecordId, expectedExecutionToken)
            }) { statement ->
                if (heartbeatAt != null) statement[lastCheckInAt] = heartbeatAt
                statement[this@JobRecordsTable.endedAt] = endedAt
                statement[this@JobRecordsTable.state] = state
                statement[this@JobRecordsTable.failureDetails] = failureDetails
                statement[this@JobRecordsTable.resultSummary] = resultSummary
                statement[this@JobRecordsTable.retryAt] = retryAt
                if (processedCount != null) statement[this@JobRecordsTable.processedCount] = processedCount
                if (failedCount != null) statement[this@JobRecordsTable.failedCount] = failedCount
                if (totalCount != null) statement[this@JobRecordsTable.totalCount] = totalCount
            } > 0
        }
    }

    internal fun tryMarkTimedOut(jobRecordId: Long, cutoffAt: Long, endedAt: Long, failureDetails: String? = null,
        reason: JobTimeoutReason = JobTimeoutReason.HEARTBEAT, expectedExecutionToken: UUID? = null): JobRecord? = db {
        // Never wait on a business transaction that may be about to check in. Lock the job
        // first, then its heartbeat, and recheck using a fresh snapshot after both locks.
        if (!lockRunningJob(jobRecordId)) return@db null
        val heartbeat = dbConnection().prepareStatement(
            "SELECT execution_token, last_checkin_at FROM ${JobHeartbeatsTable.tableName} WHERE job_record_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setLong(1, jobRecordId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getObject(1, UUID::class.java) to rs.getLong(2).let { if (rs.wasNull()) null else it }
                else null
            }
        }
        val job = selectAll().where { id eq jobRecordId }.single().let(::map)
        if (expectedExecutionToken != null && job.executionToken != expectedExecutionToken) return@db null
        val latestCheckIn = if (heartbeat?.first == job.executionToken) heartbeat?.second ?: job.lastCheckInAt else job.lastCheckInAt
        val activityAt = if (reason == JobTimeoutReason.MAX_RUNTIME) job.startedAt else latestCheckIn ?: job.startedAt
        if (activityAt == null || activityAt > cutoffAt) return@db null

        updateReturning(where = { runningExecution(jobRecordId, expectedExecutionToken) }) { statement ->
            statement[this@JobRecordsTable.endedAt] = endedAt
            statement[state] = JobState.TIMED_OUT
            statement[this@JobRecordsTable.failureDetails] = failureDetails
            statement[resultSummary] = failureDetails
            statement[timeoutReason] = reason
            statement[lastCheckInAt] = latestCheckIn
        }.singleOrNull()?.let(::map)
    }

    private fun lockRunningJob(jobId: Long): Boolean = dbConnection().prepareStatement(
        "SELECT id FROM $tableName WHERE id = ? AND state = ${JobState.RUNNING.id} FOR UPDATE SKIP LOCKED"
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.executeQuery().use { it.next() }
    }

    private fun runningExecution(jobId: Long, expectedExecutionToken: UUID?): Op<Boolean> {
        val running = (id eq jobId) and (state eq JobState.RUNNING)
        return if (expectedExecutionToken == null) running else running and (executionToken eq expectedExecutionToken)
    }

    /**
     * Returns a [JobState.RUNNING] record to [JobState.PENDING] so another dispatcher can pick it
     * up. Used when a dispatcher was interrupted before the job could complete.
     */
    internal fun tryRequeue(jobRecordId: Long, expectedExecutionToken: UUID? = null): Boolean {
        return db {
            update({
                runningExecution(jobRecordId, expectedExecutionToken)
            }) { statement ->
                statement[this@JobRecordsTable.state] = JobState.PENDING
                statement[this@JobRecordsTable.systemIdentifier] = null
                statement[startedAt] = null
                statement[lastCheckInAt] = null
            } > 0
        }
    }

    /**
     * Returns every [JobState.RUNNING] record owned by [systemIdentifier] that started before
     * [startedBefore] to [JobState.PENDING]. A record matching both conditions cannot have a live
     * dispatcher behind it as long as [systemIdentifier] is unique to one running process.
     */
    internal fun requeueOrphanedRunningJobs(systemIdentifier: String, startedBefore: Long): List<JobRecord> {
        return db {
            updateReturning(
                where = {
                    (state eq JobState.RUNNING) and
                        (this@JobRecordsTable.systemIdentifier eq systemIdentifier) and
                        (startedAt.isNotNull()) and (startedAt lessEq startedBefore)
                }
            ) { statement ->
                statement[this@JobRecordsTable.state] = JobState.PENDING
                statement[this@JobRecordsTable.systemIdentifier] = null
                statement[startedAt] = null
                statement[lastCheckInAt] = null
            }.map(::map)
        }
    }

    /**
     * Summarises the scheduling state of each job type in [jobTypeIdentifiers] with one query:
     * whether any non-terminated record exists, and when the most recent scheduled record ended.
     * Types with no records at all are absent from the result.
     */
    fun getScheduleStates(jobTypeIdentifiers: Collection<String>): Map<String, ScheduleState> {
        if (jobTypeIdentifiers.isEmpty()) {
            return emptyMap()
        }

        val nonTerminatedIds = JobState.entries.filter { !it.terminated }.joinToString(",") { it.id.toString() }
        val terminatedIds = JobState.terminatedStates.joinToString(",") { it.id.toString() }
        val placeholders = jobTypeIdentifiers.joinToString(",") { "?" }
        val sql = """
            SELECT type,
                   BOOL_OR(state IN ($nonTerminatedIds) OR retry_at IS NOT NULL) AS has_non_terminated,
                   MAX(ended_at) FILTER (WHERE trigger_source = ${JobTriggerSource.SCHEDULE.id} AND state IN ($terminatedIds) AND ended_at IS NOT NULL) AS last_ended_at
            FROM $tableName
            WHERE type IN ($placeholders)
            GROUP BY type
        """.trimIndent()

        return db {
            dbConnection().prepareStatement(sql).use { statement ->
                jobTypeIdentifiers.forEachIndexed { index, typeId -> statement.setString(index + 1, typeId) }
                statement.executeQuery().use { rs ->
                    val states = mutableMapOf<String, ScheduleState>()
                    while (rs.next()) {
                        val lastEndedAt = rs.getLong("last_ended_at").let { if (rs.wasNull()) null else it }
                        states[rs.getString("type")] = ScheduleState(rs.getBoolean("has_non_terminated"), lastEndedAt)
                    }
                    states
                }
            }
        }
    }

    data class ScheduleState(val hasNonTerminatedJob: Boolean, val lastEndedScheduledAt: Long?)

    internal fun getJobDetails(jobId: Long): JsonObject? = db {
        select(jobDetails).where { id eq jobId }.single()[jobDetails]
    }

    internal fun updateJobDetails(jobId: Long, details: JsonObject, expectedExecutionToken: UUID? = null): Boolean {
        return db {
            update({ if (expectedExecutionToken == null) id eq jobId else runningExecution(jobId, expectedExecutionToken) }) { statement ->
                statement[jobDetails] = details
            } > 0
        }
    }

    /** Saves a progress snapshot only while the attempt is running. */
    internal fun updateProgress(jobId: Long, processedCount: Long?, failedCount: Long?, totalCount: Long?, expectedExecutionToken: UUID? = null): Boolean {
        validateJobCounts(processedCount, failedCount, totalCount)
        return db {
            update({ runningExecution(jobId, expectedExecutionToken) }) { statement ->
                statement[this@JobRecordsTable.processedCount] = processedCount
                statement[this@JobRecordsTable.failedCount] = failedCount
                statement[this@JobRecordsTable.totalCount] = totalCount
            } > 0
        }
    }

    /**
     * Commits the heartbeat independently of a job's business transaction. The mirror on
     * job_records is updated only when that row is unlocked; timeout decisions use job_heartbeats.
     */
    internal fun updateLastCheckIn(jobId: Long, checkInAt: Long, expectedExecutionToken: UUID? = null): JobState? {
        DbConnection.createConnection().use { connection ->
            connection.autoCommit = false
            try {
                fun currentExecution(): Pair<JobState, UUID?>? = connection.prepareStatement(
                    "SELECT state, execution_token FROM $tableName WHERE id = ?"
                ).use { statement ->
                    statement.setLong(1, jobId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) JobState.map(rs.getInt(1)) to rs.getObject(2, UUID::class.java) else null
                    }
                }
                fun checkOwnership(current: Pair<JobState, UUID?>?) {
                    if (current != null && expectedExecutionToken != null && current.second != expectedExecutionToken) {
                        throw JobOwnershipLostException(jobId)
                    }
                }
                val current = currentExecution()
                checkOwnership(current)
                if (current == null || current.first != JobState.RUNNING) return current?.first
                val token = current.second ?: throw JobOwnershipLostException(jobId)
                val updated = connection.prepareStatement("""
                    UPDATE ${JobHeartbeatsTable.tableName}
                    SET last_checkin_at = GREATEST(last_checkin_at, ?)
                    WHERE job_record_id = ? AND execution_token = ?
                """.trimIndent()).use { statement ->
                    statement.setLong(1, checkInAt)
                    statement.setLong(2, jobId)
                    statement.setObject(3, token)
                    statement.executeUpdate()
                }
                if (updated == 0) throw JobOwnershipLostException(jobId)
                // SKIP LOCKED is essential: this connection must never wait for the caller's
                // transaction, nor for a timeout transaction that is locking the heartbeat.
                connection.prepareStatement("""
                    WITH unlocked AS (
                        SELECT id FROM $tableName WHERE id = ? AND state = ${JobState.RUNNING.id}
                        AND execution_token = ? FOR UPDATE SKIP LOCKED
                    )
                    UPDATE $tableName job SET last_checkin_at = GREATEST(job.last_checkin_at, ?)
                    FROM unlocked WHERE job.id = unlocked.id
                """.trimIndent()).use { statement ->
                    statement.setLong(1, jobId)
                    statement.setObject(2, token)
                    statement.setLong(3, checkInAt)
                    statement.executeUpdate()
                }
                val latest = currentExecution()
                checkOwnership(latest)
                connection.commit()
                return latest?.first
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }
    }

    /** Reads durable requests, including future retries which can be queued immediately. */
    internal fun getRetryCandidates(jobTypeIds: List<String>): List<Long> = db {
        if (jobTypeIds.isEmpty()) emptyList() else select(id).where {
            (type inList jobTypeIds) and (state eq JobState.FAILED) and retryAt.isNotNull()
        }.orderBy(id).map { it[id] }
    }

    /** Claim, clear the request, and insert its retry in one transaction. Safe to call again. */
    internal fun enqueueRetry(jobId: Long): JobRecord? = db {
        val parent = selectAll().where { (id eq jobId) and (state eq JobState.FAILED) and retryAt.isNotNull() }
            .forUpdate().singleOrNull()?.let(::map) ?: return@db null
        // Release scheduled-chain uniqueness before the insert; both changes commit together.
        update({ id eq jobId }) { it[retryAt] = null }
        create(copyForExecution(parent, DbConnection.transactionAt, parent.retryAt))
    }

    fun hasNonTerminatedJob(jobTypeIdentifier: String): Boolean {
        return db {
            val nonTerminatedStates = JobState.entries.filter { !it.terminated }
            select(id).where {
                (type eq jobTypeIdentifier) and ((state inList nonTerminatedStates) or retryAt.isNotNull())
            }.limit(1).any()
        }
    }

    fun getLastEndedScheduledAt(jobTypeIdentifier: String): Long? {
        return db {
            val terminatedStates = JobState.terminatedStates
            select(endedAt).where {
                (type eq jobTypeIdentifier) and
                    (trigger.source eq JobTriggerSource.SCHEDULE) and
                    (state inList terminatedStates) and
                    endedAt.isNotNull()
            }.orderBy(endedAt to SortOrder.DESC).limit(1)
                .firstOrNull()?.let { it[endedAt] }
        }
    }

    fun getAverageRuntimeSecs(jobType: String, maxRecords: Int, queryNode: QueryNode?): Int? {
        require(maxRecords > 0) { "maxRecords must be > 0" }

        val jsonFilter = queryNode?.toParameterizedSql("job_details")?.let { "AND $it" } ?: ""

        val sql = """
            SELECT AVG((ended_at - started_at) / 1000.0) as avg_seconds
            FROM (
                SELECT ended_at, started_at
                FROM $tableName
                WHERE type = ?
                AND state = ?
                AND ended_at IS NOT NULL
                $jsonFilter
                ORDER BY ended_at DESC
                LIMIT ?
            ) as recent_jobs
        """.trimIndent()

        return db {
            dbConnection().prepareStatement(sql).use { statement ->
                val currentIndex = AtomicInteger(1)
                statement.setString(currentIndex.getAndIncrement(), jobType)
                statement.setInt(currentIndex.getAndIncrement(), JobState.COMPLETED.id)
                queryNode?.addValue(currentIndex, statement)
                statement.setInt(currentIndex.get(), maxRecords)

                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        val avg = rs.getDouble("avg_seconds")
                        if (rs.wasNull()) null else avg.roundToInt()
                    } else {
                        null
                    }
                }
            }
        }
    }
}
