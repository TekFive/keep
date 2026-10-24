package org.tekfive.keep.audit

import org.tekfive.keep.data.UuidData
import kotlin.reflect.KMutableProperty1

/** Connects an application's record properties to the audit engine without imposing a record superclass. */
class AuditStateBinding<D : UuidData, W : Any>(
    val createdBy: KMutableProperty1<in D, W>,
    val provenances: KMutableProperty1<in D, List<PropertyProvenance<W>>>,
)
