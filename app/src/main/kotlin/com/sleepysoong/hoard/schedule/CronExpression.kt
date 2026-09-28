package com.sleepysoong.hoard.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Standard 5-field cron: `minute hour day-of-month month day-of-week`.
 * Fields: `*`, numbers, ranges `a-b`, lists `a,b`, steps `*&#47;n` / `a-b/n`; month 1-12,
 * weekday 0-7 (0 and 7 = Sunday); names are not supported. Like Vixie cron, when both
 * day-of-month and day-of-week are restricted a day matches if *either* does.
 * No seconds field (6/7-field expressions are rejected).
 */
class CronExpression private constructor(
    val source: String,
    private val minutes: BooleanArray,
    private val hours: BooleanArray,
    private val days: BooleanArray,
    private val months: BooleanArray,
    private val weekdays: BooleanArray,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean
) {
    /** The first matching minute strictly after [after], in [zone]. Null if none within ~5 years. */
    fun next(after: Instant, zone: ZoneId): Instant? {
        var t = ZonedDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(5)
        while (t.isBefore(limit)) {
            if (!months[t.monthValue]) {
                t = t.withDayOfMonth(1).toLocalDate().plusMonths(1).atStartOfDay(zone); continue
            }
            if (!dayMatches(t.toLocalDate())) {
                t = t.toLocalDate().plusDays(1).atStartOfDay(zone); continue
            }
            if (!hours[t.hour]) { t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1); continue }
            if (!minutes[t.minute]) { t = t.plusMinutes(1); continue }
            return t.toInstant()
        }
        return null
    }

    private fun dayMatches(d: LocalDate): Boolean {
        val dom = days[d.dayOfMonth]
        val dow = weekdays[d.dayOfWeek.value % 7]
        return when {
            domRestricted && dowRestricted -> dom || dow
            domRestricted -> dom
            dowRestricted -> dow
            else -> true
        }
    }

    override fun toString() = source

    companion object {
        fun parse(expression: String): CronExpression {
            val parts = expression.trim().split(Regex("\\s+"))
            if (parts.size != 5) throw IllegalArgumentException("cron must have 5 fields (minute hour day month weekday), got ${parts.size}")
            val weekdays = field(parts[4], 0, 7, "weekday")
            if (weekdays[7]) weekdays[0] = true
            return CronExpression(
                source = parts.joinToString(" "),
                minutes = field(parts[0], 0, 59, "minute"),
                hours = field(parts[1], 0, 23, "hour"),
                days = field(parts[2], 1, 31, "day-of-month"),
                months = field(parts[3], 1, 12, "month"),
                weekdays = weekdays,
                domRestricted = parts[2] != "*",
                dowRestricted = parts[4] != "*"
            )
        }

        private fun field(spec: String, min: Int, max: Int, name: String): BooleanArray {
            val out = BooleanArray(max + 1)
            for (part in spec.split(",")) {
                if (part.isEmpty()) throw IllegalArgumentException("empty $name list item in \"$spec\"")
                val (range, stepText) = part.split("/").let { if (it.size > 2) throw IllegalArgumentException("bad step in $name \"$part\"") else it[0] to it.getOrNull(1) }
                val step = stepText?.let { it.toIntOrNull()?.takeIf { s -> s > 0 } ?: throw IllegalArgumentException("bad step \"$it\" in $name") } ?: 1
                val (lo, hi) = when {
                    range == "*" -> min to max
                    range.contains("-") -> range.split("-").let {
                        if (it.size != 2) throw IllegalArgumentException("bad range \"$range\" in $name")
                        num(it[0], min, max, name) to num(it[1], min, max, name)
                    }
                    else -> num(range, min, max, name).let { v -> v to if (stepText != null) max else v }
                }
                if (lo > hi) throw IllegalArgumentException("range $lo-$hi is reversed in $name")
                var v = lo
                while (v <= hi) { out[v] = true; v += step }
            }
            return out
        }

        private fun num(s: String, min: Int, max: Int, name: String): Int {
            val v = s.toIntOrNull() ?: throw IllegalArgumentException("\"$s\" is not a number in $name (names like MON are not supported)")
            if (v !in min..max) throw IllegalArgumentException("$name $v out of range $min-$max")
            return v
        }
    }
}
