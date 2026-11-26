package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.view.KeyEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    UNAVAILABLE,
    NEEDS_PERMISSION,
    BINDING,
    READY
}

object ShizukuController {

    private const val REQUEST_CODE_SHIZUKU = 9001
    private val PACKAGE_REGEX = Regex("^[a-zA-Z0-9_.]+$")

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var shellService: IEntityShellService? = null

    @Volatile
    private var bindDeferred: CompletableDeferred<IEntityShellService?>? = null

    private val userServiceArgs: Shizuku.UserServiceArgs?
        get() {
            val ctx = appContext ?: return null
            return Shizuku.UserServiceArgs(ComponentName(ctx, ShizukuShellService::class.java))
                .daemon(false)
                .processNameSuffix("shizuku_shell")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = IEntityShellService.Stub.asInterface(service)
            shellService = bound
            BridgeRepository.setShizukuStatus(ShizukuStatus.READY)
            BridgeRepository.addLog(LogLevel.OK, "Shizuku UserService bound & ready")
            bindDeferred?.complete(bound)
            bindDeferred = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            refreshStatus()
            bindDeferred?.complete(null)
            bindDeferred = null
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
        if (hasPermission()) {
            bindShellService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        BridgeRepository.setShizukuStatus(ShizukuStatus.UNAVAILABLE)
        BridgeRepository.addLog(LogLevel.INFO, "Shizuku binder disconnected")
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_SHIZUKU) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    BridgeRepository.addLog(LogLevel.OK, "Shizuku permission granted")
                    bindShellService()
                } else {
                    BridgeRepository.setShizukuStatus(ShizukuStatus.NEEDS_PERMISSION)
                    BridgeRepository.addLog(LogLevel.ERR, "Shizuku permission denied")
                }
            }
        }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) {
            refreshStatus()
            return
        }
        initialized = true
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            refreshStatus()
        } catch (e: Throwable) {
            BridgeRepository.setShizukuStatus(ShizukuStatus.UNAVAILABLE)
            BridgeRepository.addLog(LogLevel.INFO, "Shizuku unavailable: ${e.message}")
        }
    }

    fun refreshStatus() {
        val status = try {
            when {
                !Shizuku.pingBinder() -> ShizukuStatus.UNAVAILABLE
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NEEDS_PERMISSION
                shellService != null -> ShizukuStatus.READY
                else -> ShizukuStatus.BINDING
            }
        } catch (_: Throwable) {
            ShizukuStatus.UNAVAILABLE
        }
        BridgeRepository.setShizukuStatus(status)
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                BridgeRepository.setShizukuStatus(ShizukuStatus.UNAVAILABLE)
                BridgeRepository.addLog(LogLevel.ERR, "Shizuku service is not running")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindShellService()
            } else if (!Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            } else {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            }
        } catch (e: Throwable) {
            BridgeRepository.addLog(LogLevel.ERR, "Shizuku request failed: ${e.message}")
        }
    }

    private fun bindShellService() {
        if (shellService != null) {
            BridgeRepository.setShizukuStatus(ShizukuStatus.READY)
            return
        }
        val args = userServiceArgs ?: return
        try {
            BridgeRepository.setShizukuStatus(ShizukuStatus.BINDING)
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Throwable) {
            BridgeRepository.setShizukuStatus(ShizukuStatus.UNAVAILABLE)
            BridgeRepository.addLog(LogLevel.ERR, "Failed to bind Shizuku UserService: ${e.message}")
        }
    }

    private suspend fun getOrBindService(): IEntityShellService? {
        shellService?.let { return it }
        if (!hasPermission()) return null

        val deferred = bindDeferred ?: CompletableDeferred<IEntityShellService?>().also {
            bindDeferred = it
            withContext(Dispatchers.Main) {
                bindShellService()
            }
        }
        return withTimeoutOrNull(2500L) {
            deferred.await()
        } ?: shellService
    }

    suspend fun runShellCommand(vararg command: String): Result<String> = withContext(Dispatchers.IO) {
        val service = getOrBindService()
            ?: return@withContext Result.failure(IllegalStateException("Shizuku shell service unavailable"))
        return@withContext try {
            val raw = service.execCommand(command)
            if (raw.startsWith("OK:")) {
                Result.success(raw.removePrefix("OK:"))
            } else {
                Result.failure(IllegalStateException(raw))
            }
        } catch (e: Exception) {
            shellService = null
            Result.failure(e)
        }
    }

    suspend fun launchApp(context: Context, packageName: String): AccessibilityActionResult {
        val cleanPkg = packageName.trim()
        if (!PACKAGE_REGEX.matches(cleanPkg)) {
            return AccessibilityActionResult.Error("Invalid package name '$cleanPkg'")
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(cleanPkg)
        val component = launchIntent?.component

        // 1. Prefer Shizuku `am start` when available
        if (hasPermission()) {
            val shizukuCmd = if (component != null) {
                arrayOf("am", "start", "-n", component.flattenToShortString())
            } else {
                arrayOf("monkey", "-p", cleanPkg, "-c", "android.intent.category.LAUNCHER", "1")
            }
            val shizukuResult = runShellCommand(*shizukuCmd)
            if (shizukuResult.isSuccess) {
                return AccessibilityActionResult.Success(
                    detail = "Launched $cleanPkg via Shizuku",
                    packageName = cleanPkg
                )
            }
        }

        // 2. Fallback to standard Android Intent
        if (launchIntent == null) {
            return AccessibilityActionResult.Error(
                "App '$cleanPkg' is not installed or has no launch activity"
            )
        }
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
            AccessibilityActionResult.Success(
                detail = "Launched $cleanPkg via Intent",
                packageName = cleanPkg
            )
        } catch (e: Exception) {
            AccessibilityActionResult.Error("Failed to launch $cleanPkg: ${e.message}")
        }
    }

    suspend fun sendKeyEvent(keyNameOrCode: String): AccessibilityActionResult {
        val resolvedCode = when (keyNameOrCode.trim().lowercase()) {
            "enter", "return", "go" -> KeyEvent.KEYCODE_ENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "home" -> KeyEvent.KEYCODE_HOME
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "tab" -> KeyEvent.KEYCODE_TAB
            "del", "backspace" -> KeyEvent.KEYCODE_DEL
            else -> keyNameOrCode.trim().toIntOrNull()
        } ?: return AccessibilityActionResult.Error("Unsupported key event '$keyNameOrCode'")

        if (hasPermission()) {
            val res = runShellCommand("input", "keyevent", resolvedCode.toString())
            if (res.isSuccess) {
                return AccessibilityActionResult.Success("Injected keyevent $resolvedCode via Shizuku")
            }
        }

        // Accessibility fallback for Back/Home if Shizuku isn't active
        val service = EntityAccessibilityService.instance
        if (service != null) {
            if (resolvedCode == KeyEvent.KEYCODE_BACK) {
                return service.performBack()
            } else if (resolvedCode == KeyEvent.KEYCODE_HOME) {
                return service.performHome()
            }
        }

        return AccessibilityActionResult.Error(
            "Shizuku unavailable for keyevent $resolvedCode (authorize Shizuku first)"
        )
    }
}
