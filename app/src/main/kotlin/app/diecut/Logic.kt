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
    fun fit(n: Int, w: Float, h: Float, labelH: Float, maxIcon: Float = 64f): Fit {
        var best = Fit(1, 1, w, h, 0f)
        for (cols in 1..max(1, n)) {
            val rows = ceil(max(1, n) / cols.toFloat()).toInt()
            val cw = w / cols
            val ch = h / rows
            val icon = min(min(cw, ch - labelH) * 0.72f, maxIcon)
            if (icon > best.icon) best = Fit(cols, rows, cw, ch, icon)
        }
        return best
    }

    /** How many evenly spaced steps of about [step] fit along [length] (at least [least]). */
    fun steps(length: Float, step: Float, least: Int): Int = max(least, Math.round(length / step))
}
