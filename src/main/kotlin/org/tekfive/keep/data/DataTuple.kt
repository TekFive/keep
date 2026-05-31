package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tekfive.keep.db.TransactionCache
import org.tekfive.keep.db.db
import org.tekfive.keep.paged.PagedResult
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Abstract base for table classes that automatically map between Exposed table columns and [Data]
 * subclass constructor properties using reflection. Column-to-property matching is by name.
 *
 * Data class hierarchies are supported. For example:
 * ```
 * abstract class BaseData(val name: String) : Data()
 * class UserData(name: String, var email: String) : BaseData(name)
 * ```
 *
 * @param managedColumns Column names managed by framework subclasses (e.g. `id` in [DataTable]),
 *   excluded from Data property mapping and validation. Passed as a constructor parameter to
 *   avoid initialization-order issues with the init validation block.
 */
abstract class DataTuple<D : Data>(
    name: String,
    private val managedColumns: Set<String> = emptySet(),
) : Table(name) {


    abstract val id: Column<Long>
    /**
     * The concrete [KClass] for type parameter D, resolved at runtime via Java generic type
     * metadata. See [resolveDataClass] for how this handles intermediate abstract table classes.
     */
    @Suppress("UNCHECKED_CAST")
    val dataClass: KClass<D> = resolveDataClass(this.javaClass) as KClass<D>

    init {
        // Validate that every data Column property on this table has a matching primary
        // constructor val/var property in D's class hierarchy, and vice versa.
        // This uses class metadata (names and types), not instance values, so it works during
        // superclass construction before subclass column properties are initialized.
        val constructorPropertyNames = collectConstructorPropertyNames(dataClass)
        val columnPropertyNames = collectColumnPropertyNames(this::class) - managedColumns
        val columnGroupPropertyNames = collectColumnGroupPropertyNames(this::class)
        val allTablePropertyNames = columnPropertyNames + columnGroupPropertyNames

        val normalizedConstructorNames = constructorPropertyNames.mapTo(mutableSetOf()) { it.removePrefix("_") }
        val missingInData = allTablePropertyNames - normalizedConstructorNames
        val missingInTable = normalizedConstructorNames - allTablePropertyNames

        check(missingInData.isEmpty() && missingInTable.isEmpty()) {
            buildString {
                append("${this@DataTuple::class.simpleName} <-> ${dataClass.simpleName} mapping error: ")
                if (missingInData.isNotEmpty()) append("Columns without matching Data properties: $missingInData. ")
                if (missingInTable.isNotEmpty()) append("Data properties without matching columns: $missingInTable.")
            }
        }
    }

    /**
     * Lazy map of Kotlin property name to [Column] instance. Built after construction completes
     * (so subclass column properties are initialized). Excludes [managedColumns].
     */
    protected val columnPropertyMap: Map<String, Column<*>> by lazy {
        val result = mutableMapOf<String, Column<*>>()
        for (prop in this::class.memberProperties) {
            if (prop.name in managedColumns) continue
            // Filter by return type BEFORE calling the getter to avoid triggering
            // Exposed internals (e.g. Table.ddl) that require a transaction context.
            val classifier = prop.returnType.classifier
            if (classifier !is KClass<*> || !Column::class.isSuperclassOf(classifier)) continue
            val value = callPropertyGetter(prop) as? Column<*> ?: continue
            result[prop.name] = value
        }
        result
    }

    protected val columnGroupPropertyMap: Map<String, ColumnGroup<*>> by lazy {
        val result = mutableMapOf<String, ColumnGroup<*>>()
        for (prop in this::class.memberProperties) {
            val classifier = prop.returnType.classifier
            if (classifier !is KClass<*> || !ColumnGroup::class.isSuperclassOf(classifier)) continue
            val value = callPropertyGetter(prop) as? ColumnGroup<*> ?: continue
            result[prop.name] = value
        }
        result
    }

    /** Calls the property getter, handling @JvmField properties on objects (static fields with no receiver). */
    private fun callPropertyGetter(prop: KProperty1<*, *>): Any? {
        // @JvmField properties have no Java getter — their FieldGetter rejects receiver arguments.
        return if (prop.javaGetter == null) {
            prop.javaField?.get(this)
        } else {
            prop.getter.call(this)
        }
    }

    /**
     * Resolves a table [Column] for a Data constructor property name. Handles underscore-prefixed
     * backing fields (e.g. `_state`) by falling back to the stripped name (`state`).
     */
    protected fun resolveColumn(propertyName: String): Column<*>? {
        return columnPropertyMap[propertyName]
            ?: if (propertyName.startsWith("_")) columnPropertyMap[propertyName.removePrefix("_")] else null
    }

    /**
     * Resolves a [ColumnGroup] for a Data constructor property name, with the same underscore
     * fallback as [resolveColumn].
     */
    protected fun resolveColumnGroup(propertyName: String): ColumnGroup<*>? {
        return columnGroupPropertyMap[propertyName]
            ?: if (propertyName.startsWith("_")) columnGroupPropertyMap[propertyName.removePrefix("_")] else null
    }

    protected val valProperties: Map<String, KProperty1<*, *>> by lazy {
        collectDataProperties(dataClass, isVar = false)
    }

    protected val varProperties: Map<String, KProperty1<*, *>> by lazy {
        collectDataProperties(dataClass, isVar = true)
    }

    /**
     * Constructs a [Data] instance of type D from a [ResultRow]. Matches each primary constructor
     * parameter by name to a table column, reads the value from the row, and invokes the
     * constructor.
     */
    open fun map(row: ResultRow): D {
        val constructor = dataClass.primaryConstructor
            ?: throw IllegalStateException("${dataClass.qualifiedName} must have a primary constructor")

        val args = constructor.parameters.associateWith { param ->
            val column = resolveColumn(param.name!!)
            if (column != null) {
                val value = row[column]
                if (value == null && !param.type.isMarkedNullable) {
                    val classifier = param.type.classifier
                    if (classifier is KClass<*> && List::class.isSuperclassOf(classifier)) {
                        emptyList<Any>()
                    } else {
                        value
                    }
                } else {
                    value
                }
            } else {
                val group = resolveColumnGroup(param.name!!)
                    ?: throw IllegalStateException("No column or column group found for parameter '${param.name}'")
                group.map(row)
            }
        }

        return constructor.callBy(args)
    }

    fun mapOrNull(row: ResultRow): D? {
        return if (row.getOrNull(id) != null) {
            map(row)
        } else {
            null
        }
    }

    private fun cacheKey(id: Long): Any = Pair(this, id)

    @Suppress("UNCHECKED_CAST")
    fun getById(id: Long): D {
        val cache = TransactionCache.current
        val key = cacheKey(id)
        cache?.get<D>(key)?.let { return it }

        val pkColumn = primaryKey?.columns?.singleOrNull()
            ?: throw IllegalStateException("getById requires a single-column primary key")
        val result = db{ selectAll().where { (pkColumn as Column<Any?>) eq id }.single().let { map(it) } }
        cache?.put(key, result)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    fun findById(id: Long?): D? {
        if (id == null) return null
        val key = cacheKey(id)
        val cache = TransactionCache.current
        if (cache != null) {
            return cache.getOrPut(key) { queryById(id) }
        }
        return queryById(id)
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryById(id: Long): D? {
        val pkColumn = primaryKey?.columns?.singleOrNull()
            ?: throw IllegalStateException("findById requires a single-column primary key")
        return db { selectAll().where { (pkColumn as Column<Any?>) eq id }.singleOrNull()?.let { map(it) } }
    }

    @Suppress("UNCHECKED_CAST")
    fun findByIds(ids: List<Long>): List<D> {
        if (ids.isEmpty()) return emptyList()

        val cache = TransactionCache.current
        val resultsById = mutableMapOf<Long, D?>()
        val uncachedIds = mutableListOf<Long>()

        for (id in ids) {
            val key = cacheKey(id)
            if (cache != null && cache.containsKey(key)) {
                resultsById[id] = cache.get<D>(key)
            } else if (!resultsById.containsKey(id)) {
                uncachedIds.add(id)
            }
        }

        if (uncachedIds.isNotEmpty()) {
            val pkColumn = primaryKey?.columns?.singleOrNull()
                ?: throw IllegalStateException("findByIds requires a single-column primary key")
            val fetchedById = mutableMapOf<Long, D>()
            val distinctUncachedIds = uncachedIds.distinct()
            db {
                for (row in selectAll().where { (pkColumn as Column<Any?>) inList distinctUncachedIds }) {
                    val data = map(row)
                    fetchedById[data.id] = data
                }
                for (id in distinctUncachedIds) {
                    val data = fetchedById[id]
                    cache?.put(cacheKey(id), data)
                    resultsById[id] = data
                }
            }
        }

        return ids.mapNotNull { resultsById[it] }
    }


    @Suppress("UNCHECKED_CAST")
    fun <T : Any> findByUnique(value: T?, column: Column<T>): D? {
        if (value == null) return null
        return db { selectAll().where { (column as Column<Any?>) eq (value as Any?) }.singleOrNull()?.let { map(it) } }
    }

    @Suppress("UNCHECKED_CAST")
    fun findByUnique(predicate: Op<Boolean>): D? {
        return db { selectAll().where(predicate).singleOrNull()?.let { map(it) } }
    }

    fun findWhere(predicate: Op<Boolean>, vararg order: Pair<Expression<*>, SortOrder>): List<D> {
        return db {
            var query = selectAll().where(predicate)
            if (order.isNotEmpty()) {
                query = query.orderBy(*order)
            }
            query.map(::map)
        }
    }


    fun findPaged(
        predicate: Op<Boolean>,
        page: Int,
        size: Int,
        vararg order: Pair<Expression<*>, SortOrder>,
    ): PagedResult<D> {
        val total = count(predicate)
        val offset = (page - 1) * size
        var query = selectAll().where(predicate)
        if (order.isNotEmpty()) query = query.orderBy(*order)
        val data = query.limit(size).offset(offset.toLong()).map(::map)
        return PagedResult(data, total, page, size)
    }

    fun count(predicate: Op<Boolean>): Int {
        return db { selectAll().where(predicate).count().toInt() }
    }

    fun rowExists(predicate: Op<Boolean>): Boolean {
        return count(predicate) > 0
    }


    companion object {
        /**
         * Resolves the concrete [KClass] for type parameter D by walking the Java generic
         * superclass chain from [clazz] up to [DataTuple].
         *
         * Handles intermediate abstract classes by tracking type variable bindings at each level.
         * For example, given:
         * ```
         * abstract class BaseTable<D : Data>(name: String) : DataTable<D>(name)
         * class UsersTable : BaseTable<UserData>("users")
         * ```
         * Walking from UsersTable:
         * 1. UsersTable → BaseTable<UserData>: binds BaseTable.D = UserData
         * 2. BaseTable  → DataTable<D>:        D is a TypeVariable, resolved via bindings to UserData
         */
        private fun resolveDataClass(clazz: Class<*>): KClass<*> {
            val bindings = mutableMapOf<TypeVariable<*>, java.lang.reflect.Type>()
            var current: Class<*> = clazz
            while (true) {
                val superclass = current.superclass ?: break
                val genericSuper = current.genericSuperclass
                if (genericSuper is ParameterizedType) {
                    val rawType = genericSuper.rawType as Class<*>
                    val typeParams = rawType.typeParameters
                    val typeArgs = genericSuper.actualTypeArguments
                    // Record bindings, resolving any type variables through previously seen bindings.
                    for (i in typeParams.indices) {
                        var resolved = typeArgs[i]
                        while (resolved is TypeVariable<*>) {
                            resolved = bindings[resolved] ?: break
                        }
                        bindings[typeParams[i]] = resolved
                    }
                    if (rawType == DataTuple::class.java) {
                        var resolved: java.lang.reflect.Type = bindings[typeParams[0]] ?: typeArgs[0]
                        while (resolved is TypeVariable<*>) {
                            resolved = bindings[resolved] ?: break
                        }
                        return (resolved as Class<*>).kotlin
                    }
                }
                current = superclass
            }
            throw IllegalStateException("Cannot determine Data class type for ${clazz.name}")
        }

        /**
         * Collects Column property names declared in [DataTuple] subclasses, walking from
         * [klass] up to (but not including) [DataTuple]. This excludes internal Column
         * properties from [Table] and [DataTuple].
         */
        private fun collectColumnPropertyNames(klass: KClass<*>): Set<String> {
            val result = mutableSetOf<String>()
            var current: KClass<*> = klass
            while (current != DataTuple::class && current != Any::class) {
                for (prop in current.declaredMemberProperties) {
                    if (prop.returnType.classifier.let { it is KClass<*> && Column::class.isSuperclassOf(it) }) {
                        result.add(prop.name)
                    }
                }
                current = current.supertypes
                    .mapNotNull { it.classifier as? KClass<*> }
                    .firstOrNull { DataTuple::class.isSuperclassOf(it) } ?: break
            }
            return result
        }

        /**
         * Collects [ColumnGroup] property names declared in [DataTuple] subclasses, walking from
         * [klass] up to (but not including) [DataTuple].
         */
        private fun collectColumnGroupPropertyNames(klass: KClass<*>): Set<String> {
            val result = mutableSetOf<String>()
            var current: KClass<*> = klass
            while (current != DataTuple::class && current != Any::class) {
                for (prop in current.declaredMemberProperties) {
                    if (prop.returnType.classifier.let { it is KClass<*> && ColumnGroup::class.isSuperclassOf(it) }) {
                        result.add(prop.name)
                    }
                }
                current = current.supertypes
                    .mapNotNull { it.classifier as? KClass<*> }
                    .firstOrNull { DataTuple::class.isSuperclassOf(it) } ?: break
            }
            return result
        }

        /**
         * Collects the names of all primary constructor val/var properties from [klass] up through
         * its superclass chain, stopping before [Data]. A property is considered a "constructor
         * property" if it is declared in a class AND its name matches a primary constructor
         * parameter of that class.
         */
        private fun collectConstructorPropertyNames(klass: KClass<*>): Set<String> {
            val result = mutableSetOf<String>()
            var current: KClass<*> = klass
            while (current != Data::class && current != Any::class) {
                val constructor = current.primaryConstructor
                if (constructor != null) {
                    val paramNames = constructor.parameters.mapNotNull { it.name }.toSet()
                    for (prop in current.declaredMemberProperties) {
                        if (prop.name in paramNames) {
                            result.add(prop.name)
                        }
                    }
                }
                current = current.supertypes
                    .mapNotNull { it.classifier as? KClass<*> }
                    .firstOrNull { Data::class.isSuperclassOf(it) } ?: break
            }
            return result
        }

        /**
         * Like [collectConstructorPropertyNames] but returns the actual [KProperty1] instances,
         * filtered by mutability. When [isVar] is true, collects only `var` properties; when false,
         * only `val` properties.
         */
        internal fun collectDataProperties(klass: KClass<*>, isVar: Boolean): Map<String, KProperty1<*, *>> {
            val result = mutableMapOf<String, KProperty1<*, *>>()
            var current: KClass<*> = klass
            while (current != Data::class && current != Any::class) {
                val constructor = current.primaryConstructor
                if (constructor != null) {
                    val paramNames = constructor.parameters.mapNotNull { it.name }.toSet()
                    for (prop in current.declaredMemberProperties) {
                        if (prop.name in paramNames && prop.name !in result) {
                            if ((prop is KMutableProperty1) == isVar) {
                                result[prop.name] = prop
                            }
                        }
                    }
                }
                current = current.supertypes
                    .mapNotNull { it.classifier as? KClass<*> }
                    .firstOrNull { Data::class.isSuperclassOf(it) } ?: break
            }
            return result
        }
    }
}
