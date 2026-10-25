package app.diecut

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Everyone's dates, shared by all the dates widgets: one JSON file in app storage. */
object DayBook {
    private fun file(ctx: Context) = File(ctx.filesDir, "dates.json")

    fun all(ctx: Context): List<Day> = try {
        val a = JSONArray(file(ctx).readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Day(
                id = o.getString("id"),
                name = o.getString("name"),
                month = o.getInt("month"),
                day = o.getInt("day"),
                kind = runCatching { Day.Kind.valueOf(o.getString("kind")) }.getOrDefault(Day.Kind.BIRTHDAY),
                message = o.optString("message"),
                source = o.optString("source").ifEmpty { null },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(ctx: Context, days: List<Day>) {
        val a = JSONArray()
        for (d in days) a.put(JSONObject().apply {
            put("id", d.id); put("name", d.name); put("month", d.month); put("day", d.day)
            put("kind", d.kind.name); put("message", d.message); put("source", d.source ?: "")
        })
        file(ctx).writeText(a.toString())
        WidgetDraw.drawAll(ctx)
    }

    fun put(ctx: Context, d: Day) = save(ctx, all(ctx).filter { it.id != d.id } + d)

    fun remove(ctx: Context, id: String) = save(ctx, all(ctx).filter { it.id != id })

    fun newId(): String = UUID.randomUUID().toString()

    /** The dates a widget follows: all of them, or the ones chosen for it. */
    fun forSpec(ctx: Context, spec: Spec): List<Day> {
        val all = all(ctx)
        return if (spec.allDates) all else all.filter { it.id in spec.dates }
    }
}

/** Birthdays and anniversaries already in the phone's contacts (Google contacts sync them there). */
object ContactDates {
    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun read(ctx: Context): List<Day> {
        if (!allowed(ctx)) return emptyList()
        val out = mutableListOf<Day>()
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Event.START_DATE,
                ContactsContract.CommonDataKinds.Event.TYPE,
                ContactsContract.CommonDataKinds.Event.LABEL,
            ),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val (month, day) = monthDay(c.getString(2) ?: continue) ?: continue
                val type = c.getInt(3)
                val kind = when (type) {
                    ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> Day.Kind.BIRTHDAY
                    ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY -> Day.Kind.ANNIVERSARY
                    else -> Day.Kind.SPECIAL
                }
                val label = c.getString(4)
                val shown = if (kind == Day.Kind.SPECIAL && !label.isNullOrBlank()) "$name's $label" else name
                out += Day(DayBook.newId(), shown, month, day, kind, source = "$key|$type")
            }
        }
        return out.distinctBy { it.source }.sortedBy { it.name.lowercase() }
    }

    fun monthDay(s: String) = Upcoming.monthDay(s)
}

/**
 * Two inexact daily alarms (no exact-alarm permission): just after midnight
 * every dates widget moves on to the new day, and at nine any date that is
 * today, with a message saved for it, becomes a notification.
 */
object DayAlarms {
    const val ACTION_MIDNIGHT = "app.diecut.MIDNIGHT"
    const val ACTION_MORNING = "app.diecut.MORNING"
    private const val CHANNEL = "dates"

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        fun at(time: LocalTime): Long {
            var t = now.toLocalDate().atTime(time)
            if (!t.isAfter(now)) t = t.plusDays(1)
            return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        am.setWindow(AlarmManager.RTC_WAKEUP, at(LocalTime.of(0, 0, 30)), 10 * 60_000L, pending(ctx, ACTION_MIDNIGHT))
        am.setWindow(AlarmManager.RTC_WAKEUP, at(LocalTime.of(9, 0)), 15 * 60_000L, pending(ctx, ACTION_MORNING))
    }

    private fun pending(ctx: Context, action: String) = PendingIntent.getBroadcast(
        ctx, action.hashCode(), Intent(ctx, DayReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Today's dates as notifications, each with its message. */
    fun remind(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dates", NotificationManager.IMPORTANCE_DEFAULT))
        val today = Upcoming.groups(DayBook.all(ctx), LocalDate.now(), 1).firstOrNull()?.takeIf { it.daysAway == 0 } ?: return
        for (d in today.people) {
            val open = PendingIntent.getActivity(
                ctx, d.id.hashCode(), Intent(ctx, CelebrateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Today is ${Upcoming.occasion(listOf(d))}")
                .setContentText(d.message.ifBlank { "Wish ${d.name} well today." })
                .setStyle(Notification.BigTextStyle().bigText(d.message.ifBlank { "Wish ${d.name} well today." }))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(d.id.hashCode(), n)
        }
    }
}

class DayReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            DayAlarms.ACTION_MIDNIGHT -> WidgetDraw.drawAll(ctx)
            DayAlarms.ACTION_MORNING -> DayAlarms.remind(ctx)
            else -> WidgetDraw.drawAll(ctx) // booted, or the clock or time zone changed
        }
        DayAlarms.schedule(ctx)
    }
}
