package me.rerere.aestus

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.store by preferencesDataStore("aestus")

object ConfigKeys {
    val llmApiKey = stringPreferencesKey("llm_api_key")
    val llmApiBase = stringPreferencesKey("llm_api_base")
    val llmModel = stringPreferencesKey("llm_model")
    val aiName = stringPreferencesKey("ai_name")
    val humanName = stringPreferencesKey("human_name")
    val systemPrompt = stringPreferencesKey("system_prompt")
    val cityName = stringPreferencesKey("city_name")
    val proactiveInterval = stringPreferencesKey("proactive_interval")
}

data class AppConfig(
    val llmApiKey: String = "",
    val llmApiBase: String = "https://api.deepseek.com/v1",
    val llmModel: String = "deepseek-v4-flash",
    val aiName: String = "AI",
    val humanName: String = "你",
    val systemPrompt: String = "",
    val cityName: String = "",
    val proactiveInterval: Int = 900,
)

class ConfigRepository(private val context: Context) {
    val config: Flow<AppConfig> = context.store.data.map { prefs ->
        AppConfig(
            llmApiKey = prefs[ConfigKeys.llmApiKey] ?: "",
            llmApiBase = prefs[ConfigKeys.llmApiBase] ?: "https://api.deepseek.com/v1",
            llmModel = prefs[ConfigKeys.llmModel] ?: "deepseek-v4-flash",
            aiName = prefs[ConfigKeys.aiName] ?: "AI",
            humanName = prefs[ConfigKeys.humanName] ?: "你",
            systemPrompt = prefs[ConfigKeys.systemPrompt] ?: "",
            cityName = prefs[ConfigKeys.cityName] ?: "",
            proactiveInterval = prefs[ConfigKeys.proactiveInterval]?.toIntOrNull() ?: 900,
        )
    }

    suspend fun updateAiName(name: String) {
        context.store.edit { prefs -> prefs[ConfigKeys.aiName] = name }
    }

    suspend fun save(cfg: AppConfig) {
        context.store.edit { prefs ->
            prefs[ConfigKeys.llmApiKey] = cfg.llmApiKey
            prefs[ConfigKeys.llmApiBase] = cfg.llmApiBase
            prefs[ConfigKeys.llmModel] = cfg.llmModel
            prefs[ConfigKeys.aiName] = cfg.aiName
            prefs[ConfigKeys.humanName] = cfg.humanName
            prefs[ConfigKeys.systemPrompt] = cfg.systemPrompt
            prefs[ConfigKeys.cityName] = cfg.cityName
            prefs[ConfigKeys.proactiveInterval] = cfg.proactiveInterval.toString()
        }
    }
}

@Serializable
data class DecisionResult(
    val action: String,
    val message: String = "",
    val reasoning: String = "",
)
