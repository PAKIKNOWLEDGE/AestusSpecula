package me.rerere.aestus

import android.content.res.AssetManager
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LocalRelayServer(
    private val assetManager: AssetManager,
    private val db: Database,
    private val configRepo: ConfigRepository,
) {
    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val appFlow = MutableSharedFlow<JsonElement>(extraBufferCapacity = 64)
    val channelFlow = MutableSharedFlow<JsonElement>(extraBufferCapacity = 64)

    private var authSecret: String = ""
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun broadcastAppMessage(msg: JsonObject) {
        appFlow.tryEmit(msg)
    }

    fun broadcastTyping(active: Boolean) {
        appFlow.tryEmit(buildJsonObject {
            put("type", JsonPrimitive("typing"))
            put("active", JsonPrimitive(active))
        })
    }

    fun start(port: Int = RELAY_PORT): Int {
        server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respondText("Internal error", status = HttpStatusCode.InternalServerError)
                }
            }
            routing {
                post("/app/send") { handleSend(call) }
                get("/app/history") { handleHistory(call) }
                get("/app/stream") { handleStream(call) }
                get("/app/status") { handleStatus(call) }
                get("/healthz") { call.respond(buildJsonObject { put("ok", JsonPrimitive(true)); put("app", JsonPrimitive("aestus")) }) }
                post("/app/ping") { call.respond(buildJsonObject { put("ok", JsonPrimitive(true)) }) }
                post("/app/upload") { handleUpload(call) }
                get("/uploads/{name}") { handleUploads(call) }
                get("/{path...}") { handleStatic(call) }
            }
        }
        server!!.start(wait = false)
        scope.launch {
            configRepo.config.collect { cfg ->
                authSecret = cfg.relaySecret
            }
        }
        return port
    }

    fun stop() {
        server?.stop(1000, 2000)
        scope.cancel()
    }

    private fun authOk(call: ApplicationCall): Boolean {
        val header = call.request.headers["Authorization"] ?: ""
        val token = if (header.startsWith("Bearer ")) header.removePrefix("Bearer ")
        else call.request.queryParameters["token"] ?: ""
        return token.isNotEmpty() && token == authSecret
    }

    private suspend fun handleSend(call: ApplicationCall) {
        if (!authOk(call)) { call.respond(HttpStatusCode.Unauthorized); return }
        val body = json.parseToJsonElement(call.receiveText()).jsonObject
        val text = (body["text"]?.jsonPrimitive?.content ?: "").trim()
        val attachments = body["attachments"]?.jsonArray ?: emptyList()
        if (text.isEmpty() && attachments.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest); return
        }
        val meta = buildJsonObject {
            put("user", JsonPrimitive("human"))
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments))
        }
        val id = db.insertMessage("in", "user", text, meta.toString())
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id)); put("ts", JsonPrimitive(nowIso()))
            put("direction", JsonPrimitive("in")); put("from", JsonPrimitive("human"))
            put("kind", JsonPrimitive("user")); put("text", JsonPrimitive(text)); put("meta", meta)
        }
        appFlow.emit(msg)
        channelFlow.emit(buildJsonObject {
            put("id", JsonPrimitive(id)); put("content", JsonPrimitive(text))
            put("user", JsonPrimitive("human")); put("ts", JsonPrimitive(nowIso()))
            put("attachments", if (attachments.isNotEmpty()) JsonArray(attachments) else JsonArray(emptyList()))
        })
        broadcastTyping(true)
        call.respond(buildJsonObject { put("id", JsonPrimitive(id)) })
    }

    private suspend fun handleHistory(call: ApplicationCall) {
        if (!authOk(call)) { call.respond(HttpStatusCode.Unauthorized); return }
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceAtMost(500)
        val messages = db.getMessages(since, limit).map { msg ->
            buildJsonObject {
                put("id", JsonPrimitive(msg.id)); put("ts", JsonPrimitive(msg.ts))
                put("from", JsonPrimitive(if (msg.direction == "in") "human" else "ai"))
                put("kind", JsonPrimitive(msg.kind)); put("text", JsonPrimitive(msg.text))
                put("meta", runCatching { json.parseToJsonElement(msg.meta).jsonObject }.getOrDefault(buildJsonObject {}))
            }
        }
        call.respond(buildJsonObject { put("messages", JsonArray(messages)) })
    }

    private suspend fun handleStream(call: ApplicationCall) {
        if (!authOk(call)) { call.respond(HttpStatusCode.Unauthorized); return }
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.EventStream
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeStringUtf8("retry: 3000\n: connected\n\n")
                var flowJob: Job? = null
                try {
                    flowJob = scope.launch {
                        appFlow.collect { payload ->
                            try { channel.writeStringUtf8("data: ${payload}\n\n") } catch (_: Exception) { }
                        }
                    }
                    while (true) {
                        delay(15000)
                        channel.writeStringUtf8("event: ping\ndata: {}\n\n")
                    }
                } catch (_: Exception) { }
                finally { flowJob?.cancel() }
            }
        })
    }

    private suspend fun handleStatus(call: ApplicationCall) {
        if (!authOk(call)) { call.respond(HttpStatusCode.Unauthorized); return }
        call.respond(buildJsonObject { put("online", JsonPrimitive(true)) })
    }

    private suspend fun handleUpload(call: ApplicationCall) {
        if (!authOk(call)) { call.respond(HttpStatusCode.Unauthorized); return }
        val name = call.request.queryParameters["name"] ?: "file"
        val bytes = call.receive<ByteArray>()
        val mime = call.request.contentType()?.toString() ?: "application/octet-stream"
        val uploadDir = File("/tmp/aestus_uploads").also { it.mkdirs() }
        val safeName = "att-${UUID.randomUUID()}-${name.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        File(uploadDir, safeName).writeBytes(bytes)
        call.respond(buildJsonObject {
            put("url", JsonPrimitive("/uploads/$safeName")); put("name", JsonPrimitive(name))
            put("size", JsonPrimitive(bytes.size)); put("mime", JsonPrimitive(mime))
            put("kind", JsonPrimitive(if (mime.startsWith("image/")) "image" else "file"))
        })
    }

    private suspend fun handleUploads(call: ApplicationCall) {
        if (!authOk(call)) { call.respond(HttpStatusCode.Unauthorized); return }
        val name = call.parameters["name"] ?: ""
        val file = File("/tmp/aestus_uploads", name)
        if (!file.exists()) { call.respond(HttpStatusCode.NotFound); return }
        call.respondFile(file)
    }

    private suspend fun handleStatic(call: ApplicationCall) {
        val path = call.parameters.getAll("path")?.joinToString("/") ?: "index.html"
        val safePath = if (path.isBlank() || path.endsWith("/") || !path.contains('.')) "web/index.html"
        else "web/$path"
        try {
            val input = assetManager.open(safePath)
            val bytes = input.readBytes().also { input.close() }
            val ext = safePath.substringAfterLast('.', "")
            val ct = when (ext) {
                "html" -> ContentType.Text.Html; "css" -> ContentType.Text.CSS
                "js" -> ContentType.Application.JavaScript; "json" -> ContentType.Application.Json
                "png" -> ContentType.Image.PNG; "svg" -> ContentType.Image.SVG
                "ico" -> ContentType.Image.XIcon; "mp3" -> ContentType.Audio.MPEG
                "webmanifest" -> ContentType.Application.Json
                else -> ContentType.Application.OctetStream
            }
            call.respondBytes(bytes, ct)
        } catch (_: Exception) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
        }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    companion object {
        const val RELAY_PORT = 9199
    }
}
