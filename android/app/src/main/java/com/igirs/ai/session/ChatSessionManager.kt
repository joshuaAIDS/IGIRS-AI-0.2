package com.igirs.ai.session

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

data class StoredMessage(
    val role: String,
    val content: String,
    val timestamp: Long,
    val imageDataUri: String? = null
)

class ChatSessionManager(context: Context) {

    companion object {
        private const val TAG = "IGIRS.SessionMgr"
        private const val DB_NAME = "igirs_chat_history.db"
        private const val DB_VERSION = 1
    }

    private val dbHelper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE sessions (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    created_at INTEGER,
                    updated_at INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT,
                    role TEXT,
                    content TEXT,
                    timestamp INTEGER,
                    image_data_uri TEXT,
                    FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS messages")
            db.execSQL("DROP TABLE IF EXISTS sessions")
            onCreate(db)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    @Synchronized
    fun createNewSession(title: String = "New Chat"): ChatSession {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("created_at", now)
            put("updated_at", now)
        }
        db.insert("sessions", null, values)
        Log.d(TAG, "Created new session: $id")
        return ChatSession(id, title, now, now, 0)
    }

    @Synchronized
    fun getAllSessions(): List<ChatSession> {
        val db = dbHelper.readableDatabase
        val sessions = mutableListOf<ChatSession>()
        val query = """
            SELECT s.id, s.title, s.created_at, s.updated_at, COUNT(m.id) as messageCount
            FROM sessions s
            LEFT JOIN messages m ON s.id = m.session_id
            GROUP BY s.id
            ORDER BY s.updated_at DESC
        """.trimIndent()
        
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                sessions.add(
                    ChatSession(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        createdAt = cursor.getLong(2),
                        updatedAt = cursor.getLong(3),
                        messageCount = cursor.getInt(4)
                    )
                )
            }
        }
        return sessions
    }

    @Synchronized
    fun getSessionById(sessionId: String): ChatSession? {
        val db = dbHelper.readableDatabase
        val query = """
            SELECT s.id, s.title, s.created_at, s.updated_at, COUNT(m.id) as messageCount
            FROM sessions s
            LEFT JOIN messages m ON s.id = m.session_id
            WHERE s.id = ?
            GROUP BY s.id
        """.trimIndent()
        
        db.rawQuery(query, arrayOf(sessionId)).use { cursor ->
            if (cursor.moveToFirst()) {
                return ChatSession(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    createdAt = cursor.getLong(2),
                    updatedAt = cursor.getLong(3),
                    messageCount = cursor.getInt(4)
                )
            }
        }
        return null
    }

    @Synchronized
    fun saveMessage(sessionId: String, role: String, content: String, imageDataUri: String? = null) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("session_id", sessionId)
                put("role", role)
                put("content", content)
                put("timestamp", now)
                put("image_data_uri", imageDataUri)
            }
            db.insert("messages", null, values)
            
            val updateValues = ContentValues().apply {
                put("updated_at", now)
            }
            db.update("sessions", updateValues, "id = ?", arrayOf(sessionId))
            db.setTransactionSuccessful()
            Log.d(TAG, "Saved message for session ${"$"}sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving message", e)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getMessages(sessionId: String): List<StoredMessage> {
        val db = dbHelper.readableDatabase
        val messages = mutableListOf<StoredMessage>()
        db.query(
            "messages",
            arrayOf("role", "content", "timestamp", "image_data_uri"),
            "session_id = ?",
            arrayOf(sessionId),
            null, null, "timestamp ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(
                    StoredMessage(
                        role = cursor.getString(0),
                        content = cursor.getString(1),
                        timestamp = cursor.getLong(2),
                        imageDataUri = cursor.getString(3)
                    )
                )
            }
        }
        return messages
    }

    @Synchronized
    fun renameSession(sessionId: String, newTitle: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("title", newTitle)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("sessions", values, "id = ?", arrayOf(sessionId))
        Log.d(TAG, "Renamed session ${"$"}sessionId to ${"$"}newTitle")
    }

    @Synchronized
    fun deleteSession(sessionId: String) {
        val db = dbHelper.writableDatabase
        db.delete("sessions", "id = ?", arrayOf(sessionId))
        Log.d(TAG, "Deleted session ${"$"}sessionId")
    }

    @Synchronized
    fun deleteAllSessions() {
        val db = dbHelper.writableDatabase
        db.delete("sessions", null, null)
        Log.d(TAG, "Deleted all sessions")
    }

    @Synchronized
    fun searchSessions(query: String): List<ChatSession> {
        val db = dbHelper.readableDatabase
        val sessions = mutableListOf<ChatSession>()
        val sqlQuery = """
            SELECT s.id, s.title, s.created_at, s.updated_at, COUNT(m.id) as messageCount
            FROM sessions s
            LEFT JOIN messages m ON s.id = m.session_id
            WHERE s.title LIKE ? OR s.id IN (
                SELECT session_id FROM messages WHERE content LIKE ?
            )
            GROUP BY s.id
            ORDER BY s.updated_at DESC
        """.trimIndent()
        
        val likeQuery = "%${"$"}query%"
        db.rawQuery(sqlQuery, arrayOf(likeQuery, likeQuery)).use { cursor ->
            while (cursor.moveToNext()) {
                sessions.add(
                    ChatSession(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        createdAt = cursor.getLong(2),
                        updatedAt = cursor.getLong(3),
                        messageCount = cursor.getInt(4)
                    )
                )
            }
        }
        return sessions
    }

    @Synchronized
    fun autoTitleSession(sessionId: String, firstUserMessage: String) {
        val truncated = if (firstUserMessage.length > 40) {
            firstUserMessage.substring(0, 40) + "..."
        } else {
            firstUserMessage
        }
        renameSession(sessionId, truncated)
    }

    @Synchronized
    fun getSessionsGroupedByDate(): Map<String, List<ChatSession>> {
        val allSessions = getAllSessions()
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val sevenDaysAgo = today.minusDays(7)
        val thirtyDaysAgo = today.minusDays(30)

        val grouped = mutableMapOf<String, MutableList<ChatSession>>(
            "Today" to mutableListOf(),
            "Yesterday" to mutableListOf(),
            "Previous 7 Days" to mutableListOf(),
            "Previous 30 Days" to mutableListOf(),
            "Older" to mutableListOf()
        )

        for (session in allSessions) {
            val sessionDate = Instant.ofEpochMilli(session.updatedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                
            when {
                sessionDate == today -> grouped["Today"]?.add(session)
                sessionDate == yesterday -> grouped["Yesterday"]?.add(session)
                sessionDate.isAfter(sevenDaysAgo) -> grouped["Previous 7 Days"]?.add(session)
                sessionDate.isAfter(thirtyDaysAgo) -> grouped["Previous 30 Days"]?.add(session)
                else -> grouped["Older"]?.add(session)
            }
        }
        
        return grouped.filterValues { it.isNotEmpty() }
    }
}
