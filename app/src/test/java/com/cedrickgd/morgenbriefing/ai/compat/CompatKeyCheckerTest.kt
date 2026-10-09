package com.cedrickgd.morgenbriefing.ai.compat

import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.KeyCheckOutcome
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.RecordingUsage
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class CompatKeyCheckerTest {
    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val usage = RecordingUsage()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun checker(dialect: CompatDialect) = CompatBackend(http, dialect).keys

    private fun auth(provider: AiProviderId, key: String?, path: String) =
        ProviderAuth(provider, key, baseUrl = server.local(path))

    private val modelList = """{"object":"list","data":[{"id":"llama4:8b","object":"model","owned_by":"library"},{"id":"qwen3:14b","object":"model"},{"id":"llama4:8b"}]}"""

    @Test
    fun `Mistral lists the models without spending tokens`() = runBlocking<Unit> {
        val mistral = checker(CompatDialect.MISTRAL)
        server.enqueue(CompatSse.json(200, """{"object":"list","data":[{"id":"mistral-medium-3-5"}]}"""))
        assertEquals(KeyCheckOutcome(AiKeyCheck.OK), mistral.check(auth(AiProviderId.MISTRAL, CompatSse.MISTRAL_KEY, "/v1"), "mistral-medium-3-5", usage))
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer ${CompatSse.MISTRAL_KEY}", recorded.getHeader("Authorization"))

        listOf(401 to AiKeyCheck.INVALID, 402 to AiKeyCheck.OK_NO_CREDIT, 429 to AiKeyCheck.OK, 500 to AiKeyCheck.NETWORK).forEach { (code, expected) ->
            server.enqueue(CompatSse.httpError(code))
            assertEquals("HTTP $code", expected, mistral.check(auth(AiProviderId.MISTRAL, CompatSse.MISTRAL_KEY, "/v1"), "m", usage).check)
        }
        assertEquals(AiKeyCheck.INVALID, mistral.check(auth(AiProviderId.MISTRAL, null, "/v1"), "m", usage).check)
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `OpenRouter reads the key's own status`() = runBlocking<Unit> {
        val openRouter = checker(CompatDialect.OPENROUTER)
        server.enqueue(CompatSse.json(200, """{"data":{"label":"sk-or-v1-au7...890","limit":null,"usage":0.12,"is_free_tier":false}}"""))
        assertEquals(AiKeyCheck.OK, openRouter.check(auth(AiProviderId.OPENROUTER, CompatSse.OPENROUTER_KEY, "/api/v1"), "m", usage).check)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/key", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer ${CompatSse.OPENROUTER_KEY}", recorded.getHeader("Authorization"))
        assertEquals("Morgenbriefing", recorded.getHeader("X-OpenRouter-Title"))
        server.enqueue(CompatSse.httpError(401))
        assertEquals(AiKeyCheck.INVALID, openRouter.check(auth(AiProviderId.OPENROUTER, CompatSse.OPENROUTER_KEY, "/api/v1"), "m", usage).check)
    }

    @Test
    fun `DeepSeek reads its balance`() = runBlocking<Unit> {
        val deepSeek = checker(CompatDialect.DEEPSEEK)
        fun balance(available: Boolean) = CompatSse.json(
            200,
            """{"is_available":$available,"balance_infos":[{"currency":"USD","total_balance":"${if (available) "4.20" else "0.00"}","granted_balance":"0.00","topped_up_balance":"0.00"}]}""",
        )
        server.enqueue(balance(true))
        assertEquals(AiKeyCheck.OK, deepSeek.check(auth(AiProviderId.DEEPSEEK, CompatSse.DEEPSEEK_KEY, "/"), "deepseek-flash", usage).check)
        assertEquals("/user/balance", server.takeRequest().requestUrl!!.encodedPath)
        server.enqueue(balance(false))
        assertEquals(AiKeyCheck.OK_NO_CREDIT, deepSeek.check(auth(AiProviderId.DEEPSEEK, CompatSse.DEEPSEEK_KEY, "/"), "deepseek-flash", usage).check)
        server.enqueue(CompatSse.httpError(401))
        assertEquals(AiKeyCheck.INVALID, deepSeek.check(auth(AiProviderId.DEEPSEEK, CompatSse.DEEPSEEK_KEY, "/"), "deepseek-flash", usage).check)
    }

    @Test
    fun `the own server is a connection test that finds its models`() = runBlocking<Unit> {
        val custom = checker(CompatDialect.CUSTOM)
        server.enqueue(CompatSse.json(200, modelList))
        // Without a path the address gets /v1 (Ollama, LM Studio); without a key no Authorization.
        val outcome = custom.check(auth(AiProviderId.CUSTOM, null, "/"), "", usage)
        assertEquals(KeyCheckOutcome(AiKeyCheck.OK, listOf("llama4:8b", "qwen3:14b")), outcome)
        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.requestUrl!!.encodedPath)
        assertNull(recorded.getHeader("Authorization"))

        server.enqueue(CompatSse.json(200, """{"object":"list","data":[]}"""))
        assertEquals(KeyCheckOutcome(AiKeyCheck.OK), custom.check(auth(AiProviderId.CUSTOM, CompatSse.SERVER_KEY, "/lmstudio/v1"), "", usage))
        val withKey = server.takeRequest()
        assertEquals("/lmstudio/v1/models", withKey.requestUrl!!.encodedPath)
        assertEquals("Bearer ${CompatSse.SERVER_KEY}", withKey.getHeader("Authorization"))

        listOf(
            CompatSse.httpError(401) to AiKeyCheck.INVALID,
            CompatSse.httpError(403) to AiKeyCheck.INVALID,
            CompatSse.json(404, "404 page not found") to AiKeyCheck.NOT_AN_AI_SERVER,
            CompatSse.json(200, "<html><body>FRITZ!Box</body></html>") to AiKeyCheck.NOT_AN_AI_SERVER,
            CompatSse.json(200, """{"models":[]}""") to AiKeyCheck.NOT_AN_AI_SERVER,
            CompatSse.httpError(502) to AiKeyCheck.SERVER_UNREACHABLE,
        ).forEach { (response, expected) ->
            server.enqueue(response)
            assertEquals(expected, custom.check(auth(AiProviderId.CUSTOM, CompatSse.SERVER_KEY, "/v1"), "", usage).check)
        }
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `an own server that cannot be reached as typed`() = runBlocking<Unit> {
        val custom = checker(CompatDialect.CUSTOM)
        // Nothing listens on port 1.
        assertEquals(AiKeyCheck.SERVER_UNREACHABLE, custom.check(ProviderAuth(AiProviderId.CUSTOM, null, baseUrl = "http://localhost:1/v1"), "", usage).check)
        // A raw LAN IP over http is refused by Android's network security config: said before trying.
        assertEquals(AiKeyCheck.CLEARTEXT_BLOCKED, custom.check(ProviderAuth(AiProviderId.CUSTOM, null, baseUrl = "http://192.168.178.20:11434"), "", usage).check)
        assertEquals(AiKeyCheck.SERVER_UNREACHABLE, custom.check(ProviderAuth(AiProviderId.CUSTOM, null, baseUrl = null), "", usage).check)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `https to a server without TLS is a certificate problem, not a dead server`() = runBlocking<Unit> {
        // Plain Ollama behind an https:// address (the key tip sends IP users to https://): it
        // answers the TLS handshake with plain HTTP, the handshake fails.
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { plain ->
            thread(isDaemon = true) {
                while (!plain.isClosed) {
                    runCatching {
                        plain.accept().use { socket ->
                            socket.getInputStream().read(ByteArray(4096))
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                flush()
                            }
                            // Until the client gives up, so the answer is not lost to a reset.
                            socket.getInputStream().read(ByteArray(4096))
                        }
                    }
                }
            }
            val auth = ProviderAuth(AiProviderId.CUSTOM, null, baseUrl = "https://127.0.0.1:${plain.localPort}/v1")
            assertEquals(AiKeyCheck.SERVER_CERTIFICATE, checker(CompatDialect.CUSTOM).check(auth, "", usage).check)
        }
    }

    @Test
    fun `the model picker loads the server's models`() = runBlocking<Unit> {
        server.enqueue(CompatSse.json(200, modelList))
        assertEquals(listOf("llama4:8b", "qwen3:14b"), CompatModels.list(http, server.local("/v1"), " "))
        assertNull(server.takeRequest().getHeader("Authorization"))
        server.enqueue(CompatSse.httpError(401))
        assertEquals(emptyList<String>(), CompatModels.list(http, server.local("/v1"), CompatSse.SERVER_KEY))
        assertEquals(emptyList<String>(), CompatModels.list(http, "http://localhost:1/v1", null))
    }
}
