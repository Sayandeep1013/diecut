package app.diecut

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * The editor: a widget's content, shape and colours, with a live preview.
 * It is also the widget's configuration activity, opened when a widget is
 * added and again to change it. Every change is saved and drawn at once.
 */
class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // The defaults are a fine widget, so leaving early still keeps it.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        setContent { DiecutTheme { Editor(id) { finish() } } }
    }
}

@Composable
fun DiecutTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), content = content)
}

private val turns = listOf(0 to "On tap", 30 to "30 min", 60 to "Hourly", 360 to "6 hours", 1440 to "Daily")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Editor(id: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var spec by remember { mutableStateOf(Store.spec(ctx, id)) }
    val size = remember { WidgetDraw.size(ctx, id, spec.content) }
    val (w, h) = size
    fun update(s: Spec) {
        spec = s
        scope.launch(Dispatchers.Default) {
            Store.put(ctx, id, s)
            WidgetDraw.draw(ctx, AppWidgetManager.getInstance(ctx), id)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val names = withContext(Dispatchers.IO) { uris.mapNotNull { Photos.import(ctx, it) } }
            update(spec.copy(photos = spec.photos + names))
        }
    }
    var choosingApps by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Edit widget") }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Preview(spec, w, h)

            Section("Content")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Content.entries.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = spec.content == c,
                        onClick = { update(spec.copy(content = c)) },
                        shape = SegmentedButtonDefaults.itemShape(i, Content.entries.size),
                    ) { Text(c.label) }
                }
            }
            when (spec.content) {
                Content.PHOTO -> {
                    OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (spec.photos.isEmpty()) "Choose photos" else "Add photos")
                    }
                    if (spec.photos.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(spec.photos, key = { it }) { name ->
                                PhotoThumb(name, current = name == spec.currentPhoto()) {
                                    val rest = spec.photos - name
                                    update(spec.copy(photos = rest, photoIndex = 0))
                                }
                            }
                        }
                        Text("Tap a picture to remove it.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (spec.photos.size > 1) {
                        Text("Next photo", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((minutes, label) in turns) {
                                FilterChip(selected = spec.turnMinutes == minutes, onClick = {
                                    update(spec.copy(turnMinutes = minutes, lastTurn = System.currentTimeMillis()))
                                }, label = { Text(label) })
                            }
                        }
                    }
                }
                Content.CLOCK -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (style in ClockStyle.entries) {
                            FilterChip(selected = spec.clock == style, onClick = { update(spec.copy(clock = style)) }, label = { Text(style.label) })
                        }
                    }
                    if (spec.clock != ClockStyle.ANALOGUE) {
                        Toggle("Show the date", spec.showDate) { update(spec.copy(showDate = it)) }
                    }
                }
                Content.APPS -> {
                    if (spec.apps.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (a in spec.apps) AppIcon(ctx, a)
                        }
                    }
                    OutlinedButton(onClick = { choosingApps = true }) {
                        Text(if (spec.apps.isEmpty()) "Choose apps (up to 8)" else "Change apps (${spec.apps.size} of 8)")
                    }
                    Toggle("Grow with the apps", spec.fit) { update(spec.copy(fit = it)) }
                    Text(
                        "Like a dock: the shape gets longer with each app. Resizing the widget on the home screen turns this off and fills the size you set.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Toggle("Monochrome icons", spec.mono) { update(spec.copy(mono = it)) }
                    Toggle("Show names", spec.labels) { update(spec.copy(labels = it)) }
                }
                Content.DATES -> {
                    Toggle("Count down to every date", spec.allDates) { update(spec.copy(allDates = it)) }
                    Text(
                        if (spec.allDates) "Shows whichever date comes next. Turn this off to pick the dates (or the one date) this widget follows."
                        else "Tick the dates this widget follows.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DatesManager(
                        selected = if (spec.allDates) null else spec.dates,
                        onToggle = { id -> update(spec.copy(dates = if (id in spec.dates) spec.dates - id else spec.dates + id)) },
                    )
                }
            }

            Section("Shape")
            ShapeRow(spec, w, h) { update(spec.copy(shape = it)) }

            Section("Colour")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in Palettes.all(ctx)) {
                    val on = p.id == spec.palette
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color(p.paper))
                            .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { update(spec.copy(palette = p.id)) }
                            .semantics { contentDescription = p.label },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(p.ink))) }
                }
            }
            Text(Palettes.resolve(ctx, spec.palette).label, style = MaterialTheme.typography.bodySmall)

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Done") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (choosingApps) {
        AppPicker(spec.apps, onDismiss = { choosingApps = false }) {
            choosingApps = false
            update(spec.copy(apps = it))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = set)
    }
}

/** The widget as it will look, on a stand-in wallpaper so the cut-outs show. */
@Composable
fun Preview(spec: Spec, w: Float, h: Float) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, spec, w, h) {
        value = withContext(Dispatchers.Default) {
            Renderer.render(ctx, spec, w, h, ctx.resources.displayMetrics.density, preview = true).bitmap
        }
    }
    val k = min(1f, min(300f / w, 220f / h))
    Box(
        Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3F5E96), Color(0xFF9B7FB8), Color(0xFFE9A07A))))
            .semantics { contentDescription = "Widget preview" },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.size((w * k).dp, (h * k).dp), contentScale = ContentScale.FillBounds) }
    }
}

@Composable
private fun PhotoThumb(name: String, current: Boolean, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { Renderer.load(File(Store.photoDir(ctx), name), 200) }
    }
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
            .border(if (current) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onRemove)
            .semantics { contentDescription = "Remove photo" },
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(72.dp), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun ShapeRow(spec: Spec, w: Float, h: Float, pick: (Shape) -> Unit) {
    val ctx = LocalContext.current
    val th = 56f
    val tw = th * (w / h).coerceIn(0.75f, 2.2f)
    val thumbs by produceState<Map<Shape, Bitmap>>(emptyMap(), spec.palette, tw) {
        value = withContext(Dispatchers.Default) {
            Shape.entries.associateWith { Renderer.thumb(ctx, it, spec.palette, tw, th, ctx.resources.displayMetrics.density) }
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Shape.entries) { s ->
            val on = s == spec.shape
            Column(
                Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { pick(s) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(tw.dp, th.dp), contentAlignment = Alignment.Center) {
                    thumbs[s]?.let { Image(it.asImageBitmap(), null, Modifier.size(tw.dp, th.dp)) }
                }
                Spacer(Modifier.height(4.dp))
                Text(s.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AppPicker(initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val all by produceState(emptyList<AppEntry>()) { value = withContext(Dispatchers.Default) { Apps.launchable(ctx) } }
    var chosen by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Apps (${chosen.size} of 8)") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    // The ones chosen first, in their order, then the rest.
                    val shown = all.sortedBy { initial.indexOf(it.component).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                        .filter { it.label.contains(query, ignoreCase = true) }
                    items(shown, key = { it.component }) { app ->
                        val on = app.component in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                chosen = when {
                                    on -> chosen - app.component
                                    chosen.size < 8 -> chosen + app.component
                                    else -> chosen
                                }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(ctx, app.component)
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, Modifier.weight(1f))
                            Checkbox(checked = on, onCheckedChange = null)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AppIcon(ctx: Context, component: String) {
    val bmp = remember(component) { Apps.icon(ctx, component)?.toBitmap(96, 96)?.asImageBitmap() }
    if (bmp != null) Image(bmp, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
}
