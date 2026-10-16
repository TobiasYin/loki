package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** State pushed to the execution document is only an invalidation/stop notice.
 * Full chat state remains owned by ProductHost and the independent chat bridge;
 * execution obtains task context through its normal authorized RPCs.
 * Keep every session/active Run so concurrent executions see their own epochs
 * and stop notices. Never serialize or traverse messages/history/attachments. */
internal object ExecutionStateProjection {
    fun project(state:JSONObject):JSONObject {
        val sessions=JSONArray()
        state.optJSONArray("sessions")?.let {rows->for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            sessions.put(JSONObject().put("id",row.getString("id")).put("capabilityEpoch",row.getLong("capabilityEpoch")))
        }}
        // An explicitly empty active list is authoritative. Only legacy states
        // without this field may use their runs list; never resurrect history.
        val source=if(state.has("activeRuns"))state.optJSONArray("activeRuns") else state.optJSONArray("runs")
        val runs=JSONArray()
        source?.let {rows->for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            val result=JSONObject().put("id",row.getString("id"))
            for(key in listOf("sessionId","status","stopReason"))if(row.has(key)) {
                if(row.isNull(key))result.put(key,JSONObject.NULL) else result.put(key,row.getString(key))
            }
            runs.put(result)
        }}
        return JSONObject().put("sessions",sessions).put("activeRuns",runs)
    }
}
