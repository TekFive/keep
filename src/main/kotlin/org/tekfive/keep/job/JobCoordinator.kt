package org.tekfive.keep.job

import org.tekfive.keep.job.db.DatabaseGatekeeper
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.job.dispatch.Dispatcher
import org.tekfive.keep.db.db
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

class JobCoordinator(
    val systemIdentifier: String,
    val registry: JobRegistry,
    configuration: JobConfiguration,
) : Runnable {

    val configuration: JobConfiguration = JobConfigurationGuard(configuration)

    @Volatile
    private var coordinationThread: Thread? = null

    private val candidateQueue = LinkedBlockingQueue<Pair<Long, JobSpec>>()

    private val databaseGatekeeper = DatabaseGatekeeper(configuration)

    private val dispatchers = CopyOnWriteArrayList<Dispatcher>()

    private val timeoutMonitor = JobTimeoutMonitor(
        configuration = this.configuration,
        registry = registry,
        databaseGatekeeper = databaseGatekeeper,
        jobsTable = JobRecordsTable,
    )

    fun start() {
        if (coordinationThread?.let { it.isAlive } != true) {
            val thread = Thread(this, "Job Coordinator")
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

    @Synchronized
    override fun run() {
        while (Thread.currentThread() == coordinationThread) {
            adjustDispatchersIfNecessary()

            timeoutMonitor.sweep()

            scheduleJobs()

            val maxCandidates = configuration.maximumCandidatesBuffer - candidateQueue.size
            if (maxCandidates > 0) {
                val jobTypeIds = registry.jobTypeIds
                if (jobTypeIds.isNotEmpty()) {
                    try {
                        val candidateIdsTypes = databaseGatekeeper { db { JobRecordsTable.getJobIdStartCandidates(maxCandidates, jobTypeIds, System.currentTimeMillis()) } }
                        val candidateIdsSpecs = candidateIdsTypes.mapNotNull { (id, typeId) -> registry[typeId]?.let { spec -> id to spec } }
                        candidateQueue.addAll(candidateIdsSpecs)
                    } catch (e: SQLException) {
                        databaseGatekeeper.onSQLException(e)
                    }
                }
            }

            if (Thread.currentThread() == coordinationThread) {
                try {
                    Thread.sleep(configuration.pollSeconds * 1000L)
                } catch (_: InterruptedException) {
                    if (Thread.currentThread() != coordinationThread) {
                        break
                    }
                }
            } else {
                break
            }
        }
    }

    private fun scheduleJobs() {
        for (spec in registry.scheduledJobSpecs) {
            try {
                db {
                    if (!JobRecordsTable.hasNonTerminatedJob(spec.jobTypeIdentifier)) {
                        val lastEndedAt = JobRecordsTable.getLastEndedScheduledAt(spec.jobTypeIdentifier)
                        val nextJob = spec.schedule(lastEndedAt)
                        if (nextJob != null) {
                            try {
                                JobRecordsTable.create(nextJob)
                            } catch (e: SQLException) {
                                if (e.sqlState != "23505") {
                                    throw e
                                }
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                databaseGatekeeper.onSQLException(e)
            }
        }
    }

    private fun adjustDispatchersIfNecessary() {
        dispatchers.removeIf { !it.isAlive }
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

    override fun getDatabaseBackoffSeconds(e: SQLException): Int {
        return configuration.getDatabaseBackoffSeconds(e)
    }
}
