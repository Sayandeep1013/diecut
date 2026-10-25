package app.diecut

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.sin
import kotlin.random.Random

/** Everyone's dates, from the app's home screen. */
class DatesActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DiecutTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Dates") }) }) { pad ->
                    Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        DatesManager(selected = null, onToggle = null)
                    }
                }
            }
        }
    }
}

private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * The list of dates with adding, importing and editing. With [selected],
 * each has a tick for whether this widget follows it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DatesManager(selected: List<String>?, onToggle: ((String) -> Unit)?, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    var days by remember { mutableStateOf(DayBook.all(ctx)) }
    var editing by remember { mutableStateOf<Day?>(null) }
    var importing by remember { mutableStateOf(false) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun changed() {
        days = DayBook.all(ctx)
        onChanged()
        DayAlarms.schedule(ctx)
        // The morning reminder needs notifications; asked once there is something to remind of.
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = Day(DayBook.newId(), "", LocalDate.now().monthValue, LocalDate.now().dayOfMonth) }) { Text("Add a date") }
        OutlinedButton(onClick = { importing = true }) { Text("Import from contacts") }
    }
    Spacer(Modifier.height(8.dp))
    if (days.isEmpty()) {
        Text("No dates yet. Import birthdays from your contacts, or add one.", style = MaterialTheme.typography.bodyMedium)
    }
    val today = LocalDate.now()
    for (d in days.sortedBy { Upcoming.next(it, today) }) {
        val next = Upcoming.next(d, today)
        val away = java.time.temporal.ChronoUnit.DAYS.between(today, next).toInt()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editing = d }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null && onToggle != null) {
                Checkbox(checked = d.id in selected, onCheckedChange = { onToggle(d.id) })
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${d.kind.label} · ${next.format(shortDate)} · ${Upcoming.countdown(away).lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (d.message.isNotBlank()) {
                    Text("“${d.message}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 1)
                }
            }
        }
    }

    editing?.let { d ->
        DayDialog(d, isNew = days.none { it.id == d.id }, onDismiss = { editing = null }, onSave = {
            DayBook.put(ctx, it)
            editing = null
            changed()
        }, onDelete = {
            DayBook.remove(ctx, d.id)
            editing = null
            changed()
        })
    }
    if (importing) {
        ImportDialog(onDismiss = { importing = false }) { picked ->
            DayBook.save(ctx, DayBook.all(ctx) + picked)
            importing = false
            changed()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DayDialog(day: Day, isNew: Boolean, onDismiss: () -> Unit, onSave: (Day) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(day.name) }
    var kind by remember { mutableStateOf(day.kind) }
    var month by remember { mutableStateOf(day.month) }
    var dayOf by remember { mutableStateOf(day.day) }
    var message by remember { mutableStateOf(day.message) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add a date" else "Edit date") },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(day.copy(name = name.trim(), kind = kind, month = month, day = dayOf, message = message.trim()))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(if (kind == Day.Kind.SPECIAL) "What is it" else "Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in Day.Kind.entries) FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                }
                OutlinedButton(onClick = { picking = true }) {
                    Text(LocalDate.of(2024, month, dayOf).format(DateTimeFormatter.ofPattern("d MMMM")))
                }
                OutlinedTextField(
                    message, { message = it },
                    label = { Text("Message for the day (optional)") },
                    placeholder = { Text("Something to say or remember") },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
    if (picking) {
        // The year is not kept: the date comes round every year.
        val year = if (month == 2 && dayOf == 29) 2028 else LocalDate.now().year
        val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.of(year, month, dayOf).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        month = d.monthValue
                        dayOf = d.dayOfMonth
                    }
                    picking = false
                }) { Text("OK") }
            },
        ) { DatePicker(state, showModeToggle = false) }
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (List<Day>) -> Unit) {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(ContactDates.allowed(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val have = remember { DayBook.all(ctx).mapNotNull { it.source }.toSet() }
    val found = remember(allowed) { if (allowed) ContactDates.read(ctx).filter { it.source !in have } else emptyList() }
    var picked by remember(found) { mutableStateOf(found.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from contacts") },
        confirmButton = {
            if (allowed) TextButton(enabled = picked.isNotEmpty(), onClick = { onImport(found.filter { it.id in picked }) }) { Text("Import ${picked.size}") }
            else TextButton(onClick = { ask.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow contacts") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            when {
                !allowed -> Text("Diecut reads the birthdays and anniversaries saved in your contacts (Google contacts sync them there). Nothing leaves your phone.")
                found.isEmpty() -> Text("No new birthdays or anniversaries in your contacts.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(found, key = { it.id }) { d ->
                        val on = d.id in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (on) picked - d.id else picked + d.id }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(d.name)
                                Text("${d.kind.label} · ${LocalDate.of(2024, d.month, d.day).format(DateTimeFormatter.ofPattern("d MMM"))}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Someone's day, full screen: confetti, what to say, and a way to say it. */
class CelebrateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val days = if (id == AppWidgetManager.INVALID_APPWIDGET_ID) DayBook.all(this) else DayBook.forSpec(this, Store.spec(this, id))
        val today = Upcoming.groups(days, LocalDate.now(), 1).firstOrNull()?.takeIf { it.daysAway == 0 }?.people ?: emptyList()
        setContent { DiecutTheme { Celebrate(today) { finish() } } }
    }
}

private class Piece(val x: Float, val phase: Float, val speed: Int, val sway: Float, val spin: Int, val w: Float, val color: Color)

private val partyColours = listOf(0xFFE8483A, 0xFFF2B33D, 0xFF3DA35D, 0xFF3B7DD8, 0xFFB65FCF, 0xFFF27BA3, 0xFFFFFFFF).map { Color(it) }

@Composable
private fun Celebrate(people: List<Day>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val pieces = remember {
        val r = Random(5)
        List(90) { Piece(r.nextFloat(), r.nextFloat(), 1 + r.nextInt(2), 8f + r.nextFloat() * 22f, if (r.nextBoolean()) 1 else -1, 7f + r.nextFloat() * 8f, partyColours[r.nextInt(partyColours.size)]) }
    }
    val t by rememberInfiniteTransition(label = "confetti").animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "t")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B1E3F), Color(0xFF7A3B5E), Color(0xFFE9A07A))))) {
        Canvas(Modifier.fillMaxSize()) {
            for (p in pieces) {
                // Whole numbers of falls a loop, so the loop has no seam.
                val y = ((p.phase + t * p.speed) % 1f) * (size.height + 60f) - 30f
                val x = p.x * size.width + p.sway * sin((t * 4 + p.phase) * 2 * Math.PI).toFloat()
                rotate(degrees = 360f * t * 3 * p.spin, pivot = Offset(x, y)) {
                    drawRect(p.color, topLeft = Offset(x - p.w / 2, y - p.w / 4), size = Size(p.w, p.w / 2))
                }
            }
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🎉", fontSize = 72.sp)
            Spacer(Modifier.height(12.dp))
            if (people.isEmpty()) {
                Text("No one's day today.", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            } else {
                Text("Today is", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
                Text(Upcoming.occasion(people), color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                for (d in people) {
                    if (d.message.isNotBlank()) {
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Remember, for ${d.name}", style = MaterialTheme.typography.labelLarge)
                                Text(d.message, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                            }
                        }
                    }
                    Button(onClick = { wish(ctx, d) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("Wish ${d.name}") }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDone) { Text("Done", color = Color.White) }
        }
    }
}

/** A greeting, ready to send in any app. */
private fun wish(ctx: Context, d: Day) {
    val text = when (d.kind) {
        Day.Kind.BIRTHDAY -> "Happy birthday, ${d.name}! 🎂"
        Day.Kind.ANNIVERSARY -> "Happy anniversary, ${d.name}! 💐"
        Day.Kind.SPECIAL -> "Thinking of you today: ${d.name} 🎉"
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Wish ${d.name}").apply { if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
}
