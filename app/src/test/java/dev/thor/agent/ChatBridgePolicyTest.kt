package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class ChatBridgePolicyTest {
    @Test fun onlyExactMainDocumentInstanceAndCurrentGenerationCanSend() {
        fun accepts(origin:String=BridgePolicy.ORIGIN,main:Boolean=true,instance:Boolean=true,url:String=BridgePolicy.PAGE,
            bytes:Int=100,generation:String="current",ready:Boolean=true)=BridgePolicy.acceptsChat(origin,main,instance,url,bytes,"current",generation,ready)
        assertTrue(accepts())
        assertFalse(accepts(origin="https://appassets.androidplatform.net.evil.invalid"));assertFalse(accepts(main=false))
        assertFalse(accepts(instance=false));assertFalse(accepts(url=BridgePolicy.RUNTIME_PAGE));assertFalse(accepts(url=BridgePolicy.PAGE+"#x"))
        assertFalse(accepts(generation="previous"));assertFalse(accepts(ready=false));assertFalse(accepts(bytes=0))
        assertTrue(accepts(bytes=BridgePolicy.UI_MAX_BYTES));assertFalse(accepts(bytes=BridgePolicy.UI_MAX_BYTES+1))
        assertFalse(BridgePolicy.acceptsChat(BridgePolicy.ORIGIN,true,true,BridgePolicy.PAGE,10,"","",true))
    }
    @Test fun longChineseAndEmojiPromptsFitByteBudgetWithoutExpandingExecutionAuthority() {
        val text="中文😀".repeat(5000) // 20,000 UTF-16 code units; 50,000 UTF-8 bytes.
        assertEquals(20000,text.length)
        val command=mapOf("op" to "send","sessionId" to "chat","text" to text,"clientSendId" to "send-1")
        assertTrue(BridgePolicy.acceptsCommand(command));assertTrue(text.toByteArray().size<BridgePolicy.UI_MAX_BYTES)
        assertFalse(BridgePolicy.acceptsCommand(command+mapOf("text" to text+"x")))
        for(key in listOf("lease","connectionId","url","headers","toolId","arguments","eval","uri","bytes"))
            assertFalse("Extra authority field $key",BridgePolicy.acceptsCommand(command+mapOf(key to "forbidden")))
    }
    @Test fun fixedUiCommandsRetainTypesAndRequiredIdentifiers() {
        val valid=listOf(mapOf("op" to "ready"),mapOf("op" to "new"),mapOf("op" to "stop","runId" to "run"),
            mapOf("op" to "stopPreparing","sessionId" to "chat"),mapOf("op" to "voice.start","id" to "voice","sessionId" to "chat"),
            mapOf("op" to "capability","sessionId" to "chat","capability" to "perception","enabled" to true,"clientMutationId" to "change"),
            mapOf("op" to "sessionOptions","sessionId" to "chat","selectedSkills" to listOf("攻略")),
            mapOf("op" to "approve","runId" to "run","approvalId" to "one-approval"))
        valid.forEach {assertTrue(it.toString(),BridgePolicy.acceptsCommand(it))}
        assertFalse(BridgePolicy.acceptsCommand(mapOf("op" to "stop")))
        assertFalse(BridgePolicy.acceptsCommand(mapOf("op" to "sessionOptions","sessionId" to "chat")))
        assertFalse(BridgePolicy.acceptsCommand(valid[5]+mapOf("enabled" to "true")))
        assertFalse(BridgePolicy.acceptsCommand(valid[5]+mapOf("capability" to "http")))
        assertFalse(BridgePolicy.acceptsCommand(valid[6]+mapOf("selectedSkills" to listOf(mapOf("toolId" to "input.tap")))))
        for(op in listOf("host.rpc","request.start","tool.execute","begin","runPreparedNative","runtime.work.begin","runtime.ui.event"))
            assertFalse(op,BridgePolicy.acceptsCommand(mapOf("op" to op)))
        assertFalse(BridgePolicy.acceptsCommand(mapOf("op" to "seed")));assertTrue(BridgePolicy.acceptsCommand(mapOf("op" to "seed"),true))
    }
    @Test fun runtimeToChatAllowsOnlyBoundedControlMetadataNeverTranscriptOrCallbackNames() {
        assertTrue(RuntimeUiEventPolicy.accepts("ready",emptyMap(),40))
        val prep=mapOf("sessionId" to "chat","phase" to "probing","elapsedMs" to 1200L)
        assertTrue(RuntimeUiEventPolicy.accepts("preparationState",prep,120));assertFalse(RuntimeUiEventPolicy.accepts("preparationState",prep+mapOf("elapsedMs" to -1),120))
        assertFalse(RuntimeUiEventPolicy.accepts("preparationState",prep+mapOf("elapsedMs" to 1.5),120))
        assertFalse(RuntimeUiEventPolicy.accepts("preparationState",prep+mapOf("text" to "private"),140))
        val fail=mapOf("sessionId" to "chat","clientSendId" to "send-1","errorCode" to "IO_ERROR")
        assertTrue(RuntimeUiEventPolicy.accepts("sendFailure",fail,150))
        assertFalse(RuntimeUiEventPolicy.accepts("sendFailure",fail+mapOf("errorCode" to "upstream response body"),150))
        assertFalse(RuntimeUiEventPolicy.accepts("sendFailure",fail+mapOf("text" to "prompt"),160))
        assertFalse(RuntimeUiEventPolicy.accepts("eval",emptyMap(),30));assertFalse(RuntimeUiEventPolicy.accepts("ready",emptyMap(),2049))
        assertFalse(RuntimeUiEventPolicy.accepts("ready",mapOf("lease" to "secret"),60))
    }
    @Test fun modelProviderChoiceCarriesOnlyAnOptionalBoundedConfigurationId() {
        val legacy=mapOf("op" to "sessionOptions","sessionId" to "chat","modelId" to "deepseek-chat")
        assertTrue(BridgePolicy.acceptsCommand(legacy))
        val choice=legacy+mapOf("providerConfigId" to "provider-one")
        assertTrue(BridgePolicy.acceptsCommand(choice))
        assertTrue(BridgePolicy.acceptsCommand(choice+mapOf("providerConfigId" to "p".repeat(160))))
        for(invalid in listOf("","p".repeat(161),true,17,mapOf("apiKey" to "forbidden")))
            assertFalse(BridgePolicy.acceptsCommand(choice+mapOf("providerConfigId" to invalid)))
        assertFalse(BridgePolicy.acceptsCommand(choice+mapOf("apiKey" to "forbidden")))
        assertFalse(BridgePolicy.acceptsCommand(choice+mapOf("endpoint" to "https://invalid.example")))
    }
    @Test fun historyPagingRequestsOnlyTheSelectedSessionWithoutClientControlledLimitsOrSql() {
        val command=mapOf("op" to "historyMore","sessionId" to "chat")
        assertTrue(BridgePolicy.acceptsCommand(command))
        assertTrue(BridgePolicy.acceptsCommand(command+mapOf("sessionId" to "s".repeat(160))))
        assertFalse(BridgePolicy.acceptsCommand(mapOf("op" to "historyMore")))
        for(value in listOf("","s".repeat(161),true,18))assertFalse(BridgePolicy.acceptsCommand(command+mapOf("sessionId" to value)))
        for(key in listOf("limit","offset","pageLimit","historyPageLimit","cursor","query","sql","includeAll"))
            assertFalse("Paging must retain host-selected budgets",BridgePolicy.acceptsCommand(command+mapOf(key to "arbitrary")))
    }
}
