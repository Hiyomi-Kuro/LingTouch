package com.kaori.lingtouch.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kaori.lingtouch.LingTouchApp

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = LingTouchApp.get(context)
        when (intent.action) {
            ACTION_STOP -> app.agent.stop()
            ACTION_PAUSE -> app.agent.pause()
            ACTION_RESUME -> app.agent.resume()
            ACTION_ALLOW -> app.agent.confirmation.pending.value?.let { app.agent.confirm(it.id, true) }
            ACTION_DENY -> app.agent.confirmation.pending.value?.let { app.agent.confirm(it.id, false) }
        }
    }

    companion object {
        const val ACTION_STOP = "com.kaori.lingtouch.action.STOP"
        const val ACTION_PAUSE = "com.kaori.lingtouch.action.PAUSE"
        const val ACTION_RESUME = "com.kaori.lingtouch.action.RESUME"
        const val ACTION_ALLOW = "com.kaori.lingtouch.action.ALLOW"
        const val ACTION_DENY = "com.kaori.lingtouch.action.DENY"
    }
}
