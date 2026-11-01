package com.kaori.lingtouch.accessibility

/** Latest window/package information observed from accessibility events. */
object ForegroundState {
    @Volatile var packageName: String = ""
    @Volatile var activityName: String = ""
    @Volatile var windowTitle: String = ""
    @Volatile var lastEventAt: Long = 0L
    @Volatile var lastEventType: Int = 0

    fun reset() {
        packageName = ""
        activityName = ""
        windowTitle = ""
        lastEventAt = 0L
        lastEventType = 0
    }
}
