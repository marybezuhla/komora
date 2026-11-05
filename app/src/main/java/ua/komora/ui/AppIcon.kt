package ua.komora.ui
import androidx.compose.runtime.getValue
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import ua.komora.Stores
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ua.komora.net.Rules
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.komora.R

/**
 * An installed app's icon, drawn round like the bundled ones: the two layers of an adaptive icon are
 * drawn in full and the middle cut out as a circle, so every launcher style looks the same here.
 */
private fun installedIcon(context: Context, packageName: String, px: Int): ImageBitmap? = try {
    val drawable = context.packageManager.getApplicationIcon(packageName)
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val circle = android.graphics.Path().apply { addCircle(px / 2f, px / 2f, px / 2f, android.graphics.Path.Direction.CW) }
    canvas.clipPath(circle)
    if (drawable is AdaptiveIconDrawable) {
        // A layer is 108 dp, of which the middle 72 dp is the visible icon
        val full = px * 1.5f
        val offset = ((px - full) / 2f).toInt()
        for (layer in listOfNotNull(drawable.background, drawable.foreground)) {
            layer.setBounds(offset, offset, offset + full.toInt(), offset + full.toInt())
            layer.draw(canvas)
        }
    } else {
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
    }
    bitmap.asImageBitmap()
} catch (_: Exception) {
    // An icon Android cannot draw: the bundled copy or the letters instead
    null
}

/**
 * Each app's own icon, copied from its repository into Komora (round, as most launchers show it).
 * Listed by hand, not looked up by name, so the build's resource shrinking keeps them.
 */
private val APP_ICONS = mapOf(
    "ua.komora" to R.drawable.app_icon_komora,
    "ua.tarilka" to R.drawable.app_icon_tarilka,
    "ua.mandat" to R.drawable.app_icon_mandat,
    "ua.sprotyv" to R.drawable.app_icon_sprotyv,
    "ua.pysanka" to R.drawable.app_icon_pysanka,
    "ua.svitlyna" to R.drawable.app_icon_svitlyna,
    "ua.hramota" to R.drawable.app_icon_hramota,
    "ua.surma" to R.drawable.app_icon_surma,
    "ua.shyriai" to R.drawable.app_icon_shyriai,
)

internal val ICON = 48.dp

/**
 * The app's icon. Once installed, it comes from the app itself on this phone, so a new icon shows as
 * soon as the app is updated; before that, the copy bundled in Komora, or for an added app
 * ([addedRepo]) the icon from its repository if the owner turned added apps' pictures on. An app with
 * none of these gets a small circle with its first letters. Decoration only.
 */
@Composable
internal fun AppIcon(packageName: String, name: String, color: Long, installedVersion: String?, addedRepo: String? = null, tag: String? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { ICON.roundToPx() }
    // Read again whenever the installed version changes (an update may bring a new icon)
    val installed = remember(packageName, installedVersion) {
        if (installedVersion == null) null else installedIcon(context, packageName, px)
    }
    if (installed != null) {
        Image(installed, contentDescription = null, modifier = Modifier.size(ICON).clearAndSetSemantics { })
        return
    }
    if (addedRepo != null) {
        val version by SourcePictures.version.collectAsState()
        val fetched by androidx.compose.runtime.produceState<ImageBitmap?>(null, addedRepo, tag, version) {
            value = withContext(Dispatchers.IO) {
                if (!ua.komora.catalog.Sources.pictures(context)) null
                else Stores.sourceIcons(context).get(addedRepo, SourcePictures.ICON, SourcePictures.ICON_ONCE)?.firstOrNull()?.let { smallIcon(it, px) }
            }
        }
        fetched?.let {
            Image(it, contentDescription = null, modifier = Modifier.size(ICON).clip(CircleShape).clearAndSetSemantics { })
            return
        }
    }
    val icon = APP_ICONS[packageName]
    if (icon != null) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(ICON).clip(CircleShape).clearAndSetSemantics { })
        return
    }
    Box(
        Modifier.size(ICON).background(Color(color), CircleShape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(2).replaceFirstChar { it.uppercase() }, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** An added app's icon, decoded small: only a PNG (by its first bytes) of sane size, never larger than [px] on screen. */
private fun smallIcon(bytes: ByteArray, px: Int): ImageBitmap? = try {
    val png = bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    if (!png) null else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w > Rules.MAX_ICON_SIDE || h > Rules.MAX_ICON_SIDE) null else {
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= px) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }
    }
} catch (_: Throwable) {
    // A broken picture, or one too big for memory (OutOfMemoryError, hence Throwable): not shown, never a crash
    null
}
