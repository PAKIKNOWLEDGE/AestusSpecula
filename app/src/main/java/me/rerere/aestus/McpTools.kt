package me.rerere.aestus

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class Tool(
    val name: String,
    val description: String,
    val handler: suspend (Map<String, String>) -> String,
)

class McpTools(private val context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var lastStepCount: Float = 0f
    private var stepsInitialized = false

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                if (!stepsInitialized) {
                    lastStepCount = event.values[0]
                    stepsInitialized = true
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun init() {
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor != null) {
            sensorManager.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun destroy() {
        sensorManager.unregisterListener(stepListener)
    }

    private suspend fun getSteps(): String = withContext(Dispatchers.IO) {
        if (!stepsInitialized) return@withContext "计步器未初始化（未获取到步数数据）"
        val current = lastStepCount
        return@withContext "今日步数: ${current.toInt()}"
    }

    private suspend fun getUsageStats(): String = withContext(Dispatchers.IO) {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24 * 60 * 60 * 1000
        val events = usageStatsManager.queryEvents(startTime, endTime)
        val appUsage = mutableMapOf<String, Long>()
        var lastEvent: UsageEvents.Event? = null
        while (events.hasNextEvent()) {
            val event = UsageEvents.Event()
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastEvent = event
            } else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND && lastEvent != null) {
                val duration = event.timeStamp - lastEvent.timeStamp
                appUsage[lastEvent.packageName ?: "unknown"] =
                    (appUsage[lastEvent.packageName ?: "unknown"] ?: 0) + duration
                lastEvent = null
            }
        }
        val sorted = appUsage.entries.sortedByDescending { it.value }.take(5)
        if (sorted.isEmpty()) return@withContext "未获取到使用数据（可能缺少权限）"
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return@withContext buildString {
            appendLine("当前时间: $now")
            appendLine("今日使用最多的应用 Top 5：")
            sorted.forEachIndexed { i, (pkg, time) ->
                val minutes = time / 60000
                val appName = pkg.substringAfterLast('.')
                appendLine("${i + 1}. $appName - ${minutes}分钟")
            }
        }
    }

    private suspend fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return "当前时间: ${sdf.format(Date())}"
    }

    val tools: List<Tool> = listOf(
        Tool(
            name = "get_steps",
            description = "获取今日步数",
            handler = { getSteps() }
        ),
        Tool(
            name = "get_usage_stats",
            description = "获取今日手机使用情况和最常用应用Top5",
            handler = { getUsageStats() }
        ),
        Tool(
            name = "get_current_time",
            description = "获取当前日期和时间",
            handler = { getCurrentTime() }
        ),
    )

    suspend fun executeTool(name: String, args: Map<String, String> = emptyMap()): String {
        val tool = tools.find { it.name == name } ?: return "未知工具: $name"
        return tool.handler(args)
    }
}
