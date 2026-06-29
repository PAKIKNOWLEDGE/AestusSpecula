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
    val relaySecret = stringPreferencesKey("relay_secret")
    val llmApiKey = stringPreferencesKey("llm_api_key")
    val llmApiBase = stringPreferencesKey("llm_api_base")
    val llmModel = stringPreferencesKey("llm_model")
    val aiName = stringPreferencesKey("ai_name")
    val humanName = stringPreferencesKey("human_name")
    val decisionPrompt = stringPreferencesKey("decision_prompt")
    val proactiveInterval = stringPreferencesKey("proactive_interval")
}

data class AppConfig(
    val relaySecret: String = "",
    val llmApiKey: String = "",
    val llmApiBase: String = "https://api.deepseek.com/v1",
    val llmModel: String = "deepseek-v4-flash",
    val aiName: String = "AI",
    val humanName: String = "你",
    val decisionPrompt: String = DECISION_PROMPT_DEFAULT,
    val proactiveInterval: Int = 900,
)

class ConfigRepository(private val context: Context) {
    val config: Flow<AppConfig> = context.store.data.map { prefs ->
        AppConfig(
            relaySecret = prefs[ConfigKeys.relaySecret] ?: "",
            llmApiKey = prefs[ConfigKeys.llmApiKey] ?: "",
            llmApiBase = prefs[ConfigKeys.llmApiBase] ?: "https://api.deepseek.com/v1",
            llmModel = prefs[ConfigKeys.llmModel] ?: "deepseek-v4-flash",
            aiName = prefs[ConfigKeys.aiName] ?: "AI",
            humanName = prefs[ConfigKeys.humanName] ?: "你",
            decisionPrompt = prefs[ConfigKeys.decisionPrompt] ?: DECISION_PROMPT_DEFAULT,
            proactiveInterval = prefs[ConfigKeys.proactiveInterval]?.toIntOrNull() ?: 900,
        )
    }

    suspend fun save(cfg: AppConfig) {
        context.store.edit { prefs ->
            prefs[ConfigKeys.relaySecret] = cfg.relaySecret
            prefs[ConfigKeys.llmApiKey] = cfg.llmApiKey
            prefs[ConfigKeys.llmApiBase] = cfg.llmApiBase
            prefs[ConfigKeys.llmModel] = cfg.llmModel
            prefs[ConfigKeys.aiName] = cfg.aiName
            prefs[ConfigKeys.humanName] = cfg.humanName
            prefs[ConfigKeys.decisionPrompt] = cfg.decisionPrompt
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

const val DECISION_PROMPT_DEFAULT = """你是一个角色扮演场景的导演，负责判断助手（assistant）下一次应主动向用户发消息的时间。

【功能说明】
· 主动发消息：助手可在设定时间主动向用户发送消息，无需用户提问。
· 你需要评估当前的下次发消息时间：若认为无需调整，则保持原时间；否则进行修改。

【考虑角度】
1. 助手在睡醒、完成某件事、安全回到宿舍等情况下，应主动向用户报备或打招呼。
2. 若上下文中提到助手因睡着、忙碌等原因未能及时查看用户的消息，则应设定在助手睡醒或忙完后，主动给用户发一条消息。
3. 若上下文中提到助手需要在某个时间点监督或提醒用户，则设定在该时间点主动发消息。
4. 当一个话题尚未聊完时，需要假设：用户在助手最后一条消息发出后始终没有回复，那么助手应在什么时间主动发消息？请据此设定时间。

回复格式（只输出 JSON，不要其他内容）：
{
  "action": "send" | "skip",
  "message": "要发送的消息内容（仅 action=send 时需要）",
  "reasoning": "判断理由"
}"""
