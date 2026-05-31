package org.tekfive.keep.job

sealed class JobResult : Exception()

class JobWaiting(val infoMessage: String? = null) : JobResult()

class JobCompleted(val infoMessage: String? = null) : JobResult()

class JobFailed(val errorMessage: String? = null, val retryIfAllowed: Boolean = false) : JobResult()
