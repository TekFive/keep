package org.tekfive.keep.job

import org.slf4j.LoggerFactory
import org.tekfive.keep.db.db
import org.tekfive.keep.job.db.DatabaseGatekeeper
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.job.dispatch.Dispatcher
import org.tekfive.keep.utils.isUniqueConstraint
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Polls [JobRecordsTable] for runnable work and hands it to a pool of [Dispatcher] threads.
 *
 * The coordination thread never exits because of a failure inside one polling cycle: every
 * cycle is guarded, logged, and retried on the next poll. Dispatcher threads that die are
 * replaced on the next cycle. [systemIdentifier] must be unique to one running process when
 * [JobConfiguration.reclaimOrphanedJobsOnStart] is enabled.
 */
class JobCoordinator(
    val systemIdentifier: String,
    val registry: JobRegistry,
    configuration: JobConfiguration,
) : Runnable {

    private val log = LoggerFactory.getLogger(JobCoordinator::class.java)

    val configuration: JobConfiguration = JobConfigurationGuard(configuration)

    @Volatile
    private var coordinationThread: Thread? = null

    @Volatile
    private var startedAt: Long = 0L

    @Volatile
    private var orphanReclaimPending: Boolean = false

    private val wakeSignal = Semaphore(0)

    private val candidateQueue = LinkedBlockingQueue<Pair<Long, JobSpec>>()

    private val databaseGatekeeper = DatabaseGatekeeper(this.configuration)

    private val dispatchers = CopyOnWriteArrayList<Dispatcher>()

    private val timeoutMonitor = JobTimeoutMonitor(
        configuration = this.configuration,
        registry = registry,
        databaseGatekeeper = databaseGatekeeper,
        jobsTable = JobRecordsTable,
    )

    fun start() {
        if (coordinationThread?.let { it.isAlive } != true) {
            startedAt = System.currentTimeMillis()
            orphanReclaimPending = configuration.reclaimOrphanedJobsOnStart
            warnAboutConfiguration()

            val thread = Thread(this, "Job Coordinator")
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                log.error("Job coordinator thread {} died unexpectedly; no further jobs will be dispatched until start() is called again.", t.name, e)
            }
            // run() uses coordinationThread as its ownership token, so publish it before the
            // new thread can evaluate the loop condition.
            coordinationThread = thread
            thread.start()
        }
    }

    fun stop(waitForStop: Boolean = false) {
        val thread = coordinationThread
        coordinationThread = null
        thread?.interrupt()

        val dispatchers = dispatchers.toList()
        this.dispatchers.clear()

        dispatchers.forEach { it.interrupt() }

        if (waitForStop) {
            dispatchers.forEach { it.join() }
            thread?.join()
        }
    }

    /**
     * Ends the current poll wait early so newly inserted work is picked up without waiting for
     * [JobConfiguration.pollSeconds] to elapse. Safe to call from any thread.
     */
    fun wakeUp() {
        wakeSignal.drainPermits()
        wakeSignal.release()
    }

    val isRunning: Boolean
        get() = coordinationThread?.isAlive == true

    private fun isOwner(): Boolean = Thread.currentThread() == coordinationThread

    @Synchronized
    override fun run() {
        while (isOwner()) {
            try {
                if (orphanReclaimPending) {
                    reclaimOrphanedJobs()
                }

                adjustDispatchersIfNecessary()

                timeoutMonitor.sweep()

                scheduleJobs()

                fetchCandidates()
            } catch (_: InterruptedException) {
                if (!isOwner()) {
                    break
                }
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                log.error("Job coordination cycle failed; it will be retried on the next poll.", e)
            }

            if (!isOwner()) {
                break
            }

            try {
                wakeSignal.tryAcquire(configuration.pollSeconds.toLong(), TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                if (!isOwner()) {
                    break
                }
            }
        }
    }

    private fun reclaimOrphanedJobs() {
        val requeued = try {
            databaseGatekeeper { db { JobRecordsTable.requeueOrphanedRunningJobs(systemIdentifier, startedAt) } }
        } catch (e: SQLException) {
            if (!databaseGatekeeper.onSQLException(e)) {
                log.error("Unable to reclaim orphaned running jobs for system '{}'.", systemIdentifier, e)
            }
            return
        }

        orphanReclaimPending = false
        if (requeued.isNotEmpty()) {
            log.warn(
                "Returned {} job(s) left running by a previous '{}' process to pending: {}",
                requeued.size,
                systemIdentifier,
                requeued.joinToString { "${it.type}#${it.id}" },
            )
        }
    }

    private fun scheduleJobs() {
        val specs = registry.scheduledJobSpecs
        if (specs.isEmpty()) {
            return
        }

        val scheduleStates = try {
            databaseGatekeeper { db { JobRecordsTable.getScheduleStates(specs.map { it.jobTypeIdentifier }) } }
        } catch (e: SQLException) {
            if (!databaseGatekeeper.onSQLException(e)) {
                log.error("Unable to read scheduled job state.", e)
            }
            return
        }

        for (spec in specs) {
            val scheduleState = scheduleStates[spec.jobTypeIdentifier]
            if (scheduleState?.hasNonTerminatedJob == true) {
                continue
            }

            val nextJob = try {
                spec.schedule(scheduleState?.lastEndedScheduledAt)
            } catch (e: Exception) {
                log.error("Scheduled job spec {} failed to compute its next run; it will be retried on the next poll.", spec.jobTypeIdentifier, e)
                continue
            } ?: continue

            try {
                databaseGatekeeper { db { JobRecordsTable.create(nextJob) } }
            } catch (e: SQLException) {
                if (e.isUniqueConstraint) {
                    // Another coordinator scheduled this type first.
                    continue
                }
                if (!databaseGatekeeper.onSQLException(e)) {
                    log.error("Unable to insert the next scheduled run of {}.", spec.jobTypeIdentifier, e)
                }
            }
        }
    }

    private fun fetchCandidates() {
        val queued = candidateQueue.toList()
        val maxCandidates = configuration.maximumCandidatesBuffer - queued.size
        if (maxCandidates <= 0) {
            return
        }

        val jobTypeIds = registry.jobTypeIds
        if (jobTypeIds.isEmpty()) {
            return
        }

        try {
            val candidateIdsTypes = databaseGatekeeper {
                db {
                    JobRecordsTable.getJobIdStartCandidates(
                        maxCandidates,
                        jobTypeIds,
                        System.currentTimeMillis(),
                        excludeIds = queued.map { it.first },
                    )
                }
            }
            val candidateIdsSpecs = candidateIdsTypes.mapNotNull { (id, typeId) -> registry[typeId]?.let { spec -> id to spec } }
            candidateQueue.addAll(candidateIdsSpecs)
        } catch (e: SQLException) {
            if (!databaseGatekeeper.onSQLException(e)) {
                log.error("Unable to query for runnable jobs.", e)
            }
        }
    }

    private fun adjustDispatchersIfNecessary() {
        val dead = dispatchers.filter { !it.isAlive }
        if (dead.isNotEmpty()) {
            dispatchers.removeAll(dead.toSet())
            log.warn("Replacing {} dispatcher thread(s) that exited unexpectedly: {}", dead.size, dead.joinToString { it.name })
        }

        if (dispatchers.size != configuration.dispatchCount) {
            var delta = configuration.dispatchCount - dispatchers.size
            if (delta > 0) {
                for (i in 1 .. delta) {
                    dispatchers.add(Dispatcher(
                        configuration,
                        candidateQueue,
                        dispatchers,
                        databaseGatekeeper,
                        JobRecordsTable,
                        systemIdentifier,
                    ).apply { start() })
                }
            } else {
                while (delta < 0) {
                    dispatchers.removeAt(0).interrupt()
                    ++delta
                }
            }
        }
    }

    private fun warnAboutConfiguration() {
        if (configuration.dispatchCount <= 0) {
            log.warn("Job configuration dispatchCount is 0; the coordinator will poll but never execute a job.")
        }
        if (configuration.maximumCandidatesBuffer <= 0) {
            log.warn("Job configuration maximumCandidatesBuffer is 0; the coordinator will never fetch runnable jobs.")
        }

        for (spec in registry.jobSpecs) {
            val timeoutSeconds = spec.timeoutSeconds ?: configuration.defaultJobTimeoutSeconds
            if (timeoutSeconds <= 0) {
                log.warn(
                    "Job type {} has timeout detection disabled; a running record orphaned by a crash of another process will never be recovered automatically.",
                    spec.jobTypeIdentifier,
                )
            } else if (timeoutSeconds < configuration.minSecondsBetweenJobCheckin) {
                log.warn(
                    "Job type {} times out after {}s but check-ins are throttled to one per {}s; long runs of this type will always time out.",
                    spec.jobTypeIdentifier,
                    timeoutSeconds,
                    configuration.minSecondsBetweenJobCheckin,
                )
            }
        }
    }
}

internal class JobConfigurationGuard(val configuration: JobConfiguration) : JobConfiguration {

    override val dispatchCount: Int
        get() = configuration.dispatchCount.coerceAtLeast(0)

    override val pollSeconds: Int
        get() = configuration.pollSeconds.coerceAtLeast(1)

    override val maximumCandidatesBuffer: Int
        get() = configuration.maximumCandidatesBuffer.coerceAtLeast(0)

    override val maxEstimatedRuntimeRecords: Int
        get() = configuration.maxEstimatedRuntimeRecords.coerceAtLeast(0)

    override val minSecondsBetweenJobCheckin: Int
        get() = configuration.minSecondsBetweenJobCheckin.coerceAtLeast(1)

    override val defaultJobTimeoutSeconds: Int
        get() = configuration.defaultJobTimeoutSeconds.coerceAtLeast(0)

    override val defaultMinSecondsBetweenJobRetry: Int
        get() = configuration.defaultMinSecondsBetweenJobRetry.coerceAtLeast(0)

    override val minSaveLogLevel
        get() = configuration.minSaveLogLevel

    override val reclaimOrphanedJobsOnStart: Boolean
        get() = configuration.reclaimOrphanedJobsOnStart

    override fun getDatabaseBackoffSeconds(e: SQLException): Int {
        return configuration.getDatabaseBackoffSeconds(e)
    }
}
