package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class WebSocketService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.example.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.action.DISCONNECT"
        const val EXTRA_WS_URL = "extra_ws_url"

        private const val CHANNEL_ID = "entity_body_ws_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        private val KNOWN_APP_PACKAGES = mapOf(
            "chatgpt" to "com.openai.chatgpt",
            "openai" to "com.openai.chatgpt",
            "claude" to "com.anthropic.claude",
            "anthropic" to "com.anthropic.claude",
            "gemini" to "com.google.android.apps.bard",
            "bard" to "com.google.android.apps.bard",
            "perplexity" to "com.perplexity.app.android",
            "copilot" to "com.microsoft.copilot"
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var outgoingCollectorJob: Job? = null
    private var reconnectJob: Job? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var currentUrl: String = ""

    @Volatile
    private var shouldReconnect: Boolean = false

    @Volatile
    private var reconnectAttempt: Int = 0

    override fun onCreate() {
        super.onCreate()
        BridgeRepository.loadPreferences(this)
        ShizukuController.init(this)
        createNotificationChannel()
        startForegroundWithStatus("Initializing bridge…")

        outgoingCollectorJob = serviceScope.launch {
            BridgeRepository.outgoingMessages.collect { payload ->
                val sent = activeWebSocket?.send(payload) == true
                if (!sent) {
                    BridgeRepository.addLog(LogLevel.ERR, "Dropped outgoing payload (socket offline)")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectAndStop("Disconnected by user")
            }

            ACTION_CONNECT -> {
                val url = intent.getStringExtra(EXTRA_WS_URL)?.trim().orEmpty()
                if (url.isEmpty()) {
                    shouldReconnect = false
                    BridgeRepository.setConnectionState(ConnectionState.ERROR)
                    BridgeRepository.addLog(LogLevel.ERR, "Cannot connect: WebSocket URL is empty")
                    stopSelf()
                } else {
                    shouldReconnect = true
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    connectWebSocket(url)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shouldReconnect = false
        reconnectJob?.cancel()
        outgoingCollectorJob?.cancel()
        activeWebSocket?.close(1000, "Service destroyed")
        activeWebSocket = null
        okHttpClient.dispatcher.executorService.shutdown()
        serviceScope.cancel()
        BridgeRepository.setConnectionState(ConnectionState.DISCONNECTED)
        super.onDestroy()
    }

    private fun connectWebSocket(url: String) {
        if (!url.startsWith("ws://", ignoreCase = true) && !url.startsWith("wss://", ignoreCase = true)) {
            shouldReconnect = false
            BridgeRepository.setConnectionState(ConnectionState.ERROR, url)
            BridgeRepository.addLog(LogLevel.ERR, "Invalid URL '$url' (must start with ws:// or wss://)")
            updateNotification("Invalid URL")
            return
        }

        val previousSocket = activeWebSocket
        activeWebSocket = null
        previousSocket?.close(1000, "Reconnecting")

        currentUrl = url
        BridgeRepository.setConnectionState(ConnectionState.CONNECTING, url)
        BridgeRepository.addLog(LogLevel.WS, "Connecting to $url…")
        updateNotification("Connecting to $url")

        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            shouldReconnect = false
            BridgeRepository.setConnectionState(ConnectionState.ERROR, url)
            BridgeRepository.addLog(LogLevel.ERR, "Malformed WebSocket URL: ${e.message}")
            updateNotification("Malformed URL")
            return
        }

        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                reconnectJob?.cancel()
                BridgeRepository.setConnectionState(ConnectionState.CONNECTED, currentUrl)
                BridgeRepository.addLog(LogLevel.OK, "WebSocket connected to $currentUrl")
                updateNotification("Connected • $currentUrl")

                val hello = BridgeProtocol.statusJson(
                    event = "connected",
                    accessibilityEnabled = EntityAccessibilityService.instance != null,
                    shizukuReady = ShizukuController.hasPermission(),
                    activePackage = BridgeRepository.foregroundPackage.value,
                    targetAiPackage = BridgeRepository.getTargetAiPackage(this@WebSocketService)
                )
                webSocket.send(hello)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                BridgeRepository.addLog(LogLevel.CMD, "RX: $text")
                handleIncomingMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                BridgeRepository.addLog(LogLevel.WS, "Closing ($code): ${reason.ifBlank { "remote closed" }}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeWebSocket === webSocket) {
                    activeWebSocket = null
                }
                BridgeRepository.setConnectionState(ConnectionState.DISCONNECTED, currentUrl)
                BridgeRepository.addLog(LogLevel.INFO, "WebSocket closed ($code)")
                if (shouldReconnect) {
                    scheduleReconnect("Connection closed ($code)")
                } else {
                    updateNotification("Disconnected")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeWebSocket === webSocket) {
                    activeWebSocket = null
                }
                val errorDetail = t.message ?: response?.message ?: "Connection failed"
                BridgeRepository.setConnectionState(ConnectionState.ERROR, currentUrl)
                BridgeRepository.addLog(LogLevel.ERR, "WebSocket error: $errorDetail")
                if (shouldReconnect) {
                    scheduleReconnect(errorDetail)
                } else {
                    updateNotification("Error: $errorDetail")
                }
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (!shouldReconnect || currentUrl.isBlank()) return
        if (reconnectJob?.isActive == true) return

        val shift = min(reconnectAttempt, 4)
        val delayMs = min(MAX_RECONNECT_DELAY_MS, 2_000L * (1L shl shift))
        reconnectAttempt += 1

        BridgeRepository.addLog(
            LogLevel.WS,
            "Reconnecting in ${delayMs / 1000}s (attempt #$reconnectAttempt) — $reason"
        )
        updateNotification("Reconnecting in ${delayMs / 1000}s…")

        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (shouldReconnect && currentUrl.isNotBlank()) {
                connectWebSocket(currentUrl)
            }
        }
    }

    private fun handleIncomingMessage(webSocket: WebSocket, rawText: String) {
        serviceScope.launch {
            val json = try {
                JSONObject(rawText)
            } catch (e: Exception) {
                val errMsg = "Invalid JSON payload: ${e.message}"
                BridgeRepository.addLog(LogLevel.ERR, errMsg)
                webSocket.send(BridgeProtocol.actionResultJson("parse", false, errMsg))
                return@launch
            }

            val rawAction = json.optString("action", "").trim().lowercase()
            if (rawAction == "type_and_send" || rawAction == "send_prompt" || rawAction == "ask") {
                val text = json.optString("text", "")
                val sendButtonText = json.optString("send_text", "Send").ifBlank { "Send" }
                if (text.isBlank()) {
                    val errMsg = "Missing 'text' field for '$rawAction'"
                    BridgeRepository.addLog(LogLevel.ERR, errMsg)
                    webSocket.send(BridgeProtocol.actionResultJson(rawAction, false, errMsg))
                    return@launch
                }

                val service = EntityAccessibilityService.instance
                if (service == null) {
                    sendMissingServiceError(webSocket, rawAction)
                    return@launch
                }
                val result = withContext(Dispatchers.Main) {
                    service.typeAndSend(text = text, sendButtonText = sendButtonText)
                }
                sendActionResult(webSocket, rawAction, result)
                return@launch
            }

            val parsed = BridgeProtocol.parseCommand(rawText)
            if (parsed.isFailure) {
                val errMsg = parsed.exceptionOrNull()?.message ?: "Invalid JSON command"
                BridgeRepository.addLog(LogLevel.ERR, errMsg)
                webSocket.send(BridgeProtocol.actionResultJson("parse", false, errMsg))
                return@launch
            }

            when (val cmd = parsed.getOrThrow()) {
                is BridgeCommand.Ping -> {
                    val pong = BridgeProtocol.statusJson(
                        event = "pong",
                        accessibilityEnabled = EntityAccessibilityService.instance != null,
                        shizukuReady = ShizukuController.hasPermission(),
                        activePackage = BridgeRepository.foregroundPackage.value,
                        targetAiPackage = BridgeRepository.getTargetAiPackage(this@WebSocketService)
                    )
                    webSocket.send(pong)
                    BridgeRepository.addLog(LogLevel.OK, "Sent pong status")
                }

                is BridgeCommand.LaunchApp -> {
                    val resolvedPkg = resolveTargetPackage(cmd.packageName, cmd.appName)
                    val result = if (resolvedPkg == null) {
                        AccessibilityActionResult.Error(
                            "Could not resolve package for '${cmd.appName ?: cmd.packageName.orEmpty()}'"
                        )
                    } else {
                        ShizukuController.launchApp(this@WebSocketService, resolvedPkg)
                    }
                    sendActionResult(webSocket, "launch_app", result)
                }

                is BridgeCommand.SetTargetApp -> {
                    BridgeRepository.setTargetAiPackage(this@WebSocketService, cmd.packageName)
                    sendActionResult(
                        webSocket,
                        "set_target_app",
                        AccessibilityActionResult.Success(
                            detail = "Target AI app set to ${cmd.packageName}",
                            packageName = cmd.packageName
                        )
                    )
                }

                is BridgeCommand.KeyEventCommand -> {
                    val result = ShizukuController.sendKeyEvent(cmd.key)
                    sendActionResult(webSocket, "key_event", result)
                }

                is BridgeCommand.TypeText -> {
                    val service = EntityAccessibilityService.instance
                    if (service == null) {
                        sendMissingServiceError(webSocket, "type")
                        return@launch
                    }
                    val result = withContext(Dispatchers.Main) {
                        service.injectText(cmd.text, cmd.targetId)
                    }
                    sendActionResult(webSocket, "type", result)
                }

                is BridgeCommand.ClickElement -> {
                    val service = EntityAccessibilityService.instance
                    if (service == null) {
                        sendMissingServiceError(webSocket, "click")
                        return@launch
                    }
                    val result = withContext(Dispatchers.Main) {
                        service.clickElement(cmd.text, cmd.viewId)
                    }
                    sendActionResult(webSocket, "click", result)
                }

                is BridgeCommand.TypeAndSend -> {
                    val service = EntityAccessibilityService.instance
                    if (service == null) {
                        sendMissingServiceError(webSocket, "type_and_send")
                        return@launch
                    }
                    val result = withContext(Dispatchers.Main) {
                        service.typeAndSend(cmd.text, cmd.sendButtonText)
                    }
                    sendActionResult(webSocket, "type_and_send", result)
                }

                is BridgeCommand.ReadLastMessage -> {
                    val service = EntityAccessibilityService.instance
                    if (service == null) {
                        sendMissingServiceError(webSocket, "read_last_message")
                        return@launch
                    }
                    val result = withContext(Dispatchers.Main) {
                        service.readLastAiMessage()
                    }
                    when (result) {
                        is AccessibilityActionResult.Success -> {
                            val responsePayload = BridgeProtocol.aiResponseJson(
                                packageName = result.packageName,
                                text = result.detail
                            )
                            webSocket.send(responsePayload)
                            BridgeRepository.addLog(
                                LogLevel.OK,
                                "Sent AI response (${result.detail.take(80)}${if (result.detail.length > 80) "…" else ""})"
                            )
                        }

                        is AccessibilityActionResult.Error -> {
                            sendActionResult(webSocket, "read_last_message", result)
                        }
                    }
                }

                is BridgeCommand.NavigateBack -> {
                    val result = ShizukuController.sendKeyEvent("back")
                    sendActionResult(webSocket, "back", result)
                }

                is BridgeCommand.NavigateHome -> {
                    val result = ShizukuController.sendKeyEvent("home")
                    sendActionResult(webSocket, "home", result)
                }

                is BridgeCommand.Scroll -> {
                    val service = EntityAccessibilityService.instance
                    if (service == null) {
                        sendMissingServiceError(webSocket, "scroll")
                        return@launch
                    }
                    val result = withContext(Dispatchers.Main) { service.scrollChat(cmd.forward) }
                    sendActionResult(webSocket, "scroll", result)
                }

                is BridgeCommand.Unknown -> {
                    val msg = "Unknown action '${cmd.action}'"
                    BridgeRepository.addLog(LogLevel.ERR, msg)
                    webSocket.send(BridgeProtocol.actionResultJson(cmd.action, false, msg))
                }
            }
        }
    }

    private fun resolveTargetPackage(packageName: String?, appName: String?): String? {
        if (!packageName.isNullOrBlank()) return packageName.trim()
        val query = appName?.trim()?.lowercase() ?: return null
        KNOWN_APP_PACKAGES[query]?.let { return it }

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
        return apps.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(packageManager).toString().lowercase()
            label == query || label.contains(query)
        }?.activityInfo?.packageName
    }

    private fun sendMissingServiceError(webSocket: WebSocket, action: String) {
        val msg = "Accessibility Service is not enabled on device"
        BridgeRepository.addLog(LogLevel.ERR, "[$action] $msg")
        webSocket.send(BridgeProtocol.actionResultJson(action, false, msg))
    }

    private fun sendActionResult(
        webSocket: WebSocket,
        action: String,
        result: AccessibilityActionResult
    ) {
        when (result) {
            is AccessibilityActionResult.Success -> {
                BridgeRepository.addLog(LogLevel.OK, "[$action] ${result.detail}")
                webSocket.send(BridgeProtocol.actionResultJson(action, true, result.detail))
            }

            is AccessibilityActionResult.Error -> {
                BridgeRepository.addLog(LogLevel.ERR, "[$action] ${result.reason}")
                webSocket.send(BridgeProtocol.actionResultJson(action, false, result.reason))
            }
        }
    }

    private fun disconnectAndStop(reason: String) {
        shouldReconnect = false
        reconnectJob?.cancel()
        activeWebSocket?.close(1000, reason)
        activeWebSocket = null
        BridgeRepository.setConnectionState(ConnectionState.DISCONNECTED)
        BridgeRepository.addLog(LogLevel.INFO, reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithStatus(statusText: String) {
        val notification = buildNotification(statusText)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, WebSocketService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.btn_disconnect),
                disconnectPendingIntent
            )
            .build()
    }
}
