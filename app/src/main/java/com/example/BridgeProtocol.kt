package com.example

import org.json.JSONObject

sealed class BridgeCommand {
    data class LaunchApp(val packageName: String?, val appName: String?) : BridgeCommand()
    data class SetTargetApp(val packageName: String) : BridgeCommand()
    data class TypeText(val text: String, val targetId: String? = null) : BridgeCommand()
    data class ClickElement(val text: String?, val viewId: String? = null) : BridgeCommand()
    data class TypeAndSend(val text: String, val sendButtonText: String = "Send") : BridgeCommand()
    data class KeyEventCommand(val key: String) : BridgeCommand()
    data object ReadLastMessage : BridgeCommand()
    data object NavigateBack : BridgeCommand()
    data object NavigateHome : BridgeCommand()
    data class Scroll(val forward: Boolean) : BridgeCommand()
    data object Ping : BridgeCommand()
    data class Unknown(val action: String, val raw: String) : BridgeCommand()
}

object BridgeProtocol {

    fun parseCommand(rawJson: String): Result<BridgeCommand> {
        return runCatching {
            val json = JSONObject(rawJson)
            val action = json.optString("action", "").trim().lowercase()
            if (action.isEmpty()) {
                throw IllegalArgumentException("Missing 'action' field in JSON payload")
            }

            when (action) {
                "launch_app", "open_app", "open", "launch" -> {
                    val pkg = json.optString("package", "").ifBlank {
                        json.optString("package_name", "")
                    }.ifBlank { null }
                    val app = json.optString("app", "").ifBlank {
                        json.optString("name", "")
                    }.ifBlank { null }
                    BridgeCommand.LaunchApp(packageName = pkg, appName = app)
                }

                "set_target_app" -> {
                    val pkg = json.optString("package", "").trim()
                    if (pkg.isEmpty()) {
                        throw IllegalArgumentException("Missing 'package' field for 'set_target_app'")
                    }
                    BridgeCommand.SetTargetApp(packageName = pkg)
                }

                "type", "set_text", "input" -> {
                    val text = json.optString("text", "")
                    if (text.isEmpty() && !json.has("text")) {
                        throw IllegalArgumentException("Missing 'text' field for 'type' action")
                    }
                    val targetId = json.optString("id", "").ifBlank { null }
                    BridgeCommand.TypeText(text = text, targetId = targetId)
                }

                "click", "tap", "press" -> {
                    val text = json.optString("text", "").ifBlank { null }
                    val viewId = json.optString("id", "").ifBlank {
                        json.optString("view_id", "")
                    }.ifBlank { null }
                    if (text == null && viewId == null) {
                        throw IllegalArgumentException("Either 'text' or 'id' must be provided for 'click' action")
                    }
                    BridgeCommand.ClickElement(text = text, viewId = viewId)
                }

                "type_and_send", "send_prompt", "ask" -> {
                    val text = json.optString("text", "")
                    if (text.isBlank()) {
                        throw IllegalArgumentException("Missing 'text' field for 'type_and_send' action")
                    }
                    val sendText = json.optString("send_text", "Send").ifBlank { "Send" }
                    BridgeCommand.TypeAndSend(text = text, sendButtonText = sendText)
                }

                "key_event", "press_key", "keyevent", "key" -> {
                    val key = json.optString("key", "").ifBlank {
                        json.optString("keycode", "")
                    }
                    if (key.isBlank()) {
                        throw IllegalArgumentException("Missing 'key' or 'keycode' for key_event")
                    }
                    BridgeCommand.KeyEventCommand(key = key)
                }

                "enter" -> BridgeCommand.KeyEventCommand(key = "enter")

                "read", "read_last_message", "read_message", "get_response" -> {
                    BridgeCommand.ReadLastMessage
                }

                "back" -> BridgeCommand.NavigateBack
                "home" -> BridgeCommand.NavigateHome
                "scroll_down", "scroll_forward" -> BridgeCommand.Scroll(forward = true)
                "scroll_up", "scroll_backward" -> BridgeCommand.Scroll(forward = false)
                "ping" -> BridgeCommand.Ping
                else -> BridgeCommand.Unknown(action = action, raw = rawJson)
            }
        }
    }

    fun screenUpdateJson(text: String, packageName: String): String {
        return JSONObject().apply {
            put("action", "screen_update")
            put("text", text)
            put("package", packageName)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun responseReadyJson(cleanMessage: String, packageName: String): String {
        return JSONObject().apply {
            put("action", "response_ready")
            put("message", cleanMessage)
            put("package", packageName)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun actionResultJson(action: String, success: Boolean, detail: String): String {
        return JSONObject().apply {
            put("type", if (success) "action_result" else "error")
            put("action", action)
            put("status", if (success) "ok" else "error")
            put("detail", detail)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun aiResponseJson(packageName: String, text: String): String {
        return JSONObject().apply {
            put("type", "ai_response")
            put("package", packageName)
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun statusJson(
        event: String,
        accessibilityEnabled: Boolean,
        shizukuReady: Boolean,
        activePackage: String?,
        targetAiPackage: String
    ): String {
        return JSONObject().apply {
            put("type", "status")
            put("event", event)
            put("accessibility_enabled", accessibilityEnabled)
            put("shizuku_ready", shizukuReady)
            put("active_package", activePackage ?: "")
            put("target_ai_package", targetAiPackage)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
}
