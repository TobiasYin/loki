package dev.thor.agent

import dev.thor.tools.Code
import org.junit.Assert.*
import org.junit.Test

class ChatNoticePolicyTest {
    @Test fun allToolPrepareFailuresStayInTheirReceiptsAndPreserveUnrelatedPageErrors() {
        for(code in Code.entries) {
            assertNull(code.name,ChatNoticePolicy.rpcFailure(null,"tool.prepare",code.name,false,true))
            assertEquals(code.name,"existing page error",ChatNoticePolicy.rpcFailure("existing page error","tool.prepare",code.name,false,true))
        }
    }

    @Test fun aRealStorageFailureStillSurfacesEvenDuringAToolOrCancellation() {
        for(operation in listOf("tool.prepare","tool.cancel","authority","begin","persist")) {
            val message=ChatNoticePolicy.rpcFailure(null,operation,"CANCELLED",true,false)
            assertTrue(operation,message!!.contains("存储"));assertTrue(message.contains("历史已保留"))
        }
    }

    @Test fun expectedCancellationDoesNotLeakButLaunchAndOtherRpcFailuresRemainActionable() {
        for(operation in listOf("tool.cancel","authority"))for(code in listOf("CANCELLED","STALE_AUTHORIZATION"))
            assertNull(ChatNoticePolicy.rpcFailure(null,operation,code,false,true))
        assertTrue(ChatNoticePolicy.rpcFailure(null,"begin","INVALID_ARGUMENT",false,false)!!.contains("模型未配置"))
        assertTrue(ChatNoticePolicy.rpcFailure(null,"begin","INVALID_ARGUMENT",false,true)!!.contains("任务暂未启动"))
        assertNotNull(ChatNoticePolicy.rpcFailure(null,"persist","IO_ERROR",false,true))
        assertNotNull(ChatNoticePolicy.rpcFailure(null,"authority","PERMISSION_MISSING",false,true))
    }
}
