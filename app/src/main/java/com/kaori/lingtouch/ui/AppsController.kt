package com.kaori.lingtouch.ui

import android.view.View
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.apps.InstalledApp
import com.kaori.lingtouch.databinding.DialogAppBinding
import com.kaori.lingtouch.databinding.ViewAppsBinding
import com.kaori.lingtouch.ui.adapter.ActivityAdapter
import com.kaori.lingtouch.ui.adapter.AppAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsController(
    private val activity: MainActivity,
    private val binding: ViewAppsBinding
) {
    private val app get() = LingTouchApp.get(activity)
    private val adapter = AppAdapter { showAppDialog(it) }
    private var query: String = ""
    private var filter: Int = 0
    private var dialog: androidx.appcompat.app.AlertDialog? = null

    init {
        binding.appsList.layoutManager = LinearLayoutManager(activity)
        binding.appsList.adapter = adapter
        binding.appSearch.doAfterTextChanged { text ->
            query = text?.toString()?.trim().orEmpty()
            render()
        }
        binding.appFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chipSystemApps -> 1
                R.id.chipThirdApps -> 2
                else -> 0
            }
            render()
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.apps.apps.collect { render() }
            }
        }
    }

    fun onShown() {
        render()
        if (app.apps.cached().isEmpty()) refresh()
    }

    fun refresh() {
        activity.lifecycleScope.launch {
            binding.appsProgress.visibility = View.VISIBLE
            runCatching { withContext(Dispatchers.IO) { app.apps.scan() } }
                .onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_SHORT).show() }
            binding.appsProgress.visibility = View.GONE
            render()
        }
    }

    private fun render() {
        val all = app.apps.cached()
        val filtered = all.asSequence()
            .filter {
                when (filter) {
                    1 -> it.isSystem
                    2 -> !it.isSystem
                    else -> true
                }
            }
            .filter {
                query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
            }
            .toList()
        adapter.submitList(filtered)
        binding.appsCount.text = activity.getString(R.string.apps_count, filtered.size)
        binding.appsProgress.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAppDialog(item: InstalledApp) {
        val dialogBinding = DialogAppBinding.inflate(activity.layoutInflater)
        dialogBinding.dialogAppIcon.setImageBitmap(item.icon)
        dialogBinding.dialogAppLabel.text = item.label
        dialogBinding.dialogAppPackage.text = item.packageName

        val activityAdapter = ActivityAdapter { entry ->
            activity.lifecycleScope.launch {
                val outcome = app.apps.launchComponent(entry.packageName, entry.className)
                Toast.makeText(
                    activity,
                    if (outcome.success) activity.getString(R.string.apps_launched, entry.label) else outcome.message,
                    Toast.LENGTH_SHORT
                ).show()
                if (outcome.success) dialog?.dismiss()
            }
        }
        dialogBinding.dialogActivities.layoutManager = LinearLayoutManager(activity)
        dialogBinding.dialogActivities.adapter = activityAdapter

        val built = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog = built

        dialogBinding.dialogLaunchApp.setOnClickListener {
            activity.lifecycleScope.launch {
                val outcome = app.apps.launchApp(item.packageName)
                Toast.makeText(activity, outcome.message, Toast.LENGTH_SHORT).show()
                if (outcome.success) built.dismiss()
            }
        }
        dialogBinding.dialogViewMemory.setOnClickListener {
            built.dismiss()
            activity.openMemoryFor(item.packageName)
        }
        built.show()
        activity.lifecycleScope.launch {
            val entries = runCatching { withContext(Dispatchers.IO) { app.apps.activities(item.packageName) } }
                .getOrDefault(emptyList())
            activityAdapter.submitList(entries)
            if (entries.isEmpty()) {
                dialogBinding.dialogActivities.visibility = View.GONE
            }
        }
    }
}
