package dev.thor.tools

/** Platform-neutral projection of what Accessibility actually exposed; never discovers hidden data. */
data class UiElement(val path:String,val parentPath:String?,val role:String?,val id:String?,val text:String?,val description:String?,val value:String?,
    val password:Boolean,val clickable:Boolean,val checkable:Boolean,val checked:Boolean,val selected:Boolean,val enabled:Boolean,
    val visible:Boolean,val scrollable:Boolean,val editable:Boolean,val bounds:List<Int>,val actions:List<String>,
    val collection:Map<String,Any?>?=null,val collectionItem:Map<String,Any?>?=null,val range:Map<String,Any?>?=null,
    val textSourceStart:Int=0,val textSourceLength:Int?=null,val descriptionSourceStart:Int=0,val descriptionSourceLength:Int?=null,val valueSourceStart:Int=0,
    // Android selection indexes refer to the original full text in UTF-16 units, never the projected text slice.
    val selectionStart:Int?=null,val selectionEnd:Int?=null,val focused:Boolean=false) {
    fun semantic()=parentPath==null || !id.isNullOrBlank() || (!password && listOf(text,description,value).any {!it.isNullOrBlank()}) || password || clickable || checkable || selected || scrollable || editable || actions.isNotEmpty() || collection!=null || collectionItem!=null || range!=null
    fun safe()=if(password)copy(id=null,text="[password hidden]",description=null,value=null,textSourceStart=0,textSourceLength=null,descriptionSourceStart=0,descriptionSourceLength=null,valueSourceStart=0,selectionStart=null,selectionEnd=null,focused=false) else this
}
data class UiTreePage(val nodes:List<Map<String,Any?>>,val paths:List<String>,val nextOffset:Int?,val matched:Int,val reasons:Set<String>,val visible:Int,val offscreen:Int,val blocked:Boolean=false)
object SemanticTree {
    /** Conservative UTF-8 JSON size, including escaping, without platform JSON dependencies. */
    internal fun jsonBytes(value:Any?):Int=when(value) {
        null->4
        is String->2+value.sumOf {c->when {c=='"' || c=='\\' || c=='/'->2;c.code<32 || c.code>=127->6;else->1}}
        is Number,is Boolean->value.toString().length
        is Map<*,*>->2+value.entries.sumOf {jsonBytes(it.key.toString())+1+jsonBytes(it.value)+1}
        is List<*>->2+value.sumOf {jsonBytes(it)+1}
        else->jsonBytes(value.toString())
    }
    fun project(raw:List<UiElement>,ref:(String)->String,maxNodes:Int=80,maxChars:Int=12000,offset:Int=0,query:String="",visibility:String="all",textOffset:Int=0):UiTreePage {
        requireTool(maxNodes in 1..128 && maxChars in 1..12000 && offset in 0..2048 && query.length<=200 && textOffset in 0..1000000 && visibility in setOf("all","visible","offscreen"),Code.LIMIT_EXCEEDED,"Invalid semantic tree page budget")
        val safe=raw.map {it.safe()};val byPath=safe.associateBy {it.path};val semantic=safe.filter {it.semantic()};val kept=semantic.map {it.path}.toSet()
        fun parent(node:UiElement):UiElement? {var p=node.parentPath;while(p!=null){val n=byPath[p] ?: return null;if(p in kept)return n;p=n.parentPath};return null}
        fun scrollParent(node:UiElement):UiElement? {var p=node.parentPath;while(p!=null){val n=byPath[p] ?: return null;if(n.scrollable || n.actions.any {it.startsWith("scroll_")})return n;p=n.parentPath};return null}
        val matched=semantic.filter {n->(visibility=="all" || n.visible==(visibility=="visible")) && (query.isEmpty() || listOf(n.id,n.text,n.description,n.value).filterNotNull().any {it.contains(query,ignoreCase=true)})}
        val page=mutableListOf<Map<String,Any?>>();val paths=mutableListOf<String>();val reasons=linkedSetOf<String>();var chars=0;var consumed=0;var wireBytes=2
        fun bounded(value:String?):String? {if(value==null)return null;val left=(maxChars-chars).coerceAtLeast(0);val text=value.take(left);chars+=text.length;if(text.length<value.length)reasons.add("maxChars");return text}
        val emitted=mutableSetOf<String>()
        fun add(n:UiElement,matchedHere:Boolean):Boolean {
            if(n.path in emitted)return true
            if(page.size>=maxNodes){reasons.add("maxNodes");return false}
            if(chars>=maxChars){reasons.add("maxChars");return false}
            val p=parent(n);val scroll=scrollParent(n)
            fun slice(value:String?,sourceStart:Int=0):Pair<Int,String?> {
                if(value==null)return 0 to null
                val hit=if(query.isNotEmpty())value.indexOf(query,ignoreCase=true) else -1
                val start=if(hit>=0)(hit-100).coerceAtLeast(0) else (textOffset-sourceStart).coerceIn(0,value.length)
                return (sourceStart+start) to value.substring(start)
            }
            val textSlice=slice(n.text,n.textSourceStart);val descSlice=slice(n.description,n.descriptionSourceStart)
            val role=bounded(n.role);val id=bounded(n.id);val text=bounded(textSlice.second);val desc=bounded(descSlice.second);val value=bounded(slice(n.value,n.valueSourceStart).second)
            val output=linkedMapOf<String,Any?>("ref" to ref(n.path),"nodeRef" to ref(n.path),"parentRef" to p?.let {ref(it.path)},"scrollAncestorRef" to scroll?.let {ref(it.path)},
                "role" to role,"id" to id,"text" to text,"description" to desc,"value" to value,"matchesQuery" to matchedHere,
                "textStart" to textSlice.first,"textLength" to (n.textSourceLength ?: n.text?.length),"textTruncated" to (n.text!=null && (textSlice.first>0 || text?.length!=(n.textSourceLength ?: n.text.length))),
                "descriptionStart" to descSlice.first,"descriptionLength" to (n.descriptionSourceLength ?: n.description?.length),
                "password" to n.password,"clickable" to n.clickable,"checkable" to n.checkable,"checked" to n.checked,"selected" to n.selected,"enabled" to n.enabled,"focused" to n.focused,
                "visibleToUser" to n.visible,"scrollable" to n.scrollable,"editable" to n.editable,"actions" to n.actions,"bounds" to n.bounds,
                "collection" to n.collection,"collectionItem" to n.collectionItem,"range" to n.range)
            if(n.editable && !n.password && n.selectionStart!=null && n.selectionEnd!=null)
                output["textSelection"]=mapOf("start" to n.selectionStart,"end" to n.selectionEnd,"unit" to "utf16")
            val encodedBytes=jsonBytes(output)
            if(wireBytes+encodedBytes>45000){reasons.add("maxOutputBytes");return false}
            wireBytes+=encodedBytes+1;page.add(output)
            paths.add(n.path);emitted.add(n.path);return true
        }
        for(n in matched.drop(offset)) {
            val ancestors=mutableListOf<UiElement>();var p=parent(n)
            while(p!=null){ancestors.add(p);p=parent(p)}
            var contextFits=true
            for(ancestor in ancestors.asReversed())if(!add(ancestor,ancestor in matched)){contextFits=false;break}
            if(!contextFits || !add(n,true)){if(consumed==0 && page.isNotEmpty())reasons.add("ancestorContextBudget");break}
            consumed++
        }
        val blocked=consumed==0 && offset<matched.size
        if(blocked)reasons.add("pageCannotFitContext")
        return UiTreePage(page,paths,if(consumed>0 && offset+consumed<matched.size)offset+consumed else null,matched.size,reasons,semantic.count {it.visible},semantic.count {!it.visible},blocked)
    }
}

/** Ref identity is snapshot/window/package plus exact refreshed node evidence, never its path alone. */
data class UiNodeRefGuard(val snapshotId:String,val windowId:Int,val packageName:String?,val signature:String,val expires:Long) {
    fun check(snapshot:String?,window:Int,pkg:String?,freshSignature:String,now:Long) {
        requireTool(snapshot==snapshotId && now<expires && window==windowId && pkg==packageName && freshSignature==signature,
            Code.TARGET_CHANGED,"Node ref expired or its snapshot/window/app/node changed; read ui_tree again")
    }
}
