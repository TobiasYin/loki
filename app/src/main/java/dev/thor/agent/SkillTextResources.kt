package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** Typed file entries for model calls; accept the prior map for stored/older Native callers. */
internal fun skillTextResources(args:JSONObject):JSONObject? {
    require(!(args.has("files") && args.has("resources")))
    if(args.has("resources"))return args.getJSONObject("resources")
    if(!args.has("files"))return null
    val files=args.getJSONArray("files");require(files.length()<=127)
    val resources=JSONObject()
    for(entry in files.objects()) {
        require(entry.keys().asSequence().toSet()==setOf("path","content"))
        val path=entry.getString("path");require(!resources.has(path))
        resources.put(path,entry.getString("content"))
    }
    return resources
}
