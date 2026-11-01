package com.kaori.lingtouch.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.agent.AgentStatus
import com.kaori.lingtouch.agent.AgentUiState
import com.kaori.lingtouch.databinding.OverlayBallBinding
import com.kaori.lingtouch.databinding.OverlayPanelBinding
import com.kaori.lingtouch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Floating ball + compact control panel rendered above other apps. */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var ball: View? = null
    private var panel: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = LingTouchApp.get(this)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
        startAsForeground()
        addBall()
        scope.launch {
            app.agent.state.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        removePanel()
        ball?.let { runCatching { windowManager.removeView(it) } }
        ball = null
        super.onDestroy()
    }

    private fun addBall() {
        val binding = OverlayBallBinding.inflate(android.view.LayoutInflater.from(this))
        val view = binding.root
        val metrics = resources.displayMetrics
        val savedX = app.settings.state.value.overlayX
        val savedY = app.settings.state.value.overlayY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX else metrics.widthPixels - dp(76)
            y = if (savedY >= 0) savedY else metrics.heightPixels / 3
        }
        ballParams = params

        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        view.setOnTouchListener { v, event ->
            val current = ballParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = current.x
                    startY = current.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    current.x = (startX + dx).coerceIn(0, metrics.widthPixels - dp(56))
                    current.y = (startY + dy).coerceIn(0, metrics.heightPixels - dp(56))
                    runCatching { windowManager.updateViewLayout(v, current) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        app.settings.update { it.copy(overlayX = current.x, overlayY = current.y) }
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { togglePanel() }
        runCatching { windowManager.addView(view, params) }
        ball = view
    }

    private fun togglePanel() {
        if (panel != null) {
            removePanel()
        } else {
            addPanel()
        }
    }

    private fun addPanel() {
        val binding = OverlayPanelBinding.inflate(android.view.LayoutInflater.from(this))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = resources.displayMetrics
            x = (ballParams?.x ?: dp(16)).coerceIn(0, maxOf(0, metrics.widthPixels - dp(260)))
            y = ((ballParams?.y ?: dp(100)) + dp(64)).coerceIn(0, maxOf(0, metrics.heightPixels - dp(300)))
        }
        binding.overlayOpenButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            removePanel()
        }
        binding.overlayPauseButton.setOnClickListener {
            if (app.agent.state.value.status == AgentStatus.PAUSED) app.agent.resume() else app.agent.pause()
            render(app.agent.state.value)
        }
        binding.overlayStopButton.setOnClickListener {
            app.agent.stop()
            render(app.agent.state.value)
        }
        binding.overlayMinimizeButton.setOnClickListener { removePanel() }
        runCatching { windowManager.addView(binding.root, params) }
        panel = binding.root
        render(app.agent.state.value)
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    private fun render(state: AgentUiState) {
        val color = when (state.status) {
            AgentStatus.RUNNING -> R.color.lt_ball_running
            AgentStatus.FAILED -> R.color.lt_ball_error
            AgentStatus.WAITING_CONFIRMATION -> R.color.lt_warning
            else -> R.color.lt_ball
        }
        (ball as? TextView)?.backgroundTintList =
            ContextCompat.getColorStateList(this, color)
        val binding = panel?.let { OverlayPanelBinding.bind(it) } ?: return
        binding.panelStatus.text = buildString {
            append(when (state.status) {
                AgentStatus.IDLE -> getString(R.string.overlay_status_idle)
                AgentStatus.RUNNING -> getString(R.string.home_running)
                AgentStatus.PAUSED -> getString(R.string.home_paused)
                AgentStatus.WAITING_CONFIRMATION -> getString(R.string.task_confirm_title)
                AgentStatus.COMPLETED -> getString(R.string.task_done)
                AgentStatus.FAILED -> getString(R.string.task_failed)
                AgentStatus.CANCELLED -> getString(R.string.task_cancelled)
            })
            if (state.goal.isNotBlank()) append(" · ${state.goal.take(30)}")
        }
        binding.overlayPauseButton.text = if (state.status == AgentStatus.PAUSED) {
            getString(R.string.overlay_resume)
        } else {
            getString(R.string.overlay_pause)
        }
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_open))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_overlay), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "lt_overlay"
        private const val NOTIFICATION_ID = 4300

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayService::class.java)) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
