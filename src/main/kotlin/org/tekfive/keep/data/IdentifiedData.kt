package org.tekfive.keep.data

import org.tekfive.jfk.JsonContainer
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.toJsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.primaryConstructor

/** Shared entity behavior for KEEP data objects with a strongly typed primary key. */
abstract class IdentifiedData<ID : Any> : HasId, ToJsonObject {
    private var databaseId: ID? = null
    private var snapshot: Map<String, Any?>? = null
    private var mutableProperties: List<KProperty1<Any, *>>? = null

    val linkedToDb: Boolean
        get() = databaseId != null

    val notLinkedToDb: Boolean
        get() = !linkedToDb

    open val idOrNull: ID?
        get() = databaseId

    override val id: ID
        get() = databaseId ?: throw IllegalStateException("$this has not been saved.")

    /** True if any var property has changed since the last snapshot (or if never snapshotted). */
    val isDirty: Boolean
        get() {
            val snapshot = snapshot ?: return true
            val properties = mutableProperties ?: return true
            return properties.any { it.get(this) != snapshot[it.name] }
        }

    /** Names of var properties that differ from the last snapshot. Empty if clean. */
    val dirtyProperties: Set<String>
        get() {
            val snapshot = snapshot
                ?: return mutableProperties?.mapTo(mutableSetOf()) { it.name } ?: emptySet()
            val properties = mutableProperties ?: return emptySet()
            return properties
                .filterTo(mutableSetOf()) { it.get(this) != snapshot[it.name] }
                .mapTo(mutableSetOf()) { it.name }
        }

    fun getSaveAction(): String = if (linkedToDb) "Update" else "Create"

    /** Returns a [JsonObject] containing only the dirty var properties and their current values. */
    fun dirtyPropertiesAsJson(): JsonObject {
        val properties = mutableProperties ?: return JsonObject(emptyMap())
        val snapshot = snapshot
        return properties
            .filter { snapshot == null || it.get(this) != snapshot[it.name] }
            .associate { it.name to it.get(this) }
            .toJsonObject()
    }

    override fun additionalJsonValues(): Map<String, Any?> =
        if (linkedToDb) mapOf("id" to id) else emptyMap()

    open fun linkToDB(id: ID) {
        databaseId = id
    }

    fun unlinkFromDB() {
        databaseId = null
        snapshot = null
        mutableProperties = null
    }

    /** Captures the current var property values as the clean baseline. */
    fun snapshot(varProperties: List<KProperty1<Any, *>>, propertyNames: Set<String>? = null) {
        mutableProperties = varProperties
        val currentValues = varProperties.associate { it.name to it.get(this) }
        snapshot = if (propertyNames == null || snapshot == null) {
            currentValues
        } else {
            snapshot!!.toMutableMap().also { existing ->
                propertyNames.forEach { propertyName ->
                    existing[propertyName] = currentValues[propertyName]
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        return constructorProperties(this::class).all { it.get(this) == it.get(other) }
    }

    override fun hashCode(): Int {
        var result = 0
        constructorProperties(this::class).forEach { property ->
            result = 31 * result + (property.get(this)?.hashCode() ?: 0)
        }
        return result
    }

    /** Additional properties whose values should be masked in [toString] output. */
    open val propertiesNotToPrint: List<KProperty<*>>
        get() = emptyList()

    override fun toString(): String {
        val className = this::class.simpleName ?: "Data"
        val sensitiveNames = propertiesNotToPrint.mapTo(mutableSetOf()) { it.name }
        val parts = mutableListOf<String>()
        if (linkedToDb) parts += "id=$id"

        constructorProperties(this::class).sortedBy { it.name }.forEach { property ->
            val value = property.get(this)
            val display = when {
                property.name.endsWith("Id") -> value.toString()
                property.name in sensitiveNames -> "***"
                isSensitiveName(property.name) -> "***"
                value is JsonContainer -> "${value::class.simpleName}(${value.size})"
                value is ByteArray -> "ByteArray(${value.size})"
                else -> value.toString()
            }
            parts += "${property.name}=$display"
        }

        return "$className(${parts.joinToString(", ")})"
    }

    companion object {
        private val sensitiveNamePattern = Regex("(?i)(secret|password|token|credential|key)")
        private val propertyCache = ConcurrentHashMap<KClass<*>, List<KProperty1<Any, *>>>()

        internal fun escapeHtml(input: String?): String {
            if (input.isNullOrBlank()) return ""
            return buildString(input.length) {
                input.forEach { character ->
                    when (character) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        '\'' -> append("&#x27;")
                        else -> append(character)
                    }
                }
            }
        }

        private fun isSensitiveName(name: String): Boolean =
            sensitiveNamePattern.containsMatchIn(name)

        private fun constructorProperties(klass: KClass<*>): List<KProperty1<Any, *>> =
            propertyCache.getOrPut(klass) { collectConstructorProperties(klass) }

        private fun collectConstructorProperties(klass: KClass<*>): List<KProperty1<Any, *>> {
            val declaredProperties = mutableMapOf<String, KProperty1<*, *>>()
            var current: KClass<*> = klass
            while (current != IdentifiedData::class && current != Any::class) {
                current.primaryConstructor?.let { constructor ->
                    val parameterNames = constructor.parameters.mapNotNull { it.name }.toSet()
                    current.declaredMemberProperties.forEach { property ->
                        if (property.name in parameterNames && property.name !in declaredProperties) {
                            declaredProperties[property.name] = property
                        }
                    }
                }
                current = current.supertypes
                    .mapNotNull { it.classifier as? KClass<*> }
                    .firstOrNull { IdentifiedData::class.isSuperclassOf(it) }
                    ?: break
            }

            val constructor = klass.primaryConstructor ?: return emptyList()
            return constructor.parameters.mapNotNull { parameter ->
                @Suppress("UNCHECKED_CAST")
                declaredProperties[parameter.name] as? KProperty1<Any, *>
            }
        }
    }
}
