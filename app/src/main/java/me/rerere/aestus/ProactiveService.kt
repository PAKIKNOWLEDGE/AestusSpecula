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
    private var engine: Engine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.d("Svc", "ACTION_STOP")
            engine?.stop()
            engine = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRIGGER) {
            engine?.triggerNow()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                startEngine()
            } catch (e: Exception) {
                AppLog.e("Svc", "startEngine failed", e)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startEngine() {
        AppLog.d("Svc", "startEngine begin")
        engine?.stop()
        engine = null

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
        engine = Engine(repo, db, mcp, memory).also { it.start() }
        AppLog.d("Svc", "startEngine done")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.d("Svc", "onDestroy")
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Aestus", NotificationManager.IMPORTANCE_LOW).apply {
            description = "后台服务"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aestus Specula")
            .setContentText("运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val TAG = "Aestus"
        const val CHANNEL_ID = "aestus"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "me.rerere.aestus.STOP"
        const val ACTION_TRIGGER = "me.rerere.aestus.TRIGGER"

        fun start(ctx: Context) {
            val i = Intent(ctx, ProactiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, ProactiveService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }

        fun trigger(ctx: Context) {
            val i = Intent(ctx, ProactiveService::class.java).apply { action = ACTION_TRIGGER }
            ctx.startService(i)
        }
    }
}
