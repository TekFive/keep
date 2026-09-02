package org.tekfive.keep.job.db

import org.tekfive.jfk.JsonObject
import org.jetbrains.exposed.v1.core.Op
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
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbConnection
import org.tekfive.keep.db.inDbTransaction
import org.tekfive.keep.data.addedAt
import org.tekfive.keep.data.createdAt
import org.tekfive.keep.data.uniqueConstraint
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobSpec
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.dispatch.DispatchContext
import org.tekfive.keep.job.schedule.ScheduledJobSpec
import org.tekfive.keep.json.jsonObject
import org.tekfive.keep.utils.isUniqueConstraint
import java.sql.SQLException
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
    val scheduledJob = bool("scheduled_job").default(false)
    val lockKey = varchar("lock_key", 255).nullable()
    val maxConcurrentJobs = integer("max_concurrent_jobs").nullable()
    val concurrencyKey = varchar("concurrency_key", 255).nullable()

    override val customIndices = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS job_records_running_type_lock_key_uq ON $tableName (type, lock_key) WHERE state = ${JobState.RUNNING.id} AND lock_key IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS job_records_running_concurrency_scope_idx ON $tableName (type, concurrency_key) WHERE state = ${JobState.RUNNING.id} AND max_concurrent_jobs IS NOT NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS job_records_scheduled_chain_type_uq ON $tableName (type) WHERE scheduled_job = TRUE AND state IN (${JobState.PENDING.id}, ${JobState.RUNNING.id})",
        "CREATE INDEX IF NOT EXISTS job_records_pending_priority_created_idx ON $tableName (priority DESC, created_at) WHERE state = ${JobState.PENDING.id}",
        "CREATE INDEX IF NOT EXISTS job_records_type_state_idx ON $tableName (type, state)",
    )

    fun launchCopy(copy: JobRecord, now: Long = System.currentTimeMillis()): JobRecord {
        return db {
            create(JobRecord(
                copy.type,
                now,
                copy.priority,
                copy.parentJobId,
                null,
                copy.attempt + 1,
                copy.estimatedRuntimeSeconds,
                copy.state,
                copy.jobDetails,
                null,
                null,
                null,
                null,
                null,
                scheduledJob = copy.scheduledJob,
                lockKey = copy.lockKey,
                maxConcurrentJobs = copy.maxConcurrentJobs,
                concurrencyKey = copy.concurrencyKey,
            ))
        }
    }

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
     * A null [maxConcurrentJobs] is not filtered on: the spec-level default is unknown here, so the
     * limit is left to [tryCaptureRunLock].
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
            selectAll().where {
                (state eq JobState.RUNNING) and (type inList jobTypeIds)
            }.map(::map)
        }
    }

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
    ): Long {
        var estimatedRuntimeSeconds: Int? = null
        if (maxEstimatedRuntimeRecords > 0) {
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
        val resolvedScheduledJob = (parentJobContext as? DispatchContext)?.jobRecord?.scheduledJob
            ?: (spec is ScheduledJobSpec)
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
                scheduledJob = resolvedScheduledJob,
                lockKey = resolvedLockKey,
                maxConcurrentJobs = resolvedMaxConcurrentJobs,
                concurrencyKey = resolvedConcurrencyKey,
            )
            create(jobRecord)
            jobRecord.id
        }
    }

    internal fun tryCaptureRunLock(jobId: Long, systemIdentifier: String, spec: JobSpec): JobRecord? {
        return db {
            val jobRecord = findById(jobId) ?: return@db null
            if (jobRecord.state !in JobState.waitingStates) return@db null

            val maxConcurrentJobs = jobRecord.maxConcurrentJobs ?: spec.maxConcurrentJobs
            if (maxConcurrentJobs != null) {
                require(maxConcurrentJobs > 0) { "Max concurrent jobs must be greater than 0." }

                val scope = concurrencyScope(spec.jobTypeIdentifier, jobRecord.concurrencyKey)
                captureConcurrencyScopeLock(scope)
                if (countRunningJobs(spec.jobTypeIdentifier, jobRecord.concurrencyKey) >= maxConcurrentJobs) {
                    return@db null
                }
            }

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
                }
            } catch (e: SQLException) {
                if (e.isUniqueConstraint) {
                    return@db null
                }
                throw e
            }

            rows.singleOrNull()?.let { map(it) }
        }
    }

    private fun concurrencyScope(jobTypeIdentifier: String, concurrencyKey: String?): String {
        return if (concurrencyKey == null) {
            "job:$jobTypeIdentifier"
        } else {
            "job:$jobTypeIdentifier:$concurrencyKey"
        }
    }

    private fun captureConcurrencyScopeLock(scope: String) {
        dbConnection().prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, scope)
            statement.execute()
        }
    }

    private fun countRunningJobs(jobTypeIdentifier: String, concurrencyKey: String?): Int {
        val sql = if (concurrencyKey == null) {
            "SELECT COUNT(*) FROM $tableName WHERE type = ? AND state = ?"
        } else {
            "SELECT COUNT(*) FROM $tableName WHERE type = ? AND state = ? AND concurrency_key = ?"
        }

        dbConnection().prepareStatement(sql).use { statement ->
            statement.setString(1, jobTypeIdentifier)
            statement.setInt(2, JobState.RUNNING.id)
            if (concurrencyKey != null) {
                statement.setString(3, concurrencyKey)
            }

            statement.executeQuery().use { result ->
                result.next()
                return result.getInt(1)
            }
        }
    }

    internal fun markEnded(jobRecordId: Long, endedAt: Long, state: JobState, failureDetails: String? = null) {
        tryMarkEnded(jobRecordId, endedAt, state, failureDetails)
    }

    internal fun tryMarkEnded(jobRecordId: Long, endedAt: Long, state: JobState, failureDetails: String? = null): Boolean {
        return db {
            update({
                (id eq jobRecordId) and (this@JobRecordsTable.state eq JobState.RUNNING)
            }) { statement ->
                statement[this@JobRecordsTable.endedAt] = endedAt
                statement[this@JobRecordsTable.state] = state
                statement[this@JobRecordsTable.failureDetails] = failureDetails
            } > 0
        }
    }

    internal fun tryMarkTimedOut(jobRecordId: Long, cutoffAt: Long, endedAt: Long, failureDetails: String? = null): JobRecord? {
        return db {
            val timedOutPredicate =
                ((lastCheckInAt.isNotNull()) and (lastCheckInAt lessEq cutoffAt)) or
                    ((lastCheckInAt.isNull()) and (startedAt.isNotNull()) and (startedAt lessEq cutoffAt))

            val result = updateReturning(
                where = {
                    (id eq jobRecordId) and
                        (state eq JobState.RUNNING) and
                        timedOutPredicate
                }
            ) { statement ->
                statement[this@JobRecordsTable.endedAt] = endedAt
                statement[this@JobRecordsTable.state] = JobState.TIMED_OUT
                statement[this@JobRecordsTable.failureDetails] = failureDetails
            }

            result.singleOrNull()?.let(::map)
        }
    }

    /**
     * Returns a [JobState.RUNNING] record to [JobState.PENDING] so another dispatcher can pick it
     * up. Used when a dispatcher was interrupted before the job could complete.
     */
    internal fun tryRequeue(jobRecordId: Long): Boolean {
        return db {
            update({
                (id eq jobRecordId) and (this@JobRecordsTable.state eq JobState.RUNNING)
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
                   BOOL_OR(state IN ($nonTerminatedIds)) AS has_non_terminated,
                   MAX(ended_at) FILTER (WHERE scheduled_job AND state IN ($terminatedIds) AND ended_at IS NOT NULL) AS last_ended_at
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

    internal fun updateJobDetails(jobId: Long, details: JsonObject) {
        db {
            update({ id eq jobId }) { statement ->
                statement[jobDetails] = details
            }
        }
    }

    /**
     * Records a check-in for [jobId] and returns the record's current state, or null when no record
     * has that id.
     *
     * A job may run its whole body inside one transaction, which would hide its check-ins from the
     * timeout monitor until that transaction commits. When called from inside a transaction the
     * update is therefore committed immediately on a separate connection.
     */
    internal fun updateLastCheckIn(jobId: Long, checkInAt: Long): JobState? {
        if (inDbTransaction()) {
            return updateLastCheckInOnNewConnection(jobId, checkInAt)
        }

        return db {
            val result = updateReturning(
                returning = listOf(state),
                where = {
                    id eq jobId
                }
            ) { statement ->
                statement[lastCheckInAt] = checkInAt
            }

            result.firstOrNull()?.let { it[state] }
        }
    }

    /** Commits a check-in outside of the caller's transaction so it is visible immediately. */
    private fun updateLastCheckInOnNewConnection(jobId: Long, checkInAt: Long): JobState? {
        val sql = "UPDATE $tableName SET last_checkin_at = ? WHERE id = ? RETURNING state"

        DbConnection.createConnection().use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, checkInAt)
                statement.setLong(2, jobId)
                statement.executeQuery().use { rs ->
                    return if (rs.next()) JobState.map(rs.getInt(1)) else null
                }
            }
        }
    }


    fun hasNonTerminatedJob(jobTypeIdentifier: String): Boolean {
        return db {
            val nonTerminatedStates = JobState.entries.filter { !it.terminated }
            select(id).where {
                (type eq jobTypeIdentifier) and (state inList nonTerminatedStates)
            }.limit(1).any()
        }
    }

    fun getLastEndedScheduledAt(jobTypeIdentifier: String): Long? {
        return db {
            val terminatedStates = JobState.terminatedStates
            select(endedAt).where {
                (type eq jobTypeIdentifier) and
                    (scheduledJob eq true) and
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
