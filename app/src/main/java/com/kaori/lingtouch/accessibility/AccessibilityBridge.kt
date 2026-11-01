package com.kaori.lingtouch.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** Process-wide access point for the single accessibility service instance. */
object AccessibilityBridge {
    @Volatile private var ref: WeakReference<LingTouchAccessibilityService>? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun attach(service: LingTouchAccessibilityService) {
        ref = WeakReference(service)
        _connected.value = true
    }

    fun detach(service: LingTouchAccessibilityService) {
        if (ref?.get() === service) ref = null
        _connected.value = false
        ForegroundState.reset()
    }

    fun service(): LingTouchAccessibilityService? = ref?.get()?.takeIf { it != null }

    fun isConnected(): Boolean = service() != null

    fun snapshot(): UiTree? = service()?.snapshot()

    fun foregroundPackage(): String = service()?.foregroundPackage().orEmpty()

    fun isEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, LingTouchAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
    }

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
