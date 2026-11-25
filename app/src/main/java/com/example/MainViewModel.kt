package com.example

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MainUiState(
    val wsUrlInput: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val accessibilityEnabled: Boolean = false,
    val foregroundPackage: String? = null,
    val lastAiMessage: String? = null,
    val logs: List<TelemetryLogEntry> = emptyList()
)

class MainViewModel : ViewModel() {

    private val _wsUrlInput = MutableStateFlow("")
    private val _systemAccessibilityEnabled = MutableStateFlow(false)

    val uiState: StateFlow<MainUiState> = combine(
        _wsUrlInput,
        BridgeRepository.connectionState,
        BridgeRepository.accessibilityActive,
        _systemAccessibilityEnabled,
        BridgeRepository.foregroundPackage,
        BridgeRepository.lastAiMessage,
        BridgeRepository.logs
    ) { values ->
        val urlInput = values[0] as String
        val connState = values[1] as ConnectionState
        val serviceActive = values[2] as Boolean
        val sysEnabled = values[3] as Boolean
        val fgPkg = values[4] as String?
        val lastMsg = values[5] as String?
        @Suppress("UNCHECKED_CAST")
        val logList = values[6] as List<TelemetryLogEntry>

        MainUiState(
            wsUrlInput = urlInput,
            connectionState = connState,
            accessibilityEnabled = serviceActive || sysEnabled,
            foregroundPackage = fgPkg,
            lastAiMessage = lastMsg,
            logs = logList
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    fun onWsUrlChanged(newValue: String) {
        _wsUrlInput.value = newValue
    }

    fun refreshAccessibilityStatus(context: Context) {
        val enabledInSystem = isAccessibilityServiceEnabled(context)
        _systemAccessibilityEnabled.value = enabledInSystem
    }

    fun readCurrentScreenNow() {
        val service = EntityAccessibilityService.instance
        if (service == null) {
            BridgeRepository.addLog(
                LogLevel.ERR,
                "Cannot read screen: Accessibility Service is disabled"
            )
            return
        }
        when (val result = service.readLastAiMessage()) {
            is AccessibilityActionResult.Success -> {
                BridgeRepository.addLog(
                    LogLevel.OK,
                    "Read screen (${result.packageName}): ${result.detail.take(120)}"
                )
                if (BridgeRepository.connectionState.value == ConnectionState.CONNECTED) {
                    BridgeRepository.emitOutgoingMessage(
                        BridgeProtocol.aiResponseJson(result.packageName, result.detail)
                    )
                }
            }

            is AccessibilityActionResult.Error -> {
                BridgeRepository.addLog(LogLevel.ERR, result.reason)
            }
        }
    }

    fun clearLogs() {
        BridgeRepository.clearLogs()
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val expectedId = "${context.packageName}/${EntityAccessibilityService::class.java.name}"
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (enabledServices.any { it.id.equals(expectedId, ignoreCase = true) }) {
            return true
        }
        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return settingValue.split(':').any {
            it.equals(expectedId, ignoreCase = true) ||
                it.equals("${context.packageName}/.EntityAccessibilityService", ignoreCase = true)
        }
    }
}
