package me.rerere.aestus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.*

data class Conversation(
    val id: Long = 0,
    val title: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

data class Message(
    val id: Long = 0,
    val convId: Long = 1,
    val ts: String = "",
    val direction: String = "",
    val kind: String = "",
    val text: String = "",
    val meta: String = "{}",
)

class Database(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS conversations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL DEFAULT '新会话',
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conv_id INTEGER NOT NULL DEFAULT 1,
            ts TEXT NOT NULL,
            direction TEXT NOT NULL,
            kind TEXT NOT NULL,
            text TEXT NOT NULL,
            meta TEXT NOT NULL DEFAULT '{}'
        )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conv_id, id)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS memories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            summary TEXT NOT NULL,
            category TEXT NOT NULL DEFAULT 'general',
            tags TEXT NOT NULL DEFAULT '[]'
        )""")
        val ts = nowIso()
        db.execSQL("INSERT INTO conversations (title, created_at, updated_at) VALUES ('默认对话', '$ts', '$ts')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '新会话',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )""")
            db.execSQL("ALTER TABLE messages ADD COLUMN conv_id INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conv_id, id)")
            val ts = nowIso()
            db.execSQL("INSERT OR IGNORE INTO conversations (id, title, created_at, updated_at) VALUES (1, '默认对话', '$ts', '$ts')")
        }
    }

    // ── Conversations ───────────────────────────────────────

    fun listConversations(): List<Conversation> {
        val cursor = readableDatabase.rawQuery("SELECT * FROM conversations ORDER BY updated_at DESC", null)
        val list = mutableListOf<Conversation>()
        while (cursor.moveToNext()) {
            list.add(Conversation(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                createdAt = cursor.getString(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getString(cursor.getColumnIndexOrThrow("updated_at")),
            ))
        }
        cursor.close()
        return list
    }

    fun createConversation(title: String = "新对话"): Long {
        val ts = nowIso()
        val values = ContentValues().apply {
            put("title", title)
            put("created_at", ts)
            put("updated_at", ts)
        }
        return writableDatabase.insert("conversations", null, values)
    }

    fun renameConversation(id: Long, title: String) {
        val values = ContentValues().apply {
            put("title", title)
            put("updated_at", nowIso())
        }
        writableDatabase.update("conversations", values, "id=?", arrayOf(id.toString()))
    }

    fun deleteConversation(id: Long) {
        writableDatabase.delete("messages", "conv_id=?", arrayOf(id.toString()))
        writableDatabase.delete("conversations", "id=?", arrayOf(id.toString()))
    }

    // ── Messages ────────────────────────────────────────────

    fun insertMessage(direction: String, kind: String, text: String, meta: String = "{}", convId: Long = 1): Long {
        val ts = nowIso()
        val values = ContentValues().apply {
            put("conv_id", convId)
            put("ts", ts)
            put("direction", direction)
            put("kind", kind)
            put("text", text)
            put("meta", meta)
        }
        val id = writableDatabase.insert("messages", null, values)
        writableDatabase.execSQL("UPDATE conversations SET updated_at='$ts' WHERE id=$convId")
        return id
    }

    fun getMessages(convId: Long = 1, since: Long = 0, limit: Int = 200): List<Message> {
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE conv_id=? AND id > ? ORDER BY id ASC LIMIT ?",
            arrayOf(convId.toString(), since.toString(), limit.toString())
        )
        val list = mutableListOf<Message>()
        while (cursor.moveToNext()) {
            list.add(Message(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                convId = cursor.getLong(cursor.getColumnIndexOrThrow("conv_id")),
                ts = cursor.getString(cursor.getColumnIndexOrThrow("ts")),
                direction = cursor.getString(cursor.getColumnIndexOrThrow("direction")),
                kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
                meta = cursor.getString(cursor.getColumnIndexOrThrow("meta")),
            ))
        }
        cursor.close()
        return list
    }

    fun clearMessages(convId: Long = 1) {
        writableDatabase.delete("messages", "conv_id=?", arrayOf(convId.toString()))
    }

    fun getLatestMessageId(convId: Long = 1): Long {
        val cursor = readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(id), 0) FROM messages WHERE conv_id=?",
            arrayOf(convId.toString())
        )
        cursor.moveToFirst()
        val id = cursor.getLong(0)
        cursor.close()
        return id
    }

    // ── Memories (unchanged) ────────────────────────────────

    fun insertMemory(summary: String, category: String = "general", tags: String = "[]") {
        val ts = nowIso()
        writableDatabase.insert("memories", null, ContentValues().apply {
            put("ts", ts); put("summary", summary); put("category", category); put("tags", tags)
        })
    }

    fun getMemories(limit: Int = 20): List<String> {
        val cursor = readableDatabase.rawQuery("SELECT summary FROM memories ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
        val list = mutableListOf<String>()
        while (cursor.moveToNext()) list.add(cursor.getString(0))
        cursor.close()
        return list
    }

    private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    companion object {
        const val DB_NAME = "aestus.db"
        const val DB_VERSION = 2
    }
}
