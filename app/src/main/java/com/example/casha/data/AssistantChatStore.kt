package com.example.casha.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AssistantChatTurn(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    val answer: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("question", question)
        .put("answer", answer)
        .put("timestamp", timestamp)
        .put("isError", isError)

    companion object {
        fun fromJson(json: JSONObject): AssistantChatTurn {
            return AssistantChatTurn(
                id = json.optString("id", UUID.randomUUID().toString()),
                question = json.getString("question"),
                answer = json.getString("answer"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                isError = json.optBoolean("isError", false)
            )
        }

        fun decodeList(raw: String): List<AssistantChatTurn> {
            if (raw.isBlank() || raw == "[]") return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun encodeList(turns: List<AssistantChatTurn>): String {
            val array = JSONArray()
            turns.forEach { array.put(it.toJson()) }
            return array.toString()
        }
    }
}

data class AssistantChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val turns: List<AssistantChatTurn> = emptyList()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("timestamp", timestamp)
        val array = JSONArray()
        turns.forEach { array.put(it.toJson()) }
        obj.put("turns", array)
        return obj
    }

    companion object {
        fun fromJson(json: JSONObject): AssistantChatSession {
            val array = json.optJSONArray("turns")
            val turnsList = if (array != null) {
                (0 until array.length()).map { AssistantChatTurn.fromJson(array.getJSONObject(it)) }
            } else emptyList()
            return AssistantChatSession(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", "Saved conversation"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                turns = turnsList
            )
        }

        fun decodeList(raw: String): List<AssistantChatSession> {
            if (raw.isBlank() || raw == "[]") return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun encodeList(sessions: List<AssistantChatSession>): String {
            val array = JSONArray()
            sessions.forEach { array.put(it.toJson()) }
            return array.toString()
        }
    }
}

class AssistantChatStore(context: Context, val scope: String) {
    private val prefs: SharedPreferences = context.getSharedPreferences("assistant_chat_$scope", Context.MODE_PRIVATE)

    fun load(): List<AssistantChatTurn> {
        val raw = prefs.getString("chat_history", "[]") ?: "[]"
        return AssistantChatTurn.decodeList(raw)
    }

    fun save(turns: List<AssistantChatTurn>): Boolean {
        // Keep the last 100 turns locally on device for speed and minimal memory
        val trimmed = turns.takeLast(100)
        return prefs.edit().putString("chat_history", AssistantChatTurn.encodeList(trimmed)).commit()
    }

    fun clear(): Boolean {
        return prefs.edit().remove("chat_history").commit()
    }

    fun loadArchivedSessions(): List<AssistantChatSession> {
        val raw = prefs.getString("archived_sessions", "[]") ?: "[]"
        return AssistantChatSession.decodeList(raw)
    }

    fun archiveCurrentSession(title: String? = null): Boolean {
        val currentTurns = load()
        if (currentTurns.isEmpty()) return true
        val autoTitle = title ?: currentTurns.firstOrNull { it.question.isNotBlank() }?.question?.take(40) ?: "Saved chat"
        val newSession = AssistantChatSession(
            title = autoTitle,
            timestamp = currentTurns.lastOrNull()?.timestamp ?: System.currentTimeMillis(),
            turns = currentTurns
        )
        val existing = loadArchivedSessions()
        val updated = (listOf(newSession) + existing).take(30)
        return prefs.edit()
            .putString("archived_sessions", AssistantChatSession.encodeList(updated))
            .remove("chat_history")
            .commit()
    }

    fun deleteArchivedSession(id: String): Boolean {
        val existing = loadArchivedSessions().filter { it.id != id }
        return prefs.edit().putString("archived_sessions", AssistantChatSession.encodeList(existing)).commit()
    }

    fun restoreSession(session: AssistantChatSession): List<AssistantChatTurn> {
        val currentTurns = load()
        val existing = loadArchivedSessions().filter { it.id != session.id }
        val updatedSessions = if (currentTurns.isNotEmpty()) {
            val autoTitle = currentTurns.firstOrNull { it.question.isNotBlank() }?.question?.take(40) ?: "Saved chat"
            listOf(AssistantChatSession(title = autoTitle, timestamp = System.currentTimeMillis(), turns = currentTurns)) + existing
        } else {
            existing
        }
        prefs.edit()
            .putString("archived_sessions", AssistantChatSession.encodeList(updatedSessions))
            .putString("chat_history", AssistantChatTurn.encodeList(session.turns))
            .commit()
        return session.turns
    }
}
