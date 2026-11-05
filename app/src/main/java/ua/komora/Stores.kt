package ua.komora

import android.content.Context
import ua.komora.net.ReleaseStore
import ua.komora.net.ScreenshotStore
import java.io.File

/** What Komora keeps from GitHub, one store each for the whole app, so checks on screen and in the background share it. */
object Stores {
    private var releases: ReleaseStore? = null
    private var screenshots: ScreenshotStore? = null

    /** Release answers: in a folder Android never backs up or moves to another phone. */
    @Synchronized
    fun releases(context: Context): ReleaseStore = releases
        ?: ReleaseStore(File(context.applicationContext.noBackupFilesDir, "releases")).also { releases = it }

    /** Screenshots: in the cache, which Android may empty when space runs low (they are simply fetched again). */
    @Synchronized
    fun screenshots(context: Context): ScreenshotStore = screenshots
        ?: ScreenshotStore(File(context.applicationContext.cacheDir, "screenshots")).also { screenshots = it }
}
