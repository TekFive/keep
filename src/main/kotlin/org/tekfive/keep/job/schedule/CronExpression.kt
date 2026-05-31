package org.tekfive.keep.job.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Standard 5-field cron expression parser.
 *
 * Fields: minute (0-59), hour (0-23), day-of-month (1-31), month (1-12), day-of-week (0-6, Sunday=0).
 *
 * Supports: wildcards (`*`), ranges (`1-5`), lists (`1,3,5`), and steps (`*\/5`, `1-10/2`).
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val dayOfMonthWildcard: Boolean,
    private val dayOfWeekWildcard: Boolean,
) {

    fun nextAfter(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        var dt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            .plusMinutes(1)
            .withSecond(0)
            .withNano(0)

        val limit = dt.plusYears(4)

        while (dt.isBefore(limit)) {
            if (dt.monthValue !in months) {
                dt = dt.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0)
                continue
            }
            if (!matchesDay(dt)) {
                dt = dt.plusDays(1).withHour(0).withMinute(0)
                continue
            }
            if (dt.hour !in hours) {
                dt = dt.plusHours(1).withMinute(0)
                continue
            }
            if (dt.minute !in minutes) {
                dt = dt.plusMinutes(1)
                continue
            }
            return dt.toInstant().toEpochMilli()
        }

        throw IllegalStateException("No matching time found within 4 years for cron expression")
    }

    private fun matchesDay(dt: ZonedDateTime): Boolean {
        // java.time: Monday=1 .. Sunday=7; cron: Sunday=0, Monday=1 .. Saturday=6
        val cronDow = dt.dayOfWeek.value % 7
        val dayOfMonthMatches = dt.dayOfMonth in daysOfMonth
        val dayOfWeekMatches = cronDow in daysOfWeek
        return if (!dayOfMonthWildcard && !dayOfWeekWildcard) {
            dayOfMonthMatches || dayOfWeekMatches
        } else {
            dayOfMonthMatches && dayOfWeekMatches
        }
    }

    companion object {

        fun parse(expression: String): CronExpression {
            val parts = expression.trim().split("\\s+".toRegex())
            require(parts.size == 5) { "Cron expression must have 5 fields: \"$expression\"" }
            return CronExpression(
                minutes = parseField(parts[0], 0, 59),
                hours = parseField(parts[1], 0, 23),
                daysOfMonth = parseField(parts[2], 1, 31),
                months = parseField(parts[3], 1, 12),
                daysOfWeek = parseField(parts[4], 0, 7),
                dayOfMonthWildcard = parts[2] == "*",
                dayOfWeekWildcard = parts[4] == "*",
            )
        }

        private fun parseField(field: String, min: Int, max: Int): Set<Int> {
            val values = mutableSetOf<Int>()
            for (part in field.split(",")) {
                when {
                    part.contains("/") -> {
                        val (range, stepStr) = part.split("/", limit = 2)
                        val step = stepStr.toInt()
                        require(step > 0) { "Step must be positive: $field" }
                        val start = if (range == "*") min else range.split("-")[0].toInt()
                        val end = if (range == "*" || !range.contains("-")) max else range.split("-")[1].toInt()
                        var i = start
                        while (i <= end) {
                            values.add(i)
                            i += step
                        }
                    }
                    part == "*" -> values.addAll(min..max)
                    part.contains("-") -> {
                        val (startStr, endStr) = part.split("-", limit = 2)
                        values.addAll(startStr.toInt()..endStr.toInt())
                    }
                    else -> values.add(part.toInt())
                }
            }
            // Normalize day-of-week: cron allows 7 as Sunday (alias for 0)
            if (max == 7 && values.remove(7)) {
                values.add(0)
            }
            return values
        }
    }
}
