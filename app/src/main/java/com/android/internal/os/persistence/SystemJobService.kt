package com.android.internal.os.persistence

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent

class SystemJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            val intent = Intent(this, Class.forName("com.android.internal.os.BootService"))
            startService(intent)
        } catch (_: Exception) {}
        jobFinished(params, true)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
