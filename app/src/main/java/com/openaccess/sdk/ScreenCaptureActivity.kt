package com.openaccess.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.openaccess.sdk.service.DisplayCapture

class ScreenCaptureActivity : Activity() {
    companion object {
        private const val RC_SCREEN = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val projIntent = DisplayCapture.getProjectionIntent(this)
            startActivityForResult(projIntent, RC_SCREEN)
        } catch (e: Exception) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SCREEN) {
            if (resultCode == RESULT_OK && data != null) {
                DisplayCapture.setProjection(resultCode, data)
                DisplayCapture.initProjection(this)
            }
        }
        finish()
    }
}
