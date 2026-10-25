package app.diecut

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Content(val label: String) { PHOTO("Photo"), CLOCK("Clock"), APPS("Apps"), DATES("Dates") }

/** How a clock shows the time. The digital ones are system fonts, which a widget's TextClock can use. */
enum class ClockStyle(val label: String, val family: String?) {
    LIGHT("Light", "sans-serif-light"),
    BOLD("Bold", "sans-serif-black"),
    SERIF("Serif", "serif"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    ANALOGUE("Analogue", null),
}

/** Everything one widget is: its content, its shape and its colours. */
data class Spec(
    val content: Content,
    val shape: Shape = Shape.STAMP,
    val palette: String = "paper",
    /** Picture file names in [Store.photoDir], copied in from the Photo Picker. */
    val photos: List<String> = emptyList(),
    val photoIndex: Int = 0,
    /** Minutes between photos; 0 changes only on a tap. */
    val turnMinutes: Int = 0,
    val lastTurn: Long = 0L,
    val clock: ClockStyle = ClockStyle.LIGHT,
    val showDate: Boolean = true,
    /** Flattened component names of the apps to open. */
    val apps: List<String> = emptyList(),
    val labels: Boolean = false,
    /** Grow the shape with the apps, like a dock, instead of filling the widget. Resizing the widget turns it off. */
    val fit: Boolean = true,
    /** App icons in one colour. */
    val mono: Boolean = false,
    /** Follow every date in the book, or only [dates] (their ids). */
    val allDates: Boolean = true,
    val dates: List<String> = emptyList(),
) {
    fun currentPhoto(): String? = if (photos.isEmpty()) null else photos[photoIndex.mod(photos.size)]

    fun toJson(): String = JSONObject().apply {
        put("content", content.name)
        put("shape", shape.name)
        put("palette", palette)
        put("photos", JSONArray(photos))
        put("photoIndex", photoIndex)
        put("turnMinutes", turnMinutes)
        put("lastTurn", lastTurn)
        put("clock", clock.name)
        put("showDate", showDate)
        put("apps", JSONArray(apps))
        put("labels", labels)
        put("fit", fit)
        put("mono", mono)
        put("allDates", allDates)
        put("dates", JSONArray(dates))
    }.toString()

    companion object {
        fun fromJson(s: String): Spec? = try {
            val o = JSONObject(s)
            fun list(key: String) = o.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            Spec(
                content = Content.valueOf(o.getString("content")),
                shape = runCatching { Shape.valueOf(o.getString("shape")) }.getOrDefault(Shape.STAMP),
                palette = o.optString("palette", "paper"),
                photos = list("photos"),
                photoIndex = o.optInt("photoIndex"),
                turnMinutes = o.optInt("turnMinutes"),
                lastTurn = o.optLong("lastTurn"),
                clock = runCatching { ClockStyle.valueOf(o.getString("clock")) }.getOrDefault(ClockStyle.LIGHT),
                showDate = o.optBoolean("showDate", true),
                apps = list("apps"),
                labels = o.optBoolean("labels"),
                fit = o.optBoolean("fit", true),
                mono = o.optBoolean("mono"),
                allDates = o.optBoolean("allDates", true),
                dates = list("dates"),
            )
        } catch (e: Exception) {
            null
        }

        fun defaultFor(content: Content) = when (content) {
            Content.PHOTO -> Spec(content, Shape.STAMP, "paper")
            Content.CLOCK -> Spec(content, Shape.CIRCLE, "ink")
            Content.APPS -> Spec(content, Shape.TICKET, "cream")
            Content.DATES -> Spec(content, Shape.SCALLOP, "blush")
        }
    }
}

/** Per-widget settings, keyed by widget id. */
object Store {
    private const val PREFS = "widgets"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun photoDir(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }

    /** The content a widget starts with: the kind it was added as. */
    fun contentOf(ctx: Context, id: Int): Content {
        val cls = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)?.provider?.className
        return when (cls) {
            ClockWidget::class.java.name -> Content.CLOCK
            AppsWidget::class.java.name -> Content.APPS
            DatesWidget::class.java.name -> Content.DATES
            else -> Content.PHOTO
        }
    }

    /** A widget's settings; a new one gets its kind's defaults (and an apps widget four apps), saved. */
    fun spec(ctx: Context, id: Int): Spec {
        prefs(ctx).getString("spec_$id", null)?.let { Spec.fromJson(it) }?.let { return it }
        val content = contentOf(ctx, id)
        val fresh = Spec.defaultFor(content).let { if (content == Content.APPS) it.copy(apps = Apps.suggested(ctx)) else it }
        prefs(ctx).edit().putString("spec_$id", fresh.toJson()).apply()
        return fresh
    }

    fun put(ctx: Context, id: Int, spec: Spec) {
        val old = prefs(ctx).getString("spec_$id", null)?.let { Spec.fromJson(it) }
        prefs(ctx).edit().putString("spec_$id", spec.toJson()).apply()
        // Pictures no longer used by this widget are deleted.
        old?.photos?.filter { it !in spec.photos }?.forEach { File(photoDir(ctx), it).delete() }
    }

    fun delete(ctx: Context, id: Int) {
        prefs(ctx).getString("spec_$id", null)?.let { Spec.fromJson(it) }?.photos?.forEach { File(photoDir(ctx), it).delete() }
        prefs(ctx).edit().remove("spec_$id").remove("size_$id").remove("placed_$id").apply()
    }

    /** Move on to the next photo. */
    fun next(ctx: Context, id: Int) {
        val s = spec(ctx, id)
        if (s.photos.size > 1) put(ctx, id, s.copy(photoIndex = (s.photoIndex + 1).mod(s.photos.size), lastTurn = System.currentTimeMillis()))
    }

    /** Move on if the widget's interval has passed (checked on each periodic update). */
    fun turnIfDue(ctx: Context, id: Int) {
        val s = spec(ctx, id)
        if (Timing.due(s.photos.size, s.turnMinutes, s.lastTurn, System.currentTimeMillis())) next(ctx, id)
    }

    /**
     * Note the size the launcher gives a widget. A change well after it was
     * placed is the person resizing it, which turns growing with the apps off:
     * they chose the size.
     */
    fun noteSize(ctx: Context, id: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val old = p.getString("size_$id", null)
        val placed = p.getLong("placed_$id", 0L)
        val e = p.edit().putString("size_$id", "${w}x$h")
        if (placed == 0L) e.putLong("placed_$id", now)
        e.apply()
        // Launchers settle a new widget's size in its first moments; later changes are the person's.
        if (old != null && old != "${w}x$h" && placed != 0L && now - placed > 15_000) {
            val s = spec(ctx, id)
            if (s.fit) put(ctx, id, s.copy(fit = false))
        }
    }

    /** A widget just pinned from the app, to set up when the app comes back. */
    fun setPending(ctx: Context, id: Int) = prefs(ctx).edit().putInt("pending", id).apply()

    fun takePending(ctx: Context): Int? {
        val id = prefs(ctx).getInt("pending", 0)
        if (id == 0) return null
        prefs(ctx).edit().remove("pending").apply()
        return id
    }
}

/** A widget's colours: the material the shape is cut from, and the ink on it. */
class Palette(val id: String, val label: String, val paper: Int, val ink: Int)

object Palettes {
    private val fixed = listOf(
        Palette("paper", "Paper", Color.rgb(255, 253, 248), Color.rgb(28, 27, 26)),
        Palette("cream", "Cream", Color.rgb(243, 233, 210), Color.rgb(59, 47, 37)),
        Palette("kraft", "Kraft", Color.rgb(201, 164, 122), Color.rgb(46, 33, 22)),
        Palette("ink", "Ink", Color.rgb(27, 27, 29), Color.rgb(242, 238, 232)),
        Palette("red", "Post red", Color.rgb(200, 64, 47), Color.rgb(255, 244, 236)),
        Palette("navy", "Navy", Color.rgb(35, 50, 79), Color.rgb(241, 233, 218)),
        Palette("sage", "Sage", Color.rgb(169, 191, 161), Color.rgb(34, 48, 31)),
        Palette("blush", "Blush", Color.rgb(239, 198, 193), Color.rgb(74, 37, 34)),
        Palette("mustard", "Mustard", Color.rgb(226, 177, 60), Color.rgb(43, 34, 16)),
    )

    /** The fixed colours and two taken from the wallpaper (Material You). */
    fun all(ctx: Context): List<Palette> = fixed + listOf(
        Palette("wall_light", "Wallpaper", ctx.getColor(android.R.color.system_accent1_100), ctx.getColor(android.R.color.system_accent1_900)),
        Palette("wall_deep", "Wallpaper deep", ctx.getColor(android.R.color.system_accent1_800), ctx.getColor(android.R.color.system_accent1_50)),
    )

    fun resolve(ctx: Context, id: String): Palette = all(ctx).firstOrNull { it.id == id } ?: fixed[0]
}
