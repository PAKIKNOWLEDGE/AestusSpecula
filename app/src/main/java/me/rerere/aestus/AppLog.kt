package me.rerere.aestus

import java.text.SimpleDateFormat
import java.util.*

object AppLog {
    private val buffer = ArrayDeque<String>(200)
    private val maxLines = 200
    private val dateFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun d(tag: String, msg: String) {
        val line = "[${dateFmt.format(Date())}] [$tag] $msg"
        android.util.Log.d("Aestus", line)
        if (buffer.size >= maxLines) buffer.removeFirst()
        buffer.addLast(line)
    }

    @Synchronized
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val line = "[${dateFmt.format(Date())}] [$tag] E: $msg"
        android.util.Log.e("Aestus", line, tr)
        if (buffer.size >= maxLines) buffer.removeFirst()
        buffer.addLast(line)
    }

    @Synchronized
    fun getRecent(n: Int = 100): List<String> {
        return buffer.toList().takeLast(n)
    }
}
