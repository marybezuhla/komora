package ua.komora

import android.content.Context
import ua.komora.net.ReleaseStore
import ua.komora.net.ScreenshotStore
import java.io.File

/** What Komora keeps from GitHub, one store each for the whole app, so checks on screen and in the background share it. */
object Stores {
    private var releases: ReleaseStore? = null
    private var screenshots: ScreenshotStore? = null
    private var sourceScreenshots: ScreenshotStore? = null
    private var sourceIcons: ScreenshotStore? = null

    /** Release answers: in a folder Android never backs up or moves to another phone. */
    @Synchronized
    fun releases(context: Context): ReleaseStore = releases
        ?: ReleaseStore(File(context.applicationContext.noBackupFilesDir, "releases")).also { releases = it }

    /** Screenshots: in the cache, which Android may empty when space runs low (they are simply fetched again). */
    @Synchronized
    fun screenshots(context: Context): ScreenshotStore = screenshots
        ?: ScreenshotStore(File(context.applicationContext.cacheDir, "screenshots")).also { screenshots = it }

    /**
     * Added sources' screenshots and icons (only when the owner turned them on): kept apart from the
     * list's own, so turning them off removes every one of them at once.
     */
    @Synchronized
    fun sourceScreenshots(context: Context): ScreenshotStore = sourceScreenshots
        ?: ScreenshotStore(File(context.applicationContext.cacheDir, "source-screenshots")).also { sourceScreenshots = it }

    @Synchronized
    fun sourceIcons(context: Context): ScreenshotStore = sourceIcons
        ?: ScreenshotStore(File(context.applicationContext.cacheDir, "source-icons"), maxBytes = 5L * 1024 * 1024).also { sourceIcons = it }

    /** Every added source's picture gone from the phone. */
    @Synchronized
    fun forgetSourcePictures(context: Context) {
        File(context.applicationContext.cacheDir, "source-screenshots").deleteRecursively()
        File(context.applicationContext.cacheDir, "source-icons").deleteRecursively()
    }
}
