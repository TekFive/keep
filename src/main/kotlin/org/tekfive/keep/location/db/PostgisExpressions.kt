package org.tekfive.keep.location.db

import org.jetbrains.exposed.v1.core.*
import org.tekfive.keep.location.Coordinates

private fun QueryBuilder.appendPointLiteral(point: Coordinates) {
    append("ST_SetSRID(ST_MakePoint(${point.longitude}, ${point.latitude}), 4326)::geography")
}

// ---------------------------------------------------------------------------
// PostGIS function expressions
// ---------------------------------------------------------------------------

/**
 * `ST_Distance(a, b)` — returns the distance in meters between two geography expressions.
 */
class StDistance(
    left: Expression<*>,
    right: Expression<*>,
) : CustomFunction<Double>("ST_Distance", DoubleColumnType(), left, right)

/**
 * `ST_Distance(column, point)` — returns the distance in meters between a geography column
 * and a literal [Coordinates] point.
 */
class StDistanceToPoint(
    private val column: Expression<*>,
    private val point: Coordinates,
) : ExpressionWithColumnType<Double>() {
    override val columnType: IColumnType<Double> = DoubleColumnType()
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append("ST_Distance(")
            append(column)
            append(", ")
            appendPointLiteral(point)
            append(")")
        }
    }
}

/**
 * `ST_DWithin(a, b, distance)` — returns true if two geographies are within [meters] of each other.
 * Uses the spatial index for efficient radius queries.
 */
class StDWithin(
    private val left: Expression<*>,
    private val right: Expression<*>,
    private val meters: Double,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append("ST_DWithin(")
            append(left)
            append(", ")
            append(right)
            append(", $meters)")
        }
    }
}

/**
 * `ST_DWithin(column, point, distance)` — returns true if a geography column is within
 * [meters] of a literal [Coordinates] point.
 */
class StDWithinPoint(
    private val column: Expression<*>,
    private val point: Coordinates,
    private val meters: Double,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append("ST_DWithin(")
            append(column)
            append(", ")
            appendPointLiteral(point)
            append(", $meters)")
        }
    }
}

/**
 * `ST_AsText(column)` — returns the WKT representation of a geography.
 */
class StAsText(
    column: Expression<*>,
) : CustomFunction<String>("ST_AsText", VarCharColumnType(256), column)

// ---------------------------------------------------------------------------
// Unit conversion
// ---------------------------------------------------------------------------

private const val METERS_PER_MILE = 1609.344

// ---------------------------------------------------------------------------
// Column extension operators — meters
// ---------------------------------------------------------------------------

/** Returns an expression for `ST_Distance(this, other)` in meters. */
fun Column<out Coordinates?>.distanceInMetersTo(other: Column<out Coordinates?>): StDistance =
    StDistance(this, other)

/** Returns an expression for `ST_Distance(this, point)` in meters. */
fun Column<out Coordinates?>.distanceInMetersTo(point: Coordinates): StDistanceToPoint =
    StDistanceToPoint(this, point)

/** Returns a boolean expression: true if this column is within [meters] of [other]. */
fun Column<out Coordinates?>.isWithinMeters(other: Column<out Coordinates?>, meters: Double): StDWithin =
    StDWithin(this, other, meters)

/** Returns a boolean expression: true if this column is within [meters] of [point]. Uses spatial index. */
fun Column<out Coordinates?>.isWithinMeters(point: Coordinates, meters: Double): StDWithinPoint =
    StDWithinPoint(this, point, meters)

// ---------------------------------------------------------------------------
// Column extension operators — miles
// ---------------------------------------------------------------------------

/** Returns an expression for `ST_Distance(this, other)` in miles. */
fun Column<out Coordinates?>.distanceInMilesTo(other: Column<out Coordinates?>): ExpressionWithColumnType<Double> =
    StDistance(this, other) / METERS_PER_MILE

/** Returns an expression for `ST_Distance(this, point)` in miles. */
fun Column<out Coordinates?>.distanceInMilesTo(point: Coordinates): ExpressionWithColumnType<Double> =
    StDistanceToPoint(this, point) / METERS_PER_MILE

/** Returns a boolean expression: true if this column is within [miles] of [other]. */
fun Column<out Coordinates?>.isWithinMiles(other: Column<out Coordinates?>, miles: Double): StDWithin =
    StDWithin(this, other, miles * METERS_PER_MILE)

/** Returns a boolean expression: true if this column is within [miles] of [point]. Uses spatial index. */
fun Column<out Coordinates?>.isWithinMiles(point: Coordinates, miles: Double): StDWithinPoint =
    StDWithinPoint(this, point, miles * METERS_PER_MILE)

// ---------------------------------------------------------------------------
// Other
// ---------------------------------------------------------------------------

/** Returns the WKT text representation of this geography column. */
fun Column<out Coordinates?>.asText(): StAsText =
    StAsText(this)
