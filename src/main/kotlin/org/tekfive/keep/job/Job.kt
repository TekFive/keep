package org.tekfive.keep.job

interface Job {
    fun execute(context: JobContext) : JobResult
}
