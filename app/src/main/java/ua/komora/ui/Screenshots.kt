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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import ua.komora.R
import ua.komora.net.GitHub

/** Screenshots already fetched while Komora is open: asked for once per app, not on every look. */
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

/**
 * The app's screenshots in a row, fetched from its repository only now that the details are open.
 * Nothing is shown while there are none (or no connection). A tap opens them full screen.
 */
@Composable
fun Screenshots(repo: String, lang: String) {
    val key = "$repo|$lang"
    var shots by remember(key) { mutableStateOf(ScreenshotCache.get(key)) }
    LaunchedEffect(key) {
        if (shots != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            // Stops between requests once the details are closed; a failure is not remembered, so it is tried again
            runCatching { GitHub.screenshots(repo, lang) { !isActive } }.getOrNull()?.mapNotNull { decode(it) }
                ?.also { ScreenshotCache.put(key, it) }
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
