package me.rerere.aestus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val configRepo by lazy { ConfigRepository(this) }
    var webView: WebView? = null

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(configRepo, this)
                }
            }
        }
    }

    fun ensureNotification(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                Toast.makeText(this, "请先授权通知权限", Toast.LENGTH_LONG).show()
                return
            }
        }
        onGranted()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(configRepo: ConfigRepository, activity: MainActivity) {
    val ctx = LocalContext.current
    val config by configRepo.config.collectAsState(initial = AppConfig())
    val scope = rememberCoroutineScope()

    var showSettings by remember { mutableStateOf(true) }
    var relaySecret by remember { mutableStateOf(config.relaySecret) }
    var llmApiKey by remember { mutableStateOf(config.llmApiKey) }
    var llmApiBase by remember { mutableStateOf(config.llmApiBase) }
    var llmModel by remember { mutableStateOf(config.llmModel) }
    var aiName by remember { mutableStateOf(config.aiName) }
    var humanName by remember { mutableStateOf(config.humanName) }
    var proactiveInterval by remember { mutableStateOf(config.proactiveInterval.toString()) }
    var statusText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        relaySecret = config.relaySecret
        llmApiKey = config.llmApiKey
        llmApiBase = config.llmApiBase
        llmModel = config.llmModel
        aiName = config.aiName
        humanName = config.humanName
        proactiveInterval = config.proactiveInterval.toString()
    }

    if (showSettings) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Aestus Specula", style = MaterialTheme.typography.headlineMedium)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(if (isRunning) "● Running" else "○ Stopped")
                    if (statusText.isNotBlank())
                        Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("设置", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = relaySecret, onValueChange = { relaySecret = it },
                        label = { Text("Relay Secret (PWA 登录密钥)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = llmApiKey, onValueChange = { llmApiKey = it },
                        label = { Text("LLM API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = llmApiBase, onValueChange = { llmApiBase = it },
                        label = { Text("LLM API Base URL") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = llmModel, onValueChange = { llmModel = it },
                        label = { Text("Model") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = aiName, onValueChange = { aiName = it },
                        label = { Text("AI 名称") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = humanName, onValueChange = { humanName = it },
                        label = { Text("你的名称") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proactiveInterval, onValueChange = { proactiveInterval = it },
                        label = { Text("主动间隔（秒）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        activity.ensureNotification {
                            scope.launch {
                                try {
                                    val cfg = AppConfig(
                                        relaySecret = relaySecret,
                                        llmApiKey = llmApiKey,
                                        llmApiBase = llmApiBase.ifBlank { "https://api.deepseek.com/v1" },
                                        llmModel = llmModel.ifBlank { "deepseek-v4-flash" },
                                        aiName = aiName.ifBlank { "AI" },
                                        humanName = humanName.ifBlank { "你" },
                                        proactiveInterval = proactiveInterval.toIntOrNull() ?: 900,
                                    )
                                    configRepo.save(cfg)
                                    ProactiveService.start(ctx)
                                    isRunning = true
                                    statusText = "已启动"
                                } catch (e: Exception) {
                                    statusText = "错误: ${e.message}"
                                    isRunning = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存并启动") }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            ProactiveService.stop(ctx)
                            isRunning = false
                            statusText = "已停止"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("停止") }
            }

            if (isRunning) {
                Button(
                    onClick = { showSettings = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开聊天") }
            }
        }
    } else {
        // WebView with PWA
        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowContentAccess = true
                        settings.allowFileAccess = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                view.loadUrl(request.url.toString())
                                return true
                            }
                        }
                        webChromeClient = WebChromeClient()
                        activity.webView = this
                    }
                },
                modifier = Modifier.weight(1f)
            )
            // Bottom bar with settings button
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Aestus Specula", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { showSettings = true }) {
                        Text("设置")
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            val wv = activity.webView
            if (wv != null) {
                wv.loadUrl("http://127.0.0.1:${LocalRelayServer.RELAY_PORT}/")
            }
        }
    }
}
