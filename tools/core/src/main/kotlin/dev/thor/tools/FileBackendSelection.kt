package dev.thor.tools

/** Existing relative synthetic-config reads remain compatible. Shared-device
 * absolute paths automatically use the actual shell-UID read-only backend. */
object FileBackendSelection {
    fun choose(args:Map<String,Any?>):String {
        val backend=args["backend"] as? String ?: "auto"
        requireTool(backend in setOf("auto","synthetic","shizuku"),message="Unknown file backend")
        if(backend!="auto")return backend
        val path=args["path"] as? String ?: "."
        val device=listOf("/sdcard","/storage","/mnt/sdcard").any {path==it || path.startsWith("$it/")}
        return if(device)"shizuku" else "synthetic"
    }
}
