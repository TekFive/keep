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

abstract class Data : HasLongId, ToJsonObject {
    private var _id: Long? = null
    private var _snapshot: Map<String, Any?>? = null
    private var _varProperties: List<KProperty1<Any, *>>? = null

    val linkedToDb: Boolean
        get() = _id != null

    val notLinkedToDb: Boolean
        get() = !linkedToDb

    val idOrNull: Long?
        get() = _id

    override val id: Long
        get() = _id ?: throw IllegalStateException("$this has not been saved.")

    /** True if any var property has changed since the last snapshot (or if never snapshotted). */
    val isDirty: Boolean
        get() {
            val snapshot = _snapshot ?: return true
            val props = _varProperties ?: return true
            for (prop in props) {
                if (prop.get(this) != snapshot[prop.name]) return true
            }
            return false
        }

    /** Names of var properties that differ from the last snapshot. Empty if clean. */
    val dirtyProperties: Set<String>
        get() {
            val snapshot = _snapshot ?: return _varProperties?.map { it.name }?.toSet() ?: emptySet()
            val props = _varProperties ?: return emptySet()
            val result = mutableSetOf<String>()
            for (prop in props) {
                if (prop.get(this) != snapshot[prop.name]) result.add(prop.name)
            }
            return result
        }

    fun getSaveAction(): String {
        return if (linkedToDb) {
            "Update"
        } else {
            "Create"
        }
    }

    /** Returns a [JsonObject] containing only the dirty var properties and their current values. */
    fun dirtyPropertiesAsJson(): JsonObject {
        val props = _varProperties ?: return JsonObject(emptyMap())
        val snapshot = _snapshot
        val map = mutableMapOf<String, Any?>()
        for (prop in props) {
            if (snapshot == null || prop.get(this) != snapshot[prop.name]) {
                map[prop.name] = prop.get(this)
            }
        }
        return map.toJsonObject()
    }

    override fun additionalJsonValues(): Map<String, Any?> {
        return if (linkedToDb) mapOf("id" to id) else emptyMap()
    }

    fun linkToDB(id: Long) {
        _id = id
    }

    fun unlinkFromDB() {
        _id = null
        _snapshot = null
        _varProperties = null
    }

    /**
     * Captures the current var property values as the clean baseline.
     * Called by [DataTable] after create, update, and map operations.
     */
    fun snapshot(varProperties: List<KProperty1<Any, *>>, propertyNames: Set<String>? = null) {
        _varProperties = varProperties
        val currentValues = varProperties.associate { it.name to it.get(this) }
        _snapshot = if (propertyNames == null || _snapshot == null) {
            currentValues
        } else {
            _snapshot!!.toMutableMap().also { snapshot ->
                for (propertyName in propertyNames) {
                    snapshot[propertyName] = currentValues[propertyName]
                }
            }
        }
    }

    /**
     * Data-class-style equals: two instances are equal when they are the same concrete type and
     * all primary constructor properties (val and var, across the class hierarchy up to [Data])
     * have equal values. The database `id` is NOT included — equality is based on data content.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        for (prop in constructorProperties(this::class)) {
            if (prop.get(this) != prop.get(other)) return false
        }
        return true
    }

    /**
     * Data-class-style hashCode: combines the hash codes of all primary constructor properties
     * in constructor parameter order using the standard `31 * result + hash` accumulator.
     */
    override fun hashCode(): Int {
        var result = 0
        for (prop in constructorProperties(this::class)) {
            result = 31 * result + (prop.get(this)?.hashCode() ?: 0)
        }
        return result
    }

    /**
     * Additional properties whose values should be masked in [toString] output. Override in
     * subclasses to mark properties as sensitive beyond the built-in name-pattern detection.
     * Values are rendered as `"***"`. Properties ending in "Id" are never masked.
     */
    open val propertiesNotToPrint: List<KProperty<*>>
        get() = emptyList()

    /**
     * Data-class-style toString: `SimpleName(id=1, prop1=value, ...)`. The database `id` is
     * included when the entity is linked to the database.
     *
     * Values are masked (shown as `***`) when:
     * - The property is listed in [propertiesNotToPrint]
     * - The property name suggests a secret (contains "secret", "password", "token", or "key")
     *
     * Properties whose names end in "Id" are never masked regardless of other name patterns.
     *
     * Values whose types are not useful on a single line ([JsonContainer], [ByteArray]) are
     * shown as a type-and-size summary instead of their full content.
     */
    override fun toString(): String {
        val className = this::class.simpleName ?: "Data"
        val sensitiveNames = propertiesNotToPrint.mapTo(mutableSetOf()) { it.name }

        val parts = mutableListOf<String>()
        if (linkedToDb) {
            parts.add("id=$id")
        }

        val sortedProps = constructorProperties(this::class).sortedBy { it.name }
        for (prop in sortedProps) {
            val value = prop.get(this)
            val isIdProperty = prop.name.endsWith("Id")
            val display = when {
                isIdProperty -> value.toString()
                prop.name in sensitiveNames -> "***"
                isSensitiveName(prop.name) -> "***"
                value is JsonContainer -> "${value::class.simpleName}(${value.size})"
                value is ByteArray -> "ByteArray(${value.size})"
                else -> value.toString()
            }
            parts.add("${prop.name}=$display")
        }

        return "$className(${parts.joinToString(", ")})"
    }

    companion object {

        private val sensitiveNamePattern = Regex(
            "(?i)(secret|password|token|credential|key)",
        )

        private fun isSensitiveName(name: String): Boolean {
            return sensitiveNamePattern.containsMatchIn(name)
        }

        @JvmStatic
        fun escapeHtml(input: String?): String {
            if (input.isNullOrBlank()) {
                return ""
            }

            val sb = StringBuilder(input.length)
            for (c in input) {
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&#x27;")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private val propertyCache = ConcurrentHashMap<KClass<*>, List<KProperty1<Any, *>>>()

        /**
         * Returns the primary constructor properties for [klass], ordered by the concrete class's
         * constructor parameter order. Results are cached per class.
         */
        private fun constructorProperties(klass: KClass<*>): List<KProperty1<Any, *>> {
            return propertyCache.getOrPut(klass) { collectConstructorProperties(klass) }
        }

        /**
         * Walks from [klass] up through the superclass chain (stopping before [Data]) to collect
         * all primary constructor val/var properties. A constructor parameter is a "property" only
         * if it is declared with val/var (i.e. it appears in [declaredMemberProperties]).
         * The returned list is ordered by the concrete class's primary constructor parameter order,
         * matching Kotlin data class behavior.
         */
        private fun collectConstructorProperties(klass: KClass<*>): List<KProperty1<Any, *>> {
            // First pass: collect declared properties from the hierarchy keyed by name.
            val declaredProps = mutableMapOf<String, KProperty1<*, *>>()
            var current: KClass<*> = klass
            while (current != Data::class && current != Any::class) {
                val ctor = current.primaryConstructor
                if (ctor != null) {
                    val paramNames = ctor.parameters.mapNotNull { it.name }.toSet()
                    for (prop in current.declaredMemberProperties) {
                        if (prop.name in paramNames && prop.name !in declaredProps) {
                            declaredProps[prop.name] = prop
                        }
                    }
                }
                current = current.supertypes
                    .mapNotNull { it.classifier as? KClass<*> }
                    .firstOrNull { Data::class.isSuperclassOf(it) } ?: break
            }

            // Second pass: order by the concrete class's primary constructor parameters.
            val constructor = klass.primaryConstructor ?: return emptyList()
            val result = mutableListOf<KProperty1<Any, *>>()
            for (param in constructor.parameters) {
                val prop = declaredProps[param.name] ?: continue
                @Suppress("UNCHECKED_CAST")
                result.add(prop as KProperty1<Any, *>)
            }
            return result
        }
    }
}
