package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLogicTest {

    @Test
    fun `dollar amounts the german way`() {
        assertEquals("0 $", usd(0.0))
        assertEquals("0 $", usd(-1.0))
        assertEquals("0 $", usd(Double.NaN))
        assertEquals("< 0,01 $", usd(0.003))
        assertEquals("0,84 $", usd(0.84))
        assertEquals("5 $", usd(5.0))
        assertEquals("12,50 $", usd(12.5))
        assertEquals("1 $", usd(0.999))
    }

    @Test
    fun `usage line with and without a cap`() {
        assertEquals(
            "Diesen Monat: 0,84 $ von 5 $ · 23 Anfragen · 7 Websuchen",
            usageLine(spent = 0.84, limit = 5.0, requests = 23, webSearches = 7),
        )
        assertEquals("Diesen Monat: 0 $ · 1 Anfrage · 1 Websuche", usageLine(0.0, 0.0, 1, 1))
        assertEquals("Diesen Monat: 2,10 $ · 0 Anfragen · 0 Websuchen", usageLine(2.1, 0.0, 0, -3))
    }

    @Test
    fun `usage fraction is clamped and absent without cap`() {
        assertNull(usageFraction(1.0, 0.0))
        assertEquals(0f, usageFraction(0.0, 5.0)!!, 0f)
        assertEquals(0.2f, usageFraction(1.0, 5.0)!!, 0.0001f)
        assertEquals(1f, usageFraction(9.0, 5.0)!!, 0f)
    }

    @Test
    fun `monthly limit slider`() {
        assertEquals(49, AiLimit.SLIDER_STEPS)
        assertEquals(5.0, AiLimit.normalize(4.6), 0.0)
        assertEquals(50.0, AiLimit.normalize(80.0), 0.0)
        assertEquals(0.0, AiLimit.normalize(-2.0), 0.0)
        assertEquals(0.0, AiLimit.normalize(Double.NaN), 0.0)
        assertEquals("Kein Limit", AiLimit.label(0.0))
        assertEquals("5 $ im Monat", AiLimit.label(5.0))
        assertEquals("50 $ im Monat", AiLimit.label(50.0))
    }

    @Test
    fun `limit hint does not talk about reaching a cap that is off`() {
        assertTrue(AiLimit.hint(0.0).startsWith("Ohne Limit"))
        assertTrue(AiLimit.hint(0.3).startsWith("Ohne Limit"))
        assertTrue(AiLimit.hint(5.0).startsWith("Ist es erreicht"))
    }

    @Test
    fun `key check results`() {
        assertNull(AiKeyCheck.OK.toProblem())
        assertEquals(AiKeyProblem.INVALID, AiKeyCheck.INVALID.toProblem())
        assertEquals(AiKeyProblem.NETWORK, AiKeyCheck.NETWORK.toProblem())
        assertEquals(AiKeyProblem.UNSUPPORTED, AiKeyCheck.UNSUPPORTED.toProblem())
    }

    @Test
    fun `key messages`() {
        assertNull(aiKeyMessage(AiKeyState.Idle))
        assertEquals("✓ Der Key funktioniert und ist gespeichert.", aiKeyMessage(AiKeyState.Saved))
        assertEquals("✗ Keine Verbindung zu Anthropic – bist du online?", aiKeyMessage(AiKeyState.Failed(AiKeyProblem.NETWORK)))
        AiKeyProblem.entries.forEach { problem ->
            assertTrue(problem.name, aiKeyMessage(AiKeyState.Failed(problem))!!.startsWith("✗"))
        }
    }

    @Test
    fun `key format hint`() {
        assertTrue(looksLikeAnthropicKey("  sk-ant-api03-abcdefghijklmnopqrstuvwxyz  "))
        assertFalse(looksLikeAnthropicKey("sk-ant-short"))
        assertFalse(looksLikeAnthropicKey("github_pat_abcdefghijklmnopqrstuvwxyz"))
        assertFalse(looksLikeAnthropicKey("sk-ant-api03 abcdefghijklmnopqrstuvwxyz"))
    }
}
