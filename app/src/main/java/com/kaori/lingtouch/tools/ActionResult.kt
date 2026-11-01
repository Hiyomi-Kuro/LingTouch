package com.kaori.lingtouch.tools

import org.json.JSONObject

data class ActionResult(
    val ok: Boolean,
    val message: String,
    val data: JSONObject = JSONObject(),
    val retryable: Boolean = false,
    val changed: Boolean = false
)
