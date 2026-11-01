package com.kaori.lingtouch.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kaori.lingtouch.R
import com.kaori.lingtouch.ui.MainActivity
import com.kaori.lingtouch.ui.TaskActivity

object Notifications {
    const val CHANNEL_AGENT = "lt_agent"
    const val CHANNEL_CONFIRM = "lt_confirm"
    const val AGENT_NOTIFICATION_ID = 4100
    private const val CONFIRM_NOTIFICATION_ID = 4202

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AGENT, context.getString(R.string.notif_channel_agent), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONFIRM, context.getString(R.string.notif_channel_confirm), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun buildAgent(context: Context, state: AgentUiState): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, TaskActivity::class.java).putExtra(TaskActivity.EXTRA_TASK_ID, state.taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(state.goal.take(60).ifBlank { state.message })
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildString {
                if (state.goal.isNotBlank()) appendLine("目标：${state.goal}")
                appendLine("状态：${state.message}")
                if (state.steps.isNotEmpty()) append("步骤：${state.steps.size}")
            }))
            .setOngoing(state.isActive)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (state.isActive) {
            builder.addAction(0, context.getString(R.string.notif_stop), broadcast(context, NotificationActionReceiver.ACTION_STOP, 1))
            if (state.status == AgentStatus.PAUSED) {
                builder.addAction(0, context.getString(R.string.overlay_resume), broadcast(context, NotificationActionReceiver.ACTION_RESUME, 2))
            } else {
                builder.addAction(0, context.getString(R.string.overlay_pause), broadcast(context, NotificationActionReceiver.ACTION_PAUSE, 3))
            }
        }
        return builder.build()
    }

    fun notifyConfirmation(context: Context, request: ConfirmationBroker.Request) {
        val open = PendingIntent.getActivity(
            context, 10, Intent(context, TaskActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val allow = broadcast(context, NotificationActionReceiver.ACTION_ALLOW, 20 + request.id.toInt())
        val deny = broadcast(context, NotificationActionReceiver.ACTION_DENY, 40 + request.id.toInt())
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(context.getString(R.string.notif_confirm_title))
            .setContentText(request.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, request.confirmLabel ?: context.getString(R.string.notif_allow), allow)
            .addAction(0, request.denyLabel ?: context.getString(R.string.notif_deny), deny)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(CONFIRM_NOTIFICATION_ID, notification)
    }

    fun cancelConfirmation(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(CONFIRM_NOTIFICATION_ID)
    }

    fun cancelAgent(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(AGENT_NOTIFICATION_ID)
    }

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
