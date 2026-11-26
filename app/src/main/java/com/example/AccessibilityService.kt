package com.example

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
            "إرسال",
            "Enviar",
            "Send message",
            "Send prompt",
            "Submit",
            "Ask"
        )

        private val STOP_ALIASES = listOf(
            "Stop generating",
            "Stop streaming",
            "Stop response",
            "Stop",
            "إيقاف",
            "إيقاف التوليد",
            "Detener"
        )

        private val NON_SEND_DESCRIPTIONS = setOf(
            "attach",
            "attachment",
            "add",
            "plus",
            "microphone",
            "mic",
            "voice",
            "dictate",
            "camera",
            "gallery",
            "photo",
            "image",
            "clear",
            "emoji",
            "menu"
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
            "enviar",
            "stop",
            "stop generating",
            "stop streaming",
            "copy",
            "copy code",
            "regenerate",
            "retry",
            "read aloud",
            "good response",
            "bad response",
            "like",
            "dislike",
            "share",
            "edit",
            "new chat",
            "chatgpt",
            "claude",
            "gemini",
            "copilot",
            "message chatgpt",
            "ask anything",
            "ask gemini",
            "reply to claude",
            "message copilot",
            "memory updated",
            "thinking",
            "searching the web"
        )
    }

    private var serviceScope: CoroutineScope? = null
    private var scrapeJob: Job? = null
    private var responseReadyJob: Job? = null
    private var postSendWaitJob: Job? = null

    private var lastScrapedScreenText: String = ""
    private var lastReportedReadyMessage: String = ""
    private var lastInjectedPrompt: String = ""

    @Volatile
    private var wasGenerating: Boolean = false

    @Volatile
    private var awaitingResponse: Boolean = false

    @Volatile
    private var suppressScrapeUntilMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        BridgeRepository.loadPreferences(this)
        ShizukuController.init(this)
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
        postSendWaitJob?.cancel()
        serviceScope?.cancel()
        serviceScope = null
        instance = null
        BridgeRepository.setAccessibilityActive(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = try {
            event.packageName?.toString()
        } catch (_: Exception) {
            null
        } ?: return

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
            val remainingSuppress = suppressScrapeUntilMs - System.currentTimeMillis()
            if (remainingSuppress > 0) {
                delay(remainingSuppress)
            } else {
                delay(300L)
            }

            val root = safeGetActiveRoot() ?: return@launch
            val pkg = safePackageName(root)
            if (pkg == packageName || pkg == "com.android.systemui") return@launch

            // 1. Deduplicated screen state update
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

            // 2. Intelligent response completion check
            evaluateResponseReadyLoop(root, pkg)
        }
    }

    private fun evaluateResponseReadyLoop(root: AccessibilityNodeInfo, pkg: String) {
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
                val freshRoot = safeGetActiveRoot() ?: return@launch
                if (isStopGeneratingVisible(freshRoot)) {
                    wasGenerating = true
                    return@launch
                }

                val cleanMessage = extractCleanLastAiBubble(freshRoot)
                if (!cleanMessage.isNullOrBlank() &&
                    cleanMessage != lastInjectedPrompt &&
                    cleanMessage != lastReportedReadyMessage
                ) {
                    wasGenerating = false
                    awaitingResponse = false
                    lastReportedReadyMessage = cleanMessage
                    BridgeRepository.setLastAiMessage(cleanMessage)

                    val preview = cleanMessage.take(70) + if (cleanMessage.length > 70) "…" else ""
                    BridgeRepository.addLog(LogLevel.OK, "Response ready: $preview")

                    if (BridgeRepository.connectionState.value == ConnectionState.CONNECTED) {
                        BridgeRepository.emitOutgoingMessage(
                            BridgeProtocol.responseReadyJson(
                                cleanMessage = cleanMessage,
                                packageName = safePackageName(freshRoot).ifBlank { pkg }
                            )
                        )
                    }
                }
            }
        }
    }

    private fun getCurrentActivePackage(): String {
        val rootPkg = safePackageName(safeGetActiveRoot())
        if (rootPkg.isNotBlank() && rootPkg != packageName && rootPkg != "com.android.systemui") {
            return rootPkg
        }
        return BridgeRepository.foregroundPackage.value.orEmpty()
    }

    private fun safeGetActiveRoot(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow ?: getApplicationWindowRoots().firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun getApplicationWindowRoots(): List<AccessibilityNodeInfo> {
        return try {
            windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.isActive }
                .mapNotNull { window ->
                    try {
                        window.root
                    } catch (_: Exception) {
                        null
                    }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun safeGetCandidateRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            rootInActiveWindow?.let { list.add(it) }
        } catch (_: Exception) {
        }
        for (root in getApplicationWindowRoots()) {
            if (list.none { it == root }) {
                list.add(root)
            }
        }
        return list
    }

    private fun safePackageName(node: AccessibilityNodeInfo?): String {
        return try {
            node?.packageName?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun ensureTargetAiAppActive(): AccessibilityActionResult {
        val currentPkg = getCurrentActivePackage()
        if (currentPkg in BridgeRepository.KNOWN_AI_PACKAGES) {
            return AccessibilityActionResult.Success("AI app active: $currentPkg", currentPkg)
        }

        val targetPkg = BridgeRepository.getTargetAiPackage(this)
        BridgeRepository.addLog(
            LogLevel.INFO,
            "Active window '$currentPkg' is not an AI app; launching $targetPkg…"
        )

        val launchResult = ShizukuController.launchApp(this, targetPkg)
        if (launchResult is AccessibilityActionResult.Error) {
            return launchResult
        }

        // Wait up to 6 seconds for the AI app window and EditText composer to appear
        repeat(24) {
            delay(250L)
            val pkgNow = getCurrentActivePackage()
            val roots = safeGetCandidateRoots()
            val editReady = findBestEditTextNode(roots, null) != null
            if ((pkgNow == targetPkg || pkgNow in BridgeRepository.KNOWN_AI_PACKAGES) && editReady) {
                delay(350L)
                return AccessibilityActionResult.Success("Loaded AI app: $pkgNow", pkgNow)
            }
        }

        val finalPkg = getCurrentActivePackage()
        return if (finalPkg in BridgeRepository.KNOWN_AI_PACKAGES) {
            AccessibilityActionResult.Success("Loaded AI app: $finalPkg", finalPkg)
        } else {
            AccessibilityActionResult.Error(
                "Timed out waiting for target AI app '$targetPkg' to load (current='$finalPkg')"
            )
        }
    }

    suspend fun typeAndSend(
        text: String,
        sendButtonText: String = "Send"
    ): AccessibilityActionResult {
        // 1. App Auto-Detection & Selection
        val ensureApp = ensureTargetAiAppActive()
        if (ensureApp is AccessibilityActionResult.Error) {
            return ensureApp
        }

        // 2. Inject text into EditText
        val typeResult = injectText(text)
        if (typeResult is AccessibilityActionResult.Error) {
            return typeResult
        }

        delay(300L)

        // 3. Click Send button (or fallback to last clickable node in input area)
        val editBounds = findBestEditTextBounds()
        val clickResult = clickSendOrLastInputNode(
            preferredLabel = sendButtonText,
            editBounds = editBounds
        )
        if (clickResult is AccessibilityActionResult.Error) {
            return clickResult
        }

        // 4. Mark awaiting response & wait 2 seconds before reading the screen
        lastInjectedPrompt = text.trim()
        awaitingResponse = true
        wasGenerating = false
        suppressScrapeUntilMs = System.currentTimeMillis() + 2_000L

        postSendWaitJob?.cancel()
        postSendWaitJob = serviceScope?.launch {
            delay(2_000L)
            val root = safeGetActiveRoot() ?: return@launch
            val pkg = safePackageName(root)
            if (pkg != packageName && pkg != "com.android.systemui") {
                evaluateResponseReadyLoop(root, pkg)
            }
        }

        val clickDetail = (clickResult as AccessibilityActionResult.Success).detail
        return AccessibilityActionResult.Success(
            detail = "Typed ${text.length} chars & $clickDetail",
            packageName = clickResult.packageName
        )
    }

    suspend fun injectText(
        text: String,
        targetViewId: String? = null
    ): AccessibilityActionResult {
        val roots = safeGetCandidateRoots()
        if (roots.isEmpty()) {
            return AccessibilityActionResult.Error("No active window root available (rootInActiveWindow is null)")
        }

        val targetNode = findBestEditTextNode(roots, targetViewId)
            ?: return AccessibilityActionResult.Error(
                "No EditText or editable input field found on screen (${safePackageName(roots.firstOrNull())})"
            )

        val pkg = safePackageName(targetNode).ifBlank { safePackageName(roots.firstOrNull()) }

        try {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Exception) {
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val firstTry = try {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
        if (firstTry) {
            return AccessibilityActionResult.Success(
                detail = "Injected ${text.length} chars via ACTION_SET_TEXT",
                packageName = pkg
            )
        }

        try {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Exception) {
        }
        delay(120L)

        val refreshedNode = findBestEditTextNode(safeGetCandidateRoots(), targetViewId) ?: targetNode
        val secondTry = try {
            refreshedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
        if (secondTry) {
            return AccessibilityActionResult.Success(
                detail = "Injected ${text.length} chars via click + ACTION_SET_TEXT",
                packageName = pkg
            )
        }

        val pasted = try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("entity_body_input", text))
            refreshedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
        if (pasted) {
            return AccessibilityActionResult.Success(
                detail = "Injected ${text.length} chars via ACTION_PASTE fallback",
                packageName = pkg
            )
        }

        return AccessibilityActionResult.Error("Found EditText ($pkg) but ACTION_SET_TEXT was rejected")
    }

    private fun findBestEditTextNode(
        roots: List<AccessibilityNodeInfo>,
        targetViewId: String?
    ): AccessibilityNodeInfo? {
        for (root in roots) {
            if (!targetViewId.isNullOrBlank()) {
                val byId = try {
                    root.findAccessibilityNodeInfosByViewId(targetViewId)
                } catch (_: Exception) {
                    null
                }
                val match = byId?.firstOrNull { it.isEnabled }
                if (match != null) return match
            }
        }

        val allEditCandidates = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            val pkg = safePackageName(root)
            if (pkg == packageName || pkg == "com.android.systemui") continue

            allEditCandidates += collectNodes(root) { node ->
                val cls = node.className?.toString().orEmpty()
                cls.equals("android.widget.EditText", ignoreCase = true) ||
                    cls.contains("EditText", ignoreCase = true) ||
                    node.isEditable
            }
        }

        if (allEditCandidates.isEmpty()) return null

        return allEditCandidates.firstOrNull {
            it.isVisibleToUser && it.isEnabled && it.isFocused &&
                it.className?.toString()?.contains("EditText", ignoreCase = true) == true
        } ?: allEditCandidates.firstOrNull {
            it.isVisibleToUser && it.isEnabled &&
                it.className?.toString()?.contains("EditText", ignoreCase = true) == true
        } ?: allEditCandidates.firstOrNull {
            it.isVisibleToUser && it.isEnabled && it.isEditable
        } ?: allEditCandidates.firstOrNull {
            it.className?.toString()?.contains("EditText", ignoreCase = true) == true
        } ?: allEditCandidates.firstOrNull()
    }

    private fun findBestEditTextBounds(): Rect? {
        val node = findBestEditTextNode(safeGetCandidateRoots(), null) ?: return null
        val rect = Rect()
        return try {
            node.getBoundsInScreen(rect)
            rect
        } catch (_: Exception) {
            null
        }
    }

    fun clickElement(text: String?, viewId: String? = null): AccessibilityActionResult {
        val roots = safeGetCandidateRoots()
        if (roots.isEmpty()) {
            return AccessibilityActionResult.Error("No active window root available (rootInActiveWindow is null)")
        }

        if (!viewId.isNullOrBlank()) {
            for (r in roots) {
                val byId = try {
                    r.findAccessibilityNodeInfosByViewId(viewId)
                } catch (_: Exception) {
                    null
                }
                val clickable = byId?.firstNotNullOfOrNull { findClickableSelfOrParent(it) }
                if (clickable != null && safePerformClick(clickable)) {
                    return AccessibilityActionResult.Success(
                        detail = "Clicked element by ID '$viewId'",
                        packageName = safePackageName(r)
                    )
                }
            }
        }

        if (!text.isNullOrBlank()) {
            val isSendQuery = SEND_ALIASES.any { it.equals(text.trim(), ignoreCase = true) }
            val candidates = if (isSendQuery) {
                (listOf(text.trim()) + SEND_ALIASES).distinct()
            } else {
                listOf(text.trim())
            }

            for (r in roots) {
                val pkg = safePackageName(r)
                if (pkg == packageName || pkg == "com.android.systemui") continue

                for (candidate in candidates) {
                    val matched = try {
                        r.findAccessibilityNodeInfosByText(candidate)
                    } catch (_: Exception) {
                        null
                    }
                    if (!matched.isNullOrEmpty()) {
                        val exactClickable = matched.firstOrNull { node ->
                            node.isVisibleToUser &&
                                (node.text?.toString()?.equals(candidate, ignoreCase = true) == true ||
                                    node.contentDescription?.toString()?.equals(candidate, ignoreCase = true) == true)
                        }?.let { findClickableSelfOrParent(it) }

                        val target = exactClickable ?: matched.firstOrNull { it.isVisibleToUser }
                            ?.let { findClickableSelfOrParent(it) }

                        if (target != null && safePerformClick(target)) {
                            if (isSendQuery) awaitingResponse = true
                            return AccessibilityActionResult.Success(
                                detail = "Clicked '$candidate' via text search",
                                packageName = pkg
                            )
                        }
                    }
                }

                val exactDescMatch = collectNodes(r) { node ->
                    node.isVisibleToUser && candidates.any { candidate ->
                        node.contentDescription?.toString()?.trim()?.equals(candidate, ignoreCase = true) == true ||
                            node.text?.toString()?.trim()?.equals(candidate, ignoreCase = true) == true
                    }
                }.firstNotNullOfOrNull { findClickableSelfOrParent(it) }

                if (exactDescMatch != null && safePerformClick(exactDescMatch)) {
                    if (isSendQuery) awaitingResponse = true
                    return AccessibilityActionResult.Success(
                        detail = "Clicked '$text' via contentDescription/text match",
                        packageName = pkg
                    )
                }

                val partialDescMatch = collectNodes(r) { node ->
                    node.isVisibleToUser && candidates.any { candidate ->
                        node.contentDescription?.toString()?.contains(candidate, ignoreCase = true) == true ||
                            node.text?.toString()?.contains(candidate, ignoreCase = true) == true
                    }
                }.firstNotNullOfOrNull { findClickableSelfOrParent(it) }

                if (partialDescMatch != null && safePerformClick(partialDescMatch)) {
                    if (isSendQuery) awaitingResponse = true
                    return AccessibilityActionResult.Success(
                        detail = "Clicked '$text' via partial match",
                        packageName = pkg
                    )
                }

                if (isSendQuery) {
                    for (commonId in COMMON_SEND_VIEW_IDS) {
                        val byCommonId = try {
                            r.findAccessibilityNodeInfosByViewId(commonId)
                        } catch (_: Exception) {
                            null
                        }
                        val clickable = byCommonId?.firstNotNullOfOrNull { findClickableSelfOrParent(it) }
                        if (clickable != null && safePerformClick(clickable)) {
                            awaitingResponse = true
                            return AccessibilityActionResult.Success(
                                detail = "Clicked Send via common ID '$commonId'",
                                packageName = pkg
                            )
                        }
                    }
                }
            }
        }

        return AccessibilityActionResult.Error(
            "UI element not found or not clickable (text='${text.orEmpty()}', id='${viewId.orEmpty()}')"
        )
    }

    private fun clickSendOrLastInputNode(
        preferredLabel: String,
        editBounds: Rect?
    ): AccessibilityActionResult {
        val directResult = clickElement(text = preferredLabel)
        if (directResult is AccessibilityActionResult.Success) {
            return directResult
        }

        val roots = safeGetCandidateRoots()
        for (root in roots) {
            val pkg = safePackageName(root)
            if (pkg == packageName || pkg == "com.android.systemui") continue

            val editNode = findBestEditTextNode(listOf(root), null)
            val resolvedEditBounds = editBounds ?: editNode?.let {
                val r = Rect()
                try {
                    it.getBoundsInScreen(r)
                    r
                } catch (_: Exception) {
                    null
                }
            }

            if (editNode != null) {
                var container: AccessibilityNodeInfo? = editNode.parent
                var depth = 0
                while (container != null && depth < 3) {
                    val clickablesInContainer = collectNodes(container) { node ->
                        isCandidateInputAreaButton(node, editNode, resolvedEditBounds)
                    }
                    val lastClickable = clickablesInContainer.lastOrNull()
                    if (lastClickable != null && safePerformClick(lastClickable)) {
                        return AccessibilityActionResult.Success(
                            detail = "Clicked last clickable node in input container",
                            packageName = pkg
                        )
                    }
                    container = container.parent
                    depth++
                }
            }

            if (resolvedEditBounds != null) {
                val bandClickables = collectNodes(root) { node ->
                    if (!isCandidateInputAreaButton(node, editNode, resolvedEditBounds)) {
                        return@collectNodes false
                    }
                    val r = Rect()
                    try {
                        node.getBoundsInScreen(r)
                        r.bottom >= (resolvedEditBounds.top - 120) &&
                            r.top <= (resolvedEditBounds.bottom + 120)
                    } catch (_: Exception) {
                        false
                    }
                }
                val lastInBand = bandClickables.lastOrNull()
                if (lastInBand != null && safePerformClick(lastInBand)) {
                    return AccessibilityActionResult.Success(
                        detail = "Clicked last clickable node in input band",
                        packageName = pkg
                    )
                }
            }
        }

        return AccessibilityActionResult.Error(
            "Send button ('Send', 'إرسال', 'Enviar') and fallback input-area button were not found"
        )
    }

    private fun isCandidateInputAreaButton(
        node: AccessibilityNodeInfo,
        editNode: AccessibilityNodeInfo?,
        editBounds: Rect?
    ): Boolean {
        if (!node.isVisibleToUser || !node.isClickable || !node.isEnabled) return false
        if (editNode != null && node == editNode) return false

        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true) || node.isEditable) return false

        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val txt = node.text?.toString()?.trim()?.lowercase().orEmpty()
        if (desc in NON_SEND_DESCRIPTIONS || txt in NON_SEND_DESCRIPTIONS) return false

        if (editBounds != null) {
            val r = Rect()
            try {
                node.getBoundsInScreen(r)
                if (r.contains(editBounds) && r.width() > editBounds.width() + 20) {
                    return false
                }
            } catch (_: Exception) {
            }
        }
        return true
    }

    fun readLastAiMessage(): AccessibilityActionResult {
        val root = safeGetActiveRoot()
            ?: return AccessibilityActionResult.Error("No active window root available (rootInActiveWindow is null)")

        val pkg = safePackageName(root)
        val lastMessage = extractCleanLastAiBubble(root)
        return if (!lastMessage.isNullOrBlank()) {
            BridgeRepository.setLastAiMessage(lastMessage)
            AccessibilityActionResult.Success(detail = lastMessage, packageName = pkg)
        } else {
            AccessibilityActionResult.Error("No clean AI message bubble found on screen ($pkg)")
        }
    }

    fun scrollChat(forward: Boolean): AccessibilityActionResult {
        val root = safeGetActiveRoot()
            ?: return AccessibilityActionResult.Error("No active window root available (rootInActiveWindow is null)")

        val scrollable = collectNodes(root) { it.isVisibleToUser && it.isScrollable }
            .maxByOrNull { node ->
                val rect = Rect()
                try {
                    node.getBoundsInScreen(rect)
                    rect.width().toLong() * rect.height().toLong()
                } catch (_: Exception) {
                    0L
                }
            }
            ?: return AccessibilityActionResult.Error("No scrollable view found on current screen")

        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = try {
            scrollable.performAction(action)
        } catch (_: Exception) {
            false
        }
        return if (ok) {
            AccessibilityActionResult.Success(
                detail = if (forward) "Scrolled forward" else "Scrolled backward",
                packageName = safePackageName(root)
            )
        } else {
            AccessibilityActionResult.Error("Scroll action could not be performed")
        }
    }

    fun performBack(): AccessibilityActionResult {
        val ok = try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
            false
        }
        return if (ok) {
            AccessibilityActionResult.Success("Performed global BACK")
        } else {
            AccessibilityActionResult.Error("Global BACK action failed")
        }
    }

    fun performHome(): AccessibilityActionResult {
        val ok = try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Exception) {
            false
        }
        return if (ok) {
            AccessibilityActionResult.Success("Performed global HOME")
        } else {
            AccessibilityActionResult.Error("Global HOME action failed")
        }
    }

    private fun safePerformClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        }
    }

    private fun isStopGeneratingVisible(root: AccessibilityNodeInfo): Boolean {
        for (stopId in COMMON_STOP_VIEW_IDS) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(stopId)
            } catch (_: Exception) {
                null
            }
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
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(sendId)
            } catch (_: Exception) {
                null
            }
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
            try {
                if (!node.isVisibleToUser) return
                val rawText = node.text?.toString()?.trim()
                if (!rawText.isNullOrEmpty() && seen.add(rawText)) {
                    collected.add(rawText)
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { walk(it) }
                }
            } catch (_: Exception) {
            }
        }

        walk(root)
        return collected
    }

    private fun extractCleanLastAiBubble(root: AccessibilityNodeInfo): String? {
        val rootRect = Rect()
        try {
            root.getBoundsInScreen(rootRect)
        } catch (_: Exception) {
        }
        val screenWidth = rootRect.width().coerceAtLeast(1)

        val inputTopY = collectNodes(root) { node ->
            node.isVisibleToUser &&
                (node.className?.toString()?.contains("EditText", ignoreCase = true) == true || node.isEditable)
        }.minOfOrNull { node ->
            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
                rect.top
            } catch (_: Exception) {
                Int.MAX_VALUE
            }
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
            try {
                node.getBoundsInScreen(rect)
                rect.width().toLong() * rect.height().toLong()
            } catch (_: Exception) {
                0L
            }
        }

        if (primaryChatContainer != null && primaryChatContainer.childCount > 0) {
            val aiBubbles = mutableListOf<String>()
            for (i in 0 until primaryChatContainer.childCount) {
                val child = try {
                    primaryChatContainer.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue

                val childRect = Rect()
                try {
                    child.getBoundsInScreen(childRect)
                    if (!child.isVisibleToUser || childRect.top >= inputTopY) continue
                } catch (_: Exception) {
                    continue
                }

                val itemTexts = extractMeaningfulTexts(child, inputTopY)
                if (itemTexts.isEmpty()) continue

                val combinedBubble = itemTexts.joinToString("\n").trim()
                if (isUserPromptBubble(combinedBubble, childRect, screenWidth)) {
                    continue
                }
                aiBubbles.add(combinedBubble)
            }

            val lastAiBubble = aiBubbles.lastOrNull { it.isNotBlank() }
            if (!lastAiBubble.isNullOrBlank()) {
                return lastAiBubble
            }
        }

        val fallbackTexts = extractMeaningfulTexts(root, inputTopY)
            .filterNot { lastInjectedPrompt.isNotBlank() && it.equals(lastInjectedPrompt, ignoreCase = true) }
        return fallbackTexts.lastOrNull { it.isNotBlank() }
    }

    private fun isUserPromptBubble(
        bubbleText: String,
        bubbleRect: Rect,
        screenWidth: Int
    ): Boolean {
        if (lastInjectedPrompt.isNotBlank() &&
            bubbleText.equals(lastInjectedPrompt, ignoreCase = true)
        ) {
            return true
        }
        // Heuristic: user message pills are typically compact and right-aligned in LTR chat UIs
        val isCompactRightAligned = bubbleRect.left > (screenWidth * 0.30f) &&
            bubbleRect.right > (screenWidth * 0.85f) &&
            bubbleRect.width() < (screenWidth * 0.72f)
        return isCompactRightAligned
    }

    private fun extractMeaningfulTexts(root: AccessibilityNodeInfo, maxBottomY: Int): List<String> {
        val collected = mutableListOf<String>()
        val seen = LinkedHashSet<String>()

        fun walk(node: AccessibilityNodeInfo) {
            try {
                if (!node.isVisibleToUser) return
                val cls = node.className?.toString().orEmpty()
                if (node.isEditable || cls.contains("EditText", ignoreCase = true)) return

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
            } catch (_: Exception) {
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
            try {
                if (current.isClickable && current.isEnabled) {
                    return current
                }
                current = current.parent
            } catch (_: Exception) {
                return null
            }
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
            try {
                if (predicate(current)) {
                    results.add(current)
                }
                for (i in current.childCount - 1 downTo 0) {
                    current.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Exception) {
            }
        }
        return results
    }
}
