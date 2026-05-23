package com.android.internal.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(ctx: Context, intent: Intent) {}
    override fun onDisabled(ctx: Context, intent: Intent) {}
}
