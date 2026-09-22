package org.tekfive.keep.migration.dynamic

internal data class SqlToken(val text: String, val start: Int, val end: Int, val quoted: Boolean = false)

/** Tokenizes only enough PostgreSQL syntax to adapt Exposed DDL; literals remain intact. */
internal fun sqlTokens(sql: String): List<SqlToken> = buildList {
    var i = 0
    while (i < sql.length) {
        if (sql[i].isWhitespace()) { i++; continue }
        val start = i
        val c = sql[i]
        if (sql.startsWith("--", i) || sql.startsWith("/*", i)) {
            throw IllegalArgumentException("SQL comments are not supported in dynamic migration fragments")
        }
        val dollar = if (c == '$') Regex("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$").find(sql, i)?.takeIf { it.range.first == i }?.value else null
        if (dollar != null) {
            val end = sql.indexOf(dollar, i + dollar.length)
            require(end >= 0) { "Unterminated dollar-quoted SQL body" }
            i = end + dollar.length
            add(SqlToken(sql.substring(start, i), start, i, true))
        } else if (c == '\'' || c == '"') {
            i++
            var closed = false
            val escaped = c == '\'' && start > 0 && sql[start - 1].equals('E', true)
            while (i < sql.length) {
                if (escaped && sql[i] == '\\') { i += 2; continue }
                if (sql[i] == c) {
                    i++
                    if (i < sql.length && sql[i] == c) { i++; continue }
                    closed = true
                    break
                }
                i++
            }
            require(closed) { "Unterminated SQL literal or identifier" }
            add(SqlToken(sql.substring(start, i), start, i, true))
        } else {
            if (c.isLetterOrDigit() || c == '_') {
                i++
                while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] in "_$")) i++
            } else i++
            add(SqlToken(sql.substring(start, i), start, i))
        }
    }
}

internal fun validateSqlFragment(sql: String) {
    require(sql.isNotBlank() && '\u0000' !in sql) { "SQL fragment must not be blank or contain NUL" }
    require(sqlTokens(sql).none { !it.quoted && it.text == ";" }) { "SQL fragment must not contain statement separators" }
}

internal class SqlCursor(val sql: String) {
    val tokens = sqlTokens(sql)
    var position = 0
    val done get() = position == tokens.size
    fun peek(word: String): Boolean = tokens.getOrNull(position)?.let { !it.quoted && it.text.equals(word, true) } == true
    fun take(word: String): Boolean = if (peek(word)) { position++; true } else false
    fun expect(word: String) { require(take(word)) { "Expected $word in: $sql" } }
    fun identifier(): String {
        val token = tokens.getOrNull(position++) ?: error("Missing identifier in: $sql")
        val raw = token.text
        return if (raw.startsWith('"')) raw.substring(1, raw.length - 1).replace("\"\"", "\"")
        else {
            require(!token.quoted && (raw.first().isLetter() || raw.first() == '_')) { "Expected identifier in: $sql" }
            raw.lowercase(java.util.Locale.ROOT)
        }
    }
    fun name(): QualifiedName {
        val first = identifier()
        return if (take(".")) QualifiedName(identifier(), first) else QualifiedName(first)
    }
    fun group(): String {
        expect("(")
        val start = tokens[position - 1].end
        var depth = 1
        while (!done) {
            val token = tokens[position++]
            if (!token.quoted && token.text == "(") depth++
            if (!token.quoted && token.text == ")") {
                depth--
                if (depth == 0) return sql.substring(start, token.start).trim()
            }
        }
        error("Unterminated SQL group: $sql")
    }
    fun rest(): String {
        val start = tokens.getOrNull(position)?.start ?: sql.length
        position = tokens.size
        return sql.substring(start).trim()
    }
    fun until(vararg words: String): String {
        val start = tokens.getOrNull(position)?.start ?: sql.length
        var depth = 0
        while (!done) {
            val token = tokens[position]
            if (!token.quoted && depth == 0 && words.any { token.text.equals(it, true) }) break
            if (!token.quoted && token.text in listOf("(", "[")) depth++
            if (!token.quoted && token.text in listOf(")", "]")) depth--
            position++
        }
        return sql.substring(start, tokens.getOrNull(position)?.start ?: sql.length).trim()
    }
    fun finish() { require(done) { "Unsupported SQL suffix: ${rest()} in $sql" } }
}

internal fun splitSqlList(sql: String): List<String> {
    val cursor = SqlCursor(sql)
    return buildList {
        while (!cursor.done) {
            add(cursor.until(","))
            if (!cursor.done) cursor.expect(",")
        }
    }
}
