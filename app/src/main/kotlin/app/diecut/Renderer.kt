package app.diecut

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Something on the face that opens an app when tapped, in dp. */
class Tap(val rect: RectF, val component: String)

/** Where a live clock sits over the face, in dp, and how big its type is. */
class ClockPlace(val rect: RectF, val timeSize: Float, val dateSize: Float)

/** A drawn widget: the picture, and what the RemoteViews lay over it ([party]: confetti, it is someone's day). */
class Face(val bitmap: Bitmap, val u: Float, val taps: List<Tap>, val clock: ClockPlace?, val party: Boolean = false)

/**
 * Draws a widget's face: the shape is one bitmap, and the wallpaper shows
 * through wherever the shape cuts in. The app's previews use the same code,
 * with [Face] extras (the time) drawn into the picture instead of laid over it.
 */
object Renderer {

    /** Most pixels a face may have: RemoteViews bitmaps go over IPC, and big ones fail silently. */
    private const val MAX_PIXELS = 1_200_000f

    /** Pixels per dp for a [w] x [h] dp face: no more than 2.5, and fewer for big ones. */
    fun scale(ctx: Context, w: Float, h: Float): Float {
        val u = min(ctx.resources.displayMetrics.density, 2.5f)
        return min(u, sqrt(MAX_PIXELS / (w * h)))
    }

    fun render(ctx: Context, spec: Spec, w: Float, h: Float, u: Float, preview: Boolean): Face {
        val bmp = Bitmap.createBitmap(max(1, (w * u).roundToInt()), max(1, (h * u).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(u, u)
        val pal = Palettes.resolve(ctx, spec.palette)
        // The shape fills the widget, or (apps that fit) only as much of it as the icons need.
        val box = box(spec, w, h)
        c.translate(box.left, box.top)
        val cut = spec.shape.cut(box.width(), box.height(), cells(spec, box.width(), box.height()))
        c.drawPath(cut.outline, fill(pal.paper))
        cut.band?.let { c.drawPath(it, fill(pal.ink)) }
        var taps = emptyList<Tap>()
        var clock: ClockPlace? = null
        var party = false
        when (spec.content) {
            Content.PHOTO -> photo(ctx, c, spec, cut, u, pal)
            Content.CLOCK -> {
                ground(c, cut, pal)
                // A film's clock sits in its middle frame.
                val safe = cut.frames?.let { it[it.size / 2] } ?: cut.safe
                clock = if (spec.clock == ClockStyle.ANALOGUE) analogue(c, safe, pal, preview) else digital(ctx, c, spec, safe, pal, preview)
            }
            Content.DATES -> {
                ground(c, cut, pal)
                party = dates(ctx, c, spec, cut.frames?.let { it[it.size / 2] } ?: cut.safe, pal)
            }
            Content.APPS -> {
                // Plain paper under the icons, as in the preview (a film still has its frames).
                if (cut.frames != null) ground(c, cut, pal)
                taps = apps(ctx, c, spec, cut, u, pal)
            }
        }
        val dx = box.left
        val dy = box.top
        fun RectF.moved() = RectF(left + dx, top + dy, right + dx, bottom + dy)
        return Face(bmp, u, taps.map { Tap(it.rect.moved(), it.component) }, clock?.let { ClockPlace(it.rect.moved(), it.timeSize, it.dateSize) }, party)
    }

    /** Where in a [w] x [h] dp widget the shape goes. */
    fun box(spec: Spec, w: Float, h: Float): RectF {
        val n = spec.apps.size
        if (spec.content != Content.APPS || !spec.fit || n == 0) return RectF(0f, 0f, w, h)
        var bw: Float
        var bh: Float
        if (spec.shape == Shape.FILM) {
            // Exactly a frame per app, along the widget's long side.
            if (w >= h) {
                bw = min(w, Shape.filmLength(h, n, 1f)); bh = h
            } else {
                bw = w; bh = min(h, Shape.filmLength(w, n, 1f))
            }
        } else {
            // Like a dock: each app gets a square cell as big as the widget
            // allows, and the shape is as long as those cells, so it grows
            // with every app added until it fills the widget.
            val m = min(w, h) * 0.08f
            val labelH = if (spec.labels) labelSize(w - 2 * m, h - 2 * m) * 1.6f else 0f
            val fit = Grid.fit(n, w - 2 * m, h - 2 * m, labelH)
            val side = min(fit.cellW, fit.cellH - labelH)
            bw = min(w, fit.cols * side + 2 * m)
            bh = min(h, fit.rows * (side + labelH) + 2 * m)
            when (spec.shape) {
                // Round shapes want to be square.
                Shape.CIRCLE, Shape.SCALLOP -> { val s = min(max(bw, bh), min(w, h)); bw = s; bh = s }
                // Room for the notches.
                Shape.TICKET -> if (bw < w) bw = min(w, bw + min(bw, bh) * 0.3f)
                Shape.ARCH -> bh = min(h, bh + bw * 0.25f)
                else -> {}
            }
        }
        return RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
    }

    /** How many frames a film of [w] x [h] dp has: one per app, or as many as fill it (photos take turns along it). */
    fun cells(spec: Spec, w: Float, h: Float): Int {
        if (spec.shape != Shape.FILM) return 1
        val aspect = if (spec.content == Content.PHOTO) 1.3f else 1f
        val fits = Shape.filmFits(max(w, h), min(w, h), aspect)
        return if (spec.content == Content.APPS) max(1, if (spec.fit) spec.apps.size else max(spec.apps.size, fits)) else fits
    }

    private fun labelSize(w: Float, h: Float) = (min(w, h) * 0.075f).coerceIn(8f, 12f)

    /** A small picture of [shape] for the editor's shape row. */
    fun thumb(ctx: Context, shape: Shape, palette: String, w: Float, h: Float, u: Float): Bitmap {
        val bmp = Bitmap.createBitmap((w * u).roundToInt(), (h * u).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(u, u)
        val pal = Palettes.resolve(ctx, palette)
        val cut = shape.cut(w, h, if (shape == Shape.FILM) Shape.filmFits(max(w, h), min(w, h), 1f) else 1)
        c.drawPath(cut.outline, fill(pal.paper))
        cut.band?.let { c.drawPath(it, fill(pal.ink)) }
        c.drawPath(cut.window, fill(mix(pal.paper, pal.ink, 0.22f)))
        return bmp
    }

    // ------------------------------------------------------------- content

    private fun photo(ctx: Context, c: Canvas, spec: Spec, cut: Cut, u: Float, pal: Palette) {
        if (spec.photos.isEmpty()) {
            c.drawPath(cut.window, fill(mix(pal.paper, pal.ink, 0.1f)))
            centred(c, "Tap to add a photo", cut.safe, min(13f, cut.safe.width() / 11f), pal.ink, Typeface.DEFAULT)
            return
        }
        val frames = cut.frames
        if (frames == null) {
            val win = cut.windowRect
            val pic = load(File(Store.photoDir(ctx), spec.currentPhoto()!!), (max(win.width(), win.height()) * u).roundToInt())
            picture(c, pic, win, cut.window, pal)
            return
        }
        // A film: the photos one after another along it, starting with the current one.
        val cache = HashMap<String, Bitmap?>()
        for ((i, f) in frames.withIndex()) {
            val name = spec.photos[(spec.photoIndex + i).mod(spec.photos.size)]
            val pic = cache.getOrPut(name) { load(File(Store.photoDir(ctx), name), (max(f.width(), f.height()) * u).roundToInt()) }
            picture(c, pic, f, Path().apply { addRoundRect(f, 2f, 2f, Path.Direction.CW) }, pal)
        }
    }

    /** [pic] cropped to fill [win], centred, inside [clip]. */
    private fun picture(c: Canvas, pic: Bitmap?, win: RectF, clip: Path, pal: Palette) {
        if (pic == null) {
            c.drawPath(clip, fill(mix(pal.paper, pal.ink, 0.1f)))
            return
        }
        val k = max(win.width() / pic.width, win.height() / pic.height)
        val m = Matrix().apply {
            setScale(k, k)
            postTranslate(win.centerX() - pic.width * k / 2, win.centerY() - pic.height * k / 2)
        }
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(pic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        })
    }

    /** A picture decoded at about [size] px on its short side (saved upright when it was copied in). */
    fun load(f: File, size: Int): Bitmap? = try {
        if (!f.isFile) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    private fun digital(ctx: Context, c: Canvas, spec: Spec, safe: RectF, pal: Palette, preview: Boolean): ClockPlace {
        val face = Typeface.create(spec.clock.family, Typeface.NORMAL)
        val probe = Paint().apply { typeface = face; textSize = 100f }
        val perSize = probe.measureText("00:00") / 100f
        var size = safe.width() * 0.9f / perSize
        size = min(size, safe.height() * if (spec.showDate) 0.5f else 0.64f)
        val dateSize = if (spec.showDate) max(8f, size * 0.2f) else 0f
        if (preview) {
            val time = DateFormat.format(if (DateFormat.is24HourFormat(ctx)) "H:mm" else "h:mm", Date()).toString()
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; color = pal.ink; textAlign = Paint.Align.CENTER }
            val dp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = dateSize; color = pal.ink; textAlign = Paint.Align.CENTER }
            val tfm = tp.fontMetrics
            val dfm = dp.fontMetrics
            val th = tfm.descent - tfm.ascent
            val dh = if (spec.showDate) dfm.descent - dfm.ascent else 0f
            val top = safe.centerY() - (th + dh) / 2
            c.drawText(time, safe.centerX(), top - tfm.ascent, tp)
            if (spec.showDate) c.drawText(DateFormat.format("EEE d MMM", Date()).toString(), safe.centerX(), top + th - dfm.ascent, dp)
        }
        return ClockPlace(RectF(safe), size, dateSize)
    }

    private fun analogue(c: Canvas, safe: RectF, pal: Palette, preview: Boolean): ClockPlace {
        val side = min(safe.width(), safe.height())
        val box = RectF(safe.centerX() - side / 2, safe.centerY() - side / 2, safe.centerX() + side / 2, safe.centerY() + side / 2)
        val r = side / 2
        val cx = box.centerX()
        val cy = box.centerY()
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        for (i in 0 until 12) {
            val a = Math.PI * 2 * i / 12
            val major = i % 3 == 0
            tick.strokeWidth = r * if (major) 0.045f else 0.025f
            tick.alpha = if (major) 255 else 150
            val r0 = r * if (major) 0.8f else 0.86f
            val r1 = r * 0.94f
            c.drawLine(cx + r0 * sin(a).toFloat(), cy - r0 * cos(a).toFloat(), cx + r1 * sin(a).toFloat(), cy - r1 * cos(a).toFloat(), tick)
        }
        if (preview) {
            val now = Calendar.getInstance()
            val minutes = now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f
            val hours = now.get(Calendar.HOUR) + minutes / 60f
            hand(c, cx, cy, r, hours * 30f, Hand.HOUR, pal.ink)
            hand(c, cx, cy, r, minutes * 6f, Hand.MINUTE, pal.ink)
        }
        return ClockPlace(box, 0f, 0f)
    }

    enum class Hand(val length: Float, val width: Float) { HOUR(0.5f, 0.075f), MINUTE(0.74f, 0.05f) }

    /** One clock hand at [degrees] from twelve, the minute hand with the centre cap. */
    fun hand(c: Canvas, cx: Float, cy: Float, r: Float, degrees: Float, hand: Hand, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = r * hand.width; strokeCap = Paint.Cap.ROUND }
        c.save()
        c.rotate(degrees, cx, cy)
        c.drawLine(cx, cy + r * 0.08f, cx, cy - r * hand.length, p)
        if (hand == Hand.MINUTE) c.drawCircle(cx, cy, r * 0.07f, p)
        c.restore()
    }

    /**
     * A hand as the AnalogClock wants it: as big as the clock, pointing at
     * twelve from the centre, at [u] px a dp (its density says so, so the
     * launcher shows it at exactly [side] dp).
     */
    fun handBitmap(side: Float, u: Float, hand: Hand, color: Int): Bitmap {
        val px = max(1, (side * u).roundToInt())
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(u, u)
        hand(c, side / 2, side / 2, side / 2, 0f, hand, color)
        bmp.density = (u * 160).toInt()
        return bmp
    }

    /** A clear dial: it only sets the clock's size, so its density makes it [side] dp from a few pixels. */
    fun dialBitmap(side: Float): Bitmap {
        val px = 64
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).apply { density = max(1, (px * 160 / side).toInt()) }
    }

    private fun apps(ctx: Context, c: Canvas, spec: Spec, cut: Cut, u: Float, pal: Palette): List<Tap> {
        val safe = cut.safe
        val pm = ctx.packageManager
        val entries = spec.apps.mapNotNull { flat ->
            val cn = ComponentName.unflattenFromString(flat) ?: return@mapNotNull null
            val info = try { pm.getActivityInfo(cn, 0) } catch (e: Exception) { return@mapNotNull null }
            Triple(flat, info.loadIcon(pm), info.loadLabel(pm).toString())
        }
        if (entries.isEmpty()) {
            centred(c, "Tap to choose apps", safe, min(13f, safe.width() / 10f), pal.ink, Typeface.DEFAULT)
            return emptyList()
        }
        val frames = cut.frames
        val labelSize = if (!spec.labels) 0f else if (frames != null) labelSize(frames[0].width(), frames[0].height()) * 0.85f else labelSize(safe.width(), safe.height())
        val labelH = if (spec.labels) labelSize * 1.6f else 0f
        val fit = Grid.fit(entries.size, safe.width(), safe.height(), labelH)
        val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSize; color = pal.ink; textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        // A film has a frame for each app (centred along it when there are spare
        // frames); otherwise a grid, its last row centred when it is short.
        val first = if (frames != null) (frames.size - entries.size).coerceAtLeast(0) / 2 else 0
        val taps = mutableListOf<Tap>()
        for ((i, e) in entries.withIndex()) {
            val cell = if (frames != null) {
                frames.getOrNull(first + i) ?: break
            } else {
                val row = i / fit.cols
                val inRow = if (row == fit.rows - 1) entries.size - row * fit.cols else fit.cols
                val col = i % fit.cols
                val rowLeft = safe.left + (safe.width() - inRow * fit.cellW) / 2
                RectF(rowLeft + col * fit.cellW, safe.top + row * fit.cellH, rowLeft + (col + 1) * fit.cellW, safe.top + (row + 1) * fit.cellH)
            }
            val iconSize = if (frames != null) min(72f, min(cell.width(), cell.height() - labelH) * 0.74f) else fit.icon
            val block = iconSize + labelH
            val top = cell.centerY() - block / 2
            val icon = RectF(cell.centerX() - iconSize / 2, top, cell.centerX() + iconSize / 2, top + iconSize)
            // Icons are drawn at full resolution, not through the dp scale.
            c.save()
            c.scale(1 / u, 1 / u)
            val px = Rect((icon.left * u).roundToInt(), (icon.top * u).roundToInt(), (icon.right * u).roundToInt(), (icon.bottom * u).roundToInt())
            if (spec.mono) mono(c, e.second, px, pal) else e.second.apply { bounds = px }.draw(c)
            c.restore()
            if (spec.labels) {
                val shown = TextUtils.ellipsize(e.third, lp, cell.width() * 0.94f, TextUtils.TruncateAt.END).toString()
                c.drawText(shown, cell.centerX(), icon.bottom + labelSize * 1.25f, lp)
            }
            taps += Tap(cell, e.first)
        }
        return taps
    }

    private class Line(val text: String, val size: Float, val face: Typeface, val color: Int, val lines: Int = 1)

    /**
     * The next date and how far off it is. Tall widgets list the ones after
     * it; wide ones put the count on the left. On the day it says so, with
     * any message saved for it. Returns whether today is someone's day.
     */
    private fun dates(ctx: Context, c: Canvas, spec: Spec, safe: RectF, pal: Palette): Boolean {
        val groups = Upcoming.groups(DayBook.forSpec(ctx, spec), LocalDate.now(), 4)
        if (groups.isEmpty()) {
            centred(c, "Tap to add dates", safe, min(13f, safe.width() / 10f), pal.ink, Typeface.DEFAULT)
            return false
        }
        val g = groups[0]
        val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        val soft = (pal.ink and 0x00FFFFFF) or (0xB0 shl 24)
        val big = when (g.daysAway) { 0 -> "Today"; 1 -> "Tomorrow"; else -> "${g.daysAway}" }
        val under = if (g.daysAway > 1) "days to go" else ""
        val note = if (g.daysAway == 0) g.people.map { it.message.trim() }.filter { it.isNotEmpty() }.joinToString("\n") else ""
        val date = g.date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        val word = g.daysAway <= 1
        if (safe.width() >= safe.height() * 1.8f) {
            // Wide: the count on the left, what and when on the right.
            val left = RectF(safe.left, safe.top, safe.left + min(safe.height() * 1.15f, safe.width() * 0.38f), safe.bottom)
            val bigSize = min(fitSize(big, light, left.width() * 0.9f), safe.height() * if (word) 0.32f else 0.62f)
            val small = (safe.height() * 0.13f).coerceIn(8f, 13f)
            stack(c, left, listOf(Line(big, bigSize, light, pal.ink), Line(under, small, medium, soft)), Layout.Alignment.ALIGN_CENTER)
            val right = RectF(left.right + safe.height() * 0.12f, safe.top, safe.right, safe.bottom)
            val after = groups.getOrNull(1)
            val next = after?.let { "Then ${Upcoming.names(it.people.map { p -> p.name })} · ${away(it.daysAway)}" }
            stack(c, right, listOfNotNull(
                Line(Upcoming.occasion(g.people), small * 1.3f, medium, pal.ink, 2),
                Line(date, small, Typeface.DEFAULT, soft),
                if (note.isNotEmpty()) Line(note, small * 1.05f, italic, pal.ink, 2) else null,
                if (note.isEmpty() && next != null && safe.height() >= 90) Line(next, small * 0.95f, Typeface.DEFAULT, soft) else null,
            ), Layout.Alignment.ALIGN_NORMAL)
            return g.daysAway == 0
        }
        // Stacked; a tall widget lists the dates after it along the foot.
        val small = (min(safe.width(), safe.height()) * 0.075f).coerceIn(8.5f, 14f)
        val rest = if (safe.height() >= 190f) groups.drop(1).take(3) else emptyList()
        val rowH = small * 2f
        val main = RectF(safe.left, safe.top, safe.right, safe.bottom - rest.size * rowH - if (rest.isEmpty()) 0f else small)
        val bigSize = min(fitSize(big, light, main.width() * if (word) 0.86f else 0.7f), main.height() * if (word) 0.26f else 0.36f)
        stack(c, main, listOf(
            Line(big, bigSize, light, pal.ink),
            Line(under, small, medium, soft),
            Line(Upcoming.occasion(g.people), small * 1.3f, medium, pal.ink, 2),
            Line(date, small, Typeface.DEFAULT, soft),
            Line(note, small * 1.05f, italic, pal.ink, 3),
        ), Layout.Alignment.ALIGN_CENTER)
        if (rest.isNotEmpty()) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink; alpha = 50; strokeWidth = 1f }
            var y = main.bottom + small * 0.5f
            c.drawLine(safe.left + small, y, safe.right - small, y, pen)
            for (r in rest) {
                val whenText = away(r.daysAway)
                val row = RectF(safe.left + small, y, safe.right - small, y + rowH)
                val whenW = TextPaint().apply { textSize = small; typeface = Typeface.DEFAULT }.measureText(whenText) + small
                stack(c, RectF(row.left, row.top, row.right - whenW, row.bottom), listOf(Line(Upcoming.names(r.people.map { it.name }), small, medium, pal.ink)), Layout.Alignment.ALIGN_NORMAL)
                stack(c, RectF(row.right - whenW, row.top, row.right, row.bottom), listOf(Line(whenText, small, Typeface.DEFAULT, soft)), Layout.Alignment.ALIGN_OPPOSITE)
                y += rowH
            }
        }
        return g.daysAway == 0
    }

    private fun away(days: Int) = if (days == 1) "tomorrow" else "$days days"

    /** The size [s] would be in [face] to be [width] wide. */
    private fun fitSize(s: String, face: Typeface, width: Float): Float {
        val w = TextPaint().apply { textSize = 100f; typeface = face }.measureText(s)
        return if (w <= 0f) 12f else 100f * width / w
    }

    /** [lines] one under another, centred down [r] (smaller all round if they would not fit). */
    private fun stack(c: Canvas, r: RectF, lines: List<Line>, align: Layout.Alignment) {
        val shown = lines.filter { it.text.isNotEmpty() }
        if (shown.isEmpty() || r.width() < 1f) return
        fun layouts(k: Float) = shown.map { l ->
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.size * k; typeface = l.face; color = l.color }
            StaticLayout.Builder.obtain(l.text, 0, l.text.length, tp, r.width().toInt())
                .setAlignment(align).setMaxLines(l.lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        }
        val gap = shown.minOf { it.size } * 0.3f
        var k = 1f
        var ls = layouts(k)
        var total = ls.sumOf { it.height } + gap * (ls.size - 1)
        if (total > r.height()) {
            k = r.height() / total
            ls = layouts(k)
            total = ls.sumOf { it.height } + gap * k * (ls.size - 1)
        }
        var y = r.centerY() - total / 2
        for (l in ls) {
            c.save()
            c.translate(r.left, y)
            l.draw(c)
            c.restore()
            y += l.height + gap * k
        }
    }

    /**
     * An app icon in one colour, like Android's themed icons: the app's own
     * monochrome glyph in ink on a soft disc, or (an app without one) its
     * icon in greys.
     */
    private fun mono(c: Canvas, icon: Drawable, px: Rect, pal: Palette) {
        val glyph = if (Build.VERSION.SDK_INT >= 33) (icon as? AdaptiveIconDrawable)?.monochrome else null
        if (glyph != null) {
            c.drawCircle(px.exactCenterX(), px.exactCenterY(), px.width() / 2f, fill(mix(pal.paper, pal.ink, 0.12f)))
            glyph.mutate().apply { bounds = px; setTint(pal.ink) }.draw(c)
            return
        }
        val grey = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        c.saveLayer(RectF(px), grey)
        icon.bounds = px
        icon.draw(c)
        c.restore()
    }

    // ------------------------------------------------------------- helpers

    /** Under a clock or apps: a film's frames a shade lighter, any other window a hairline. */
    private fun ground(c: Canvas, cut: Cut, pal: Palette) {
        if (cut.frames != null) {
            c.drawPath(cut.window, fill(mix(pal.paper, pal.ink, 0.13f)))
            return
        }
        c.drawPath(cut.window, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = pal.ink; alpha = 46
        })
    }

    private fun centred(c: Canvas, s: String, r: RectF, size: Float, color: Int, face: Typeface) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = face; textAlign = Paint.Align.CENTER; alpha = 190 }
        val shown = TextUtils.ellipsize(s, p, r.width() * 0.95f, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, r.centerX(), r.centerY() + size * 0.35f, p)
    }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
}
