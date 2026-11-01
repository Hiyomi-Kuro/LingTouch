package com.kaori.lingtouch.vision

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.kaori.lingtouch.R

/** Transparent consent screen for MediaProjection; forwards the grant to the service. */
class ScreenCapturePermissionActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
            Toast.makeText(this, R.string.settings_screenshot, Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
        } else {
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { launcher.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
    }
}
