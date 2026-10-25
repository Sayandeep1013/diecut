package app.diecut

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** The three widgets in the picker. They differ only in what they start as; the editor can change any of it. */
abstract class DiecutWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Also the periodic update (every half hour), when photos take turns.
        for (id in ids) {
            Store.turnIfDue(ctx, id)
            WidgetDraw.draw(ctx, mgr, id)
        }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        Store.noteSize(ctx, id, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
        WidgetDraw.draw(ctx, mgr, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) Store.delete(ctx, id)
    }
}

class PhotoWidget : DiecutWidget()
class ClockWidget : DiecutWidget()
class AppsWidget : DiecutWidget()
class DatesWidget : DiecutWidget() {
    override fun onEnabled(ctx: Context) = DayAlarms.schedule(ctx)
}

/** Taps that change a widget without opening anything (the next photo), and pin callbacks. */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (intent.action) {
            ACTION_NEXT -> {
                Store.next(ctx, id)
                WidgetDraw.draw(ctx, AppWidgetManager.getInstance(ctx), id)
            }
            ACTION_PINNED -> Store.setPending(ctx, id)
        }
    }

    companion object {
        const val ACTION_NEXT = "app.diecut.NEXT"
        const val ACTION_PINNED = "app.diecut.PINNED"
    }
}

object WidgetDraw {
    val providers = listOf(PhotoWidget::class.java, ClockWidget::class.java, AppsWidget::class.java, DatesWidget::class.java)

    private val taps = intArrayOf(R.id.tap0, R.id.tap1, R.id.tap2, R.id.tap3, R.id.tap4, R.id.tap5, R.id.tap6, R.id.tap7)

    private val times = mapOf(
        ClockStyle.LIGHT to R.id.time_light,
        ClockStyle.BOLD to R.id.time_bold,
        ClockStyle.SERIF to R.id.time_serif,
        ClockStyle.CONDENSED to R.id.time_condensed,
    )

    fun allIds(ctx: Context): List<Int> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers.flatMap { mgr.getAppWidgetIds(ComponentName(ctx, it)).toList() }
    }

    fun drawAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in allIds(ctx)) draw(ctx, mgr, id)
    }

    /** A widget's size in dp: portrait width x height, as the launcher reports it. */
    fun size(ctx: Context, id: Int, content: Content): Pair<Float, Float> {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val (dw, dh) = when (content) {
            Content.APPS -> 300 to 90
            Content.DATES -> 150 to 150
            else -> 150 to 150
        }
        val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: dw
        val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: dh
        return w.toFloat() to h.toFloat()
    }

    fun draw(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val spec = Store.spec(ctx, id)
        val (w, h) = size(ctx, id, spec.content)
        val u = Renderer.scale(ctx, w, h)
        val views = RemoteViews(ctx.packageName, R.layout.widget)
        var party = false
        try {
            val face = Renderer.render(ctx, spec, w, h, u, preview = false)
            views.setImageViewBitmap(R.id.face, face.bitmap)
            clock(views, spec, face, Palettes.resolve(ctx, spec.palette))
            // Confetti falls on someone's day: an animated drawable the launcher keeps playing.
            views.setViewVisibility(R.id.confetti, if (face.party && w < h * 1.8f) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.confetti_wide, if (face.party && w >= h * 1.8f) View.VISIBLE else View.GONE)
            party = face.party
            for ((i, view) in taps.withIndex()) {
                val tap = face.taps.getOrNull(i)
                if (tap == null) {
                    views.setViewVisibility(view, View.GONE)
                } else {
                    place(views, view, tap.rect)
                    views.setOnClickPendingIntent(view, launch(ctx, id * 16 + i, tap.component))
                }
            }
        } catch (e: Exception) {
            // A face that cannot be drawn stays as it was.
        }
        views.setOnClickPendingIntent(R.id.face, if (party) celebrate(ctx, id) else faceTap(ctx, id, spec))
        mgr.updateAppWidget(id, views)
    }

    private fun clock(views: RemoteViews, spec: Spec, face: Face, pal: Palette) {
        views.setViewVisibility(R.id.clock_box, View.GONE)
        views.setViewVisibility(R.id.clock_analog, View.GONE)
        val place = face.clock ?: return
        if (spec.clock == ClockStyle.ANALOGUE) {
            // The hands are bitmaps the system turns; the ticks are on the face.
            val side = place.rect.width()
            place(views, R.id.clock_analog, place.rect)
            views.setIcon(R.id.clock_analog, "setDial", Icon.createWithBitmap(Renderer.dialBitmap(side)))
            views.setIcon(R.id.clock_analog, "setHourHand", Icon.createWithBitmap(Renderer.handBitmap(side, face.u, Renderer.Hand.HOUR, pal.ink)))
            views.setIcon(R.id.clock_analog, "setMinuteHand", Icon.createWithBitmap(Renderer.handBitmap(side, face.u, Renderer.Hand.MINUTE, pal.ink)))
            return
        }
        place(views, R.id.clock_box, place.rect)
        val dip = TypedValue.COMPLEX_UNIT_DIP
        for ((style, view) in times) {
            if (style == spec.clock) {
                views.setViewVisibility(view, View.VISIBLE)
                views.setTextViewTextSize(view, dip, place.timeSize)
                views.setTextColor(view, pal.ink)
            } else {
                views.setViewVisibility(view, View.GONE)
            }
        }
        views.setViewVisibility(R.id.date, if (spec.showDate) View.VISIBLE else View.GONE)
        views.setTextViewTextSize(R.id.date, dip, place.dateSize)
        views.setTextColor(R.id.date, pal.ink)
    }

    /** Move [view] over [r] (dp): Android 12 and later can, so taps land where things are drawn. */
    private fun place(views: RemoteViews, view: Int, r: RectF) {
        val dip = TypedValue.COMPLEX_UNIT_DIP
        views.setViewVisibility(view, View.VISIBLE)
        views.setViewLayoutWidth(view, r.width(), dip)
        views.setViewLayoutHeight(view, r.height(), dip)
        views.setViewLayoutMargin(view, RemoteViews.MARGIN_LEFT, r.left, dip)
        views.setViewLayoutMargin(view, RemoteViews.MARGIN_TOP, r.top, dip)
    }

    private fun faceTap(ctx: Context, id: Int, spec: Spec): PendingIntent = when {
        spec.content == Content.PHOTO && spec.photos.size > 1 -> PendingIntent.getBroadcast(
            ctx, id * 16 + 8,
            Intent(ctx, TapReceiver::class.java).setAction(TapReceiver.ACTION_NEXT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        spec.content == Content.CLOCK -> PendingIntent.getActivity(
            ctx, id * 16 + 9,
            Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        else -> editor(ctx, id)
    }

    private fun celebrate(ctx: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        ctx, id * 16 + 11,
        Intent(ctx, CelebrateActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun editor(ctx: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        ctx, id * 16 + 10, editorIntent(ctx, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun editorIntent(ctx: Context, id: Int): Intent = Intent(ctx, EditorActivity::class.java)
        .setData(Uri.parse("diecut://widget/$id"))
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun launch(ctx: Context, code: Int, component: String): PendingIntent {
        val cn = ComponentName.unflattenFromString(component)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
