package com.openaccess.sdk

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.openaccess.sdk.service.DisplayCapture

class ScreenCaptureActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ScreenCaptureActivity"
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            DisplayCapture.setProjection(result.resultCode, result.data!!)
            DisplayCapture.initProjection(this)
            Log.d(TAG, "MediaProjection granted successfully")
        } else {
            Log.d(TAG, "MediaProjection denied by user")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val projIntent = DisplayCapture.getProjectionIntent(this)
            screenCaptureLauncher.launch(projIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch screen capture: ${e.message}")
            finish()
        }
    }
}
