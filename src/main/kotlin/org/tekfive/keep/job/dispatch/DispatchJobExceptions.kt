package org.tekfive.keep.job.dispatch

import org.tekfive.keep.job.JobState
import java.sql.SQLException

internal sealed class JobEndedException(message: String) : Exception(message)

internal class JobNotFoundException(val jobId: Long) : JobEndedException("A job with identifier: $jobId was not found in the database.") {

}
internal class TerminatedStateException(val state: JobState) : JobEndedException("Job has been set to terminated state: $state")

internal class OnCheckInSqlException(val sqlException: SQLException) : Exception(sqlException)
