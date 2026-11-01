package com.kaori.lingtouch.api

import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.util.bool
import com.kaori.lingtouch.util.int
import com.kaori.lingtouch.util.str
import com.kaori.lingtouch.util.Json.parseObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model catalog sourced from the provider's live /v1/models endpoint, cached locally.
 * Never relies on a permanently hard-coded model list.
 */
class ModelCatalog(
    private val settings: SettingsRepository,
    private val client: OpenCodeClient
) {
    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    init {
        loadCache()
    }

    fun loadCache() {
        val raw = settings.modelsCache() ?: return
        val root = parseObject(raw) ?: return
        val array = root.optJSONArray("models") ?: return
        val list = ArrayList<ModelInfo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.str("id")
            if (id.isBlank()) continue
            list.add(
                ModelInfo(
                    id = id,
                    name = item.str("name", id),
                    vision = item.bool("vision"),
                    jsonMode = item.bool("jsonMode", true),
                    contextLength = item.int("contextLength"),
                    ownedBy = item.str("ownedBy")
                )
            )
        }
        _models.value = list
    }

    suspend fun refresh(): ApiOutcome<List<ModelInfo>> {
        return when (val outcome = client.listModels()) {
            is ApiOutcome.Ok -> {
                _models.value = outcome.value
                saveCache(outcome.value)
                outcome
            }
            is ApiOutcome.Err -> outcome
        }
    }

    fun filter(query: String): List<ModelInfo> {
        if (query.isBlank()) return _models.value
        val q = query.trim().lowercase()
        return _models.value.filter { it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
    }

    fun visionModels(): List<ModelInfo> = _models.value.filter { it.vision }

    fun find(id: String): ModelInfo? = _models.value.firstOrNull { it.id == id }

    private fun saveCache(models: List<ModelInfo>) {
        val array = JSONArray()
        models.forEach { m ->
            array.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("vision", m.vision)
                    .put("jsonMode", m.jsonMode)
                    .put("contextLength", m.contextLength)
                    .put("ownedBy", m.ownedBy)
            )
        }
        settings.saveModelsCache(JSONObject().put("at", System.currentTimeMillis()).put("models", array).toString())
    }
}
