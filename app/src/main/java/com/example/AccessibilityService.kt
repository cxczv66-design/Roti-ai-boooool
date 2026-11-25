package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class AccessibilityActionResult {
    data class Success(val detail: String, val packageName: String = "") : AccessibilityActionResult()
    data class Error(val reason: String) : AccessibilityActionResult()
}

class EntityAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: EntityAccessibilityService? = null
            private set

        private val SEND_ALIASES = listOf(
            "Send",
            "Send message",
            "Send prompt",
            "إرسال",
            "Submit",
            "Ask"
        )

        private val STOP_ALIASES = listOf(
            "Stop generating",
            "Stop streaming",
            "Stop response",
            "Stop",
            "إيقاف",
            "إيقاف التوليد"
        )

        private val COMMON_SEND_VIEW_IDS = listOf(
            "com.openai.chatgpt:id/send_button",
            "com.openai.chatgpt:id/composer_send_button",
            "com.anthropic.claude:id/send_button",
            "com.google.android.apps.bard:id/send_button"
        )

        private val COMMON_STOP_VIEW_IDS = listOf(
            "com.openai.chatgpt:id/stop_button",
            "com.openai.chatgpt:id/composer_stop_button",
            "com.anthropic.claude:id/stop_button",
            "com.google.android.apps.bard:id/stop_button"
        )

        private val CHROME_TEXT_IGNORE = setOf(
            "send",
            "إرسال",
            "stop",
            "stop generating",
            "copy",
            "regenerate",
            "new chat",
            "message chatgpt",
            "ask anything",
            "reply to claude"
        )
    }

    private var serviceScope: CoroutineScope? = null
    private var scrapeJob: Job? = null
    private var responseReadyJob: Job? = null

    private var lastScrapedScreenText: String = ""
    private var lastReportedReadyMessage: String = ""
    private var lastInjectedPrompt: String = ""

    @Volatile
    private var wasGenerating: Boolean = false

    @Volatile
    private var awaitingResponse: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope?.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        instance = this
        BridgeRepository.setAccessibilityActive(true)
        BridgeRepository.addLog(LogLevel.OK, "Accessibility Service connected (Eyes & Hands ready)")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupServiceState()
        BridgeRepository.addLog(LogLevel.INFO, "Accessibility Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupServiceState()
        super.onDestroy()
    }

    private fun cleanupServiceState() {
        scrapeJob?.cancel()
        responseReadyJob?.cancel()
        serviceScope?.cancel()
        serviceScope = null
        instance = null
        BridgeRepository.setAccessibilityActive(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        BridgeRepository.setForegroundPackage(pkg)

        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            scheduleScreenScrapeAndLoopCheck()
        }
    }

    override fun onInterrupt() {
        BridgeRepository.addLog(LogLevel.INFO, "Accessibility Service interrupted")
    }

    private fun scheduleScreenScrapeAndLoopCheck() {
        val scope = serviceScope ?: return
        scrapeJob?.cancel()
        scrapeJob = scope.launch {
            delay(300L)
            val root = getActiveRoot() ?: return@launch
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == packageName || pkg == "com.android.systemui") return@launch

            // 1. Screen Scraping (The Eyes) - deduplicated
            val visibleLines = extractAllVisibleTexts(root)
            if (visibleLines.isNotEmpty()) {
                val combinedScreenText = visibleLines.joinToString("\n")
                if (combinedScreenText != lastScrapedScreenText) {
                    lastScrapedScreenText = combinedScreenText
                    if (BridgeRepository.connectionState.value == ConnectionState.CONNECTED) {
                        BridgeRepository.emitOutgoingMessage(
                            BridgeProtocol.screenUpdateJson(
                                text = combinedScreenText,
                                packageName = pkg
                            )
                        )
                    }
                }
            }

            // 2. Curiosity Loop Trigger detection
            evaluateCuriosityLoop(root, pkg)
        }
    }

    private fun evaluateCuriosityLoop(root: AccessibilityNodeInfo, pkg: String) {
        val stopButtonVisible = isStopGeneratingVisible(root)
        val sendNode = findSendButtonNode(root)
        val sendButtonDisabled = sendNode != null && !sendNode.isEnabled

        if (stopButtonVisible || (awaitingResponse && sendButtonDisabled)) {
            wasGenerating = true
            responseReadyJob?.cancel()
            return
        }

        val shouldCheckCompletion = wasGenerating ||
            (awaitingResponse && !stopButtonVisible && (sendNode == null || sendNode.isEnabled))

        if (shouldCheckCompletion) {
            val scope = serviceScope ?: return
            responseReadyJob?.cancel()
            responseReadyJob = scope.launch {
                delay(550L)
                val freshRoot = getActiveRoot() ?: return@launch
                if (isStopGeneratingVisible(freshRoot)) {
                    wasGenerating = true
                    return@launch
                }

                val latestMessage = extractLastAiMessageText(freshRoot)
                if (!latestMessage.isNullOrBlank() &&
                    latestMessage != lastInjectedPrompt &&
                    latestMessage != lastReportedReadyMessage
                ) {
                    wasGenerating = false
                    awaitingResponse = false
                    lastReportedReadyMessage = latestMessage
                    BridgeRepository.setLastAiMessage(latestMessage)

                    val preview = latestMessage.take(70) + if (latestMessage.length > 70) "…" else ""
                    BridgeRepository.addLog(LogLevel.OK, "Curiosity Loop ready: $preview")

                    if (BridgeRepository.connectionState.value == ConnectionState.CONNECTED) {
                        BridgeRepository.emitOutgoingMessage(
                            BridgeProtocol.responseReadyJson(
                                lastMessage = latestMessage,
                                packageName = freshRoot.packageName?.toString() ?: pkg
                            )
                        )
                    }
                }
            }
        }
    }

    private fun getActiveRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return windows.firstOrNull {
            it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }?.root
    }

    fun markAwaitingResponse(promptText: String) {
        lastInjectedPrompt = promptText.trim()
        awaitingResponse = true
        wasGenerating = false
    }

    fun injectText(text: String, targetViewId: String? = null): AccessibilityActionResult {
        val root = getActiveRoot()
            ?: return AccessibilityActionResult.Error("No active window root available")

        var targetNode: AccessibilityNodeInfo? = null

        if (!targetViewId.isNullOrBlank()) {
            targetNode = root.findAccessibilityNodeInfosByViewId(targetViewId)
                ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
        }

        if (targetNode == null) {
            val editNodes = collectNodes(root) { node ->
                val cls = node.className?.toString()
                node.isVisibleToUser && (cls == "android.widget.EditText" || node.isEditable)
            }

            targetNode = editNodes.firstOrNull {
                it.className?.toString() == "android.widget.EditText" && it.isFocused
            } ?: editNodes.firstOrNull {
                it.className?.toString() == "android.widget.EditText"
            } ?: editNodes.firstOrNull {
                it.isFocused && it.isEditable
            } ?: editNodes.firstOrNull {
                it.isEditable
            }
        }

        if (targetNode == null) {
            return AccessibilityActionResult.Error(
                "No EditText or editable input field found on screen (${root.packageName ?: "unknown"})"
            )
        }

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!targetNode.isFocused) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        return if (setSuccess) {
            val pkg = root.packageName?.toString().orEmpty()
            AccessibilityActionResult.Success(
                detail = "Injected ${text.length} chars into input field",
                packageName = pkg
            )
        } else {
            AccessibilityActionResult.Error("Found EditText but ACTION_SET_TEXT was rejected")
        }
    }

    fun clickElement(text: String?, viewId: String? = null): AccessibilityActionResult {
        val root = getActiveRoot()
            ?: return AccessibilityActionResult.Error("No active window root available")

        if (!viewId.isNullOrBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(viewId)
            val clickable = byId?.firstNotNullOfOrNull { findClickableSelfOrParent(it) }
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return AccessibilityActionResult.Success(
                    detail = "Clicked element by ID '$viewId'",
                    packageName = root.packageName?.toString().orEmpty()
                )
            }
        }

        if (!text.isNullOrBlank()) {
            val isSendQuery = SEND_ALIASES.any { it.equals(text.trim(), ignoreCase = true) }
            val candidates = if (isSendQuery) {
                (listOf(text.trim()) + SEND_ALIASES).distinct()
            } else {
                listOf(text.trim())
            }

            // 1. Standard findAccessibilityNodeInfosByText
            for (candidate in candidates) {
                val matched = root.findAccessibilityNodeInfosByText(candidate)
                if (!matched.isNullOrEmpty()) {
                    val exactClickable = matched.firstOrNull { node ->
                        node.isVisibleToUser &&
                            (node.text?.toString()?.equals(candidate, ignoreCase = true) == true ||
                                node.contentDescription?.toString()?.equals(candidate, ignoreCase = true) == true)
                    }?.let { findClickableSelfOrParent(it) }

                    val target = exactClickable ?: matched.firstOrNull { it.isVisibleToUser }
                        ?.let { findClickableSelfOrParent(it) }

                    if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        if (isSendQuery) awaitingResponse = true
                        return AccessibilityActionResult.Success(
                            detail = "Clicked '$candidate' via text search",
                            packageName = root.packageName?.toString().orEmpty()
                        )
                    }
                }
            }

            // 2. Recursive traversal checking exact text and contentDescription
            val exactDescMatch = collectNodes(root) { node ->
                node.isVisibleToUser && candidates.any { candidate ->
                    node.contentDescription?.toString()?.trim()?.equals(candidate, ignoreCase = true) == true ||
                        node.text?.toString()?.trim()?.equals(candidate, ignoreCase = true) == true
                }
            }.firstNotNullOfOrNull { findClickableSelfOrParent(it) }

            if (exactDescMatch != null && exactDescMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (isSendQuery) awaitingResponse = true
                return AccessibilityActionResult.Success(
                    detail = "Clicked '$text' via contentDescription/text match",
                    packageName = root.packageName?.toString().orEmpty()
                )
            }

            // 3. Partial match
            val partialDescMatch = collectNodes(root) { node ->
                node.isVisibleToUser && candidates.any { candidate ->
                    node.contentDescription?.toString()?.contains(candidate, ignoreCase = true) == true ||
                        node.text?.toString()?.contains(candidate, ignoreCase = true) == true
                }
            }.firstNotNullOfOrNull { findClickableSelfOrParent(it) }

            if (partialDescMatch != null && partialDescMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (isSendQuery) awaitingResponse = true
                return AccessibilityActionResult.Success(
                    detail = "Clicked '$text' via partial match",
                    packageName = root.packageName?.toString().orEmpty()
                )
            }

            // 4. Fallback common Send button view IDs
            if (isSendQuery) {
                for (commonId in COMMON_SEND_VIEW_IDS) {
                    val byCommonId = root.findAccessibilityNodeInfosByViewId(commonId)
                    val clickable = byCommonId?.firstNotNullOfOrNull { findClickableSelfOrParent(it) }
                    if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        awaitingResponse = true
                        return AccessibilityActionResult.Success(
                            detail = "Clicked Send via common ID '$commonId'",
                            packageName = root.packageName?.toString().orEmpty()
                        )
                    }
                }
            }
        }

        return AccessibilityActionResult.Error(
            "UI element not found or not clickable (text='${text.orEmpty()}', id='${viewId.orEmpty()}')"
        )
    }

    fun readLastAiMessage(): AccessibilityActionResult {
        val root = getActiveRoot()
            ?: return AccessibilityActionResult.Error("No active window root available")

        val pkg = root.packageName?.toString().orEmpty()
        val lastMessage = extractLastAiMessageText(root)
        return if (!lastMessage.isNullOrBlank()) {
            BridgeRepository.setLastAiMessage(lastMessage)
            AccessibilityActionResult.Success(detail = lastMessage, packageName = pkg)
        } else {
            AccessibilityActionResult.Error("No readable chat message blocks found on screen ($pkg)")
        }
    }

    fun scrollChat(forward: Boolean): AccessibilityActionResult {
        val root = getActiveRoot()
            ?: return AccessibilityActionResult.Error("No active window root available")

        val scrollable = collectNodes(root) { it.isVisibleToUser && it.isScrollable }
            .maxByOrNull { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.width().toLong() * rect.height().toLong()
            }
            ?: return AccessibilityActionResult.Error("No scrollable view found on current screen")

        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = scrollable.performAction(action)
        return if (ok) {
            AccessibilityActionResult.Success(
                detail = if (forward) "Scrolled forward" else "Scrolled backward",
                packageName = root.packageName?.toString().orEmpty()
            )
        } else {
            AccessibilityActionResult.Error("Scroll action could not be performed")
        }
    }

    fun performBack(): AccessibilityActionResult {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        return if (ok) {
            AccessibilityActionResult.Success("Performed global BACK")
        } else {
            AccessibilityActionResult.Error("Global BACK action failed")
        }
    }

    fun performHome(): AccessibilityActionResult {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        return if (ok) {
            AccessibilityActionResult.Success("Performed global HOME")
        } else {
            AccessibilityActionResult.Error("Global HOME action failed")
        }
    }

    private fun isStopGeneratingVisible(root: AccessibilityNodeInfo): Boolean {
        for (stopId in COMMON_STOP_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(stopId)
            if (!nodes.isNullOrEmpty() && nodes.any { it.isVisibleToUser }) {
                return true
            }
        }
        return collectNodes(root) { node ->
            if (!node.isVisibleToUser) return@collectNodes false
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            STOP_ALIASES.any { alias ->
                txt.equals(alias, ignoreCase = true) ||
                    desc.equals(alias, ignoreCase = true) ||
                    desc.contains("stop generating", ignoreCase = true) ||
                    txt.contains("stop generating", ignoreCase = true)
            }
        }.isNotEmpty()
    }

    private fun findSendButtonNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (sendId in COMMON_SEND_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(sendId)
            val visible = nodes?.firstOrNull { it.isVisibleToUser }
            if (visible != null) return visible
        }
        return collectNodes(root) { node ->
            if (!node.isVisibleToUser) return@collectNodes false
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            SEND_ALIASES.any { alias ->
                txt.equals(alias, ignoreCase = true) || desc.equals(alias, ignoreCase = true)
            }
        }.firstOrNull()
    }

    private fun extractAllVisibleTexts(root: AccessibilityNodeInfo): List<String> {
        val collected = mutableListOf<String>()
        val seen = LinkedHashSet<String>()

        fun walk(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            val rawText = node.text?.toString()?.trim()
            if (!rawText.isNullOrEmpty() && seen.add(rawText)) {
                collected.add(rawText)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }

        walk(root)
        return collected
    }

    private fun extractLastAiMessageText(root: AccessibilityNodeInfo): String? {
        val inputTopY = collectNodes(root) { node ->
            node.isVisibleToUser &&
                (node.className?.toString() == "android.widget.EditText" || node.isEditable)
        }.minOfOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        } ?: Int.MAX_VALUE

        val scrollableContainers = collectNodes(root) { node ->
            val cls = node.className?.toString().orEmpty()
            node.isVisibleToUser && (
                node.isScrollable ||
                    node.collectionInfo != null ||
                    cls.contains("RecyclerView", ignoreCase = true) ||
                    cls.contains("ListView", ignoreCase = true) ||
                    cls.contains("ScrollView", ignoreCase = true)
                )
        }

        val primaryChatContainer = scrollableContainers.maxByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.width().toLong() * rect.height().toLong()
        }

        if (primaryChatContainer != null && primaryChatContainer.childCount > 0) {
            val childMessages = mutableListOf<String>()
            for (i in 0 until primaryChatContainer.childCount) {
                val child = primaryChatContainer.getChild(i) ?: continue
                val childRect = Rect()
                child.getBoundsInScreen(childRect)
                if (!child.isVisibleToUser || childRect.top >= inputTopY) continue

                val childTexts = extractMeaningfulTexts(child, inputTopY)
                if (childTexts.isNotEmpty()) {
                    childMessages.add(childTexts.joinToString("\n"))
                }
            }

            val lastItemMessage = childMessages.lastOrNull { it.isNotBlank() }
            if (!lastItemMessage.isNullOrBlank()) {
                return lastItemMessage
            }
        }

        val allTexts = extractMeaningfulTexts(root, inputTopY)
        return allTexts.lastOrNull { it.isNotBlank() }
    }

    private fun extractMeaningfulTexts(root: AccessibilityNodeInfo, maxBottomY: Int): List<String> {
        val collected = mutableListOf<String>()
        val seen = LinkedHashSet<String>()

        fun walk(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            if (node.isEditable || node.className?.toString() == "android.widget.EditText") return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top >= maxBottomY) return

            val rawText = node.text?.toString()?.trim()
            if (!rawText.isNullOrEmpty() && isMeaningfulChatText(rawText)) {
                if (seen.add(rawText)) {
                    collected.add(rawText)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }

        walk(root)
        return collected
    }

    private fun isMeaningfulChatText(text: String): Boolean {
        if (text.length <= 1) return false
        val lower = text.lowercase()
        if (lower in CHROME_TEXT_IGNORE) return false
        if (text.length <= 10 && text.matches(Regex("^\\d{1,2}:\\d{2}(\\s?[APap][Mm])?$"))) {
            return false
        }
        return true
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.isEnabled) {
                return current
            }
            current = current.parent
            depth++
        }
        return null
    }

    private fun collectNodes(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (predicate(current)) {
                results.add(current)
            }
            for (i in current.childCount - 1 downTo 0) {
                current.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return results
    }
}
