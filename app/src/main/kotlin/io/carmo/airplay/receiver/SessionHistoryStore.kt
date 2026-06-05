package io.carmo.airplay.receiver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionHistoryEntry(
    val id: Long,
    val senderIdentifier: String,
    val senderDisplayName: String?,
    val sessionType: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long,
    val disconnectReason: String,
    val qualityProfile: String,
    val videoFramesRendered: Int,
    val decoderRestarts: Int,
    val audioUnderruns: Int,
    val peakBitrateKbps: Int?
)

class SessionHistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                senderIdentifier TEXT NOT NULL,
                senderDisplayName TEXT,
                sessionType TEXT NOT NULL,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER,
                durationMs INTEGER NOT NULL DEFAULT 0,
                disconnectReason TEXT NOT NULL DEFAULT '',
                qualityProfile TEXT NOT NULL,
                videoFramesRendered INTEGER NOT NULL DEFAULT 0,
                decoderRestarts INTEGER NOT NULL DEFAULT 0,
                audioUnderruns INTEGER NOT NULL DEFAULT 0,
                peakBitrateKbps INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun startSession(senderId: String?, senderName: String?, sessionType: String): Long? {
        if (!ReceiverPreferences.sessionHistoryEnabled(appContext)) return null
        val values = ContentValues().apply {
            put("senderIdentifier", hashIdentifier(senderId))
            if (!ReceiverPreferences.hideSenderNamesInHistory(appContext)) {
                put("senderDisplayName", senderName?.trim()?.takeIf { it.isNotBlank() })
            }
            put("sessionType", sessionType)
            put("startedAt", System.currentTimeMillis())
            put("durationMs", 0L)
            put("disconnectReason", "")
            put("qualityProfile", ReceiverPreferences.qualityProfile(appContext))
        }
        return writableDatabase.insert(TABLE_NAME, null, values)
    }

    fun finishSession(id: Long, stats: ReceiverSessionStats, disconnectReason: String?) {
        if (id <= 0L) return
        val endedAt = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("endedAt", endedAt)
            put("durationMs", stats.durationMs)
            put("disconnectReason", disconnectReason ?: stats.lastDisconnectReason ?: "unknown")
            put("qualityProfile", ReceiverPreferences.qualityProfile(appContext))
            put("videoFramesRendered", stats.videoFramesRendered)
            put("decoderRestarts", stats.decoderRestarts)
            put("audioUnderruns", stats.audioUnderruns)
            putNull("peakBitrateKbps")
        }
        writableDatabase.update(TABLE_NAME, values, "id=?", arrayOf(id.toString()))
        trim()
    }

    fun recent(limit: Int = 20): List<SessionHistoryEntry> {
        val cursor = readableDatabase.query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            "startedAt DESC",
            limit.coerceAtLeast(1).toString()
        )
        cursor.use {
            val rows = mutableListOf<SessionHistoryEntry>()
            while (it.moveToNext()) {
                rows.add(
                    SessionHistoryEntry(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        senderIdentifier = it.getString(it.getColumnIndexOrThrow("senderIdentifier")),
                        senderDisplayName = it.getStringOrNull("senderDisplayName"),
                        sessionType = it.getString(it.getColumnIndexOrThrow("sessionType")),
                        startedAt = it.getLong(it.getColumnIndexOrThrow("startedAt")),
                        endedAt = it.getLongOrNull("endedAt"),
                        durationMs = it.getLong(it.getColumnIndexOrThrow("durationMs")),
                        disconnectReason = it.getString(it.getColumnIndexOrThrow("disconnectReason")),
                        qualityProfile = it.getString(it.getColumnIndexOrThrow("qualityProfile")),
                        videoFramesRendered = it.getInt(it.getColumnIndexOrThrow("videoFramesRendered")),
                        decoderRestarts = it.getInt(it.getColumnIndexOrThrow("decoderRestarts")),
                        audioUnderruns = it.getInt(it.getColumnIndexOrThrow("audioUnderruns")),
                        peakBitrateKbps = it.getIntOrNull("peakBitrateKbps")
                    )
                )
            }
            return rows
        }
    }

    fun clear() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }

    fun diagnosticsSummary(limit: Int = 10): String {
        val rows = recent(limit)
        if (rows.isEmpty()) return "  none"
        val formatter = SimpleDateFormat("MMM d HH:mm", Locale.US)
        return rows.joinToString("\n") { row ->
            val name = row.senderDisplayName ?: "Hidden"
            val problems = mutableListOf<String>()
            if (row.decoderRestarts > 0) problems.add("${row.decoderRestarts} decoder restarts")
            if (row.audioUnderruns > 0) problems.add("${row.audioUnderruns} underruns")
            val problemText = if (problems.isEmpty()) "ok" else problems.joinToString(", ")
            "  ${formatter.format(Date(row.startedAt))} $name ${row.sessionType} " +
                "${formatDuration(row.durationMs)} ${row.qualityProfile} $problemText"
        }
    }

    private fun trim() {
        writableDatabase.execSQL(
            """
            DELETE FROM $TABLE_NAME
            WHERE id NOT IN (
                SELECT id FROM $TABLE_NAME ORDER BY startedAt DESC LIMIT $MAX_ROWS
            )
            """.trimIndent()
        )
    }

    private fun hashIdentifier(senderId: String?): String {
        val clean = senderId?.trim()?.takeIf { it.isNotBlank() } ?: "unknown-sender"
        val digest = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun android.database.Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.getIntOrNull(columnName: String): Int? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getInt(index)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    companion object {
        private const val DATABASE_NAME = "session_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "session_history"
        private const val MAX_ROWS = 100
    }
}
