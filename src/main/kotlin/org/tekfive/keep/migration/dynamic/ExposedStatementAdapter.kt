package org.tekfive.keep.migration.dynamic

/** Strict boundary for Exposed's SQL output. Unknown syntax fails instead of becoming opaque SQL. */
internal object ExposedStatementAdapter {
    fun parse(sql: String): List<PostgresMigrationStatement> {
        val source = sql.trim().removeSuffix(";").trimEnd()
        val c = SqlCursor(source)
        require(c.tokens.none { !it.quoted && it.text == ";" }) { "Expected one DDL statement: $sql" }
        val result = when {
            c.take("CREATE") -> listOf(create(c))
            c.take("ALTER") -> alter(c)
            c.take("DROP") -> drop(c)
            c.take("COMMENT") -> listOf(comment(c))
            else -> throw IllegalArgumentException("Unsupported Exposed migration statement: $sql")
        }
        c.finish()
        return result
    }

    private fun create(c: SqlCursor): PostgresMigrationStatement {
        val replace = if (c.take("OR")) { c.expect("REPLACE"); true } else false
        if (c.take("SCHEMA")) { require(!replace); val exists = ifNotExists(c); return CreateSchema(c.identifier(), exists) }
        if (c.take("TABLE")) {
            require(!replace) { "CREATE OR REPLACE TABLE is unsupported" }
            val exists = ifNotExists(c)
            val table = c.name()
            val columns = mutableListOf<ColumnDefinition>()
            val constraints = mutableListOf<ConstraintDefinition>()
            for (entry in splitSqlList(c.group())) {
                val part = SqlCursor(entry)
                if (listOf("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK").any(part::peek)) constraints += constraint(part)
                else columns += column(part)
                part.finish()
            }
            return CreateTable(table, columns, constraints, exists)
        }
        if (c.take("SEQUENCE")) {
            require(!replace) { "CREATE OR REPLACE SEQUENCE is unsupported" }
            val exists = ifNotExists(c)
            val name = c.name()
            return CreateSequence(name, sequenceOptions(c), exists)
        }
        val unique = c.take("UNIQUE")
        if (c.take("INDEX")) {
            require(!replace) { "CREATE OR REPLACE INDEX is unsupported" }
            val concurrently = c.take("CONCURRENTLY")
            val exists = ifNotExists(c)
            val name = c.name()
            c.expect("ON")
            val table = c.name()
            val method = if (c.take("USING")) c.identifier() else null
            val keys = splitSqlList(c.group()).map(::SqlExpression)
            val include = if (c.take("INCLUDE")) names(c.group()) else emptyList()
            val nullsNotDistinct = if (c.take("NULLS")) { c.expect("NOT"); c.expect("DISTINCT"); true } else false
            val predicate = if (c.take("WHERE")) SqlExpression(c.rest()) else null
            return CreateIndex(IndexDefinition(name, table, keys, unique, method, include, predicate, nullsNotDistinct), concurrently, exists)
        }
        require(!unique) { "Unsupported UNIQUE statement: ${c.sql}" }
        val materialized = c.take("MATERIALIZED")
        if (c.take("VIEW")) {
            val name = c.name()
            val columns = if (c.peek("(")) names(c.group()) else emptyList()
            c.expect("AS")
            val query = SqlQuery(c.rest())
            return when {
                materialized -> { require(!replace && columns.isEmpty()); CreateMaterializedView(name, query) }
                replace -> CreateOrReplaceView(name, query, columns)
                else -> CreateView(name, query, columns)
            }
        }
        throw IllegalArgumentException("Unsupported CREATE statement: ${c.sql}")
    }

    private fun alter(c: SqlCursor): List<PostgresMigrationStatement> {
        if (c.take("TABLE")) {
            val tableIfExists = ifExists(c)
            val table = c.name()
            return splitSqlList(c.rest()).map { action ->
                val a = SqlCursor(action)
                val result = when {
                    a.take("ADD") -> {
                        if (listOf("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK").any(a::peek)) {
                            val definition = constraint(a)
                            val notValid = if (a.take("NOT")) { a.expect("VALID"); true } else false
                            AddConstraint(table, definition, notValid)
                        } else {
                            a.take("COLUMN")
                            val exists = ifNotExists(a)
                            AddColumn(table, column(a), exists)
                        }
                    }
                    a.take("DROP") -> {
                        if (a.take("CONSTRAINT")) {
                            val exists = ifExists(a); val name = a.identifier()
                            DropConstraint(table, name, exists, behavior(a), tableIfExists)
                        } else {
                            a.take("COLUMN")
                            val exists = ifExists(a); val name = a.identifier()
                            DropColumn(table, name, exists, behavior(a))
                        }
                    }
                    a.take("RENAME") -> {
                        if (a.take("TO")) RenameTable(table, a.identifier())
                        else {
                            val constraint = a.take("CONSTRAINT")
                            if (!constraint) a.take("COLUMN")
                            val from = a.identifier(); a.expect("TO"); val to = a.identifier()
                            if (constraint) RenameConstraint(table, from, to) else RenameColumn(table, from, to)
                        }
                    }
                    a.take("ALTER") -> {
                        a.take("COLUMN")
                        val name = a.identifier()
                        when {
                            a.take("TYPE") -> columnType(table, name, a)
                            a.take("SET") -> when {
                                a.take("DATA") -> { a.expect("TYPE"); columnType(table, name, a) }
                                a.take("DEFAULT") -> SetColumnDefault(table, name, SqlExpression(a.rest()))
                                a.take("NOT") -> { a.expect("NULL"); SetNotNull(table, name) }
                                else -> error("Unsupported ALTER COLUMN: $action")
                            }
                            a.take("DROP") -> when {
                                a.take("DEFAULT") -> DropColumnDefault(table, name)
                                a.take("NOT") -> { a.expect("NULL"); DropNotNull(table, name) }
                                else -> error("Unsupported ALTER COLUMN: $action")
                            }
                            else -> error("Unsupported ALTER COLUMN: $action")
                        }
                    }
                    a.take("VALIDATE") -> { a.expect("CONSTRAINT"); ValidateConstraint(table, a.identifier()) }
                    else -> error("Unsupported ALTER TABLE action: $action")
                }
                a.finish()
                require(!tableIfExists || result is DropConstraint) { "Unsupported conditional ALTER TABLE: ${c.sql}" }
                result
            }
        }
        if (c.take("SEQUENCE")) {
            val name = c.name()
            return listOf(if (c.take("OWNED")) {
                c.expect("BY")
                if (c.take("NONE")) SetSequenceOwner(name, null)
                else {
                    val parts = mutableListOf(c.identifier())
                    while (c.take(".")) parts += c.identifier()
                    require(parts.size in 2..3)
                    val column = parts.removeLast()
                    SetSequenceOwner(name, QualifiedName(parts.last(), parts.firstOrNull().takeIf { parts.size == 2 }), column)
                }
            } else AlterSequence(name, sequenceOptions(c)))
        }
        throw IllegalArgumentException("Unsupported ALTER statement: ${c.sql}")
    }

    private fun columnType(table: QualifiedName, name: String, c: SqlCursor): AlterColumnType {
        val type = PostgresType(c.until("USING"))
        val using = if (c.take("USING")) SqlExpression(c.rest()) else null
        return AlterColumnType(table, name, type, using)
    }

    private fun drop(c: SqlCursor): List<PostgresMigrationStatement> {
        val kind = when {
            c.take("MATERIALIZED") -> { c.expect("VIEW"); "MATERIALIZED VIEW" }
            c.take("FOREIGN") -> { c.expect("TABLE"); "FOREIGN TABLE" }
            else -> c.identifier().uppercase()
        }
        val concurrently = kind == "INDEX" && c.take("CONCURRENTLY")
        val exists = ifExists(c)
        val names = mutableListOf(c.name())
        while (c.take(",")) names += c.name()
        val behavior = behavior(c)
        return names.map { name ->
            when (kind) {
                "TABLE" -> DropTable(name, exists, behavior)
                "FOREIGN TABLE" -> DropForeignTable(name, exists, behavior)
                "INDEX" -> DropIndex(name, concurrently, exists, behavior)
                "VIEW" -> DropView(name, exists, behavior)
                "MATERIALIZED VIEW" -> DropMaterializedView(name, exists, behavior)
                "SEQUENCE" -> DropSequence(name, exists, behavior)
                "SCHEMA" -> DropSchema(name.name, exists, behavior)
                "TYPE" -> DropType(name, exists, behavior)
                "EXTENSION" -> DropExtension(name.name, exists, behavior)
                else -> error("Unsupported DROP statement: ${c.sql}")
            }
        }
    }

    private fun column(c: SqlCursor): ColumnDefinition {
        val name = c.identifier()
        val type = PostgresType(c.until("NOT", "NULL", "DEFAULT", "PRIMARY", "UNIQUE", "GENERATED", "COLLATE", "CONSTRAINT", "REFERENCES", "CHECK"))
        var nullable = true
        var default: SqlExpression? = null
        var primary = false
        var unique = false
        var identity: IdentityGeneration? = null
        var generated: SqlExpression? = null
        var collation: QualifiedName? = null
        while (!c.done) {
            when {
                c.take("NOT") -> { c.expect("NULL"); nullable = false }
                c.take("NULL") -> nullable = true
                c.take("DEFAULT") -> default = if (c.take("NULL")) SqlExpression("NULL") else SqlExpression(c.until("NOT", "NULL", "PRIMARY", "UNIQUE", "GENERATED", "COLLATE"))
                c.take("PRIMARY") -> { c.expect("KEY"); primary = true; nullable = false }
                c.take("UNIQUE") -> unique = true
                c.take("COLLATE") -> collation = c.name()
                c.take("GENERATED") -> {
                    val mode = if (c.take("ALWAYS")) IdentityGeneration.ALWAYS else { c.expect("BY"); c.expect("DEFAULT"); IdentityGeneration.BY_DEFAULT }
                    c.expect("AS")
                    if (c.take("IDENTITY")) identity = mode
                    else { require(mode == IdentityGeneration.ALWAYS); generated = SqlExpression(c.group()); c.expect("STORED") }
                }
                else -> error("Unsupported column definition: ${c.sql}")
            }
        }
        return ColumnDefinition(name, type, nullable, default, primary, unique, identity, generated, collation)
    }

    private fun constraint(c: SqlCursor): ConstraintDefinition {
        val name = if (c.take("CONSTRAINT")) c.identifier() else null
        return when {
            c.take("PRIMARY") -> { c.expect("KEY"); ConstraintDefinition.PrimaryKey(name, names(c.group())) }
            c.take("UNIQUE") -> {
                val nulls = if (c.take("NULLS")) { c.expect("NOT"); c.expect("DISTINCT"); true } else false
                ConstraintDefinition.Unique(name, names(c.group()), nulls)
            }
            c.take("CHECK") -> ConstraintDefinition.Check(name, SqlExpression(c.group()))
            c.take("FOREIGN") -> {
                c.expect("KEY"); val columns = names(c.group()); c.expect("REFERENCES")
                val target = c.name(); val referenced = names(c.group())
                var delete: ReferentialAction? = null
                var update: ReferentialAction? = null
                while (c.take("ON")) {
                    val isDelete = c.take("DELETE")
                    if (!isDelete) c.expect("UPDATE")
                    val action = when {
                        c.take("CASCADE") -> ReferentialAction.CASCADE
                        c.take("RESTRICT") -> ReferentialAction.RESTRICT
                        c.take("NO") -> { c.expect("ACTION"); ReferentialAction.NO_ACTION }
                        c.take("SET") -> if (c.take("NULL")) ReferentialAction.SET_NULL else { c.expect("DEFAULT"); ReferentialAction.SET_DEFAULT }
                        else -> error("Unsupported referential action: ${c.sql}")
                    }
                    if (isDelete) delete = action else update = action
                }
                ConstraintDefinition.ForeignKey(name, columns, target, referenced, delete, update)
            }
            else -> error("Unsupported constraint: ${c.sql}")
        }
    }

    private fun comment(c: SqlCursor): PostgresMigrationStatement {
        c.expect("ON")
        val kind = if (c.take("MATERIALIZED")) { c.expect("VIEW"); CommentTarget.MATERIALIZED_VIEW }
            else CommentTarget.valueOf(c.identifier().uppercase())
        val name: QualifiedName
        val column: String?
        if (kind == CommentTarget.COLUMN) {
            val parts = mutableListOf(c.identifier())
            while (c.take(".")) parts += c.identifier()
            require(parts.size in 2..3) { "A column comment requires table.column or schema.table.column" }
            column = parts.removeLast()
            name = QualifiedName(parts.last(), parts.firstOrNull().takeIf { parts.size == 2 })
        } else {
            name = c.name()
            column = null
        }
        c.expect("IS")
        val value = if (c.take("NULL")) null else {
            val token = c.tokens[c.position++].text
            require(token.startsWith("'") && token.endsWith("'"))
            token.substring(1, token.length - 1).replace("''", "'")
        }
        return SetComment(kind, name, value, column)
    }
    private fun sequenceOptions(c: SqlCursor): SequenceOptions {
        var start: Long? = null; var increment: Long? = null; var min: Long? = null; var max: Long? = null
        var cache: Long? = null; var cycle: Boolean? = null
        fun number(): Long { val minus = c.take("-"); val value = c.tokens[c.position++].text.toLong(); return if (minus) -value else value }
        while (!c.done) when {
            c.take("START") -> { c.take("WITH"); start = number() }
            c.take("INCREMENT") -> { c.take("BY"); increment = number() }
            c.take("MINVALUE") -> min = number()
            c.take("MAXVALUE") -> max = number()
            c.take("CACHE") -> cache = number()
            c.take("CYCLE") -> cycle = true
            c.take("NO") -> { c.expect("CYCLE"); cycle = false }
            else -> error("Unsupported sequence options: ${c.sql}")
        }
        return SequenceOptions(start, increment, min, max, cache, cycle)
    }
    private fun names(sql: String) = splitSqlList(sql).map { SqlCursor(it).run { identifier().also { finish() } } }
    private fun ifNotExists(c: SqlCursor): Boolean = if (c.take("IF")) { c.expect("NOT"); c.expect("EXISTS"); true } else false
    private fun ifExists(c: SqlCursor): Boolean = if (c.take("IF")) { c.expect("EXISTS"); true } else false
    private fun behavior(c: SqlCursor) = if (c.take("CASCADE")) DropBehavior.CASCADE else { c.take("RESTRICT"); DropBehavior.RESTRICT }
}
