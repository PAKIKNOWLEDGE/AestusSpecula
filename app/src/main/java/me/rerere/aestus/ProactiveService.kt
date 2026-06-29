package me.rerere.aestus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ProactiveService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engine: Engine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                startEngine()
            } catch (e: Exception) {
                Log.e(TAG, "startEngine failed", e)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startEngine() {
        val repo = ConfigRepository(this@ProactiveService)
        val cfg = repo.config.first()
        if (cfg.llmApiKey.isBlank()) {
            Log.w(TAG, "API key not set")
            stopSelf()
            return
        }
        val db = Database(this@ProactiveService)
        val mcp = McpTools(this@ProactiveService).also { it.init() }
        val memory = MemoryManager(db)
        val relay = LocalRelayServer(
            assetManager = assets,
            db = db,
            configRepo = repo,
        )
        val actualPort = relay.start()
        Log.i(TAG, "Relay started on port $actualPort")
        engine = Engine(repo, db, mcp, memory, relay).also { it.start() }
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEngine()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Aestus Specula",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "后台主动消息服务" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aestus Specula")
            .setContentText("后台运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val TAG = "AestusService"
        const val CHANNEL_ID = "aestus_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "me.rerere.aestus.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ProactiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProactiveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
