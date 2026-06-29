package me.rerere.aestus

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean
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
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var scope: CoroutineScope? = null
    private var proactiveJob: Job? = null
    private var currentAiName: String = "AI"
    private var currentHumanName: String = "你"
    private val triggerRunning = AtomicBoolean(false)

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        proactiveJob = scope?.launch { proactiveLoop() }
        AppLog.d("Engine", "started")
    }

    fun stop() {
        proactiveJob?.cancel()
        scope?.cancel()
        scope = null
        AppLog.d("Engine", "stopped")
    }

    fun triggerNow() {
        if (!triggerRunning.compareAndSet(false, true)) {
            AppLog.d("Engine", "trigger already running, skip")
            return
        }
        AppLog.d("Engine", "manual trigger requested")
        scope?.launch {
            try {
                val cfg = configRepo.config.first()
                if (cfg.llmApiKey.isBlank()) { AppLog.d("Engine", "trigger: no key"); return@launch }
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val history = buildConversationContext(cfg)
                val phoneStats = buildPhoneStats()
                val prompt = "当前时间: $now\n$phoneStats\n\n最近对话:\n$history"
                val systemPrompt = if (cfg.systemPrompt.isNotBlank()) cfg.systemPrompt
                else "你是${currentAiName}，正在和${currentHumanName}私聊。看看最近对话，有什么想说的就自然地说。不想说也没关系。"
                val reply = agentLoop(cfg, systemPrompt, prompt)
                if (!reply.isNullOrBlank() && reply.length > 10) {
                    AppLog.d("Engine", "trigger reply: ${reply.take(60)}")
                    val id = db.insertMessage("out", "reply", reply, """{"proactive":true}""")
                    val msg = buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("ts", JsonPrimitive(nowIso()))
                        put("from", JsonPrimitive("ai"))
                        put("kind", JsonPrimitive("reply"))
                        put("text", JsonPrimitive(reply))
                    }
                    MainActivity.pushToPWA(msg.toString())
                } else {
                    AppLog.d("Engine", "trigger: skipped")
                }
            } catch (e: Exception) {
                AppLog.e("Engine", "trigger error: ${e.message}")
            } finally {
                triggerRunning.set(false)
            }
        }
    }

    private suspend fun proactiveLoop() {
        val cfg = configRepo.config.first()
        currentAiName = cfg.aiName
        currentHumanName = cfg.humanName
        delay(15000)
        AppLog.d("Engine", "proactive loop start")
        while (true) {
            try {
                if (cfg.llmApiKey.isBlank()) { delay(5000); continue }
                val latest = db.getMessages(convId = 1, 0, 1)
                val shouldSkip = latest.any { msg ->
                    msg.direction == "in" && msg.ts.let { ts ->
                        try {
                            val msgTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(ts)?.time ?: 0L
                            System.currentTimeMillis() - msgTime < cfg.proactiveInterval * 1000L
                        } catch (_: Exception) { false }
                    }
                }
                if (shouldSkip) {
                    AppLog.d("Engine", "proactive skip: recent human msg")
                    delay(30000)
                    continue
                }
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val history = buildConversationContext(cfg)
                val phoneStats = buildPhoneStats()
                val prompt = "当前时间: $now\n$phoneStats\n\n最近对话:\n$history"
                val systemPrompt = if (cfg.systemPrompt.isNotBlank()) cfg.systemPrompt
                else "你是${currentAiName}，正在和${currentHumanName}私聊。看看最近对话，有什么想说的就自然地说。不想说也没关系。"
                val reply = agentLoop(cfg, systemPrompt, prompt)
                if (!reply.isNullOrBlank() && reply.length > 10) {
                    AppLog.d("Engine", "proactive msg: ${reply.take(60)}")
                    val id = db.insertMessage("out", "reply", reply, """{"proactive":true}""")
                    val msg = buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("ts", JsonPrimitive(nowIso()))
                        put("from", JsonPrimitive("ai"))
                        put("kind", JsonPrimitive("reply"))
                        put("text", JsonPrimitive(reply))
                    }
                    MainActivity.pushToPWA(msg.toString())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.e("Engine", "proactive: ${e.message}") }
            finally {
                val interval = try {
                    configRepo.config.first().proactiveInterval.let { it.toLong() * 1000 }
                } catch (_: Exception) { 900000L }
                delay(interval.coerceAtLeast(60000L))
            }
        }
    }

    private suspend fun buildConversationContext(cfg: AppConfig): String {
        val history = db.getMessages(convId = 1, since = 0, limit = 50)
        return history.takeLast(30).joinToString("\n") { msg ->
            val role = if (msg.direction == "in") cfg.humanName else cfg.aiName
            "[$role]: ${msg.text}"
        }
    }

    private suspend fun buildPhoneStats(): String {
        val sb = StringBuilder()
        for (tool in mcpTools.tools) {
            if (tool.name == "get_weather" || tool.name == "get_calendar_events") continue
            try {
                sb.appendLine(mcpTools.executeTool(tool.name))
            } catch (_: Exception) {}
        }
        return sb.toString()
    }

    private fun toolSchemas(): JsonArray {
        val schemas = mutableListOf<JsonObject>()
        for (tool in mcpTools.tools) {
            schemas.add(buildJsonObject {
                put("type", JsonPrimitive("function"))
                putJsonObject("function") {
                    put("name", JsonPrimitive(tool.name))
                    put("description", JsonPrimitive(tool.description))
                    putJsonObject("parameters") {
                        put("type", JsonPrimitive("object"))
                        put("properties", JsonObject(emptyMap()))
                    }
                }
            })
        }
        schemas.add(buildJsonObject {
            put("type", JsonPrimitive("function"))
            putJsonObject("function") {
                put("name", JsonPrimitive("save_memory"))
                put("description", JsonPrimitive("一条你想长期记住的信息"))
                putJsonObject("parameters") {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("text") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("记忆内容")) }
                        putJsonObject("category") {
                            put("type", JsonPrimitive("string"))
                            put("enum", JsonArray(listOf(JsonPrimitive("preference"), JsonPrimitive("fact"), JsonPrimitive("event"), JsonPrimitive("habit"), JsonPrimitive("general"))))
                            put("description", JsonPrimitive("分类"))
                        }
                        putJsonObject("importance") { put("type", JsonPrimitive("integer")); put("description", JsonPrimitive("1-5")) }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("text"))))
                }
            }
        })
        schemas.add(buildJsonObject {
            put("type", JsonPrimitive("function"))
            putJsonObject("function") {
                put("name", JsonPrimitive("recall_memories"))
                put("description", JsonPrimitive("长期记忆"))
                putJsonObject("parameters") {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("搜索关键词")) }
                    }
                    put("required", JsonArray(emptyList()))
                }
            }
        })
        schemas.add(buildJsonObject {
            put("type", JsonPrimitive("function"))
            putJsonObject("function") {
                put("name", JsonPrimitive("set_ai_name"))
                put("description", JsonPrimitive("给自己换个名字"))
                putJsonObject("parameters") {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("新名字")) }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("name"))))
                }
            }
        })
        return JsonArray(schemas)
    }

    private suspend fun agentLoop(cfg: AppConfig, systemPrompt: String, userInput: String): String? {
        val messages = mutableListOf<JsonObject>()
        messages.add(buildJsonObject { put("role", JsonPrimitive("system")); put("content", JsonPrimitive(systemPrompt)) })
        messages.add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(userInput)) })

        for (round in 1..8) {
            val response = llmCall(cfg, messages)
            val msg = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: break
            val content = msg["content"]?.jsonPrimitive?.contentOrNull
            val toolCalls = msg["tool_calls"]?.jsonArray
            messages.add(msg)

            if (toolCalls != null && toolCalls.isNotEmpty()) {
                for (tc in toolCalls) {
                    val result = executeToolCall(cfg, tc.jsonObject)
                    messages.add(buildJsonObject {
                        put("role", JsonPrimitive("tool"))
                        put("tool_call_id", JsonPrimitive(tc.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: ""))
                        put("content", JsonPrimitive(result))
                    })
                }
            } else if (!content.isNullOrBlank()) {
                return content
            } else break
        }
        return null
    }

    private suspend fun llmCall(cfg: AppConfig, messages: List<JsonObject>): JsonObject {
        val apiUrl = cfg.llmApiBase.trimEnd('/') + "/chat/completions"
        val body = buildJsonObject {
            put("model", JsonPrimitive(cfg.llmModel))
            putJsonArray("messages") { messages.forEach { add(it) } }
            putJsonArray("tools") { toolSchemas().forEach { add(it) } }
            put("temperature", JsonPrimitive(0.7))
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(apiUrl).header("Authorization", "Bearer ${cfg.llmApiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMedia)).build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) AppLog.e("Engine", "LLM ${resp.code}: $text")
                json.parseToJsonElement(text).jsonObject
            }
        }
    }

    private suspend fun executeToolCall(cfg: AppConfig, tc: JsonObject): String {
        val name = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val argsJson = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
        val args = try { json.parseToJsonElement(argsJson).jsonObject } catch (_: Exception) { buildJsonObject {} }
        AppLog.d("Engine", "tool: $name")
        return when (name) {
            "save_memory" -> { memoryManager.addMemory(args["text"]?.jsonPrimitive?.contentOrNull ?: "", args["category"]?.jsonPrimitive?.contentOrNull ?: "general"); "已记住" }
            "recall_memories" -> memoryManager.getMemoryContext()
            "set_ai_name" -> {
                val newName = args["name"]?.jsonPrimitive?.contentOrNull ?: ""
                if (newName.isNotBlank()) { configRepo.updateAiName(newName); currentAiName = newName; "好的，以后我叫$newName" }
                else "名字不能为空"
            }
            "get_steps", "get_usage_stats", "get_current_time" -> mcpTools.executeTool(name)
            "get_weather" -> mcpTools.executeTool(name, mapOf("city" to configRepo.config.first().cityName))
            else -> "未知工具: $name"
        }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
