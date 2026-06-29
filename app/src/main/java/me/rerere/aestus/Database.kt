package me.rerere.aestus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.*

data class Message(
    val id: Long = 0,
    val ts: String = "",
    val direction: String = "",  // "in" (human->AI) | "out" (AI->human)
    val kind: String = "",       // "user" | "reply" | "thinking" | "voice" | "call"
    val text: String = "",
    val meta: String = "{}",
)

class Database(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS messages (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ts        TEXT NOT NULL,
                direction TEXT NOT NULL,
                kind      TEXT NOT NULL,
                text      TEXT NOT NULL,
                meta      TEXT NOT NULL DEFAULT '{}'
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS memories (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ts        TEXT NOT NULL,
                summary   TEXT NOT NULL,
                category  TEXT NOT NULL DEFAULT 'general',
                tags      TEXT NOT NULL DEFAULT '[]'
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS memories")
        onCreate(db)
    }

    fun insertMessage(direction: String, kind: String, text: String, meta: String = "{}"): Long {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val values = ContentValues().apply {
            put("ts", ts)
            put("direction", direction)
            put("kind", kind)
            put("text", text)
            put("meta", meta)
        }
        return writableDatabase.insert("messages", null, values)
    }

    fun getMessages(since: Long = 0, limit: Int = 200): List<Message> {
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE id > ? ORDER BY id ASC LIMIT ?",
            arrayOf(since.toString(), limit.toString())
        )
        val messages = mutableListOf<Message>()
        while (cursor.moveToNext()) {
            messages.add(
                Message(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    ts = cursor.getString(cursor.getColumnIndexOrThrow("ts")),
                    direction = cursor.getString(cursor.getColumnIndexOrThrow("direction")),
                    kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                    text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
                    meta = cursor.getString(cursor.getColumnIndexOrThrow("meta")),
                )
            )
        }
        cursor.close()
        return messages
    }

    fun getLatestMessageId(): Long {
        val cursor = readableDatabase.rawQuery("SELECT MAX(id) FROM messages", null)
        cursor.moveToFirst()
        val id = cursor.getLong(0)
        cursor.close()
        return id
    }

    fun insertMemory(summary: String, category: String = "general", tags: String = "[]") {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val values = ContentValues().apply {
            put("ts", ts)
            put("summary", summary)
            put("category", category)
            put("tags", tags)
        }
        writableDatabase.insert("memories", null, values)
    }

    fun getMemories(limit: Int = 20): List<String> {
        val cursor = readableDatabase.rawQuery(
            "SELECT summary FROM memories ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val summaries = mutableListOf<String>()
        while (cursor.moveToNext()) {
            summaries.add(cursor.getString(0))
        }
        cursor.close()
        return summaries
    }

    companion object {
        const val DB_NAME = "aestus.db"
        const val DB_VERSION = 1
    }
}
