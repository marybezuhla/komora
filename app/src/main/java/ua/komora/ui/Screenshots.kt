package ua.komora.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import ua.komora.R
import ua.komora.Stores
import ua.komora.net.GitHub
import ua.komora.net.ScreenshotStore

/** Screenshots already shown while Komora is open, ready to show again without reading them from storage. */
private object ScreenshotCache {
    private val map = LinkedHashMap<String, List<ImageBitmap>>()
    @Synchronized fun get(key: String) = map[key]
    @Synchronized fun put(key: String, value: List<ImageBitmap>) {
        map[key] = value
        // A few apps at most, to keep memory small
        while (map.size > 4) map.remove(map.keys.first())
    }
}

/** Decodes an image no larger than a phone screen needs; anything odd (too big, unreadable) is left out. */
private fun decode(bytes: ByteArray): ImageBitmap? = try {
    // Only what it says it is: PNG, JPEG or WebP by its first bytes, nothing else handed to the decoder
    if (!looksLikeImage(bytes)) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth; val h = bounds.outHeight
    if (w <= 0 || h <= 0 || w > 8000 || h > 8000) null
    else {
        // No side larger than a phone screen needs
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= 1600) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })?.asImageBitmap()
    }
} catch (_: Throwable) {
    null
}

/** PNG, JPEG or WebP by its first bytes (the decoder never sees anything else). */
internal fun looksLikeImage(b: ByteArray): Boolean {
    fun at(i: Int, vararg v: Int) = b.size >= i + v.size && v.withIndex().all { (k, x) -> (b[i + k].toInt() and 0xFF) == x }
    return at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
        at(0, 0xFF, 0xD8, 0xFF) ||
        (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50))
}

/**
 * The app's screenshots in a row. They are kept on the phone and fetched from the repository again only
 * for a new release ([tag]), when the details are open; until the release is known, the kept ones show.
 * Nothing is shown while there are none (or no connection and nothing kept). A tap opens them full screen.
 */
@Composable
fun Screenshots(repo: String, tag: String?, lang: String, added: Boolean = false) {
    val context = LocalContext.current
    val key = "$repo|$lang|$tag|$added"
    var shots by remember(key) { mutableStateOf(ScreenshotCache.get(key)) }
    LaunchedEffect(key) {
        if (shots != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            try {
                // An added app's pictures: their own store, removed at once when turned off
                val store = if (added) Stores.sourceScreenshots(context) else Stores.screenshots(context)
                // The pictures for this release, or (until it is known) the ones kept from before
                val kept = store.get(repo, lang, tag)
                val result = when {
                    kept != null -> ScreenshotStore.Result(kept, complete = tag != null)
                    tag == null -> null
                    // Stops between requests once the details are closed; what could not be fetched is tried again next time
                    else -> GitHub.screenshotList(repo, lang) { !isActive }
                        ?.let { listed -> store.update(repo, lang, tag, listed) { shot -> if (isActive) GitHub.screenshot(shot) else null } }
                        // No connection: the pictures of an earlier release, if any
                        ?: store.get(repo, lang, null)?.let { ScreenshotStore.Result(it, complete = false) }
                }
                if (result == null) null else result.pictures.mapNotNull { decode(it) }
                    // Only a whole set is remembered for this visit; a partial one is tried again next time the details open
                    .also { if (result.complete && isActive) ScreenshotCache.put(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Pictures are only a help: whatever goes wrong, the details show without them
                null
            }
        }
        shots = loaded
    }
    val list = shots.orEmpty()
    if (list.isEmpty()) return
    var open by rememberSaveable(key) { mutableStateOf(-1) }
    val label = stringResource(R.string.screenshot)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(list) { i, img ->
            Image(
                img,
                contentDescription = "$label ${i + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(260.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Image, onClickLabel = stringResource(R.string.open_fullscreen)) { open = i },
            )
        }
    }
    if (open >= 0) {
        Dialog(onDismissRequest = { open = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val pager = rememberPagerState(initialPage = open.coerceIn(0, list.lastIndex)) { list.size }
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { open = -1 }) {
                HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { page ->
                    Image(list[page], contentDescription = "$label ${page + 1}", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp))
                }
                Text(
                    "${pager.currentPage + 1} / ${list.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).clearAndSetSemantics { },
                )
            }
        }
    }
}
