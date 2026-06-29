package me.rerere.aestus

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class Engine(
    private val configRepo: ConfigRepository,
    private val db: Database,
    private val mcpTools: McpTools,
    private val memoryManager: MemoryManager,
    private val relay: LocalRelayServer,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var scope: CoroutineScope? = null
    private var channelJob: Job? = null
    private var proactiveJob: Job? = null

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        channelJob = scope?.launch { listenChannel() }
        proactiveJob = scope?.launch { proactiveLoop() }
    }

    fun stop() {
        channelJob?.cancel()
        proactiveJob?.cancel()
        scope?.cancel()
        scope = null
    }

    private suspend fun listenChannel() {
        relay.channelFlow.collect { payload ->
            try {
                val obj = payload.jsonObject
                val text = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@collect
                handleIncoming(text)
            } catch (_: Exception) {}
        }
    }

    private suspend fun handleIncoming(text: String) {
        val cfg = configRepo.config.first()
        if (cfg.llmApiKey.isBlank()) return
        relay.broadcastTyping(true)
        val context = buildContext(cfg)
        val decision = callDecisionLlm(cfg, context)
        if (decision.action == "send" && decision.message.isNotBlank()) {
            sendReply(cfg, decision.message, proactive = false)
        }
        relay.broadcastTyping(false)
    }

    private suspend fun proactiveLoop() {
        delay(10000)
        while (coroutineContext.isActive) {
            try {
                val cfg = configRepo.config.first()
                if (cfg.llmApiKey.isBlank()) {
                    delay(5000)
                    continue
                }
                val interval = (cfg.proactiveInterval * 1000L).coerceAtLeast(60000L)
                delay(interval)
                val context = buildContext(cfg)
                val decision = callDecisionLlm(cfg, context)
                if (decision.action == "send" && decision.message.isNotBlank()) {
                    sendReply(cfg, decision.message, proactive = true)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun sendReply(cfg: AppConfig, text: String, proactive: Boolean) {
        val meta = buildJsonObject {
            put("type", JsonPrimitive("reply"))
            put("text", JsonPrimitive(text))
            put("proactive", JsonPrimitive(proactive))
        }
        val id = db.insertMessage("out", "reply", text, meta.toString())
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("ts", JsonPrimitive(nowIso()))
            put("from", JsonPrimitive("ai"))
            put("kind", JsonPrimitive("reply"))
            put("text", JsonPrimitive(text))
            put("meta", meta)
        }
        relay.broadcastAppMessage(msg)
    }

    private suspend fun buildContext(cfg: AppConfig): String {
        val sb = StringBuilder()
        sb.appendLine("AI名称: ${cfg.aiName}")
        sb.appendLine("用户名称: ${cfg.humanName}")

        val memoryCtx = memoryManager.getMemoryContext()
        if (memoryCtx.isNotBlank()) sb.appendLine(memoryCtx)

        sb.appendLine()
        sb.appendLine("【手机状态】")
        for (tool in mcpTools.tools) {
            try {
                val result = mcpTools.executeTool(tool.name)
                sb.appendLine(result)
            } catch (_: Exception) {}
        }

        sb.appendLine()
        sb.appendLine("【最近对话】")
        val history = db.getMessages(0, 50)
        for (msg in history.takeLast(30)) {
            val role = if (msg.direction == "in") cfg.humanName else cfg.aiName
            sb.appendLine("[$role]: ${msg.text}")
        }
        return sb.toString()
    }

    private suspend fun callDecisionLlm(cfg: AppConfig, context: String): DecisionResult {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val apiUrl = cfg.llmApiBase.trimEnd('/') + "/chat/completions"
        val body = buildJsonObject {
            put("model", JsonPrimitive(cfg.llmModel))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(cfg.decisionPrompt))
                }
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive("当前时间: $now\n\n上下文:\n$context"))
                }
            }
            putJsonObject("response_format") { put("type", JsonPrimitive("json_object")) }
            put("temperature", JsonPrimitive(0.1))
        }
        val request = Request.Builder()
            .url(apiUrl)
            .header("Authorization", "Bearer ${cfg.llmApiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use DecisionResult("skip", reasoning = "API error: ${resp.code}")
                val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
                val content = root["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: "{}"
                try {
                    json.decodeFromString<DecisionResult>(content)
                } catch (e: Exception) {
                    DecisionResult("skip", reasoning = "parse error: ${e.message}")
                }
            }
        }
    }

    private fun nowIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
