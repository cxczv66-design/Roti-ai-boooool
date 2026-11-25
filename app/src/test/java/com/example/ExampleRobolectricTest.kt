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
    fun `parse type_and_send command returns TypeAndSend`() {
        val result = BridgeProtocol.parseCommand("""{"action":"type_and_send","text":"Hello entity"}""")
        assertTrue(result.isSuccess)
        val cmd = result.getOrThrow() as BridgeCommand.TypeAndSend
        assertEquals("Hello entity", cmd.text)
    }

    @Test
    fun `parse click command supports Arabic Send`() {
        val result = BridgeProtocol.parseCommand("""{"action":"click","text":"إرسال"}""")
        assertTrue(result.isSuccess)
        val cmd = result.getOrThrow() as BridgeCommand.ClickElement
        assertEquals("إرسال", cmd.text)
    }

    @Test
    fun `screenUpdateJson and responseReadyJson match protocol schema`() {
        val screenJson = JSONObject(BridgeProtocol.screenUpdateJson("Line 1\nLine 2", "com.openai.chatgpt"))
        assertEquals("screen_update", screenJson.getString("action"))
        assertEquals("Line 1\nLine 2", screenJson.getString("text"))

        val readyJson = JSONObject(BridgeProtocol.responseReadyJson("Final answer", "com.openai.chatgpt"))
        assertEquals("response_ready", readyJson.getString("action"))
        assertEquals("Final answer", readyJson.getString("last_message"))
    }
}
