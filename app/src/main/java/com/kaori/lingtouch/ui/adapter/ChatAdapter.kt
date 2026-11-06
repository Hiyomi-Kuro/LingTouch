package com.kaori.lingtouch.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kaori.lingtouch.R
import com.kaori.lingtouch.chat.ChatExporter
import com.kaori.lingtouch.chat.ChatMessage
import com.kaori.lingtouch.chat.ChatSession
import com.kaori.lingtouch.databinding.ItemChatMessageBinding
import com.kaori.lingtouch.databinding.ItemSessionBinding
import com.kaori.lingtouch.util.Format

data class ChatItem(
    val key: String,
    val messageId: Long,
    val role: String,
    val content: String,
    val reasoning: String,
    val meta: String?,
    val streaming: Boolean = false,
    val time: Long = 0
) {
    companion object {
        fun of(message: ChatMessage, streaming: Boolean = false): ChatItem = ChatItem(
            key = "db:${message.id}",
            messageId = message.id,
            role = message.role,
            content = message.content,
            reasoning = message.reasoning,
            meta = message.meta,
            streaming = streaming,
            time = message.createdAt
        )
    }
}

class ChatAdapter : ListAdapter<ChatItem, ChatAdapter.VH>(Diff) {

    private val expanded = HashSet<String>()

    class VH(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = holder.itemView.context
        val isUser = item.role == "user"
        val isTool = item.role == "tool"

        b.chatBubble.setBackgroundResource(
            when {
                isUser -> R.drawable.bg_chat_user
                isTool -> R.drawable.bg_chat_tool
                else -> R.drawable.bg_chat_assistant
            }
        )
        val params = b.chatBubble.layoutParams as LinearLayout.LayoutParams
        params.gravity = when {
            isUser -> Gravity.END
            isTool -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.START
        }
        params.marginStart = when {
            isUser -> context.dp(40)
            isTool -> context.dp(24)
            else -> 0
        }
        params.marginEnd = when {
            isUser -> 0
            isTool -> context.dp(24)
            else -> context.dp(16)
        }
        b.chatBubble.layoutParams = params

        b.chatRole.text = when {
            isUser -> context.getString(R.string.chat_role_you)
            isTool -> context.getString(R.string.chat_role_tool)
            else -> context.getString(R.string.chat_role_ai)
        }

        val reasoning = item.reasoning.trim()
        val hasThinking = reasoning.isNotBlank() || (item.streaming && !isUser)
        b.thinkingHeader.visibility = if (hasThinking && !isUser && !isTool) View.VISIBLE else View.GONE
        b.thinkingText.visibility = if (hasThinking && !isUser && !isTool && expanded.contains(item.key)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        b.thinkingChevron.text = if (expanded.contains(item.key)) "▾" else "▸"
        b.thinkingLabel.text = when {
            reasoning.isBlank() && item.streaming -> context.getString(R.string.chat_thinking_live)
            else -> context.getString(R.string.chat_thinking_chars, reasoning.length)
        }
        b.thinkingText.text = reasoning.ifBlank { context.getString(R.string.chat_thinking_live) }
        b.thinkingHeader.setOnClickListener {
            if (expanded.contains(item.key)) expanded.remove(item.key) else expanded.add(item.key)
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
        }

        val content = item.content.trim()
        b.chatContent.visibility = if (isTool) View.GONE else View.VISIBLE
        b.chatContent.text = when {
            content.isNotBlank() -> content
            item.streaming -> context.getString(R.string.chat_streaming)
            else -> ""
        }
        if (!isTool) {
            b.chatContent.setOnLongClickListener {
                copy(context, item.content)
                true
            }
        }

        val steps = ChatExporter.stepsOf(item.meta)
        b.chatSteps.visibility = if (steps.isNotEmpty()) View.VISIBLE else View.GONE
        b.chatSteps.text = steps.joinToString("\n")

        if (isTool) {
            b.chatSteps.visibility = View.GONE
            b.chatContent.visibility = View.VISIBLE
            b.chatContent.text = item.content
            b.chatContent.textSize = 11f
        } else {
            b.chatContent.textSize = if (isUser) 14f else 14f
        }

        b.chatMeta.visibility = if (item.time > 0) View.VISIBLE else View.GONE
        b.chatMeta.text = if (item.time > 0) Format.time(item.time) else ""
    }

    private fun copy(context: Context, text: String) {
        if (text.isBlank()) return
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("LingTouch", text))
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(a: ChatItem, b: ChatItem) = a.key == b.key
        override fun areContentsTheSame(a: ChatItem, b: ChatItem) =
            a.content == b.content && a.reasoning == b.reasoning &&
                a.streaming == b.streaming && a.meta == b.meta
    }
}

class SessionAdapter(
    private val onOpen: (ChatSession) -> Unit,
    private val onDelete: (ChatSession) -> Unit
) : ListAdapter<ChatSession, SessionAdapter.VH>(Diff) {

    class VH(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.sessionTitle.text = item.title
        val model = item.model.ifBlank { holder.itemView.context.getString(R.string.chat_default_model_name) }
        b.sessionMeta.text = "${Format.time(item.updatedAt)} · $model"
        b.root.setOnClickListener { onOpen(item) }
        b.sessionDelete.setOnClickListener { onDelete(item) }
    }

    private object Diff : DiffUtil.ItemCallback<ChatSession>() {
        override fun areItemsTheSame(a: ChatSession, b: ChatSession) = a.id == b.id
        override fun areContentsTheSame(a: ChatSession, b: ChatSession) = a == b
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
