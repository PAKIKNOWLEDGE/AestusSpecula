package me.rerere.aestus

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

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
        if (stepSensor != null)
            sensorManager.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun destroy() {
        sensorManager.unregisterListener(stepListener)
    }

    private suspend fun getSteps(): String = withContext(Dispatchers.IO) {
        if (!stepsInitialized) return@withContext "计步器未初始化"
        "今日步数: ${lastStepCount.toInt()}"
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
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastEvent = event
            else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND && lastEvent != null) {
                appUsage[lastEvent.packageName ?: "unknown"] = (appUsage[lastEvent.packageName ?: "unknown"] ?: 0) + (event.timeStamp - lastEvent.timeStamp)
                lastEvent = null
            }
        }
        val sorted = appUsage.entries.sortedByDescending { it.value }.take(5)
        if (sorted.isEmpty()) return@withContext "未获取到使用数据（可能无权限）"
        buildString {
            appendLine("今日使用最多的应用 Top 5：")
            sorted.forEachIndexed { i, (pkg, time) ->
                appendLine("${i + 1}. ${pkg.substringAfterLast('.')} - ${time / 60000}分钟")
            }
        }
    }

    private suspend fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return "当前时间: ${sdf.format(Date())}"
    }

    private suspend fun getCalendarEvents(): String = withContext(Dispatchers.IO) {
        try {
            val cal = Calendar.getInstance()
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 3)
            val end = cal.timeInMillis
            val cursor = context.contentResolver.query(
                CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendPath(java.lang.Long.toString(start))
                    .appendPath(java.lang.Long.toString(end)).build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            ) ?: return@withContext "无法读取日历（可能无权限）"
            val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            val events = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val title = cursor.getString(0) ?: continue
                val begin = cursor.getLong(2)
                val allDay = cursor.getInt(4) != 0
                events.add("- $title (${sdf.format(Date(begin))}${if (allDay) " 全天" else ""})")
            }
            cursor.close()
            if (events.isEmpty()) return@withContext "近3天无日历事件"
            buildString { appendLine("近3天日历事件："); events.forEach { appendLine(it) } }
        } catch (e: SecurityException) {
            "需要日历权限，请在手机「设置 → 应用 → Aestus Specula → 权限」中开启「日历」"
        } catch (e: Exception) {
            "日历读取失败: ${e.message}"
        }
    }

    private suspend fun getBatteryInfo(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "无法读取电量"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plugType = when {
            plugged == BatteryManager.BATTERY_PLUGGED_AC -> "交流电"
            plugged == BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
            else -> ""
        }
        return buildString {
            if (pct >= 0) append("电量: ${pct}%")
            if (charging) append(" (充电中${if (plugType.isNotEmpty()) " - $plugType" else ""})")
            else append(" (未充电)")
        }
    }

    private suspend fun getDeviceInfo(): String {
        val brand = Build.BRAND.ifBlank { "未知" }
        val model = Build.MODEL.ifBlank { "未知" }
        val version = Build.VERSION.RELEASE.ifBlank { "未知" }
        return "设备: $brand $model (Android $version)"
    }

    private suspend fun getWeather(city: String): String = withContext(Dispatchers.IO) {
        val cityName = city.ifBlank { "北京" }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://wttr.in/${java.net.URLEncoder.encode(cityName, "UTF-8")}?format=%C+%t+%w+%h&lang=zh")
                .header("User-Agent", "curl")
                .build()
            val resp = client.newCall(request).execute()
            val body = resp.body?.string()?.trim() ?: "获取失败"
            "$cityName: $body"
        } catch (e: Exception) {
            "天气获取失败: ${e.message}"
        }
    }

    val tools: List<Tool> = listOf(
        Tool("get_steps", "今日步数") { getSteps() },
        Tool("get_usage_stats", "今日手机使用情况") { getUsageStats() },
        Tool("get_current_time", "当前日期和时间") { getCurrentTime() },
        Tool("get_calendar_events", "近3天日历事件") { getCalendarEvents() },
        Tool("get_battery_info", "当前电量") { getBatteryInfo() },
        Tool("get_device_info", "设备信息") { getDeviceInfo() },
        Tool("get_weather", "当前天气（需在设置中配置城市）") { args ->
            getWeather(args.getOrDefault("city", ""))
        },
    )

    suspend fun executeTool(name: String, args: Map<String, String> = emptyMap()): String {
        return tools.find { it.name == name }?.handler?.invoke(args) ?: "未知工具: $name"
    }
}
