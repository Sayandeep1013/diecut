package app.diecut

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The pure arithmetic, kept free of Android so it can be unit tested. */
object Timing {
    /** Whether a widget with [count] photos, turning every [minutes], is due to move on. */
    fun due(count: Int, minutes: Int, last: Long, now: Long): Boolean =
        count > 1 && minutes > 0 && now - last >= minutes * 60_000L - 60_000L
}

object Grid {
    class Fit(val cols: Int, val rows: Int, val cellW: Float, val cellH: Float, val icon: Float)

    /**
     * The grid that gives [n] icons the biggest size in a [w] x [h] dp area,
     * with [labelH] dp under each icon for its name (0 for none).
     */
    fun fit(n: Int, w: Float, h: Float, labelH: Float, maxIcon: Float = 72f): Fit {
        var best = Fit(1, 1, w, h, 0f)
        for (cols in 1..max(1, n)) {
            val rows = ceil(max(1, n) / cols.toFloat()).toInt()
            val cw = w / cols
            val ch = h / rows
            val icon = min(min(cw, ch - labelH) * 0.86f, maxIcon)
            if (icon > best.icon) best = Fit(cols, rows, cw, ch, icon)
        }
        return best
    }

    /** How many evenly spaced steps of about [step] fit along [length] (at least [least]). */
    fun steps(length: Float, step: Float, least: Int): Int = max(least, Math.round(length / step))
}

/** A date to count down to: a birthday, an anniversary or any day that matters, every year. */
data class Day(
    val id: String,
    val name: String,
    val month: Int,
    val day: Int,
    val kind: Kind = Kind.BIRTHDAY,
    /** What to remember to say on the day. */
    val message: String = "",
    /** The contact it came from ("lookup key|type"), so an import is not repeated. */
    val source: String? = null,
) {
    enum class Kind(val label: String) { BIRTHDAY("Birthday"), ANNIVERSARY("Anniversary"), SPECIAL("Special day") }
}

object Upcoming {
    class Group(val date: java.time.LocalDate, val daysAway: Int, val people: List<Day>)

    /** When [d] next falls on or after [today] (29 February is kept on the 28th in other years). */
    fun next(d: Day, today: java.time.LocalDate): java.time.LocalDate {
        fun on(year: Int): java.time.LocalDate {
            val ym = java.time.YearMonth.of(year, d.month)
            return ym.atDay(min(d.day, ym.lengthOfMonth()))
        }
        val thisYear = on(today.year)
        return if (thisYear.isBefore(today)) on(today.year + 1) else thisYear
    }

    /** The coming dates in order, those on the same day together, the first [count] of them. */
    fun groups(days: List<Day>, today: java.time.LocalDate, count: Int): List<Group> =
        days.groupBy { next(it, today) }.toSortedMap().entries.take(count).map { (date, people) ->
            Group(date, java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt(), people.sortedBy { it.name.lowercase() })
        }

    /** "Priya", "Priya & Rahul", "Priya, Rahul & Sam". */
    fun names(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
    }

    /** "3 days to go", "Tomorrow", "Today". */
    fun countdown(daysAway: Int): String = when (daysAway) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> "$daysAway days to go"
    }

    /** What the day is: "Priya's birthday", "Priya & Rahul's birthdays", or a special day's own name. */
    fun occasion(people: List<Day>): String {
        val who = names(people.map { it.name })
        val kinds = people.map { it.kind }.distinct()
        if (kinds.size != 1 || kinds[0] == Day.Kind.SPECIAL) return who
        val what = if (kinds[0] == Day.Kind.BIRTHDAY) "birthday" else "anniversary"
        return "$who's $what" + if (people.size > 1) "s" else ""
    }
}
