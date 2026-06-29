package me.rerere.aestus

import android.webkit.JavascriptInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class PwaBridge(
    private val db: Database,
    private val configRepo: ConfigRepository,
    private val memoryManager: MemoryManager,
) {
    companion object {
        var onServiceAction: ((action: String, json: String) -> Unit)? = null
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    @Volatile
    var currentConvId: Long = 1

    @JavascriptInterface
    fun setConv(id: Long) { currentConvId = id }

    @JavascriptInterface
    fun getConv(): Long = currentConvId

    @JavascriptInterface
    fun listConversations(): String {
        val convs = db.listConversations()
        return JsonArray(convs.map { conv ->
            buildJsonObject {
                put("id", JsonPrimitive(conv.id))
                put("title", JsonPrimitive(conv.title))
            }
        }).toString()
    }

    @JavascriptInterface
    fun createConversation(title: String): String {
        val id = db.createConversation(title.ifBlank { "新对话" })
        currentConvId = id
        return """{"id":$id}"""
    }

    @JavascriptInterface
    fun renameConversation(id: Long, title: String) {
        db.renameConversation(id, title)
    }

    @JavascriptInterface
    fun deleteConversation(id: Long) {
        if (id <= 1) return
        db.deleteConversation(id)
        if (currentConvId == id) currentConvId = 1
    }

    // ── Messages ────────────────────────────────────────────

    @JavascriptInterface
    fun sendMessage(text: String): String {
        val id = db.insertMessage("in", "user", text, convId = currentConvId)
        val ts = nowIso()
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id)); put("ts", JsonPrimitive(ts))
            put("from", JsonPrimitive("human")); put("kind", JsonPrimitive("user"))
            put("text", JsonPrimitive(text)); put("convId", JsonPrimitive(currentConvId))
        }
        scope.launch { callLlmAndReply(id, text) }
        return msg.toString()
    }

    private suspend fun callLlmAndReply(lastMsgId: Long, text: String) {
        try {
            val cfg = configRepo.config.first()
            if (cfg.llmApiKey.isBlank()) return
            AppLog.d("Bridge", "llm call: $text")
            val history = db.getMessages(currentConvId, 0, 50)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val basePrompt = if (cfg.systemPrompt.isNotBlank()) cfg.systemPrompt
                else "你是${cfg.aiName}，正在和${cfg.humanName}私聊。"
            val messages = mutableListOf<JsonObject>()
            messages.add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive("$basePrompt\n\n当前时间: $now"))
            })
            for (msg in history.takeLast(30)) {
                messages.add(buildJsonObject {
                    put("role", JsonPrimitive(if (msg.direction == "in") "user" else "assistant"))
                    put("content", JsonPrimitive(msg.text))
                })
            }
            val apiUrl = cfg.llmApiBase.trimEnd('/') + "/chat/completions"
            val body = buildJsonObject {
                put("model", JsonPrimitive(cfg.llmModel))
                putJsonArray("messages") { messages.forEach { add(it) } }
                put("temperature", JsonPrimitive(0.7))
            }
            val request = Request.Builder()
                .url(apiUrl).header("Authorization", "Bearer ${cfg.llmApiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMedia)).build()
            val resp = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) { AppLog.e("Bridge", "LLM ${r.code}"); return@use null }
                    json.parseToJsonElement(r.body!!.string()).jsonObject
                }
            }
            val replyText = resp?.get("choices")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (replyText.isNullOrBlank()) return

            AppLog.d("Bridge", "reply: ${replyText.take(60)}")
            val id = db.insertMessage("out", "reply", replyText, convId = currentConvId)
            val ts2 = nowIso()
            val replyMsg = buildJsonObject {
                put("id", JsonPrimitive(id)); put("ts", JsonPrimitive(ts2))
                put("from", JsonPrimitive("ai")); put("kind", JsonPrimitive("reply"))
                put("text", JsonPrimitive(replyText)); put("convId", JsonPrimitive(currentConvId))
            }
            MainActivity.pushToPWA(replyMsg.toString())
        } catch (e: Exception) {
            AppLog.e("Bridge", "llm error: ${e.message}")
        }
    }

    @JavascriptInterface
    fun getHistory(limit: Int): String {
        val messages = db.getMessages(currentConvId, 0, limit.coerceIn(1, 200))
        val list = JsonArray(messages.map { msg ->
            buildJsonObject {
                put("id", JsonPrimitive(msg.id)); put("ts", JsonPrimitive(msg.ts))
                put("from", JsonPrimitive(if (msg.direction == "in") "human" else "ai"))
                put("kind", JsonPrimitive(msg.kind)); put("text", JsonPrimitive(msg.text))
                put("convId", JsonPrimitive(msg.convId))
                put("meta", runCatching { json.parseToJsonElement(msg.meta).jsonObject }.getOrDefault(buildJsonObject {}))
            }
        })
        return list.toString()
    }

    @JavascriptInterface
    fun clearMessages(): String {
        db.clearMessages(currentConvId)
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun getFullConfig(): String {
        return runBlocking {
            val cfg = configRepo.config.first()
            buildJsonObject {
                put("llmApiKey", JsonPrimitive(cfg.llmApiKey))
                put("llmApiBase", JsonPrimitive(cfg.llmApiBase))
                put("llmModel", JsonPrimitive(cfg.llmModel))
                put("aiName", JsonPrimitive(cfg.aiName))
                put("humanName", JsonPrimitive(cfg.humanName))
                put("systemPrompt", JsonPrimitive(cfg.systemPrompt))
                put("cityName", JsonPrimitive(cfg.cityName))
                put("proactiveInterval", JsonPrimitive(cfg.proactiveInterval))
            }.toString()
        }
    }

    @JavascriptInterface
    fun saveConfig(configJson: String) {
        val j = json.parseToJsonElement(configJson).jsonObject
        val cfg = AppConfig(
            llmApiKey = j["llmApiKey"]?.jsonPrimitive?.contentOrNull ?: "",
            llmApiBase = j["llmApiBase"]?.jsonPrimitive?.contentOrNull ?: "https://api.deepseek.com/v1",
            llmModel = j["llmModel"]?.jsonPrimitive?.contentOrNull ?: "deepseek-v4-flash",
            aiName = j["aiName"]?.jsonPrimitive?.contentOrNull ?: "AI",
            humanName = j["humanName"]?.jsonPrimitive?.contentOrNull ?: "你",
            systemPrompt = j["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: "",
            cityName = j["cityName"]?.jsonPrimitive?.contentOrNull ?: "",
            proactiveInterval = j["proactiveInterval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 900,
        )
        runBlocking { configRepo.save(cfg) }
        onServiceAction?.invoke("config_saved", "")
    }

    @JavascriptInterface
    fun serviceAction(action: String) {
        onServiceAction?.invoke(action, "")
    }

    @JavascriptInterface
    fun getLogs(limit: Int): String {
        return AppLog.getRecent(limit.coerceIn(10, 200)).joinToString("\n")
    }

    @JavascriptInterface
    fun getConfig(): String {
        return runBlocking {
            val cfg = configRepo.config.first()
            buildJsonObject {
                put("aiName", JsonPrimitive(cfg.aiName))
                put("humanName", JsonPrimitive(cfg.humanName))
                put("cityName", JsonPrimitive(cfg.cityName))
                put("systemPrompt", JsonPrimitive(cfg.systemPrompt))
            }.toString()
        }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
