package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json
import org.tekfive.keep.array.ArrayOverlapOp
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass

/**
 * Interface for enums stored by a declared `id` rather than ordinal.
 *
 * ```
 * enum class Status(override val id: Int, override val displayText: String) : DataEnum {
 *     ACTIVE(1, "Active"),
 *     INACTIVE(2, "Inactive"),
 * }
 * ```
 */
interface DataEnum : ToJsonObject {
    val id: Int
    val displayName: String get()= toString()

    override fun toJsonObject(): JsonObject {
        val name = toString()
        return json {
            "id" set id
            "name" set name
            "displayName" set displayName
        }
    }

    companion object {
        /**
         * Validates that no two enum constants share the same [DataEnum.id].
         * Call from your enum's companion init to catch duplicates at class-loading time:
         * ```
         * enum class Status(override val id: Int) : DataEnum {
         *     ACTIVE(1), INACTIVE(2);
         *     companion object { init { DataEnum.validate(entries) } }
         * }
         * ```
         */
        fun <T> validate(values: Collection<T>) where T : Enum<T>, T : DataEnum {
            val duplicates = values.groupBy { it.id }.filter { it.value.size > 1 }
            check(duplicates.isEmpty()) {
                val name = values.first()::class.simpleName
                val detail = duplicates.map { (id, enums) -> "$id -> ${enums.map { it.name }}" }
                "$name has duplicate DataEnum ids: $detail"
            }
        }
    }
}

/**
 * Provides the enum constants and derived [KClass] for a [DataEnum] type.
 * Implemented by [DataEnumColumnType] and available for standalone use.
 */
interface DataEnumType<E> : FromJsonObject<E> where E : Enum<E>, E : DataEnum {
    val dataEnumValues: Array<E>

    val enumType: KClass<E>
        get() = dataEnumValues[0].javaClass.kotlin

    override fun fromJson(json: JsonObject): E {
        val id = json["id"].reqInt
        return dataEnumValues.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Invalid id $id for data enum ${enumType.simpleName}")
    }

    fun findById(id: Int): E {
        return dataEnumValues.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("No ${dataEnumValues[0].javaClass.simpleName} enum exists with id $id")
    }

    fun map(id: Int): E {
        return dataEnumValues.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("No ${dataEnumValues[0].javaClass.simpleName} enum exists with id $id")
    }

    fun mapOptional(id: Int?): E? {
        return dataEnumValues.firstOrNull { it.id == id }
    }

    fun mapOptionalName(name: String?): E? {
        return dataEnumValues.firstOrNull { it.toString() == name }
    }
}

/**
 * Exposed [ColumnType] that maps a [DataEnum] enum to an INTEGER column using [DataEnum.id].
 *
 * Can be used as an enum's companion object for direct access to [map] and [mapOptional]:
 * ```
 * enum class Status(override val id: Int, override val displayText: String) : DataEnum {
 *     ACTIVE(1, "Active"),
 *     INACTIVE(2, "Inactive");
 *     companion object : DataEnumColumnType<Status>()
 * }
 *
 * val status = Status.map(1) // ACTIVE
 * ```
 *
 * Or created programmatically via [Table.dataEnum].
 */
@Suppress("UNCHECKED_CAST")
open class DataEnumColumnType<E>(
    values: Array<E>? = null,
) : ColumnType<E>(), DataEnumType<E> where E : Enum<E>, E : DataEnum {

    override val dataEnumValues: Array<E> = values
        ?: (resolveEnumClass(this.javaClass) as Class<E>).enumConstants

    private val idToEnum: Map<Int, E> = dataEnumValues.associateBy { it.id }
    private val enumName: String = enumType.simpleName ?: enumType.qualifiedName ?: "unknown"

    init {
        DataEnum.validate(dataEnumValues.toList())
    }

    override fun sqlType(): String = "INTEGER"

    override fun valueFromDB(value: Any): E {
        val id = when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> error("Cannot convert $value to $enumName id")
        }
        return map(id)
    }

    override fun notNullValueToDB(value: E): Any = value.id

    companion object {
        private fun resolveEnumClass(clazz: Class<*>): Class<*> {
            var current = clazz
            while (current != Any::class.java) {
                val genericSuper = current.genericSuperclass
                if (genericSuper is ParameterizedType && (genericSuper.rawType as Class<*>) == DataEnumColumnType::class.java) {
                    return genericSuper.actualTypeArguments[0] as Class<*>
                }
                current = current.superclass ?: break
            }
            error("Cannot resolve enum class for ${clazz.name}")
        }
    }
}

/** Registers an INTEGER column that maps to a [DataEnum] enum by its [DataEnum.id]. */
inline fun <reified E> Table.dataEnum(name: String): Column<E> where E : Enum<E>, E : DataEnum {
    return registerColumn(name, DataEnumColumnType(enumValues<E>()))
}

/** Registers an INTEGER[] array column that stores a list of [DataEnum] enums by their ids. */
inline fun <reified E> Table.dataEnumList(name: String): Column<List<E>> where E : Enum<E>, E : DataEnum {
    return array(name, DataEnumColumnType(enumValues<E>()))
}

/**
 * PostgreSQL array overlap — checks if the enum-list column contains any of the given values.
 *
 * Generates: `column && ARRAY[id1, id2, ...]::integer[]`
 *
 * Usage: `TalentsTable.workArrangements enumIntersects listOf(WorkArrangement.REMOTE)`
 */
infix fun <E> ExpressionWithColumnType<out List<E>?>.enumIntersects(values: List<E>): Op<Boolean>
    where E : Enum<E>, E : DataEnum =
    ArrayOverlapOp(this, values.map { it.id })
