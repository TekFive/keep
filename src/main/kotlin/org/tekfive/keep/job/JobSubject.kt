package org.tekfive.keep.job

import org.tekfive.jfk.ToJsonObject

/** The business entity this job concerns, for example an import, document, or customer. */
data class JobSubject(val subjectType: String, val subjectId: String) : ToJsonObject
