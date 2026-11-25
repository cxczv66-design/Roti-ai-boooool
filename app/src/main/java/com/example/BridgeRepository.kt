package com.example

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class LogLevel {
    INFO,
    WS,
    CMD,
    OK,
    ERR
}

data class TelemetryLogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

object BridgeRepository {

    private const val MAX_LOGS = 200
    private val logCounter = AtomicLong(0L)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeUrl = MutableStateFlow("")
    val activeUrl: StateFlow<String> = _activeUrl.asStateFlow()

    private val _accessibilityActive = MutableStateFlow(false)
    val accessibilityActive: StateFlow<Boolean> = _accessibilityActive.asStateFlow()

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    private val _lastAiMessage = MutableStateFlow<String?>(null)
    val lastAiMessage: StateFlow<String?> = _lastAiMessage.asStateFlow()

    private val _logs = MutableStateFlow<List<TelemetryLogEntry>>(emptyList())
    val logs: StateFlow<List<TelemetryLogEntry>> = _logs.asStateFlow()

    private val _outgoingMessages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val outgoingMessages: SharedFlow<String> = _outgoingMessages.asSharedFlow()

    fun setConnectionState(state: ConnectionState, url: String? = null) {
        _connectionState.value = state
        if (url != null) {
            _activeUrl.value = url
        }
    }

    fun setAccessibilityActive(active: Boolean) {
        _accessibilityActive.value = active
    }

    fun setForegroundPackage(packageName: String?) {
        _foregroundPackage.value = packageName
    }

    fun setLastAiMessage(message: String?) {
        _lastAiMessage.value = message
    }

    fun emitOutgoingMessage(jsonPayload: String) {
        _outgoingMessages.tryEmit(jsonPayload)
    }

    fun addLog(level: LogLevel, message: String) {
        val formattedTime = synchronized(timeFormatter) {
            timeFormatter.format(Date())
        }
        val entry = TelemetryLogEntry(
            id = logCounter.incrementAndGet(),
            timestamp = formattedTime,
            level = level,
            message = message
        )
        _logs.update { current ->
            (listOf(entry) + current).take(MAX_LOGS)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
