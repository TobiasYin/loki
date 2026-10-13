package dev.thor.agent

/** Exact origin and main document identity; same-origin child frames do not inherit authority. */
object BridgePolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE = "$ORIGIN/assets/chat/index.html"
    fun accepts(sourceOrigin: String, mainFrame: Boolean, currentUrl: String?, size: Int) =
        mainFrame && sourceOrigin == ORIGIN && currentUrl == PAGE && size in 1..16384
}
