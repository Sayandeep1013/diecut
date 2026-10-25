package app.diecut

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import java.io.File
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

/** A drawn widget: the picture, and what the RemoteViews lay over it. */
class Face(val bitmap: Bitmap, val u: Float, val taps: List<Tap>, val clock: ClockPlace?)

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
        when (spec.content) {
            Content.PHOTO -> photo(ctx, c, spec, cut, u, pal)
            Content.CLOCK -> {
                ground(c, cut, pal)
                // A film's clock sits in its middle frame.
                val safe = cut.frames?.let { it[it.size / 2] } ?: cut.safe
                clock = if (spec.clock == ClockStyle.ANALOGUE) analogue(c, safe, pal, preview) else digital(ctx, c, spec, safe, pal, preview)
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
        return Face(bmp, u, taps.map { Tap(it.rect.moved(), it.component) }, clock?.let { ClockPlace(it.rect.moved(), it.timeSize, it.dateSize) })
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
            val m = min(w, h) * 0.12f
            val labelH = if (spec.labels) labelSize(w - 2 * m, h - 2 * m) * 1.6f else 0f
            // The apps spread over the whole widget, as in the picker's preview,
            // unless that would leave each one in a cell more than twice as
            // long as it is wide: then the shape draws in around them.
            val fit = Grid.fit(n, w - 2 * m, h - 2 * m, labelH)
            val cw = min(fit.cellW, fit.cellH * 2f)
            val ch = min(fit.cellH, fit.cellW * 2f)
            bw = min(w, fit.cols * cw + 2 * m)
            bh = min(h, fit.rows * ch + 2 * m)
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
            val iconSize = if (frames != null) min(64f, min(cell.width(), cell.height() - labelH) * 0.66f) else fit.icon
            val block = iconSize + labelH
            val top = cell.centerY() - block / 2
            val icon = RectF(cell.centerX() - iconSize / 2, top, cell.centerX() + iconSize / 2, top + iconSize)
            // Icons are drawn at full resolution, not through the dp scale.
            c.save()
            c.scale(1 / u, 1 / u)
            e.second.setBounds((icon.left * u).roundToInt(), (icon.top * u).roundToInt(), (icon.right * u).roundToInt(), (icon.bottom * u).roundToInt())
            e.second.draw(c)
            c.restore()
            if (spec.labels) {
                val shown = TextUtils.ellipsize(e.third, lp, cell.width() * 0.94f, TextUtils.TruncateAt.END).toString()
                c.drawText(shown, cell.centerX(), icon.bottom + labelSize * 1.25f, lp)
            }
            taps += Tap(cell, e.first)
        }
        return taps
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
