package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import dev.thor.tools.ToolPack

/** Upgrade old failed turns that saved only the user input. Observations are
 * reconstructed from committed Native events; pending operations are never run.
 */
internal fun recoverTurnHistory(turn:JSONObject,events:List<JSONObject>):JSONObject {
    if(turn.optBoolean("complete") || turn.getJSONArray("messages").objects().any {it.optString("kind")!="user"})return turn
    val copy=JSONObject(turn.toString());val messages=copy.getJSONArray("messages")
    val prior=turn.optJSONArray("source")?:JSONArray()
    val sources=(0 until prior.length()).map {prior.getString(it)}.toMutableSet()
    val text=StringBuilder();var pending:String?=null;var toolName=""
    fun closePending() {
        pending?.let {messages.put(obj("kind" to "tool_result","toolCallId" to it,"toolName" to toolName,"isError" to true,"content" to JSONArray().put(obj("type" to "text","text" to "Previous execution interrupted; outcome unknown. Verify state before repeating side effects."))))};pending=null
    }
    fun flushText() {if(text.isNotEmpty()){closePending();messages.put(obj("kind" to "assistant","text" to text.toString(),"calls" to JSONArray()));text.clear()}}
    for(event in events) {
        val data=event.optJSONObject("data")?:continue
        when(event.optString("type")) {
            "text_delta" -> text.append(data.optString("text"))
            "tool_started" -> {
                flushText();closePending();pending=event.getString("toolCallId");toolName=data.getString("toolId").replace('.','_')
                val tool=data.getString("toolId")
                if(tool in setOf("web.search","web.fetch"))sources.add("web_search")
                ToolPack.descriptors[tool]?.let {sources.add(it.capability.name.lowercase())}
                messages.put(obj("kind" to "assistant","text" to "","calls" to JSONArray().put(obj("id" to pending,"name" to toolName,"arguments" to data.getJSONObject("arguments")))))
            }
            "tool_result" -> if(event.optString("toolCallId")==pending) {
                val content=data.optJSONArray("content") ?: JSONArray().put(obj("type" to "text","text" to data.optString("error","Result unavailable; outcome unknown")))
                messages.put(obj("kind" to "tool_result","toolCallId" to pending,"toolName" to toolName,"isError" to !data.isNull("error"),"content" to content));pending=null
            }
        }
    }
    flushText();closePending();copy.put("source",JSONArray(sources.toList()));return copy
}
