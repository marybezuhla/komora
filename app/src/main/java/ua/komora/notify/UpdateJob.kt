package ua.komora.notify

import android.app.job.JobParameters
import android.app.job.JobService

/** Android runs this once a day while update notices are on; the work happens on its own thread. */
class UpdateJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            val done = try {
                UpdateCheck.run(applicationContext)
            } catch (_: Exception) {
                false
            }
            // Not done (no connection, GitHub's limit): Android tries again a bit later
            jobFinished(params, !done)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}
