package com.example

import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

@Keep
class ShizukuShellService : IEntityShellService.Stub() {

    override fun execCommand(command: Array<out String>?): String {
        if (command.isNullOrEmpty()) {
            return "ERROR:Empty command"
        }
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText().trim()
            }
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                "OK:$output"
            } else {
                "ERROR($exitCode):$output"
            }
        } catch (e: Exception) {
            "ERROR:${e.message ?: "Shell execution failed"}"
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
