package dev.yukivpn.logging

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val priority: Int) {
    ERROR(0),
    INFO(1),
    DEBUG(2),
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
)

object AppLogger {
    private val sequence = AtomicLong()
    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()

    @Volatile
    private var logFile: File? = null

    @Synchronized
    fun initialize(context: Context) {
        if (logFile != null) return
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        logFile = file
        val loaded = if (file.exists()) {
            file.useLines { lines -> lines.mapNotNull(::decode).toList().takeLast(MAX_ENTRIES) }
        } else {
            emptyList()
        }
        loaded.maxOfOrNull { it.id }?.let(sequence::set)
        mutableEntries.value = loaded
    }

    fun error(message: String, throwable: Throwable? = null) {
        val detail = throwable?.let { ": ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        append(LogLevel.ERROR, message + detail)
    }

    fun info(message: String) = append(LogLevel.INFO, message)
    fun debug(message: String) = append(LogLevel.DEBUG, message)

    @Synchronized
    fun clear() {
        mutableEntries.value = emptyList()
        runCatching { logFile?.delete() }
    }

    @Synchronized
    private fun append(level: LogLevel, message: String) {
        val entry = LogEntry(
            id = sequence.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message.replace('\n', ' ').replace('\r', ' '),
        )
        val updated = (mutableEntries.value + entry).takeLast(MAX_ENTRIES)
        mutableEntries.value = updated
        val file = logFile ?: return
        runCatching {
            file.appendText(encode(entry) + "\n", Charsets.UTF_8)
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText(updated.joinToString("\n", postfix = "\n", transform = ::encode), Charsets.UTF_8)
            }
        }
    }

    private fun encode(entry: LogEntry) = JSONObject()
        .put("id", entry.id)
        .put("time", entry.timestamp)
        .put("level", entry.level.name)
        .put("message", entry.message)
        .toString()

    private fun decode(line: String): LogEntry? = runCatching {
        val json = JSONObject(line)
        LogEntry(
            id = json.getLong("id"),
            timestamp = json.getLong("time"),
            level = LogLevel.valueOf(json.getString("level")),
            message = json.getString("message"),
        )
    }.getOrNull()

    private const val FILE_NAME = "yukivpn.log.jsonl"
    private const val MAX_ENTRIES = 1_000
    private const val MAX_FILE_BYTES = 1_048_576L
}
