package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class SemanticTreeTest {
    private fun node(path:String,parent:String?=null,text:String?=null,id:String?=null,description:String?=null,value:String?=null,visible:Boolean=true,password:Boolean=false,scroll:Boolean=false)=
        UiElement(path,parent,"View",id,text,description,value,password,false,false,false,false,true,visible,scroll,false,listOf(0,if(visible)0 else 2000,100,if(visible)100 else 2100),if(scroll)listOf("scroll_forward","scroll_backward") else emptyList())
    @Test fun removesWrappersButPreservesSemanticParentsAndScrollContainers() {
        val raw=listOf(node("root"),node("wrapper","root"),node("scroll","wrapper",scroll=true),node("wrapper2","scroll"),node("item","wrapper2",text="Target headline"))
        val page=SemanticTree.project(raw,{"ref:$it"},query="headline")
        assertEquals(listOf("root","scroll","item"),page.paths)
        val item=page.nodes.last();assertEquals("ref:scroll",item["parentRef"]);assertEquals("ref:scroll",item["scrollAncestorRef"])
        assertEquals(listOf("scroll_forward","scroll_backward"),page.nodes[1]["actions"])
        assertEquals(true,item["matchesQuery"])
    }
    @Test fun offscreenIsReadEvidenceAndPasswordCannotBeSearchedThroughAnyField() {
        val raw=listOf(node("root"),node("off","root",text="exposed offscreen",visible=false),node("secret","root",text="private-text",id="private-id",description="private-desc",value="private-value",password=true))
        val all=SemanticTree.project(raw,{it});assertEquals(1,all.offscreen)
        val off=SemanticTree.project(raw,{it},visibility="offscreen").nodes.last()
        assertEquals("exposed offscreen",off["text"]);assertEquals(false,off["visibleToUser"])
        for(query in listOf("private-text","private-id","private-desc","private-value"))assertTrue(SemanticTree.project(raw,{it},query=query).nodes.isEmpty())
        val secret=all.nodes.last();assertEquals("[password hidden]",secret["text"]);assertNull(secret["id"]);assertNull(secret["description"]);assertNull(secret["value"])
    }
    @Test fun longTextSearchFindsTailBeforeClippingAndTextOffsetCanReadLater() {
        val text="prefix ".repeat(5000)+"TAIL-CODE-7429"
        val raw=listOf(node("root"),node("article","root",text=text))
        val found=SemanticTree.project(raw,{it},maxChars=500,query="TAIL-CODE").nodes.last()
        assertTrue((found["text"] as String).contains("TAIL-CODE-7429"));assertTrue((found["textStart"] as Int)>30000);assertEquals(text.length,found["textLength"]);assertEquals(true,found["textTruncated"])
        val later=SemanticTree.project(raw,{it},maxChars=300,textOffset=5000).nodes.last()
        assertEquals(5000,later["textStart"]);assertTrue((later["text"] as String).startsWith(text.substring(5000).take(100)))
    }
    @Test fun paginationReportsNodeCharacterAndTotalWireByteLimits() {
        val nodes=listOf(node("root"))+(0 until 200).map {node("n$it","root",text="item-$it")}
        val first=SemanticTree.project(nodes,{it},maxNodes=4);assertEquals(4,first.nodes.size);assertEquals(4,first.nextOffset);assertTrue("maxNodes" in first.reasons)
        val second=SemanticTree.project(nodes,{it},maxNodes=4,offset=first.nextOffset!!);assertEquals("item-3",second.nodes[1]["text"])
        val chars=SemanticTree.project(listOf(node("root",text="x".repeat(200))),{it},maxChars=20);assertTrue("maxChars" in chars.reasons)
        val wire=SemanticTree.project(nodes,{"ref-"+"q".repeat(300)+it},maxNodes=128)
        assertTrue("maxOutputBytes" in wire.reasons);assertTrue(SemanticTree.jsonBytes(wire.nodes)<=45000);assertNotNull(wire.nextOffset)
        assertTrue(SemanticTree.jsonBytes("😃\u2028/\n\"")>=30) // Conservative even for escaped Unicode and surrogate pairs.
    }
    @Test fun tooSmallAncestorBudgetStopsPagingInsteadOfReturningSameOffsetForever() {
        val raw=listOf(node("root"),node("scroll","root",scroll=true),node("leaf","scroll",text="Target"))
        for(size in listOf(1,2)) {
            val blocked=SemanticTree.project(raw,{it},maxNodes=size,query="Target")
            assertTrue(blocked.blocked);assertNull(blocked.nextOffset);assertTrue("pageCannotFitContext" in blocked.reasons)
        }
        val full=SemanticTree.project(raw,{it},maxNodes=3,query="Target")
        assertFalse(full.blocked);assertEquals("Target",full.nodes.last()["text"])
    }
    @Test fun platformTextWindowKeepsOriginalOffsetAndLengthForVeryLongExposedText() {
        val chunk=node("article","root",text="context TAIL-7788").copy(textSourceStart=350000,textSourceLength=500000)
        val page=SemanticTree.project(listOf(node("root"),chunk),{it},query="TAIL-7788")
        assertEquals(350000,page.nodes.last()["textStart"]);assertEquals(500000,page.nodes.last()["textLength"])
        val offset=SemanticTree.project(listOf(node("root"),chunk),{it},textOffset=350008)
        assertEquals(350008,offset.nodes.last()["textStart"]);assertTrue((offset.nodes.last()["text"] as String).startsWith("TAIL"))
    }
    @Test fun editableEmojiSelectionKeepsOriginalUtf16OffsetsEvenWhenTextIsSliced() {
        val text="a🙂中文b"
        val field=node("field","root",text=text).copy(editable=true,selectionStart=1,selectionEnd=5)
        val page=SemanticTree.project(listOf(node("root"),field),{it},textOffset=3)
        val projected=page.nodes.last()
        assertEquals("中文b",projected["text"]);assertEquals(3,projected["textStart"])
        assertEquals(mapOf("start" to 1,"end" to 5,"unit" to "utf16"),projected["textSelection"])
        // The emoji occupies UTF-16 positions 1..3, but only one Unicode code point.
        assertEquals(6,text.length);assertEquals(5,text.codePointCount(0,text.length))
    }
    @Test fun passwordsAndNonEditableNodesNeverExposeSelectionMetadata() {
        val secret=node("secret","root",text="hidden🙂",password=true).copy(editable=true,selectionStart=0,selectionEnd=8)
        val label=node("label","root",text="selectable text").copy(selectionStart=0,selectionEnd=15)
        val unknown=node("field","root",text="field").copy(editable=true,selectionStart=-1,selectionEnd=null)
        val page=SemanticTree.project(listOf(node("root"),secret,label,unknown),{it})
        assertTrue(page.nodes.none {it.containsKey("textSelection")})
        assertNull(secret.safe().selectionStart);assertNull(secret.safe().selectionEnd)
        assertEquals("[password hidden]",page.nodes.single {it["nodeRef"]=="secret"}["text"])
    }
    @Test fun actualInputFocusIsVisibleWithoutExposingPasswordFocus() {
        val focused=node("field","root",text="typing").copy(editable=true,focused=true)
        val other=node("other","root",text="other").copy(editable=true)
        val secret=node("secret","root",text="secret",password=true).copy(editable=true,focused=true)
        val page=SemanticTree.project(listOf(node("root"),focused,other,secret),{it})
        assertEquals(true,page.nodes.single {it["nodeRef"]=="field"}["focused"])
        assertEquals(false,page.nodes.single {it["nodeRef"]=="other"}["focused"])
        assertEquals(false,page.nodes.single {it["nodeRef"]=="secret"}["focused"])
    }
    @Test fun refsRejectSamePathWithWrongSnapshotWindowPackageOrRefreshedIdentityAndExpiry() {
        val ref=UiNodeRefGuard("snapshot",7,"app","same-node",100)
        ref.check("snapshot",7,"app","same-node",99)
        for(check in listOf<()->Unit>({ref.check("other",7,"app","same-node",1)},{ref.check("snapshot",8,"app","same-node",1)},{ref.check("snapshot",7,"other","same-node",1)},{ref.check("snapshot",7,"app","recycled-list-row",1)},{ref.check("snapshot",7,"app","same-node",100)})) {
            try {check();fail("Stale ref accepted")}catch(e:ToolFailure){assertEquals(Code.TARGET_CHANGED,e.code)}
        }
    }
    @Test fun scrollOwnContentUpdateMayFinishButOldObservationCannotAuthorizeAnotherInput() {
        var now=1L;var target=Target("app","valid","v1",layoutRevision="layout")
        val clock=Clock {now};val snapshots=Snapshots(clock){target};snapshots.update(listOf(DisplayFact(0,"upper",100,100,0,160),DisplayFact(4,"lower",100,100,0,160)));snapshots.calibrate(0,4);val observation=snapshots.capture()
        val backend=object:DeviceBackend {
            override fun availability(toolId:String)=Availability(Status.READY,"test","test")
            override fun release(runId:String)="none"
            override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                checkpoint.effect {target=target.copy(revision="own-scroll-content-update",interactionRevision="scrolled-list-layout")}
                checkpoint.check();snapshots.validateHeld(envelope.snapshotId)
                return ToolResult(now,"test",availability(envelope.toolId),snapshotId=envelope.snapshotId)
            }
        }
        val auth=ManualTestAuthority(clock);auth.set(Capability.SCREEN_CONTROL,true);val lease=auth.begin(backend);val gateway=Gateway(auth,backend,snapshots,clock)
        fun request(id:String)=Envelope(lease.session,lease.run,id,"input.scroll",lease.epoch,lease.deadline,observation.id)
        assertNull(gateway.execute(request("first"),emptyMap()).error)
        assertEquals(Code.TARGET_CHANGED,gateway.execute(request("second"),emptyMap()).error)
        try {Gesture(listOf(Point(10.0,2000.0),Point(10.0,10.0)),500).validate(snapshots.selected());fail("Offscreen read evidence must not authorize coordinates")}catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
    }
}
