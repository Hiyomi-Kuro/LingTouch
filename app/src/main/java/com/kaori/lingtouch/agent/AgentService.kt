package com.kaori.lingtouch.agent

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.kaori.lingtouch.LingTouchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive while a task runs and mirrors agent state into a notification. */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: AgentEngine

    override fun onCreate() {
        super.onCreate()
        engine = LingTouchApp.get(this).agent
        Notifications.createChannels(this)
        startForeground(Notifications.AGENT_NOTIFICATION_ID, Notifications.buildAgent(this, engine.state.value))
        scope.launch {
            engine.state.collect { state ->
                val manager = getSystemService(NotificationManager::class.java)
                if (state.status == AgentStatus.IDLE) {
                    manager.cancel(Notifications.AGENT_NOTIFICATION_ID)
                } else {
                    manager.notify(Notifications.AGENT_NOTIFICATION_ID, Notifications.buildAgent(this@AgentService, state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!engine.state.value.isActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentService::class.java)) }
        }
    }
}
