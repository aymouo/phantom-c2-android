package com.openaccess.sdk.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotifService : NotificationListenerService() {
    companion object {
        private const val TAG = "NotifService"
        private val notifications = mutableListOf<CapturedNotif>()

        fun getNotifications(): List<CapturedNotif> {
            synchronized(notifications) { return notifications.toList() }
        }

        data class CapturedNotif(
            val packageName: String,
            val title: String,
            val text: String,
            val time: Long
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification.extras ?: return
            val title = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
            val text = extras.getString(android.app.Notification.EXTRA_TEXT) ?: ""
            if (title.isEmpty() && text.isEmpty()) return
            val cap = CapturedNotif(
                packageName = sbn.packageName,
                title = title,
                text = text,
                time = sbn.postTime
            )
            synchronized(notifications) {
                notifications.add(0, cap)
                while (notifications.size > 100) notifications.removeAt(notifications.lastIndex)
            }
            Log.i(TAG, "Captured: $title")
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notif listener connected")
    }
}
