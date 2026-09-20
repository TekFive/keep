package org.tekfive.keep.data

/**
 * Creates a shallow copy of this object's constructor properties, retaining its runtime type,
 * database identity, and dirty-property baseline. Unsaved objects remain unsaved.
 * Referenced objects and collections are shared, as with a Kotlin data class copy.
 */
@Suppress("UNCHECKED_CAST")
fun <D : Data> D.copy(): D = copyInstance() as D

/**
 * Creates a shallow copy retaining the runtime type, persisted UUID, and dirty-property baseline.
 * Unsaved copies remain unsaved and receive their own temporary UUID.
 */
@Suppress("UNCHECKED_CAST")
fun <D : UuidData> D.copy(): D = copyInstance() as D
