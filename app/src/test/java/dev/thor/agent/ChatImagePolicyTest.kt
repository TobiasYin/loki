package dev.thor.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ChatImagePolicyTest {
    private val request=ChatImageRequest("s","r","image")
    private fun owner()=obj("id" to "r","sessionId" to "s","probe" to false)
    private fun attachment()=obj("id" to "image","sessionId" to "s","runId" to "r","mimeType" to "image/jpeg","expiresAt" to 2000L,"size" to 128,"source" to JSONArray().put("screen_perception"))
    @Test fun fixedRouteAcceptsOnlyBoundedOpaqueIdentifiers() {
        assertEquals(request,ChatImagePolicy.parse("/chat-images/s/r/image"))
        for(path in listOf("/chat-images/s/r/../secret","/chat-images/s/r/%2Fdata","/chat-images/s/r/file.jpg","/chat-images/s/r/image?path=/data","https://evil.invalid/chat-images/s/r/image","/chat-images/s/r/"+"x".repeat(161)))assertNull(path,ChatImagePolicy.parse(path))
    }
    @Test fun historicalScreenPixelsStayOwnedByTheSelectedSessionAndExactRun() {
        assertTrue(ChatImagePolicy.permits(request,"s",attachment(),owner(),1000))
        assertFalse(ChatImagePolicy.permits(request,"other",attachment(),owner(),1000))
        for(key in listOf("id","sessionId","runId"))assertFalse(key,ChatImagePolicy.permits(request,"s",attachment().put(key,"foreign"),owner(),1000))
        assertFalse(ChatImagePolicy.permits(request,"s",attachment(),owner().put("sessionId","foreign"),1000))
        assertFalse(ChatImagePolicy.permits(request,"s",attachment(),owner().put("probe",true),1000))
    }
    @Test fun missingExpiredOversizedAndNonScreenContentCannotBeServedAsImages() {
        assertFalse(ChatImagePolicy.permits(request,"s",null,owner(),1000))
        assertFalse(ChatImagePolicy.permits(request,"s",attachment(),null,1000))
        for(mime in listOf("text/html","image/svg+xml","application/javascript"))assertFalse(ChatImagePolicy.permits(request,"s",attachment().put("mimeType",mime),owner(),1000))
        assertFalse(ChatImagePolicy.permits(request,"s",attachment().put("expiresAt",999),owner(),1000))
        assertFalse(ChatImagePolicy.permits(request,"s",attachment().put("size",2_097_153),owner(),1000))
        assertFalse(ChatImagePolicy.permits(request,"s",attachment().put("source",JSONArray()),owner(),1000))
        val historical=ScreenshotHistoryPolicy.replay(attachment().put("expiresAt",999),"s"){owner()}
        assertTrue(ChatImagePolicy.permits(request,"s",historical,owner(),1000))
    }
}
