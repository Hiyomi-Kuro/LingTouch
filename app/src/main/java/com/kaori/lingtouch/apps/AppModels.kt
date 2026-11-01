package com.kaori.lingtouch.apps

import android.graphics.Bitmap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val versionName: String?,
    val icon: Bitmap? = null
)

data class ActivityEntry(
    val packageName: String,
    val className: String,
    val label: String,
    val exported: Boolean,
    val launcher: Boolean,
    val permission: String?
)

data class LaunchOutcome(
    val success: Boolean,
    val message: String,
    val component: String? = null
)
