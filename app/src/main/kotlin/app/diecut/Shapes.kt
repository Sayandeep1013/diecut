package app.diecut

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One shape cut at a size, in dp. [outline] is the paper, holes and all; the
 * content goes in [window] ([windowRect] its bounds); icons and clocks keep
 * inside [safe]. [band] is an optional frame drawn in the ink colour.
 */
class Cut(val outline: Path, val window: Path, val windowRect: RectF, val safe: RectF, val band: Path? = null)

enum class Shape(val label: String) {
    STAMP("Stamp"),
    POLAROID("Polaroid"),
    FRAME("Frame"),
    CIRCLE("Circle"),
    SQUIRCLE("Squircle"),
    TICKET("Ticket"),
    SCALLOP("Scallop"),
    ARCH("Arch");

    fun cut(w: Float, h: Float): Cut {
        val s = min(w, h)
        return when (this) {
            STAMP -> {
                // Holes about 3.6 radii apart, so each tooth is about a hole wide,
                // spread evenly along each edge with one on every corner.
                val hole = (s * 0.024f).coerceIn(2.6f, 4.2f)
                val body = rect(0f, 0f, w, h)
                val holes = Path()
                fun along(length: Float, at: (Float) -> Unit) {
                    val n = Grid.steps(length, hole * 3.6f, 2)
                    for (i in 0..n) at(length * i / n)
                }
                along(w) { x ->
                    holes.addCircle(x, 0f, hole, Path.Direction.CW)
                    holes.addCircle(x, h, hole, Path.Direction.CW)
                }
                along(h) { y ->
                    holes.addCircle(0f, y, hole, Path.Direction.CW)
                    holes.addCircle(w, y, hole, Path.Direction.CW)
                }
                body.op(holes, Path.Op.DIFFERENCE)
                val m = hole * 2.7f
                val win = RectF(m, m, w - m, h - m)
                Cut(body, rect(win), win, win)
            }
            POLAROID -> {
                val side = s * 0.065f
                val bottom = max(side * 2.6f, h * 0.2f)
                val win = RectF(side, side, w - side, h - bottom)
                Cut(round(RectF(0f, 0f, w, h), 2f), rect(win), win, win)
            }
            FRAME -> {
                val b = s * 0.065f
                val mat = s * 0.085f
                val band = rect(0f, 0f, w, h).apply { op(rect(b, b, w - b, h - b), Path.Op.DIFFERENCE) }
                val win = RectF(b + mat, b + mat, w - b - mat, h - b - mat)
                Cut(round(RectF(0f, 0f, w, h), 2f), rect(win), win, win, band)
            }
            CIRCLE -> {
                val r = s / 2
                val cx = w / 2
                val cy = h / 2
                val rim = s * 0.04f
                val win = RectF(cx - r + rim, cy - r + rim, cx + r - rim, cy + r - rim)
                val k = win.width() * (1 - 1 / sqrt(2f)) / 2
                Cut(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }, Path().apply { addOval(win, Path.Direction.CW) }, win, inset(win, k))
            }
            SQUIRCLE -> {
                val rim = s * 0.04f
                val win = RectF(rim, rim, w - rim, h - rim)
                Cut(superellipse(RectF(0f, 0f, w, h)), superellipse(win), win, inset(win, s * 0.08f))
            }
            TICKET -> {
                val corner = s * 0.09f
                val notch = s * 0.11f
                val m = s * 0.05f
                fun ticket(inset: Float): Path {
                    val p = rect(inset, inset, w - inset, h - inset)
                    val holes = Path()
                    for ((x, y) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) holes.addCircle(x, y, corner + inset, Path.Direction.CW)
                    holes.addCircle(0f, h / 2, notch + inset, Path.Direction.CW)
                    holes.addCircle(w, h / 2, notch + inset, Path.Direction.CW)
                    p.op(holes, Path.Op.DIFFERENCE)
                    return p
                }
                val win = RectF(m, m, w - m, h - m)
                Cut(ticket(0f), ticket(m), win, RectF(notch + 2 * m, m * 1.5f, w - notch - 2 * m, h - m * 1.5f))
            }
            SCALLOP -> {
                // A rounded rectangle with a circle on every few dp of its edge: a cookie cutter.
                val bump = s * 0.055f
                val radius = s * 0.28f
                val base = round(RectF(bump, bump, w - bump, h - bump), radius)
                val measure = PathMeasure(base, false)
                val length = measure.length
                val n = Grid.steps(length, bump * 1.75f, 8)
                val pos = FloatArray(2)
                val bumps = Path()
                for (i in 0 until n) {
                    measure.getPosTan(length * i / n, pos, null)
                    bumps.addCircle(pos[0], pos[1], bump, Path.Direction.CW)
                }
                val outline = Path(base).apply { op(bumps, Path.Op.UNION) }
                val inset = bump * 1.6f
                val win = RectF(inset, inset, w - inset, h - inset)
                val r = max(0f, radius - (inset - bump))
                Cut(outline, round(win, r), win, inset(win, r * 0.3f))
            }
            ARCH -> {
                val m = s * 0.045f
                val top = min(w / 2, h * 0.55f)
                val win = RectF(m, m, w - m, h - m)
                Cut(arch(RectF(0f, 0f, w, h), top), arch(win, top - m), win, RectF(m * 1.5f, m + (top - m) * 0.4f, w - m * 1.5f, h - m * 1.5f))
            }
        }
    }

    companion object {
        fun rect(l: Float, t: Float, r: Float, b: Float) = Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

        fun rect(r: RectF) = Path().apply { addRect(r, Path.Direction.CW) }

        fun round(r: RectF, radius: Float) = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }

        fun inset(r: RectF, by: Float) = RectF(r.left + by, r.top + by, r.right - by, r.bottom - by)

        /** A squircle filling [r]: |x|^n + |y|^n = 1. */
        fun superellipse(r: RectF, n: Double = 5.0): Path {
            val p = Path()
            val a = r.width() / 2
            val b = r.height() / 2
            val steps = 240
            for (i in 0 until steps) {
                val t = 2 * Math.PI * i / steps
                val c = cos(t)
                val sn = sin(t)
                val x = r.centerX() + a * (sign(c) * abs(c).pow(2 / n)).toFloat()
                val y = r.centerY() + b * (sign(sn) * abs(sn).pow(2 / n)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            return p
        }

        /** An arch: straight sides and a round top [top] dp tall. */
        fun arch(r: RectF, top: Float): Path = Path().apply {
            moveTo(r.left, r.bottom)
            lineTo(r.left, r.top + top)
            arcTo(RectF(r.left, r.top, r.right, r.top + 2 * top), 180f, 180f, false)
            lineTo(r.right, r.bottom)
            close()
        }
    }
}
