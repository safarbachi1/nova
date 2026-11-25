package com.example.core.logging

import com.example.core.security.SecretRedactor
import com.example.domain.model.EngineLogEntry
import com.example.domain.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticLogger(private val maxEntries: Int = 500) {
    private val _logs = MutableStateFlow<List<EngineLogEntry>>(emptyList())
    val logs: StateFlow<List<EngineLogEntry>> = _logs.asStateFlow()

    fun info(tag: String, message: String) = append(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = append(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = append(LogLevel.ERROR, tag, message)
    fun engine(tag: String, message: String) = append(LogLevel.ENGINE, tag, message)

    fun append(level: LogLevel, tag: String, message: String) {
        val safeMessage = SecretRedactor.redact(message)
        val entry = EngineLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = safeMessage
        )
        _logs.update { current ->
            val updated = current + entry
            if (updated.size > maxEntries) updated.takeLast(maxEntries) else updated
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun exportRedactedText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return _logs.value.joinToString("\n") { entry ->
            "[${fmt.format(Date(entry.timestampMs))}] [${entry.level.name}] [${entry.tag}] ${SecretRedactor.redact(entry.message)}"
        }
    }
}
