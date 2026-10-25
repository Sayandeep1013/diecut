package app.diecut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Pictures from the system Photo Picker (no storage permission), copied in at a widget's size. */
object Photos {
    private const val LONG_SIDE = 1600

    /** Copy [uri] into app storage, upright and no more than [LONG_SIDE] px long. Returns its file name. */
    fun import(ctx: Context, uri: Uri): String? = try {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            val long = max(info.size.width, info.size.height)
            if (long > LONG_SIDE) {
                val k = LONG_SIDE.toFloat() / long
                dec.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
            }
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val name = "${UUID.randomUUID()}.jpg"
        File(Store.photoDir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        name
    } catch (e: Exception) {
        null
    }
}

class AppEntry(val component: String, val label: String)

/** The apps a launcher lists (the manifest's <queries> makes them visible). */
object Apps {
    fun launchable(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { AppEntry(ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString(), it.loadLabel(pm).toString()) }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Four apps a new apps widget starts with, so it looks like its preview
     * at once: the phone, messages, browser and camera apps, then any others.
     */
    fun suggested(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val wants = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        )
        val picked = wants.mapNotNull { want ->
            val pkg = pm.resolveActivity(want, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: return@mapNotNull null
            pm.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
        }.distinct()
        return (picked + launchable(ctx).map { it.component }).distinct().take(4)
    }

    fun icon(ctx: Context, component: String) = try {
        ctx.packageManager.getActivityIcon(ComponentName.unflattenFromString(component)!!)
    } catch (e: Exception) {
        null
    }
}
