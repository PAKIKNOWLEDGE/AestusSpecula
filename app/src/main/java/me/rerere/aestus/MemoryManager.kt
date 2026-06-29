package me.rerere.aestus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryManager(private val db: Database) {

    suspend fun getMemoryContext(): String = withContext(Dispatchers.IO) {
        val memories = db.getMemories(10)
        if (memories.isEmpty()) return@withContext ""
        buildString {
            appendLine("【记忆摘要】")
            memories.forEachIndexed { i, summary ->
                appendLine("${i + 1}. $summary")
            }
        }
    }

    suspend fun addMemory(summary: String, category: String = "general") {
        db.insertMemory(summary, category)
    }
}
