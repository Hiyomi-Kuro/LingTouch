package com.kaori.lingtouch.ui

import android.content.DialogInterface
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.agent.AgentStatus
import com.kaori.lingtouch.agent.AgentUiState
import com.kaori.lingtouch.api.ApiOutcome
import com.kaori.lingtouch.chat.ChatEngine
import com.kaori.lingtouch.chat.ChatExporter
import com.kaori.lingtouch.chat.ChatMessage
import com.kaori.lingtouch.chat.ChatMode
import com.kaori.lingtouch.chat.ChatRef
import com.kaori.lingtouch.chat.ChatSession
import com.kaori.lingtouch.chat.ChatStore
import com.kaori.lingtouch.chat.WorkFileTools
import com.kaori.lingtouch.databinding.DialogSessionsBinding
import com.kaori.lingtouch.databinding.ViewHomeBinding
import com.kaori.lingtouch.memory.MemoryEntity
import com.kaori.lingtouch.ui.adapter.ChatAdapter
import com.kaori.lingtouch.ui.adapter.ChatItem
import com.kaori.lingtouch.ui.adapter.SessionAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat-style home shared by all three modes. CHAT and WORK run through [ChatEngine];
 * CONTROL keeps the existing verified AgentEngine and mirrors its live plan/steps/reasoning
 * into the same message list.
 */
class HomeController(
    private val activity: MainActivity,
    private val binding: ViewHomeBinding
) {
    private val app get() = LingTouchApp.get(activity)
    private val store = ChatStore(app.db)
    private val engine = ChatEngine(activity, app.settings, app.api, store)
    private val chatAdapter = ChatAdapter()

    private var mode = ChatMode.from(app.settings.state.value.lastMode)
    private var session: ChatSession? = null
    private var modelOverride = ""
    private val items = mutableListOf<ChatItem>()
    private val refs = mutableListOf<ChatRef>()
    private var running = false
    private var sendJob: Job? = null
    private var controlTaskId = -1L
    private var controlSessionId = -1L
    private var controlMessageId = -1L
    private var controlSaved = false
    private var controlContent = ""
    private var controlReasoning = ""
    private var controlStepsJson: String? = null
    private var lastPersistSignature = ""
    private var streamKey: String? = null
    private var streamText = ""
    private var streamReasoning = ""
    private var suppressRefTrigger = false
    private var suppressModeCallback = false
    private var pendingExport = ""
    private var workTools: WorkFileTools? = null
    private var workToolsUri = ""

    private val pickDirectory = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onDirectoryPicked(uri) }

    private val createDocument = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> if (uri != null) writeExport(uri) }

    init {
        binding.chatList.layoutManager = LinearLayoutManager(activity)
        binding.chatList.adapter = chatAdapter
        (binding.chatList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        binding.sendButton.setOnClickListener { send() }
        binding.stopButton.setOnClickListener { stop() }
        binding.modelChip.setOnClickListener { pickModel() }
        binding.cwdChip.setOnClickListener { pickDirectory() }
        binding.memoryChip.setOnClickListener { editMemory() }
        binding.historyChip.setOnClickListener { showHistory() }
        binding.exportChip.setOnClickListener { exportConversation() }
        binding.newChatChip.setOnClickListener { newSession() }
        binding.refButton.setOnClickListener { showReferencePicker() }
        binding.refClear.setOnClickListener {
            refs.clear()
            renderRefs()
        }
        binding.permissionHint.setOnClickListener { onPermissionHintClick() }

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !suppressModeCallback) {
                val target = modeForButton(checkedId)
                if (target != mode) switchMode(target)
            }
        }
        binding.modeGroup.check(buttonForMode(mode))

        binding.input.doAfterTextChanged { text ->
            if (suppressRefTrigger) return@doAfterTextChanged
            val value = text?.toString().orEmpty()
            if (value.endsWith("@")) {
                suppressRefTrigger = true
                binding.input.setText(value.dropLast(1))
                binding.input.setSelection(binding.input.text?.length ?: 0)
                suppressRefTrigger = false
                showReferencePicker()
            }
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.agent.state.collect { onAgentState(it) } }
                launch { app.catalog.models.collect { renderChips() } }
                launch { AccessibilityBridge.connected.collect { renderPermissionHint() } }
            }
        }

        loadModeSession()
        renderMode()
        renderChips()
        renderPermissionHint()
        renderRunning()
    }

    fun onShown() {
        renderChips()
        renderPermissionHint()
        renderRunning()
        if (mode == ChatMode.CONTROL) onAgentState(app.agent.state.value)
    }

    fun refresh() = onShown()

    fun handlesAgentTask(taskId: Long): Boolean =
        taskId > 0 && (taskId == controlTaskId || taskId == session?.taskId)

    // ------------------------------------------------------------- mode/session

    private fun modeForButton(id: Int): ChatMode = when (id) {
        R.id.modeControl -> ChatMode.CONTROL
        R.id.modeWork -> ChatMode.WORK
        else -> ChatMode.CHAT
    }

    private fun buttonForMode(value: ChatMode): Int = when (value) {
        ChatMode.CONTROL -> R.id.modeControl
        ChatMode.WORK -> R.id.modeWork
        ChatMode.CHAT -> R.id.modeChat
    }

    private fun modeLabel(value: ChatMode = mode): String = when (value) {
        ChatMode.CONTROL -> activity.getString(R.string.chat_mode_control)
        ChatMode.CHAT -> activity.getString(R.string.chat_mode_chat)
        ChatMode.WORK -> activity.getString(R.string.chat_mode_work)
    }

    private fun switchMode(target: ChatMode) {
        if (running) {
            Toast.makeText(activity, R.string.chat_mode_locked, Toast.LENGTH_SHORT).show()
            suppressModeCallback = true
            binding.modeGroup.check(buttonForMode(mode))
            suppressModeCallback = false
            return
        }
        mode = target
        app.settings.update { it.copy(lastMode = target.name) }
        refs.clear()
        modelOverride = ""
        session = null
        items.clear()
        workTools = null
        renderMode()
        renderChips()
        renderRefs()
        renderMessages()
        renderPermissionHint()
        loadModeSession()
    }

    private fun loadModeSession() {
        val target = mode
        activity.lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { store.latest(target) }
            if (mode != target) return@launch
            session = latest
            modelOverride = latest?.model.orEmpty()
            if (latest != null) {
                val messages = withContext(Dispatchers.IO) { store.messages(latest.id) }
                if (mode != target) return@launch
                items.clear()
                items.addAll(messages.map { ChatItem.of(it) })
            } else {
                items.clear()
            }
            renderMessages()
            renderChips()
            renderMode()
        }
    }

    private fun newSession() {
        if (running) {
            Toast.makeText(activity, R.string.chat_mode_locked, Toast.LENGTH_SHORT).show()
            return
        }
        session = null
        modelOverride = ""
        refs.clear()
        items.clear()
        renderMessages()
        renderChips()
        renderRefs()
        renderMode()
        binding.input.requestFocus()
    }

    private suspend fun ensureSession(title: String): ChatSession {
        val current = session
        if (current != null) return current
        val settings = app.settings.state.value
        val created = withContext(Dispatchers.IO) {
            store.createSession(
                mode = mode,
                title = title.take(48).ifBlank { modeLabel() },
                model = modelOverride,
                workUri = settings.workUri,
                workName = settings.workName
            )
        }
        session = created
        return created
    }

    private fun openSession(target: ChatSession) {
        if (running) {
            Toast.makeText(activity, R.string.chat_mode_locked, Toast.LENGTH_SHORT).show()
            return
        }
        session = target
        modelOverride = target.model
        activity.lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) { store.messages(target.id) }
            if (session?.id != target.id) return@launch
            items.clear()
            items.addAll(messages.map { ChatItem.of(it) })
            renderMessages()
            renderChips()
            renderMode()
        }
    }

    // ------------------------------------------------------------------ sending

    private fun send() {
        if (running) return
        val text = binding.input.text?.toString()?.trim().orEmpty()
        val selectedRefs = refs.toList()
        if (text.isEmpty() && selectedRefs.isEmpty()) return
        if (!app.settings.hasApiKey.value) {
            promptApiKey()
            return
        }
        val configured = modelOverride.ifBlank { app.settings.state.value.model }
        if (configured.isBlank()) {
            promptApiKey()
            return
        }
        if (mode == ChatMode.WORK && currentWorkUri().isBlank()) {
            Toast.makeText(activity, R.string.chat_cwd_required, Toast.LENGTH_SHORT).show()
            pickDirectory()
            return
        }
        if (mode == ChatMode.CONTROL && !AccessibilityBridge.isConnected()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.home_status)
                .setMessage(R.string.error_no_accessibility)
                .setPositiveButton(R.string.action_grant) { _, _ -> activity.openPermissionGuide() }
                .setNegativeButton(R.string.action_close, null)
                .show()
            return
        }

        binding.input.setText("")
        refs.clear()
        renderRefs()
        running = true
        renderRunning()
        val refsSnapshot = selectedRefs
        sendJob = activity.lifecycleScope.launch {
            try {
                sendInternal(text, refsSnapshot)
            } catch (t: Throwable) {
                running = false
                renderRunning()
                Toast.makeText(activity, t.message ?: activity.getString(R.string.task_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun sendInternal(text: String, selectedRefs: List<ChatRef>) {
        val current = ensureSession(text.ifBlank { selectedRefs.firstOrNull()?.label.orEmpty() })
        val refsJson = refsMeta(selectedRefs)
        val userMessage = withContext(Dispatchers.IO) {
            store.addMessage(current.id, "user", text, meta = refsJson)
        }
        items.add(ChatItem.of(userMessage))
        renderMessages()
        val refsContext = buildRefsContext(selectedRefs)
        when (mode) {
            ChatMode.CHAT -> runEngine(current, userMessage.id, refsContext, work = false)
            ChatMode.WORK -> runEngine(current, userMessage.id, refsContext, work = true)
            ChatMode.CONTROL -> startControl(current, text)
        }
    }

    private suspend fun runEngine(
        current: ChatSession,
        userMessageId: Long,
        refsContext: String,
        work: Boolean
    ) {
        val listener = object : ChatEngine.Listener {
            override fun onStart() {
                val key = "stream:${System.nanoTime()}"
                streamKey = key
                streamText = ""
                streamReasoning = ""
                postUi {
                    items.add(ChatItem(key, 0, "assistant", "", "", null, streaming = true))
                    renderMessages()
                    renderRunning()
                }
            }

            override fun onDelta(text: String, reasoning: String) = postUi {
                streamText = text
                streamReasoning = reasoning
                val key = streamKey ?: return@postUi
                val index = items.indexOfFirst { it.key == key }
                if (index >= 0) {
                    items[index] = items[index].copy(
                        content = text,
                        reasoning = reasoning,
                        streaming = true
                    )
                    chatAdapter.notifyItemChanged(index)
                }
                renderRunning()
            }

            override fun onTool(message: ChatMessage) = postUi {
                items.add(ChatItem.of(message))
                renderMessages()
            }

            override fun onDone(message: ChatMessage) = postUi {
                streamKey?.let { key -> items.removeAll { it.key == key } }
                streamKey = null
                items.add(ChatItem.of(message))
                running = false
                sendJob = null
                renderMessages()
                renderRunning()
            }

            override fun onError(message: String) = postUi {
                streamKey?.let { key -> items.removeAll { it.key == key } }
                streamKey = null
                running = false
                sendJob = null
                val currentSession = session
                if (currentSession != null) {
                    activity.lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            store.addMessage(
                                currentSession.id,
                                "assistant",
                                "请求失败：$message",
                                streamReasoning
                            )
                        }
                        items.add(ChatItem.of(saved))
                        renderMessages()
                    }
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                renderRunning()
            }
        }

        if (work) {
            val tools = workTools(current)
            engine.sendWork(
                session = current,
                modelOverride = modelOverride,
                userMessageId = userMessageId,
                refsContext = refsContext,
                tools = tools,
                listener = listener
            )
        } else {
            engine.sendChat(
                session = current,
                modelOverride = modelOverride,
                userMessageId = userMessageId,
                refsContext = refsContext,
                listener = listener
            )
        }
    }

    private suspend fun startControl(current: ChatSession, goal: String) {
        val started = app.agent.start(goal, modelOverride)
        if (!started) {
            running = false
            sendJob = null
            val message = app.agent.state.value.error ?: activity.getString(R.string.task_failed)
            val saved = withContext(Dispatchers.IO) {
                store.addMessage(current.id, "assistant", message)
            }
            items.add(ChatItem.of(saved))
            renderMessages()
            renderRunning()
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.home_status)
                .setMessage(message)
                .setPositiveButton(R.string.action_grant) { _, _ -> activity.openPermissionGuide() }
                .setNegativeButton(R.string.action_close, null)
                .show()
            return
        }
        val state = app.agent.state.value
        controlTaskId = state.taskId
        controlSessionId = current.id
        controlSaved = false
        controlContent = ""
        controlReasoning = ""
        controlStepsJson = null
        lastPersistSignature = ""
        session = current.copy(taskId = controlTaskId)
        controlMessageId = withContext(Dispatchers.IO) {
            store.setTaskId(current.id, controlTaskId)
            store.addMessage(current.id, "assistant", "", "", stepsMeta(state)).id
        }
        items.add(
            ChatItem(
                key = "control",
                messageId = controlMessageId,
                role = "assistant",
                content = activity.getString(R.string.chat_task_running),
                reasoning = "",
                meta = stepsMeta(state),
                streaming = true
            )
        )
        renderMessages()
        renderRunning()
        updateLiveCard(state)
    }

    // ------------------------------------------------------------------ control

    private fun onAgentState(state: AgentUiState) {
        if (controlTaskId <= 0 || state.taskId != controlTaskId) return
        if (mode == ChatMode.CONTROL) {
            updateControlItem(state)
        }
        updateLiveCard(state)
        val finished = state.status == AgentStatus.SUCCEEDED ||
            state.status == AgentStatus.FAILED || state.status == AgentStatus.CANCELLED
        if (finished) {
            finishControl(state)
        } else {
            persistControlProgress(state)
        }
    }

    private fun updateControlItem(state: AgentUiState) {
        val index = items.indexOfFirst { it.key == "control" }
        if (index >= 0) {
            val content = if (state.isActive) state.message else state.result.ifBlank { state.message }
            items[index] = items[index].copy(
                content = content,
                reasoning = state.reasoning,
                streaming = state.isActive
            )
            chatAdapter.notifyItemChanged(index)
        }
        controlContent = if (state.isActive) state.message else state.result.ifBlank { state.message }
        controlReasoning = state.reasoning
        controlStepsJson = stepsMeta(state)
    }

    private fun finishControl(state: AgentUiState) {
        if (controlSaved) return
        controlSaved = true
        val content = state.result.ifBlank {
            when (state.status) {
                AgentStatus.SUCCEEDED -> activity.getString(R.string.task_done)
                AgentStatus.CANCELLED -> activity.getString(R.string.chat_task_cancelled)
                else -> state.error ?: activity.getString(R.string.task_failed)
            }
        }
        controlContent = content
        controlReasoning = state.reasoning
        controlStepsJson = stepsMeta(state)
        val messageId = controlMessageId
        val targetSession = controlSessionId
        activity.lifecycleScope.launch {
            if (messageId > 0) {
                withContext(Dispatchers.IO) {
                    store.updateMessage(messageId, content, controlReasoning, stepsMeta(state))
                }
                val index = items.indexOfFirst { it.key == "control" }
                if (index >= 0) {
                    items[index] = items[index].copy(
                        key = "db:$messageId",
                        messageId = messageId,
                        content = content,
                        reasoning = controlReasoning,
                        meta = stepsMeta(state),
                        streaming = false
                    )
                }
            }
            running = false
            sendJob = null
            controlTaskId = -1L
            controlSessionId = -1L
            binding.liveCard.visibility = View.GONE
            renderMessages()
            renderRunning()
            renderPermissionHint()
        }
    }

    private fun persistControlProgress(state: AgentUiState) {
        if (controlMessageId <= 0) return
        val meta = stepsMeta(state)
        val signature = "${state.status}:${state.steps.size}:${state.reasoning.length}"
        if (signature == lastPersistSignature) return
        lastPersistSignature = signature
        val content = state.message
        val reasoning = state.reasoning.takeLast(80_000)
        val id = controlMessageId
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.updateMessage(id, content, reasoning, meta) }
        }
    }

    private fun updateLiveCard(state: AgentUiState) {
        if (mode != ChatMode.CONTROL || !state.isActive) {
            binding.liveCard.visibility = View.GONE
            return
        }
        binding.liveCard.visibility = View.VISIBLE
        binding.liveStatus.text = activity.getString(
            R.string.chat_live_status,
            statusLabel(state.status),
            maxOf(1, state.steps.size)
        )
        binding.livePlan.text = state.plan.ifBlank { state.message }
        binding.liveProgress.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ stop

    private fun stop() {
        if (mode == ChatMode.CONTROL && app.agent.state.value.isActive) {
            app.agent.stop()
            return
        }
        sendJob?.cancel()
        sendJob = null
        running = false
        val key = streamKey
        streamKey = null
        if (key != null) {
            val index = items.indexOfFirst { it.key == key }
            if (index >= 0) {
                items[index] = items[index].copy(
                    content = streamText.ifBlank { activity.getString(R.string.chat_stop_done) },
                    reasoning = streamReasoning,
                    streaming = false
                )
                val partial = items[index]
                activity.lifecycleScope.launch {
                    val currentSession = session
                    if (currentSession != null) {
                        withContext(Dispatchers.IO) {
                            store.addMessage(currentSession.id, "assistant", partial.content, partial.reasoning)
                        }
                    }
                }
            }
        }
        renderRunning()
        renderMessages()
    }

    // ------------------------------------------------------------------ ui state

    private fun renderMode() {
        binding.emptyTitle.text = when (mode) {
            ChatMode.CONTROL -> activity.getString(R.string.chat_empty_title_control)
            ChatMode.CHAT -> activity.getString(R.string.chat_empty_title_chat)
            ChatMode.WORK -> activity.getString(R.string.chat_empty_title_work)
        }
        binding.emptyHint.text = when (mode) {
            ChatMode.CONTROL -> activity.getString(R.string.chat_empty_hint_control)
            ChatMode.CHAT -> activity.getString(R.string.chat_empty_hint_chat)
            ChatMode.WORK -> activity.getString(R.string.chat_empty_hint_work)
        }
        binding.input.hint = when (mode) {
            ChatMode.CONTROL -> activity.getString(R.string.chat_empty_hint_control)
            ChatMode.WORK -> activity.getString(R.string.chat_empty_hint_work)
            else -> activity.getString(R.string.chat_input_hint)
        }
        suppressModeCallback = true
        binding.modeGroup.check(buttonForMode(mode))
        suppressModeCallback = false
        renderPermissionHint()
    }

    private fun renderMessages() {
        chatAdapter.submitList(items.toList())
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.chatList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isNotEmpty()) {
            binding.chatList.post { binding.chatList.scrollToPosition(items.size - 1) }
        }
    }

    private fun renderChips() {
        val settings = app.settings.state.value
        val model = modelOverride.ifBlank { settings.model }
        binding.modelChip.text = if (modelOverride.isBlank()) {
            activity.getString(R.string.chat_model_default) + " · " +
                settings.model.ifBlank { activity.getString(R.string.chat_default_model_name) }
        } else {
            activity.getString(R.string.chat_model_value, model)
        }
        val uri = currentWorkUri()
        binding.cwdChip.text = if (uri.isBlank()) {
            activity.getString(R.string.chat_cwd_none)
        } else {
            activity.getString(R.string.chat_cwd_value, currentWorkName())
        }
        binding.cwdChip.visibility = View.VISIBLE
    }

    private fun renderRunning() {
        binding.sendButton.visibility = if (running) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (running) View.VISIBLE else View.GONE
        binding.input.isEnabled = !running
        binding.modeGroup.isEnabled = !running
        binding.newChatChip.isEnabled = !running
        binding.modelChip.isEnabled = !running
        binding.cwdChip.isEnabled = !running
        binding.refButton.isEnabled = !running
        if (!running && mode == ChatMode.CONTROL && !app.agent.state.value.isActive) {
            binding.liveCard.visibility = View.GONE
        }
    }

    private fun renderRefs() {
        if (refs.isEmpty()) {
            binding.refRow.visibility = View.GONE
            binding.refText.text = ""
            return
        }
        binding.refRow.visibility = View.VISIBLE
        binding.refText.text = activity.getString(
            R.string.chat_ref_selected,
            refs.joinToString("  ") { "@${it.label.take(32)}" }
        )
    }

    private fun renderPermissionHint() {
        val configured = app.settings.hasApiKey.value
        val a11y = AccessibilityBridge.isConnected()
        val model = modelOverride.ifBlank { app.settings.state.value.model }
        val hint = when {
            !configured -> activity.getString(R.string.chat_permission_api)
            mode == ChatMode.CONTROL && !a11y -> activity.getString(R.string.chat_permission_accessibility)
            mode == ChatMode.CONTROL && model.isNotBlank() && app.catalog.find(model)?.vision != true ->
                activity.getString(R.string.chat_vision_hint)
            else -> if (model.isBlank()) activity.getString(R.string.chat_model_empty) else ""
        }
        binding.permissionHint.text = hint
        binding.permissionHint.visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
    }

    private fun onPermissionHintClick() {
        when {
            !app.settings.hasApiKey.value -> activity.showTab(R.id.tab_settings)
            mode == ChatMode.CONTROL && !AccessibilityBridge.isConnected() -> activity.openPermissionGuide()
            else -> activity.showTab(R.id.tab_settings)
        }
    }

    private fun statusLabel(status: AgentStatus): String = when (status) {
        AgentStatus.IDLE -> activity.getString(R.string.home_ready)
        AgentStatus.PLANNING -> activity.getString(R.string.home_running)
        AgentStatus.RUNNING -> activity.getString(R.string.home_running)
        AgentStatus.PAUSED -> activity.getString(R.string.home_paused)
        AgentStatus.WAITING_USER -> activity.getString(R.string.task_confirm_title)
        AgentStatus.RECOVERING -> activity.getString(R.string.status_running)
        AgentStatus.SUCCEEDED -> activity.getString(R.string.task_done)
        AgentStatus.FAILED -> activity.getString(R.string.task_failed)
        AgentStatus.CANCELLED -> activity.getString(R.string.task_cancelled)
    }

    // ------------------------------------------------------------- pickers/memory

    private fun pickModel() {
        if (!app.settings.hasApiKey.value) {
            promptApiKey()
            return
        }
        val models = app.catalog.models.value
        if (models.isNotEmpty()) {
            showModelDialog(models)
            return
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.chat_model_title)
            .setMessage(R.string.chat_model_loading)
            .setCancelable(false)
            .create()
        dialog.show()
        activity.lifecycleScope.launch {
            val outcome = app.catalog.refresh()
            dialog.dismiss()
            when (outcome) {
                is ApiOutcome.Ok -> showModelDialog(outcome.value)
                is ApiOutcome.Err -> {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.chat_model_title)
                        .setMessage(activity.getString(R.string.chat_model_load_failed, outcome.error.message))
                        .setPositiveButton(R.string.chat_model_manual) { _, _ -> manualModel() }
                        .setNegativeButton(R.string.action_close, null)
                        .show()
                }
            }
        }
    }

    private fun showModelDialog(models: List<com.kaori.lingtouch.api.ModelInfo>) {
        val defaultModel = app.settings.state.value.model
        val labels = ArrayList<String>()
        labels.add(activity.getString(R.string.chat_model_default_item, defaultModel.ifBlank { "default" }))
        models.forEach { model ->
            labels.add(if (model.vision) "${model.id} · 视觉" else model.id)
        }
        labels.add(activity.getString(R.string.chat_model_manual))
        val selectedIndex = when {
            modelOverride.isBlank() -> 0
            else -> models.indexOfFirst { it.id == modelOverride }.let {
                if (it >= 0) it + 1 else labels.size - 1
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.chat_model_title)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                when {
                    which == 0 -> {
                        setModel("")
                        dialog.dismiss()
                    }
                    which == labels.size - 1 -> {
                        dialog.dismiss()
                        manualModel()
                    }
                    else -> {
                        setModel(models[which - 1].id)
                        dialog.dismiss()
                    }
                }
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun manualModel() {
        val input = EditText(activity).apply {
            setText(modelOverride)
            hint = activity.getString(R.string.chat_model_manual_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val container = paddedContainer(input)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.chat_model_manual)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                setModel(input.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun setModel(value: String) {
        modelOverride = value
        val current = session
        if (current != null) {
            val updated = current.copy(model = value, updatedAt = System.currentTimeMillis())
            session = updated
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { store.updateSession(updated) }
            }
        }
        renderChips()
        renderPermissionHint()
    }

    private fun pickDirectory() {
        val initial = currentWorkUri().takeIf { it.isNotBlank() }?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        runCatching { pickDirectory.launch(initial) }
            .onFailure { runCatching { pickDirectory.launch(null) } }
    }

    private fun onDirectoryPicked(uri: Uri?) {
        if (uri == null) return
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val name = WorkFileTools.displayName(activity, uri)
        app.settings.update { it.copy(workUri = uri.toString(), workName = name) }
        val current = session
        if (current != null) {
            val updated = current.copy(
                workUri = uri.toString(),
                workName = name,
                updatedAt = System.currentTimeMillis()
            )
            session = updated
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { store.updateSession(updated) }
            }
        }
        workTools = null
        renderChips()
        renderPermissionHint()
        Toast.makeText(activity, name, Toast.LENGTH_SHORT).show()
    }

    private fun editMemory() {
        val targetMode = mode
        activity.lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { store.modeMemory(targetMode) }
            val input = EditText(activity).apply {
                setText(current)
                hint = activity.getString(R.string.chat_memory_hint)
                gravity = Gravity.TOP or Gravity.START
                minLines = 6
                maxLines = 12
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            val container = paddedContainer(input)
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.chat_memory_title, modeLabel(targetMode)))
                .setMessage(R.string.chat_memory_hint)
                .setView(container)
                .setPositiveButton(R.string.action_save) { _, _ ->
                    val value = input.text?.toString().orEmpty()
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { store.setModeMemory(targetMode, value) }
                        Toast.makeText(activity, R.string.chat_memory_saved, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton(R.string.action_clear) { _, _ ->
                    MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.chat_memory_clear_confirm)
                        .setPositiveButton(R.string.action_clear) { _, _ ->
                            activity.lifecycleScope.launch {
                                withContext(Dispatchers.IO) { store.setModeMemory(targetMode, "") }
                                Toast.makeText(activity, R.string.chat_memory_saved, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ------------------------------------------------------------------ history

    private fun showHistory() {
        activity.lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { store.sessions(mode) }
            val dialogBinding = DialogSessionsBinding.inflate(activity.layoutInflater)
            var dialog: androidx.appcompat.app.AlertDialog? = null
            val adapter = SessionAdapter(
                onOpen = { target ->
                    dialog?.dismiss()
                    openSession(target)
                },
                onDelete = { target ->
                    dialog?.dismiss()
                    confirmDeleteSession(target)
                }
            )
            dialogBinding.sessionList.layoutManager = LinearLayoutManager(activity)
            dialogBinding.sessionList.adapter = adapter
            dialogBinding.sessionEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            dialogBinding.sessionList.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
            adapter.submitList(sessions)
            dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.session_title) + " · " + modeLabel())
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.session_new) { _, _ -> newSession() }
                .setNegativeButton(R.string.action_close, null)
                .show()
        }
    }

    private fun confirmDeleteSession(target: ChatSession) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.session_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.deleteSession(target.id) }
                    if (session?.id == target.id) {
                        session = null
                        items.clear()
                        renderMessages()
                    }
                    Toast.makeText(activity, R.string.session_deleted, Toast.LENGTH_SHORT).show()
                    showHistory()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ export

    private fun exportConversation() {
        val currentSession = session
        if (currentSession == null && items.isEmpty()) {
            Toast.makeText(activity, R.string.chat_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        activity.lifecycleScope.launch {
            val messages = if (currentSession != null) {
                withContext(Dispatchers.IO) { store.messages(currentSession.id) }
            } else {
                items.map {
                    ChatMessage(
                        id = it.messageId,
                        sessionId = 0,
                        role = it.role,
                        content = it.content,
                        reasoning = it.reasoning,
                        meta = it.meta,
                        createdAt = it.time
                    )
                }
            }
            if (messages.isEmpty()) {
                Toast.makeText(activity, R.string.chat_export_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val memory = withContext(Dispatchers.IO) { store.modeMemory(mode) }
            pendingExport = ChatExporter.build(
                mode = mode,
                session = currentSession,
                model = modelOverride.ifBlank { app.settings.state.value.model },
                messages = messages,
                memory = memory
            )
            createDocument.launch(ChatExporter.fileName(mode))
        }
    }

    private fun writeExport(uri: Uri) {
        val text = pendingExport
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = activity.contentResolver.openOutputStream(uri, "wt")
                        ?: activity.contentResolver.openOutputStream(uri, "w")
                    stream?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    stream != null
                }.getOrDefault(false)
            }
            Toast.makeText(
                activity,
                if (ok) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ------------------------------------------------------------------ refs

    private fun showReferencePicker() {
        if (mode == ChatMode.WORK) {
            if (currentWorkUri().isBlank()) {
                Toast.makeText(activity, R.string.chat_cwd_required, Toast.LENGTH_SHORT).show()
                pickDirectory()
                return
            }
            activity.lifecycleScope.launch {
                val tools = workTools()
                val files = withContext(Dispatchers.IO) {
                    runCatching { tools.walk(400) }.getOrElse { emptyList() }
                }
                if (files.isEmpty()) {
                    Toast.makeText(activity, R.string.chat_ref_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showMultiChoice(
                    title = activity.getString(R.string.chat_ref_pick_work),
                    labels = files.map { "${it.path}  (${it.size} B)" },
                    existing = refs.filter { it.kind == "work-file" }.map { it.id }.toSet()
                ) { selected ->
                    refs.removeAll { it.kind == "work-file" }
                    selected.forEach { index ->
                        val file = files[index]
                        refs.add(ChatRef("work-file", file.path, file.path))
                    }
                    renderRefs()
                }
            }
        } else if (mode == ChatMode.CONTROL) {
            activity.lifecycleScope.launch {
                if (app.apps.cached().isEmpty()) {
                    withContext(Dispatchers.IO) { runCatching { app.apps.scan() } }
                }
                val apps = app.apps.cached().sortedBy { it.label.lowercase() }.take(300)
                if (apps.isEmpty()) {
                    Toast.makeText(activity, R.string.chat_ref_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showMultiChoice(
                    title = activity.getString(R.string.chat_ref_pick_control),
                    labels = apps.map { "${it.label}  (${it.packageName})" },
                    existing = refs.filter { it.kind == "app" }.map { it.id }.toSet()
                ) { selected ->
                    refs.removeAll { it.kind == "app" }
                    selected.forEach { index ->
                        val application = apps[index]
                        refs.add(ChatRef("app", application.packageName, application.label))
                    }
                    renderRefs()
                }
            }
        } else {
            activity.lifecycleScope.launch {
                val memories = withContext(Dispatchers.IO) {
                    runCatching { app.memory.list(null, null, 120) }.getOrElse { emptyList() }
                }
                if (memories.isEmpty()) {
                    Toast.makeText(activity, R.string.chat_ref_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showMultiChoice(
                    title = activity.getString(R.string.chat_ref_pick_chat),
                    labels = memories.map { it.title },
                    existing = refs.filter { it.kind == "memory" }.map { it.id }.toSet()
                ) { selected ->
                    refs.removeAll { it.kind == "memory" }
                    selected.forEach { index ->
                        val memory = memories[index]
                        refs.add(ChatRef("memory", memory.id.toString(), memory.title))
                    }
                    renderRefs()
                }
            }
        }
    }

    private fun showMultiChoice(
        title: String,
        labels: List<String>,
        existing: Set<String>,
        onSelected: (List<Int>) -> Unit
    ) {
        val checked = BooleanArray(labels.size)
        labels.forEachIndexed { index, _ -> checked[index] = false }
        val selected = ArrayList<Int>()
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), null) { _, which, isChecked ->
                if (isChecked) {
                    if (!selected.contains(which)) selected.add(which)
                } else {
                    selected.remove(which)
                }
            }
            .setPositiveButton(R.string.action_save) { _, _ -> onSelected(selected.sorted()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private suspend fun buildRefsContext(selectedRefs: List<ChatRef>): String {
        if (selectedRefs.isEmpty()) return ""
        return buildString {
            append("[用户引用的资料]\n")
            selectedRefs.forEach { ref ->
                when (ref.kind) {
                    "work-file" -> {
                        append("\n--- @").append(ref.id).append(" ---\n")
                        append(workTools().readForReference(ref.id))
                        append('\n')
                    }
                    "app" -> append("\n- 应用：").append(ref.label).append(" (").append(ref.id).append(")\n")
                    "memory" -> {
                        append("\n--- 记忆：").append(ref.label).append(" ---\n")
                        append(memoryContent(ref.id.toLongOrNull()))
                        append('\n')
                    }
                }
            }
        }
    }

    private fun refsMeta(selectedRefs: List<ChatRef>): String? {
        if (selectedRefs.isEmpty()) return null
        val array = JSONArray()
        selectedRefs.forEach {
            array.put(JSONObject().put("kind", it.kind).put("id", it.id).put("label", it.label))
        }
        return JSONObject().put("refs", array).toString()
    }

    private fun memoryContent(id: Long?): String {
        if (id == null) return ""
        return runCatching {
            app.memory.list(null, null, 500).firstOrNull { it.id == id }?.content.orEmpty()
        }.getOrDefault("")
    }

    // ------------------------------------------------------------------ helpers

    private fun currentWorkUri(): String {
        val fromSession = session?.workUri.orEmpty()
        return fromSession.ifBlank { app.settings.state.value.workUri }
    }

    private fun currentWorkName(): String {
        val fromSession = session?.workName.orEmpty()
        return fromSession.ifBlank { app.settings.state.value.workName }
    }

    private fun workTools(current: ChatSession? = session): WorkFileTools {
        val uri = currentWorkUri().ifBlank { current?.workUri.orEmpty() }
        require(uri.isNotBlank()) { "未选择工作目录" }
        val cached = workTools
        if (cached != null && workToolsUri == uri) return cached
        val created = WorkFileTools(activity, Uri.parse(uri))
        workTools = created
        workToolsUri = uri
        return created
    }

    private fun stepsMeta(state: AgentUiState): String {
        val array = JSONArray()
        state.steps.forEach { step ->
            array.put(
                JSONObject()
                    .put("index", step.index)
                    .put("action", step.action)
                    .put("detail", step.detail)
                    .put("ok", step.ok)
                    .put("message", step.message)
                    .put("commit", step.commit.name)
            )
        }
        return JSONObject()
            .put("task_id", state.taskId)
            .put("status", state.status.name)
            .put("steps", array)
            .toString()
    }

    private fun paddedContainer(view: View): FrameLayout {
        val container = FrameLayout(activity)
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding / 2, padding, 0)
        container.addView(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return container
    }

    private fun promptApiKey() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.chat_model_title)
            .setMessage(R.string.chat_model_missing_key)
            .setPositiveButton(R.string.action_grant) { _, _ -> activity.showTab(R.id.tab_settings) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun postUi(block: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) block()
        }
    }
}
