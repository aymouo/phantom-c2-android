package com.openaccess.sdk

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.openaccess.sdk.service.DisplayCapture

class ScreenCaptureActivity : Activity() {
    companion object {
        private const val TAG = "ScreenCaptureActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request screen capture: ${e.message}")
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                DisplayCapture.setProjection(resultCode, data)
                DisplayCapture.initProjection(this)
                Log.d(TAG, "MediaProjection granted")
            } else {
                Log.d(TAG, "MediaProjection denied")
            }
        }
        finish()
    }
}
