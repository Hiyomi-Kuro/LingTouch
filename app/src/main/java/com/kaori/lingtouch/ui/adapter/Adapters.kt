package com.kaori.lingtouch.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kaori.lingtouch.R
import com.kaori.lingtouch.agent.AgentStepUi
import com.kaori.lingtouch.api.ModelInfo
import com.kaori.lingtouch.apps.ActivityEntry
import com.kaori.lingtouch.apps.InstalledApp
import com.kaori.lingtouch.databinding.ItemActivityBinding
import com.kaori.lingtouch.databinding.ItemAppBinding
import com.kaori.lingtouch.databinding.ItemMemoryBinding
import com.kaori.lingtouch.databinding.ItemModelBinding
import com.kaori.lingtouch.databinding.ItemStepBinding
import com.kaori.lingtouch.databinding.ItemTaskBinding
import com.kaori.lingtouch.memory.MemoryEntity
import com.kaori.lingtouch.memory.MemoryType
import com.kaori.lingtouch.memory.TaskEntity
import com.kaori.lingtouch.util.Format
import com.kaori.lingtouch.util.Format.bytes

class AppAdapter(private val onClick: (InstalledApp) -> Unit) :
    ListAdapter<InstalledApp, AppAdapter.VH>(Diff) {

    class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        if (item.icon != null) {
            b.appIcon.setImageBitmap(item.icon)
        } else {
            b.appIcon.setImageResource(R.drawable.ic_apps)
        }
        b.appLabel.text = item.label
        b.appPackage.text = item.packageName
        b.appBadge.text = if (item.isSystem) holder.itemView.context.getString(R.string.apps_system) else ""
        b.appBadge.visibility = if (item.isSystem) android.view.View.VISIBLE else android.view.View.GONE
        b.root.setOnClickListener { onClick(item) }
    }

    private object Diff : DiffUtil.ItemCallback<InstalledApp>() {
        override fun areItemsTheSame(a: InstalledApp, b: InstalledApp) = a.packageName == b.packageName
        override fun areContentsTheSame(a: InstalledApp, b: InstalledApp) = a == b
    }
}

class ActivityAdapter(private val onLaunch: (ActivityEntry) -> Unit) :
    ListAdapter<ActivityEntry, ActivityAdapter.VH>(Diff) {

    class VH(val binding: ItemActivityBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemActivityBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.activityLabel.text = item.label
        b.activityClass.text = item.className
        b.activityBadge.text = when {
            item.launcher -> holder.itemView.context.getString(R.string.apps_exported)
            item.exported -> holder.itemView.context.getString(R.string.apps_exported)
            else -> holder.itemView.context.getString(R.string.apps_not_exported)
        }
        b.activityLaunchButton.isEnabled = item.exported
        b.activityLaunchButton.setOnClickListener { onLaunch(item) }
        b.root.setOnClickListener { if (item.exported) onLaunch(item) }
    }

    private object Diff : DiffUtil.ItemCallback<ActivityEntry>() {
        override fun areItemsTheSame(a: ActivityEntry, b: ActivityEntry) = a.className == b.className
        override fun areContentsTheSame(a: ActivityEntry, b: ActivityEntry) = a == b
    }
}

class MemoryAdapter(
    private val onEdit: (MemoryEntity) -> Unit,
    private val onDelete: (MemoryEntity) -> Unit
) : ListAdapter<MemoryEntity, MemoryAdapter.VH>(Diff) {

    class VH(val binding: ItemMemoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMemoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = holder.itemView.context
        b.memoryTitle.text = item.title
        b.memoryContent.text = item.content
        val typeLabel = when (item.type) {
            MemoryType.APP -> context.getString(R.string.memory_app)
            MemoryType.TASK -> context.getString(R.string.memory_task)
            MemoryType.GENERAL -> context.getString(R.string.memory_general)
        }
        b.memoryBadge.text = if (item.expired) context.getString(R.string.memory_expired)
        else if (item.verified) "$typeLabel · ${context.getString(R.string.memory_verified)}"
        else "$typeLabel · ${context.getString(R.string.memory_unverified)}"
        b.memoryMeta.text = context.getString(R.string.memory_use_count, item.useCount, item.successCount) +
            if (!item.packageName.isNullOrBlank()) " · ${item.packageName}" else ""
        b.memoryEditButton.setOnClickListener { onEdit(item) }
        b.memoryDeleteButton.setOnClickListener { onDelete(item) }
        b.root.setOnClickListener { onEdit(item) }
    }

    private object Diff : DiffUtil.ItemCallback<MemoryEntity>() {
        override fun areItemsTheSame(a: MemoryEntity, b: MemoryEntity) = a.id == b.id
        override fun areContentsTheSame(a: MemoryEntity, b: MemoryEntity) = a == b
    }
}

class StepAdapter : ListAdapter<AgentStepUi, StepAdapter.VH>(Diff) {
    class VH(val binding: ItemStepBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStepBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.stepIndex.text = item.index.toString()
        b.stepAction.text = if (item.detail.isBlank()) item.action else "${item.action}  ${item.detail}"
        b.stepMessage.text = item.message
        b.stepIcon.setImageResource(if (item.ok) R.drawable.ic_check else R.drawable.ic_error)
        b.stepIcon.setColorFilter(
            ContextCompat.getColor(holder.itemView.context, if (item.ok) R.color.lt_success else R.color.lt_error)
        )
    }

    private object Diff : DiffUtil.ItemCallback<AgentStepUi>() {
        override fun areItemsTheSame(a: AgentStepUi, b: AgentStepUi) = a.index == b.index
        override fun areContentsTheSame(a: AgentStepUi, b: AgentStepUi) = a == b
    }
}

class TaskAdapter(private val onClick: (TaskEntity) -> Unit) :
    ListAdapter<TaskEntity, TaskAdapter.VH>(Diff) {

    class VH(val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = holder.itemView.context
        b.taskRowGoal.text = item.goal
        b.taskRowMeta.text = "${Format.time(item.startedAt)} · ${item.stepCount} 步"
        b.taskRowStatus.text = statusLabel(context, item)
        b.taskRowStatus.setTextColor(
            ContextCompat.getColor(
                context,
                when {
                    item.status == "SUCCESS" -> R.color.lt_success
                    item.status == "FAILED" -> R.color.lt_error
                    item.status == "RUNNING" -> R.color.lt_primary
                    else -> R.color.lt_text_secondary
                }
            )
        )
        b.root.setOnClickListener { onClick(item) }
    }

    private fun statusLabel(context: android.content.Context, task: TaskEntity): String = when (task.status) {
        "SUCCESS" -> context.getString(R.string.status_success)
        "FAILED" -> context.getString(R.string.status_failed)
        "CANCELLED" -> context.getString(R.string.status_cancelled)
        "RUNNING" -> context.getString(R.string.status_running)
        else -> task.status
    }

    private object Diff : DiffUtil.ItemCallback<TaskEntity>() {
        override fun areItemsTheSame(a: TaskEntity, b: TaskEntity) = a.id == b.id
        override fun areContentsTheSame(a: TaskEntity, b: TaskEntity) = a == b
    }
}

class ModelAdapter(private val onClick: (ModelInfo) -> Unit) :
    ListAdapter<ModelInfo, ModelAdapter.VH>(Diff) {

    class VH(val binding: ItemModelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemModelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.modelName.text = item.id
        val parts = mutableListOf<String>()
        if (item.vision) parts.add("视觉")
        if (item.jsonMode) parts.add("JSON")
        if (item.contextLength > 0) parts.add("${item.contextLength / 1000}K ctx")
        if (item.ownedBy.isNotBlank()) parts.add(item.ownedBy)
        b.modelMeta.text = if (parts.isEmpty()) item.name else parts.joinToString(" · ")
        b.root.setOnClickListener { onClick(item) }
    }

    private object Diff : DiffUtil.ItemCallback<ModelInfo>() {
        override fun areItemsTheSame(a: ModelInfo, b: ModelInfo) = a.id == b.id
        override fun areContentsTheSame(a: ModelInfo, b: ModelInfo) = a == b
    }
}
