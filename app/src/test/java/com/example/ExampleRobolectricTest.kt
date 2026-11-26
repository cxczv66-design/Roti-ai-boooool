package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app_name matches Entity Body`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Entity Body", appName)
    }

    @Test
    fun `parse type_and_send and launch_app commands`() {
        val typeResult = BridgeProtocol.parseCommand("""{"action":"type_and_send","text":"Hello entity"}""")
        assertTrue(typeResult.isSuccess)
        val typeCmd = typeResult.getOrThrow() as BridgeCommand.TypeAndSend
        assertEquals("Hello entity", typeCmd.text)

        val launchResult = BridgeProtocol.parseCommand("""{"action":"launch_app","package":"com.anthropic.claude"}""")
        assertTrue(launchResult.isSuccess)
        val launchCmd = launchResult.getOrThrow() as BridgeCommand.LaunchApp
        assertEquals("com.anthropic.claude", launchCmd.packageName)

        val keyResult = BridgeProtocol.parseCommand("""{"action":"key_event","key":"enter"}""")
        assertTrue(keyResult.isSuccess)
        val keyCmd = keyResult.getOrThrow() as BridgeCommand.KeyEventCommand
        assertEquals("enter", keyCmd.key)
    }

    @Test
    fun `responseReadyJson emits message field`() {
        val readyJson = JSONObject(BridgeProtocol.responseReadyJson("Clean AI reply", "com.openai.chatgpt"))
        assertEquals("response_ready", readyJson.getString("action"))
        assertEquals("Clean AI reply", readyJson.getString("message"))
    }
}
