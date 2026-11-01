package com.kaori.lingtouch.ui

import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.databinding.DialogMemoryEditBinding
import com.kaori.lingtouch.databinding.ViewMemoryBinding
import com.kaori.lingtouch.memory.MemoryEntity
import com.kaori.lingtouch.memory.MemoryType
import com.kaori.lingtouch.ui.adapter.MemoryAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryController(
    private val activity: MainActivity,
    private val binding: ViewMemoryBinding
) {
    private val app get() = LingTouchApp.get(activity)
    private val adapter = MemoryAdapter(::showEditDialog, ::confirmDelete)
    private var query: String = ""
    private var type: MemoryType? = null

    private val exportLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeExport(it) } }

    private val importLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readImport(it) } }

    init {
        binding.memoryList.layoutManager = LinearLayoutManager(activity)
        binding.memoryList.adapter = adapter
        binding.memorySearch.doAfterTextChanged { text ->
            query = text?.toString()?.trim().orEmpty()
            load()
        }
        binding.memoryFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            type = when (checkedIds.firstOrNull()) {
                R.id.chipAppMemory -> MemoryType.APP
                R.id.chipTaskMemory -> MemoryType.TASK
                R.id.chipGeneralMemory -> MemoryType.GENERAL
                else -> null
            }
            load()
        }
        binding.addMemoryButton.setOnClickListener { showEditDialog(null) }
        binding.exportMemoryButton.setOnClickListener {
            val name = "lingtouch-memory-" +
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
            exportLauncher.launch(name)
        }
        binding.importMemoryButton.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    fun onShown() = load()

    fun filterPackage(pkg: String) {
        binding.memorySearch.setText(pkg)
        query = pkg
        type = null
        binding.chipAllMemory.isChecked = true
        load()
    }

    private fun load() {
        activity.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { app.memory.list(type, query.ifBlank { null }) }
            adapter.submitList(items)
            binding.memoryCount.text = "${items.size} 条"
            binding.memoryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.memoryList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showEditDialog(existing: MemoryEntity?) {
        val dialogBinding = DialogMemoryEditBinding.inflate(activity.layoutInflater)
        dialogBinding.memoryTitleField.setText(existing?.title.orEmpty())
        dialogBinding.memoryContentField.setText(existing?.content.orEmpty())
        dialogBinding.memoryPackageField.setText(existing?.packageName.orEmpty())

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.memory_add else R.string.memory_edit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = dialogBinding.memoryTitleField.text?.toString()?.trim().orEmpty()
                val content = dialogBinding.memoryContentField.text?.toString()?.trim().orEmpty()
                val pkg = dialogBinding.memoryPackageField.text?.toString()?.trim().orEmpty()
                if (title.isBlank() || content.isBlank()) {
                    Toast.makeText(activity, R.string.memory_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                activity.lifecycleScope.launch {
                    val label = pkg.takeIf { it.isNotBlank() }?.let { app.apps.findByPackage(it)?.label }
                    val entity = existing?.copy(
                        title = title, content = content,
                        packageName = pkg.ifBlank { null }, appLabel = label,
                        type = if (pkg.isBlank()) MemoryType.GENERAL else MemoryType.APP
                    ) ?: MemoryEntity(
                        type = if (pkg.isBlank()) MemoryType.GENERAL else MemoryType.APP,
                        packageName = pkg.ifBlank { null },
                        appLabel = label,
                        title = title,
                        content = content
                    )
                    withContext(Dispatchers.IO) { app.memory.save(entity) }
                    Toast.makeText(activity, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    load()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(memory: MemoryEntity) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.memory_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.memory.delete(memory.id) }
                    load()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun writeExport(uri: Uri) {
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = app.memory.exportJson().toString(2)
                    activity.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    app.memory.count()
                }
            }
            result.onSuccess {
                Toast.makeText(activity, activity.getString(R.string.memory_export_ok, it), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(activity, activity.getString(R.string.memory_export_fail, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readImport(uri: Uri) {
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取文件")
                    app.memory.importJson(text)
                }
            }
            result.onSuccess { count ->
                Toast.makeText(activity, activity.getString(R.string.memory_import_ok, count), Toast.LENGTH_SHORT).show()
                load()
            }.onFailure {
                Toast.makeText(activity, activity.getString(R.string.memory_import_fail, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
