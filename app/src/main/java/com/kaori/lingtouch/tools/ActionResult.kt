package com.kaori.lingtouch.tools

import com.kaori.lingtouch.agent.ActionCommit
import com.kaori.lingtouch.agent.FailureKind
import org.json.JSONObject

/**
 * Result of one tool call. `ok` only means the API accepted the request; [commit] records
 * whether the screen actually changed and [failure] carries the structured error kind.
 */
data class ActionResult(
    val ok: Boolean,
    val message: String,
    val data: JSONObject = JSONObject(),
    val retryable: Boolean = false,
    val changed: Boolean = false,
    val failure: FailureKind? = null,
    val commit: ActionCommit = if (ok) ActionCommit.DISPATCHED else ActionCommit.FAILED
)
