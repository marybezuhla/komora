package ua.komora.notify

import android.app.job.JobParameters
import android.app.job.JobService

/** Android runs this once a day while update notices are on; the work happens on its own thread. */
class UpdateJob : JobService() {
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread {
            val done = try {
                UpdateCheck.run(applicationContext) { stopped }
            } catch (_: Throwable) {
                // Never a crash in the background; tried again tomorrow
                true
            }
            // Stopped by Android: it already knows; otherwise a lost connection is tried again later
            if (!stopped) jobFinished(params, !done)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }
}
