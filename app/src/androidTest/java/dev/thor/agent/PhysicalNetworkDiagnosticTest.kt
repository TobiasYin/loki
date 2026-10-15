package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.TimeUnit

/** Explicit Native connectivity control; never a substitute for product probe success. */
@RunWith(AndroidJUnit4::class)
class PhysicalNetworkDiagnosticTest {
    @Test fun compareSystemAndSingleFamilyRoutesWithoutRequestReplay() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("physicalAcceptance")=="true")
        val prefs=context.getSharedPreferences("profiles",0)
        assertEquals("https://opencode.ai/zen/go/v1",prefs.getString("url",null))
        val addresses=Dns.SYSTEM.lookup("opencode.ai")
        val result=obj("scope" to "Native route diagnostics only; not product model acceptance","dnsFamilies" to JSONArray(addresses.map {if(it is Inet6Address)"IPv6" else "IPv4"}),"legacyDnsPublicChecks" to JSONArray(addresses.map {!it.isLoopbackAddress && !it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.hostAddress!!.startsWith("198.18.") && !it.hostAddress!!.startsWith("198.19.")}),"attempts" to JSONArray())
        val key=CredentialVault(context).readNative("model")!!
        val routes=listOf("system" to addresses,"ipv4" to addresses.filterIsInstance<Inet4Address>(),"ipv6" to addresses.filterIsInstance<Inet6Address>())
        for((label,ips) in routes) {
            if(ips.isEmpty())continue
            val attempt=obj("route" to label,"bodySent" to false)
            val client=OkHttpClient.Builder().dns(object:Dns {override fun lookup(hostname:String)=ips})
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS)
                .eventListener(object:EventListener(){override fun requestBodyEnd(call:Call,byteCount:Long){attempt.put("bodySent",true)}}).build()
            try {
                val body=obj("model" to "deepseek-v4.1-flash","input" to "Reply OK only.","stream" to false,"max_output_tokens" to 64).toString()
                val request=Request.Builder().url("https://opencode.ai/zen/go/v1/responses").header("Authorization","Bearer $key")
                    .header("x-opencode-session",id()).header("Content-Type","application/json").post(body.toRequestBody()).build()
                client.newCall(request).execute().use {attempt.put("httpStatus",it.code)}
            } catch(e:Exception) {attempt.put("exceptionClass",e.javaClass.simpleName).put("causeClass",e.cause?.javaClass?.simpleName)}
            finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
            result.getJSONArray("attempts").put(attempt)
            File(context.filesDir,"physical-network-routes.json").writeText(result.toString(2))
        }
        assertTrue(result.getJSONArray("attempts").length()>0)
    }
}
