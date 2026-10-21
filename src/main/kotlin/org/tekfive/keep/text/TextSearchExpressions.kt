package org.tekfive.keep.text

import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.intParam
import org.jetbrains.exposed.v1.core.stringParam

/** Parses document text in PostgreSQL. Null [configuration] uses the server's default search configuration. */
fun toTsVector(document: String, configuration: String? = null): ExpressionWithColumnType<TsVector> =
    toTsVector(stringParam(document), configuration)

/** Parses a text expression. Use the same explicit configuration for document and query parsing. */
fun toTsVector(document: Expression<String>, configuration: String? = null): ExpressionWithColumnType<TsVector> =
    CustomFunction("to_tsvector", TsVectorColumnType(), *textSearchArguments(document, configuration))

/** SQL NULL input produces SQL NULL, preserving the expression's Kotlin nullability. */
@JvmName("toNullableTsVector")
fun toTsVector(document: Expression<String?>, configuration: String? = null): ExpressionWithColumnType<TsVector?> =
    CustomFunction<TsVector?>("to_tsvector", TsVectorColumnType().apply { nullable = true }, *textSearchArguments(document, configuration))

/** Parses PostgreSQL tsquery syntax, including operators, prefix matching and weight restrictions. Invalid syntax raises a database error. */
fun toTsQuery(query: String, configuration: String? = null): ExpressionWithColumnType<TsQuery> =
    textSearchQuery("to_tsquery", query, configuration)

/** Parses ordinary text into a query requiring all non-stop words; punctuation is not query syntax. */
fun plainToTsQuery(query: String, configuration: String? = null): ExpressionWithColumnType<TsQuery> =
    textSearchQuery("plainto_tsquery", query, configuration)

/** Parses ordinary text into an ordered phrase query, retaining gaps caused by stop words. */
fun phraseToTsQuery(query: String, configuration: String? = null): ExpressionWithColumnType<TsQuery> =
    textSearchQuery("phraseto_tsquery", query, configuration)

/** Parses web-search input, supporting quoted phrases, OR and minus terms without tsquery syntax errors. */
fun webSearchToTsQuery(query: String, configuration: String? = null): ExpressionWithColumnType<TsQuery> =
    textSearchQuery("websearch_to_tsquery", query, configuration)

/** Native @@ matching, usable with a GIN index on the vector column. SQL NULL does not match in WHERE. */
infix fun Expression<out TsVector?>.matches(query: Expression<out TsQuery?>): Op<Boolean> =
    object : ComparisonOp(this@matches, query, "@@") {}

/** Matches a serialized query using a bound TSQUERY parameter. */
infix fun Expression<out TsVector?>.matches(query: TsQuery): Op<Boolean> =
    this matches QueryParameter(query, TsQueryColumnType())

/** PostgreSQL ts_rank with its standard weights and optional normalization bitmask. */
fun Expression<TsVector>.tsRank(query: Expression<TsQuery>, normalization: Int = 0): ExpressionWithColumnType<Float> =
    CustomFunction("ts_rank", FloatColumnType(), this, query, intParam(normalization))

/** Nullable-vector ranking preserves SQL NULL. */
@JvmName("nullableTsRank")
fun Expression<TsVector?>.tsRank(query: Expression<TsQuery>, normalization: Int = 0): ExpressionWithColumnType<Float?> =
    CustomFunction<Float?>("ts_rank", FloatColumnType().apply { nullable = true }, this, query, intParam(normalization))

/** PostgreSQL cover-density ranking; the vector must retain positions for a useful score. */
fun Expression<TsVector>.tsRankCd(query: Expression<TsQuery>, normalization: Int = 0): ExpressionWithColumnType<Float> =
    CustomFunction("ts_rank_cd", FloatColumnType(), this, query, intParam(normalization))

@JvmName("nullableTsRankCd")
fun Expression<TsVector?>.tsRankCd(query: Expression<TsQuery>, normalization: Int = 0): ExpressionWithColumnType<Float?> =
    CustomFunction<Float?>("ts_rank_cd", FloatColumnType().apply { nullable = true }, this, query, intParam(normalization))

private fun textSearchQuery(function: String, query: String, configuration: String?): ExpressionWithColumnType<TsQuery> =
    CustomFunction(function, TsQueryColumnType(), *textSearchArguments(stringParam(query), configuration))

private fun textSearchArguments(text: Expression<*>, configuration: String?): Array<Expression<*>> =
    if (configuration == null) arrayOf(text) else arrayOf(
        stringParam(configuration).castTo(object : TextColumnType() {
            override fun sqlType(): String = "REGCONFIG"
        }),
        text,
    )
