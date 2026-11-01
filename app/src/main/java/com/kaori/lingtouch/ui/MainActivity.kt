package com.kaori.lingtouch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kaori.lingtouch.agent.AgentStatus
import com.kaori.lingtouch.agent.ConfirmationBroker
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.databinding.ActivityMainBinding
import com.kaori.lingtouch.overlay.OverlayService
import com.kaori.lingtouch.vision.ScreenCapturePermissionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SHOW_CONFIRMATION = "show_confirmation"
        const val EXTRA_RESULT_TASK_ID = "result_task_id"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var home: HomeController
    private lateinit var apps: AppsController
    private lateinit var memory: MemoryController
    private lateinit var settings: SettingsController
    private var currentTab = R.id.tab_home
    private var suppressNavCallback = false
    private var confirmDialog: androidx.appcompat.app.AlertDialog? = null
    private var confirmRequestId = -1L
    private var pendingResultTaskId = -1L
    private var shownResultTaskId = -1L
    private var activeTaskId = -1L
    private val app get() = LingTouchApp.get(this)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshAll() }

    private val capturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.settings_screenshot, Toast.LENGTH_SHORT).show()
        }
        refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        home = HomeController(this, binding.homeView)
        apps = AppsController(this, binding.appsView)
        memory = MemoryController(this, binding.memoryView)
        settings = SettingsController(this, binding.settingsView)

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (!suppressNavCallback) showTab(item.itemId)
            true
        }
        showTab(R.id.tab_home)
        observeAgentPrompts()
        handleIntent(intent)

        if (!app.settings.state.value.guideDone) {
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }
        requestNotificationPermissionIfNeeded()
        if (app.settings.state.value.overlayEnabled) startOverlayIfPossible()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        home.refresh()
        settings.onShown()
        if (currentTab == R.id.tab_apps) apps.onShown()
        if (currentTab == R.id.tab_memory) memory.onShown()
    }

    fun showTab(itemId: Int) {
        currentTab = itemId
        binding.homeView.root.visibility = if (itemId == R.id.tab_home) View.VISIBLE else View.GONE
        binding.appsView.root.visibility = if (itemId == R.id.tab_apps) View.VISIBLE else View.GONE
        binding.memoryView.root.visibility = if (itemId == R.id.tab_memory) View.VISIBLE else View.GONE
        binding.settingsView.root.visibility = if (itemId == R.id.tab_settings) View.VISIBLE else View.GONE
        if (binding.bottomNav.selectedItemId != itemId) {
            suppressNavCallback = true
            binding.bottomNav.selectedItemId = itemId
            suppressNavCallback = false
        }
        when (itemId) {
            R.id.tab_home -> home.onShown()
            R.id.tab_apps -> apps.onShown()
            R.id.tab_memory -> memory.onShown()
            R.id.tab_settings -> settings.onShown()
        }
    }

    override fun onDestroy() {
        confirmDialog?.dismiss()
        confirmDialog = null
        super.onDestroy()
    }

    fun openTask(taskId: Long) {
        val intent = Intent(this, TaskActivity::class.java)
            .putExtra(TaskActivity.EXTRA_TASK_ID, taskId)
        startActivity(intent)
    }

    fun openMemoryFor(pkg: String) {
        showTab(R.id.tab_memory)
        memory.filterPackage(pkg)
    }

    fun openPermissionGuide() {
        startActivity(Intent(this, PermissionGuideActivity::class.java))
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.perm_granted, Toast.LENGTH_SHORT).show()
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun requestScreenCapture() {
        capturePermission.launch(Intent(this, ScreenCapturePermissionActivity::class.java))
    }

    fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_SHORT).show() }
    }

    private fun startOverlayIfPossible() {
        if (!Settings.canDrawOverlays(this)) return
        OverlayService.start(this)
    }

    private fun observeAgentPrompts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.agent.state.collect { onAgentState(it) } }
                launch { app.agent.confirmation.pending.collect { onPendingConfirmation(it) } }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val resultTaskId = intent?.getLongExtra(EXTRA_RESULT_TASK_ID, -1L) ?: -1L
        if (resultTaskId > 0) {
            pendingResultTaskId = resultTaskId
            window.decorView.post { showPendingResult() }
        }
    }

    private fun onAgentState(state: com.kaori.lingtouch.agent.AgentUiState) {
        if (state.isActive) activeTaskId = state.taskId
        if (state.status != AgentStatus.COMPLETED && state.status != AgentStatus.FAILED) return
        if (state.taskId <= 0) return
        if (state.taskId != activeTaskId && state.taskId != pendingResultTaskId) return
        activeTaskId = -1L
        pendingResultTaskId = -1L
        showResultDialog(state.taskId, state.status, state.result.ifBlank { state.error.orEmpty() })
    }

    private fun onPendingConfirmation(request: ConfirmationBroker.Request?) {
        if (request == null) {
            confirmDialog?.dismiss()
            confirmDialog = null
            confirmRequestId = -1L
            return
        }
        if (confirmRequestId == request.id) return
        confirmRequestId = request.id
        confirmDialog?.dismiss()
        confirmDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.task_confirm_title)
            .setMessage(request.reason)
            .setPositiveButton(request.confirmLabel ?: getString(R.string.task_confirm_allow)) { _, _ ->
                app.agent.confirm(request.id, true)
                confirmRequestId = -1L
            }
            .setNegativeButton(request.denyLabel ?: getString(R.string.task_confirm_deny)) { _, _ ->
                app.agent.confirm(request.id, false)
                confirmRequestId = -1L
            }
            .setCancelable(false)
            .show()
    }

    private fun showPendingResult() {
        val id = pendingResultTaskId
        if (id <= 0) return
        val state = app.agent.state.value
        if (state.taskId == id &&
            (state.status == AgentStatus.COMPLETED || state.status == AgentStatus.FAILED)
        ) {
            pendingResultTaskId = -1L
            showResultDialog(id, state.status, state.result.ifBlank { state.error.orEmpty() })
            return
        }
        lifecycleScope.launch {
            val task = withContext(Dispatchers.IO) { app.db.task(id) }
            pendingResultTaskId = -1L
            if (task != null) {
                val status = if (task.status == "SUCCESS") AgentStatus.COMPLETED else AgentStatus.FAILED
                showResultDialog(id, status, task.summary.orEmpty())
            }
        }
    }

    private fun showResultDialog(taskId: Long, status: AgentStatus, message: String) {
        if (shownResultTaskId == taskId) return
        shownResultTaskId = taskId
        val title = when (status) {
            AgentStatus.COMPLETED -> R.string.task_done
            AgentStatus.FAILED -> R.string.task_failed
            else -> R.string.task_cancelled
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message.ifBlank { getString(title) })
            .setPositiveButton(R.string.action_details) { _, _ -> openTask(taskId) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun refreshAll() {
        home.refresh()
        settings.onShown()
        if (currentTab == R.id.tab_apps) apps.onShown()
        if (currentTab == R.id.tab_memory) memory.onShown()
    }
}
