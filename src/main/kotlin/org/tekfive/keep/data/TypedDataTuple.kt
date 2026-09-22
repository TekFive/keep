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
import org.tekfive.keep.schema.PostgresTableObject
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.util.Collections
import java.util.IdentityHashMap
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
 * subclass constructor properties. Explicit property references take precedence over table property names.
 *
 * Data class hierarchies are supported. For example:
 * ```
 * abstract class BaseData(val name: String) : Data()
 * class UserData(name: String, var email: String) : BaseData(name)
 * ```
 *
 * @param managedColumns Column names managed by framework subclasses (e.g. `id` in [DataTable]),
 *   excluded from Data property mapping and validation.
 */
abstract class TypedDataTuple<ID : Any, D : IdentifiedData<ID>>(
    name: String,
    private val managedColumns: Set<String> = emptySet(),
) : Table(name) {

    /** Column-declared objects are collected separately from overridable table schema hooks. */
    internal val columnPostgresObjects = mutableListOf<PostgresTableObject>()

    internal val columnPreviousNames = linkedMapOf<String, List<String>>()

    private val mutableColumnProperties = linkedMapOf<String, KProperty1<*, *>>()
    private val mutableColumnGroupProperties = IdentityHashMap<ColumnGroup<*>, KProperty1<*, *>>()

    /**
     * Read-only view of explicitly bound properties, keyed by SQL column name on this table.
     * Names keep the binding valid when Exposed replaces a column through nullable/transform.
     * Available during table initialization; legacy name-based bindings are in [columnPropertyMap].
     */
    val columnProperties: Map<String, KProperty1<*, *>> = Collections.unmodifiableMap(mutableColumnProperties)

    /** Read-only view of explicitly bound group properties on this table, keyed by group identity. */
    val columnGroupProperties: Map<ColumnGroup<*>, KProperty1<*, *>> =
        Collections.unmodifiableMap(mutableColumnGroupProperties)

    @Synchronized
    internal fun bindColumnProperty(column: Column<*>, property: KProperty1<*, *>) {
        require(column.table === this) { "Column '${column.name}' must belong to table '$tableName'" }
        val previous = mutableColumnProperties[column.name]
        require(previous == null || previous == property) {
            "Column '${column.name}' is already mapped to '${previous?.name}'"
        }
        mutableColumnProperties[column.name] = property
    }

    @Synchronized
    internal fun bindColumnGroupProperty(group: ColumnGroup<*>, property: KProperty1<*, *>) {
        require(group.columns.all { it.table === this }) {
            "Column group for '${property.name}' must contain only columns from table '$tableName'"
        }
        val previous = mutableColumnGroupProperties[group]
        require(previous == null || previous == property) {
            "Column group is already mapped to '${previous?.name}'"
        }
        mutableColumnGroupProperties[group] = property
    }

    abstract val id: Column<ID>
    /**
     * The concrete [KClass] for type parameter D, resolved at runtime via Java generic type
     * metadata. See [resolveDataClass] for how this handles intermediate abstract table classes.
     */
    @Suppress("UNCHECKED_CAST")
    val dataClass: KClass<D> = resolveDataClass(this.javaClass) as KClass<D>

    private class PropertyMapping(
        val columns: Map<String, Column<*>>,
        val groups: Map<String, ColumnGroup<*>>,
        val properties: Map<String, KProperty1<*, *>>,
    )

    // Property references are available only after subclass column initializers have run.
    private val propertyMapping: PropertyMapping by lazy { buildPropertyMapping() }

    /** Validates the completed table's mapping; also runs automatically on the first read or write. */
    fun validateMapping() {
        propertyMapping
    }

    /**
     * Read-only map of Data property names to columns, including legacy name-based bindings.
     * Access after table initialization; accessing this map validates the complete mapping.
     */
    val columnPropertyMap: Map<String, Column<*>>
        get() = propertyMapping.columns

    /** Read-only map of Data property names to groups; validates the mapping on first access. */
    val columnGroupPropertyMap: Map<String, ColumnGroup<*>>
        get() = propertyMapping.groups

    private fun buildPropertyMapping(): PropertyMapping {
        val mappedColumns = linkedMapOf<String, Column<*>>()
        val mappedGroups = linkedMapOf<String, ColumnGroup<*>>()
        val explicitProperties = linkedMapOf<String, KProperty1<*, *>>()
        val managed = mutableSetOf<Column<*>>()
        val reflectedColumns = mutableListOf<Pair<String, Column<*>>>()

        fun propertyName(property: KProperty1<*, *>): String {
            val receiver = property.parameters.firstOrNull()?.type?.classifier as? KClass<*>
            check(receiver != null && receiver.isSuperclassOf(dataClass)) {
                "Property '$property' does not belong to ${dataClass.simpleName}"
            }
            val name = property.name.removePrefix("_")
            explicitProperties[name] = property
            return name
        }

        fun addColumn(name: String, column: Column<*>) {
            val key = name.removePrefix("_")
            val previous = mappedColumns.putIfAbsent(key, column)
            check((previous == null || previous === column) && key !in mappedGroups) {
                "Multiple columns or column groups map to Data property '$key' on $tableName"
            }
        }

        fun addGroup(name: String, group: ColumnGroup<*>) {
            val key = name.removePrefix("_")
            val previous = mappedGroups.putIfAbsent(key, group)
            check((previous == null || previous === group) && key !in mappedColumns) {
                "Multiple columns or column groups map to Data property '$key' on $tableName"
            }
        }

        for ((group, property) in columnGroupProperties) {
            addGroup(propertyName(property), group)
        }

        val tablePropertyNames = collectTableMappingPropertyNames()
        for (prop in this::class.memberProperties) {
            val classifier = prop.returnType.classifier as? KClass<*> ?: continue
            // Do not evaluate Table helpers such as autoIncColumn, or unrelated getters like ddl.
            if (prop.name !in tablePropertyNames) continue
            if (Column::class.isSuperclassOf(classifier)) {
                val column = callPropertyGetter(prop) as? Column<*> ?: continue
                if (prop.name in managedColumns) managed += column
                else reflectedColumns += prop.name to column
            } else if (ColumnGroup::class.isSuperclassOf(classifier)) {
                val group = callPropertyGetter(prop) as? ColumnGroup<*> ?: continue
                val name = columnGroupProperties[group]?.let(::propertyName)
                    ?: prop.name.removePrefix("_")
                addGroup(name, group)
            }
        }

        // Include property-bound columns even when registered without a Kotlin table property.
        for (column in columns) {
            if (column in managed) continue
            columnProperties[column.name]?.let { addColumn(propertyName(it), column) }
        }
        for ((name, column) in reflectedColumns) {
            if (column in managed) continue
            if (columnProperties[column.name] == null) addColumn(name, column)
        }

        val constructorNames = collectConstructorPropertyNames(dataClass).mapTo(mutableSetOf()) {
            it.removePrefix("_")
        }
        val mappedNames = mappedColumns.keys + mappedGroups.keys
        val missingInData = mappedNames - constructorNames
        val missingInTable = constructorNames - mappedNames
        check(missingInData.isEmpty() && missingInTable.isEmpty()) {
            buildString {
                append("${this@TypedDataTuple::class.simpleName} <-> ${dataClass.simpleName} mapping error: ")
                if (missingInData.isNotEmpty()) append("Columns without matching Data properties: $missingInData. ")
                if (missingInTable.isNotEmpty()) append("Data properties without matching columns: $missingInTable.")
            }
        }
        return PropertyMapping(
            Collections.unmodifiableMap(mappedColumns),
            Collections.unmodifiableMap(mappedGroups),
            Collections.unmodifiableMap(explicitProperties),
        )
    }

    /** Only mapping properties declared below TypedDataTuple, excluding Exposed's own helpers. */
    private fun collectTableMappingPropertyNames(): Set<String> {
        val result = mutableSetOf<String>()
        var current: KClass<*> = this::class
        while (current != TypedDataTuple::class && current != Any::class) {
            for (prop in current.declaredMemberProperties) {
                val classifier = prop.returnType.classifier as? KClass<*>
                if (classifier != null && (Column::class.isSuperclassOf(classifier) ||
                        ColumnGroup::class.isSuperclassOf(classifier))) {
                    result += prop.name
                }
            }
            current = current.supertypes.mapNotNull { it.classifier as? KClass<*> }
                .firstOrNull { TypedDataTuple::class.isSuperclassOf(it) } ?: break
        }
        return result
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
        mappedDataProperties(isVar = false)
    }

    protected val varProperties: Map<String, KProperty1<*, *>> by lazy {
        mappedDataProperties(isVar = true)
    }

    private fun mappedDataProperties(isVar: Boolean): Map<String, KProperty1<*, *>> =
        collectDataProperties(dataClass, isVar).entries.associate { (name, property) ->
            val mapped = propertyMapping.properties[name.removePrefix("_")] ?: property
            // Snapshot and dirty-property keys use the retained getter's name, including when
            // a public property maps an underscore-prefixed constructor backing property.
            mapped.name to mapped
        }

    /**
     * Constructs a [Data] instance of type D from a [ResultRow]. Matches each primary constructor
     * parameter by name to a table column, reads the value from the row, and invokes the
     * constructor.
     */
    open fun map(row: ResultRow): D {
        validateMapping()
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

    private fun cacheKey(id: ID): Any = Pair(this, id)

    @Suppress("UNCHECKED_CAST")
    fun getById(id: ID): D {
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
    fun findById(id: ID?): D? {
        if (id == null) return null
        val key = cacheKey(id)
        val cache = TransactionCache.current
        if (cache != null) {
            return cache.getOrPut(key) { queryById(id) }
        }
        return queryById(id)
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryById(id: ID): D? {
        val pkColumn = primaryKey?.columns?.singleOrNull()
            ?: throw IllegalStateException("findById requires a single-column primary key")
        return db { selectAll().where { (pkColumn as Column<Any?>) eq id }.singleOrNull()?.let { map(it) } }
    }

    @Suppress("UNCHECKED_CAST")
    fun findByIds(ids: List<ID>): List<D> {
        if (ids.isEmpty()) return emptyList()

        val cache = TransactionCache.current
        val resultsById = mutableMapOf<ID, D?>()
        val uncachedIds = mutableListOf<ID>()

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
            val fetchedById = mutableMapOf<ID, D>()
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

    fun findAll(vararg order: Pair<Expression<*>, SortOrder>): List<D> {
        return db {
            var query = selectAll()
            if (order.isNotEmpty()) {
                query = query.orderBy(*order)
            }
            query.map(::map)
        }
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
         * superclass chain from [clazz] up to [TypedDataTuple].
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
                    if (rawType == TypedDataTuple::class.java) {
                        var resolved: java.lang.reflect.Type = bindings[typeParams[1]] ?: typeArgs[1]
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
         * Collects the names of all primary constructor val/var properties from [klass] up through
         * its superclass chain, stopping before [IdentifiedData]. A property is considered a "constructor
         * property" if it is declared in a class AND its name matches a primary constructor
         * parameter of that class.
         */
        private fun collectConstructorPropertyNames(klass: KClass<*>): Set<String> {
            val result = mutableSetOf<String>()
            var current: KClass<*> = klass
            while (current != IdentifiedData::class && current != Any::class) {
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
                    .firstOrNull { IdentifiedData::class.isSuperclassOf(it) } ?: break
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
            while (current != IdentifiedData::class && current != Any::class) {
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
                    .firstOrNull { IdentifiedData::class.isSuperclassOf(it) } ?: break
            }
            return result
        }
    }
}
