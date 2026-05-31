package org.tekfive.keep.job.schedule

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CronExpressionTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc)
            .toInstant().toEpochMilli()
    }

    private fun toZdt(epochMillis: Long): ZonedDateTime {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), utc)
    }

    // --- Parsing ---

    @Test
    fun `parse rejects expression with wrong number of fields`() {
        assertThrows<IllegalArgumentException> { CronExpression.parse("* * *") }
        assertThrows<IllegalArgumentException> { CronExpression.parse("* * * * * *") }
    }

    @Test
    fun `parse rejects zero step`() {
        assertThrows<IllegalArgumentException> { CronExpression.parse("*/0 * * * *") }
    }

    @Test
    fun `parse all wildcards`() {
        val cron = CronExpression.parse("* * * * *")
        val after = millis(2025, 6, 15, 10, 30)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(2025, next.year)
        assertEquals(6, next.monthValue)
        assertEquals(15, next.dayOfMonth)
        assertEquals(10, next.hour)
        assertEquals(31, next.minute)
    }

    // --- Specific minute ---

    @Test
    fun `specific minute same hour`() {
        val cron = CronExpression.parse("45 * * * *")
        val after = millis(2025, 6, 15, 10, 30)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(10, next.hour)
        assertEquals(45, next.minute)
    }

    @Test
    fun `specific minute rolls to next hour`() {
        val cron = CronExpression.parse("15 * * * *")
        val after = millis(2025, 6, 15, 10, 30)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(11, next.hour)
        assertEquals(15, next.minute)
    }

    // --- Specific hour ---

    @Test
    fun `specific hour and minute`() {
        val cron = CronExpression.parse("0 3 * * *")
        val after = millis(2025, 6, 15, 10, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(16, next.dayOfMonth)
        assertEquals(3, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun `specific hour before current time same day`() {
        val cron = CronExpression.parse("30 14 * * *")
        val after = millis(2025, 6, 15, 10, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(15, next.dayOfMonth)
        assertEquals(14, next.hour)
        assertEquals(30, next.minute)
    }

    // --- Day of month ---

    @Test
    fun `specific day of month`() {
        val cron = CronExpression.parse("0 0 1 * *")
        val after = millis(2025, 6, 15, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(7, next.monthValue)
        assertEquals(1, next.dayOfMonth)
        assertEquals(0, next.hour)
        assertEquals(0, next.minute)
    }

    // --- Month ---

    @Test
    fun `specific month`() {
        val cron = CronExpression.parse("0 0 1 3 *")
        val after = millis(2025, 6, 15, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(2026, next.year)
        assertEquals(3, next.monthValue)
        assertEquals(1, next.dayOfMonth)
    }

    // --- Day of week ---

    @Test
    fun `every Monday at midnight`() {
        // Monday = 1 in cron
        val cron = CronExpression.parse("0 0 * * 1")
        // 2025-06-15 is a Sunday
        val after = millis(2025, 6, 15, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(16, next.dayOfMonth) // Monday June 16
        assertEquals(0, next.hour)
        assertEquals(0, next.minute)
        assertEquals(java.time.DayOfWeek.MONDAY, next.dayOfWeek)
    }

    @Test
    fun `every Sunday using 0`() {
        val cron = CronExpression.parse("0 9 * * 0")
        // 2025-06-16 is a Monday
        val after = millis(2025, 6, 16, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(java.time.DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(22, next.dayOfMonth)
        assertEquals(9, next.hour)
    }

    @Test
    fun `every Sunday using 7`() {
        val cron = CronExpression.parse("0 9 * * 7")
        val after = millis(2025, 6, 16, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(java.time.DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(22, next.dayOfMonth)
        assertEquals(9, next.hour)
    }

    // --- Ranges ---

    @Test
    fun `minute range`() {
        val cron = CronExpression.parse("10-15 * * * *")
        val after = millis(2025, 6, 15, 10, 5)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(10, next.minute)
    }

    @Test
    fun `hour range`() {
        val cron = CronExpression.parse("0 9-17 * * *")
        val after = millis(2025, 6, 15, 20, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(16, next.dayOfMonth)
        assertEquals(9, next.hour)
    }

    // --- Lists ---

    @Test
    fun `minute list`() {
        val cron = CronExpression.parse("0,15,30,45 * * * *")
        val after = millis(2025, 6, 15, 10, 16)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(30, next.minute)
    }

    @Test
    fun `day of week list`() {
        // Tuesday (2) and Thursday (4)
        val cron = CronExpression.parse("0 12 * * 2,4")
        // 2025-06-15 is Sunday
        val after = millis(2025, 6, 15, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(java.time.DayOfWeek.TUESDAY, next.dayOfWeek)
        assertEquals(17, next.dayOfMonth)
        assertEquals(12, next.hour)
    }

    // --- Steps ---

    @Test
    fun `every 5 minutes`() {
        val cron = CronExpression.parse("*/5 * * * *")
        val after = millis(2025, 6, 15, 10, 22)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(25, next.minute)
    }

    @Test
    fun `every 2 hours`() {
        val cron = CronExpression.parse("0 */2 * * *")
        val after = millis(2025, 6, 15, 3, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(4, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun `step with range`() {
        val cron = CronExpression.parse("0-30/10 * * * *")
        val after = millis(2025, 6, 15, 10, 5)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(10, next.minute)
    }

    // --- nextAfter always advances ---

    @Test
    fun `nextAfter always advances past the given time`() {
        val cron = CronExpression.parse("30 10 * * *")
        val exactly = millis(2025, 6, 15, 10, 30)
        val next = toZdt(cron.nextAfter(exactly, utc))
        // Should return the next day, not the same time
        assertEquals(16, next.dayOfMonth)
        assertEquals(10, next.hour)
        assertEquals(30, next.minute)
    }

    // --- Year rollover ---

    @Test
    fun `rolls over to next year`() {
        val cron = CronExpression.parse("0 0 1 1 *")
        val after = millis(2025, 3, 1, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(2026, next.year)
        assertEquals(1, next.monthValue)
        assertEquals(1, next.dayOfMonth)
    }

    // --- Combined fields ---

    @Test
    fun `weekday mornings at 9am`() {
        // Monday-Friday (1-5) at 9:00
        val cron = CronExpression.parse("0 9 * * 1-5")
        // 2025-06-14 is Saturday
        val after = millis(2025, 6, 14, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(java.time.DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(16, next.dayOfMonth)
        assertEquals(9, next.hour)
    }

    @Test
    fun `first of quarter at midnight`() {
        val cron = CronExpression.parse("0 0 1 1,4,7,10 *")
        val after = millis(2025, 5, 15, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(7, next.monthValue)
        assertEquals(1, next.dayOfMonth)
        assertEquals(0, next.hour)
    }

    @Test
    fun `day of month and day of week use standard cron or semantics when both are restricted`() {
        val cron = CronExpression.parse("0 9 1 * 1")
        val after = millis(2025, 6, 2, 9, 0) // Monday June 2, 2025

        val next = toZdt(cron.nextAfter(after, utc))

        assertEquals(java.time.DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(9, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(0, next.minute)
    }

    // --- Consecutive calls ---

    @Test
    fun `consecutive calls return successive times`() {
        val cron = CronExpression.parse("*/15 * * * *")
        var time = millis(2025, 6, 15, 10, 0)

        val results = mutableListOf<Int>()
        repeat(4) {
            time = cron.nextAfter(time, utc)
            results.add(toZdt(time).minute)
        }
        assertEquals(listOf(15, 30, 45, 0), results)
    }

    // --- Edge cases ---

    @Test
    fun `end of day rollover`() {
        val cron = CronExpression.parse("0 0 * * *")
        val after = millis(2025, 6, 15, 23, 59)
        val next = toZdt(cron.nextAfter(after, utc))
        assertEquals(16, next.dayOfMonth)
        assertEquals(0, next.hour)
    }

    @Test
    fun `february 29 in leap year`() {
        val cron = CronExpression.parse("0 0 29 2 *")
        val after = millis(2025, 1, 1, 0, 0)
        val next = toZdt(cron.nextAfter(after, utc))
        // 2028 is the next leap year
        assertEquals(2028, next.year)
        assertEquals(2, next.monthValue)
        assertEquals(29, next.dayOfMonth)
    }

    @Test
    fun `no match within window throws`() {
        // Feb 31 never exists
        val cron = CronExpression.parse("0 0 31 2 *")
        val after = millis(2025, 1, 1, 0, 0)
        assertThrows<IllegalStateException> {
            cron.nextAfter(after, utc)
        }
    }

    // --- Timezone ---

    @Test
    fun `respects timezone`() {
        val cron = CronExpression.parse("0 9 * * *")
        val eastern = ZoneId.of("America/New_York")
        val after = millis(2025, 6, 15, 12, 0) // 12:00 UTC = 8:00 ET

        val next = cron.nextAfter(after, eastern)
        val nextEt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), eastern)
        assertEquals(9, nextEt.hour)
        assertEquals(15, nextEt.dayOfMonth)

        // 9:00 ET = 13:00 UTC
        val nextUtc = toZdt(next)
        assertEquals(13, nextUtc.hour)
    }
}
