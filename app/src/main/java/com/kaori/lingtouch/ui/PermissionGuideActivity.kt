package com.kaori.lingtouch.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.databinding.ActivityPermissionGuideBinding
import com.kaori.lingtouch.vision.ScreenCapturePermissionActivity
import com.kaori.lingtouch.vision.ScreenCaptureService

class PermissionGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionGuideBinding
    private val app get() = LingTouchApp.get(this)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    private val capturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.guideAccessButton.setOnClickListener { AccessibilityBridge.openSettings(this) }
        binding.guideOverlayButton.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                )
            }
        }
        binding.guideNotificationButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.guideScreenshotButton.setOnClickListener {
            capturePermission.launch(Intent(this, ScreenCapturePermissionActivity::class.java))
        }
        binding.guideShizukuButton.setOnClickListener {
            val state = app.shizuku.state.value
            if (!state.running) {
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, app.shizuku.requestPermission(), Toast.LENGTH_LONG).show()
            app.shizuku.refresh()
        }
        binding.guideDoneButton.setOnClickListener {
            app.settings.markGuideDone()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        binding.guideAccessStatus.text = status(AccessibilityBridge.isConnected())
        binding.guideOverlayStatus.text = status(Settings.canDrawOverlays(this))
        binding.guideNotificationStatus.text = status(NotificationManagerCompat.from(this).areNotificationsEnabled())
        binding.guideScreenshotStatus.text = status(ScreenCaptureService.isReady)
        val shizuku = app.shizuku.state.value
        binding.guideShizukuStatus.text = shizuku.message
        binding.guideShizukuButton.text = if (shizuku.permission) {
            getString(R.string.perm_granted)
        } else {
            getString(R.string.action_grant)
        }
    }

    private fun status(granted: Boolean): String =
        if (granted) getString(R.string.perm_granted) else getString(R.string.perm_denied)
}
