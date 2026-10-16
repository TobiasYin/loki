package dev.thor.agent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelConnectionTestTest {
    @Test fun currentDraftIsUsedAndRecognizesEachStreamingProtocol() {
        for((protocol,path,chunk) in listOf(
            Triple("openai-responses","/v1/responses","{\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}"),
            Triple("anthropic-messages","/v1/messages","{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}"),
            Triple("openai-completions","/v1/chat/completions","{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}"))) {
            MockWebServer().use {server->
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("event: delta\ndata: $chunk\n\ndata: [DONE]\n\n"));server.start()
                val result=ModelConnectionTest.run(ModelConnectionTest.Draft(server.url("/v1").toString(),protocol,"draft-model","draft-secret","X-Draft-Key","Token {{credential}}"))
                assertTrue(result.message,result.success)
                val request=server.takeRequest()
                assertEquals(path,request.path);assertEquals("Token draft-secret",request.getHeader("X-Draft-Key"));assertNull(request.getHeader("Authorization"))
                assertEquals("draft-model",JSONObject(request.body.readUtf8()).getString("model"))
                assertFalse(result.message.contains("draft-secret"));assertTrue(result.message.contains("图片和工具"))
            }
        }
    }
    @Test fun rejectsRedirectsAndDoesNotLeakProviderErrorOrCredentials() {
        MockWebServer().use {source->MockWebServer().use {destination->
            source.start();destination.start()
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location",destination.url("/steal")))
            val draft=ModelConnectionTest.Draft(source.url("/v1").toString(),"openai-responses","draft-model","secret")
            val redirect=ModelConnectionTest.run(draft)
            assertFalse(redirect.success);assertTrue(redirect.message.contains("重定向"));assertEquals(0,destination.requestCount)
            source.enqueue(MockResponse().setResponseCode(401).setBody("secret provider diagnostic"))
            val denied=ModelConnectionTest.run(draft);assertFalse(denied.success);assertTrue(denied.message.contains("401"));assertFalse(denied.message.contains("secret"))
        }}
    }
    @Test fun thinkingOrToolDeltasDoNotPretendTextStreamingPassed() {
        assertFalse(ModelConnectionTest.hasText("openai-responses",JSONObject("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"thought\"}")))
        assertFalse(ModelConnectionTest.hasText("anthropic-messages",JSONObject("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}")))
    }
    @Test fun invalidDraftCannotSendCredentials() {
        for(draft in listOf(
            ModelConnectionTest.Draft("https://user:password@example.com/v1","openai-responses","model","secret"),
            ModelConnectionTest.Draft("https://example.com/v1?token=secret","openai-responses","model","secret"),
            ModelConnectionTest.Draft("https://example.com/v1","openai-responses","model","secret","Host","{{credential}}"),
            ModelConnectionTest.Draft("https://example.com/v1","openai-responses","model","secret","Authorization","Bearer {{credential}}\r\nInjected: yes"))) {
            assertTrue(runCatching {draft.validate()}.isFailure)
        }
    }
    @Test fun lowEffortIsSentOnlyForTheMatchingSupportedModelProfile() {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("event: response.output_text.delta\ndata: {\ndata: \"delta\": \"OK\"}\n\n"));server.start()
            val draft=ModelConnectionTest.Draft(server.url("/v1").toString(),"openai-responses","deepseek-v4.1-flash","secret")
            val profile=JSONObject("{\"id\":\"deepseek-v4.1-flash\",\"protocol\":\"openai-responses\",\"reasoning\":true,\"efforts\":[\"low\",\"high\",\"max\"]}")
            val configured=draft.withProfile(profile)
            assertEquals("low",configured.reasoningEffort)
            assertTrue(ModelConnectionTest.run(configured).success)
            val body=JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("low",body.getJSONObject("reasoning").getString("effort"))
            assertEquals("auto",body.getJSONObject("reasoning").getString("summary"))
            assertEquals("input_text",body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
            assertNull(draft.withProfile(JSONObject(profile.toString()).put("id","another-model")).reasoningEffort)
            assertNull(draft.withProfile(JSONObject(profile.toString()).put("efforts",org.json.JSONArray(listOf("high")))).reasoningEffort)
        }
    }
    @Test fun nullableErrorIsNotFailureAndFailedResponseIsNotTextSuccess() {
        assertFalse(ModelConnectionTest.isFailure(JSONObject("{\"type\":\"response.output_text.delta\",\"error\":null,\"delta\":\"OK\"}")))
        assertTrue(ModelConnectionTest.isFailure(JSONObject("{\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"private diagnostic\"}}}")))
        val frames=ModelConnectionTest.SseFrames()
        assertNull(frames.accept(": keepalive"));assertNull(frames.accept("event: response.output_text.delta"))
        assertNull(frames.accept("data: {\"delta\":\"OK\"}"))
        val event=frames.accept("")!!
        assertEquals("response.output_text.delta",event.getString("type"));assertTrue(ModelConnectionTest.hasText("openai-responses",event))
    }

    @Test fun responsesRequestMatchesPiStoreReasoningAndExactGoAffinity() {
        val draft=ModelConnectionTest.Draft("https://opencode.ai/zen/go/v1","openai-responses","deepseek-v4.1-flash","fake-secret",reasoningEffort="low")
        val first=ModelConnectionTest.request(draft);val second=ModelConnectionTest.request(draft)
        assertEquals("https://opencode.ai/zen/go/v1/responses",first.url.toString())
        val session=first.header("x-opencode-session")!!
        assertEquals(session,java.util.UUID.fromString(session).toString())
        assertNotEquals(session,second.header("x-opencode-session"))
        val buffer=okio.Buffer();first.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
        assertFalse(body.getBoolean("store"));assertEquals(2048,body.getInt("max_output_tokens"))
        assertEquals("reasoning.encrypted_content",body.getJSONArray("include").getString(0))
        assertEquals("low",body.getJSONObject("reasoning").getString("effort"))
        for(url in listOf("http://opencode.ai/zen/go/v1","https://opencode.ai.evil.test/zen/go/v1","https://opencode.ai/other/v1","https://opencode.ai:8443/zen/go/v1"))
            assertNull(ModelConnectionTest.request(draft.copy(baseUrl=url)).header("x-opencode-session"))
    }
    @Test fun badRequestDiagnosticOnlyReturnsRedactedWhitelistedFields() {
        MockWebServer().use {server->
            server.start();val secret="private_fake+test_key_1234567890"
            val encoded=java.net.URLEncoder.encode(secret,"UTF-8")
            val base64=java.util.Base64.getEncoder().encodeToString(secret.toByteArray())
            val message="Missing x-opencode-session; reflected Bearer $secret / $encoded / $base64"
            val response=JSONObject().put("error",JSONObject().put("code","invalid_request_error").put("type","bad_request").put("param","x-opencode-session").put("message",message).put("headers",secret)).put("rawEcho","DO_NOT_SHOW")
            server.enqueue(MockResponse().setResponseCode(400).setBody(response.toString()))
            val result=ModelConnectionTest.run(ModelConnectionTest.Draft(server.url("/v1").toString(),"openai-responses","draft-model",secret))
            assertFalse(result.success);assertTrue(result.message.contains("400"));assertTrue(result.message.contains("x-opencode-session"))
            assertTrue(result.message.contains("invalid_request_error"));assertTrue(result.message.contains("[已隐藏]"))
            for(private in listOf(secret,encoded,base64,"DO_NOT_SHOW","headers="))assertFalse(result.message.contains(private))
            assertTrue(result.message.length<600)
        }
    }

}
