package org.tekfive.keep.data

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/** Generates a client-side RFC 9562 UUIDv7 using Kotlin's monotonic in-process generator. */
@OptIn(ExperimentalUuidApi::class)
fun uuidV7(): UUID = Uuid.generateV7().toJavaUuid()
