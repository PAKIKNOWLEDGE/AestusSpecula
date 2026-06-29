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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val configRepo by lazy { ConfigRepository(this) }
    var webView: WebView? = null

    companion object {
        private var webViewRef: java.lang.ref.WeakReference<WebView>? = null

        fun setWebView(wv: WebView) {
            webViewRef = java.lang.ref.WeakReference(wv)
        }

        fun pushToPWA(json: String) {
            val wv = webViewRef?.get() ?: return
            val safeJson = json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            wv.post {
                wv.evaluateJavascript("bridgeAddMessage('$safeJson')", null)
            }
        }
    }

    val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(this)
                }
            }
        }
    }
}

@Composable
fun ChatScreen(activity: MainActivity) {
    val ctx = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.setAllowFileAccessFromFileURLs(true)
                    settings.setAllowUniversalAccessFromFileURLs(true)
                    settings.mediaPlaybackRequiresUserGesture = false
                    addJavascriptInterface(
                        PwaBridge(
                            Database(context),
                            ConfigRepository(context),
                            MemoryManager(Database(context)),
                        ).also { bridge ->
                            PwaBridge.onServiceAction = { action, _ ->
                                when (action) {
                                    "start" -> ProactiveService.start(context)
                                    "stop" -> ProactiveService.stop(context)
                                    "trigger" -> ProactiveService.trigger(context)
                                    "config_saved" -> {
                                        ProactiveService.stop(context)
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            ProactiveService.start(context)
                                        }, 300)
                                    }
                                }
                            }
                        },
                        "PwaBridge"
                    )
                    MainActivity.setWebView(this)
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
    }

    LaunchedEffect(Unit) {
        activity.webView?.loadUrl("file:///android_asset/web/index.html")
    }

    // Request notification permission on launch
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                activity.notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
