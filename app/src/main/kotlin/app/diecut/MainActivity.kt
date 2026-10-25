package app.diecut

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/** The app: add a widget to the home screen, or change one already there. */
class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DayAlarms.schedule(this)
        setContent { DiecutTheme { Home(tick) } }
    }

    override fun onResume() {
        super.onResume()
        tick++
        // A widget just added from here goes straight to its editor.
        Store.takePending(this)?.let { id ->
            if (id in WidgetDraw.allIds(this)) startActivity(WidgetDraw.editorIntent(this, id))
        }
    }
}

private class Kind(val content: Content, val provider: Class<*>, val about: String)

private val kinds = listOf(
    Kind(Content.PHOTO, PhotoWidget::class.java, "A picture, or a few that take turns"),
    Kind(Content.CLOCK, ClockWidget::class.java, "Digital or analogue, always on time"),
    Kind(Content.APPS, AppsWidget::class.java, "Up to eight apps, each a tap away"),
    Kind(Content.DATES, DatesWidget::class.java, "Days to go to birthdays and special dates"),
)

private fun pin(ctx: Context, kind: Kind) {
    val mgr = AppWidgetManager.getInstance(ctx)
    // The launcher fills in the new widget's id; the callback remembers it.
    val done = PendingIntent.getBroadcast(
        ctx, kind.content.ordinal,
        Intent(ctx, TapReceiver::class.java).setAction(TapReceiver.ACTION_PINNED),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    mgr.requestPinAppWidget(ComponentName(ctx, kind.provider), null, done)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(tick: Int) {
    val ctx = LocalContext.current
    val ids = remember(tick) { WidgetDraw.allIds(ctx) }
    val canPin = remember { AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported }
    Scaffold(topBar = { TopAppBar(title = { Text("Diecut") }) }) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Home-screen widgets cut to any shape. Where a shape cuts in, the wallpaper shows through.", style = MaterialTheme.typography.bodyLarge)
            }
            item { Heading("Add a widget") }
            items(kinds) { kind ->
                ElevatedCard(onClick = { if (canPin) pin(ctx, kind) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${kind.content.label} widget", style = MaterialTheme.typography.titleMedium)
                        Text(kind.about, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (!canPin) item {
                Text("Your launcher adds widgets itself: long-press the home screen, open Widgets and find Diecut.")
            }
            item {
                ElevatedCard(onClick = { ctx.startActivity(Intent(ctx, DatesActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Your dates", style = MaterialTheme.typography.titleMedium)
                        Text("Birthdays, anniversaries and special days, with a message for each", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { Heading("On your home screen") }
            if (ids.isEmpty()) item { Text("None yet.", style = MaterialTheme.typography.bodyMedium) }
            items(ids, key = { it }) { id -> Placed(id, tick) }
        }
    }
}

@Composable
private fun Heading(s: String) {
    Text(s, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Placed(id: Int, tick: Int) {
    val ctx = LocalContext.current
    val spec = remember(tick) { Store.spec(ctx, id) }
    val (w, h) = remember(tick) { WidgetDraw.size(ctx, id, spec.content) }
    val bmp by produceState<Bitmap?>(null, spec, tick) {
        value = withContext(Dispatchers.Default) { Renderer.render(ctx, spec, w, h, ctx.resources.displayMetrics.density, preview = true).bitmap }
    }
    val k = min(88f / h, 140f / w)
    ElevatedCard(onClick = { ctx.startActivity(WidgetDraw.editorIntent(ctx, id)) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(150.dp, 96.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF3F5E96), Color(0xFFE9A07A)))),
                contentAlignment = Alignment.Center,
            ) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.size((w * k).dp, (h * k).dp)) }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("${spec.content.label} · ${spec.shape.label}", style = MaterialTheme.typography.titleMedium)
                Text(Palettes.resolve(ctx, spec.palette).label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
