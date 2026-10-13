package dev.thor.bridge

/** Transport callback metadata, not values provided by JavaScript. */
object BridgePeerPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE = "$ORIGIN/assets/index.html"
    fun accepts(sourceOrigin: String, mainFrame: Boolean, sameWebView: Boolean, currentUrl: String?, expectedPage: String = PAGE): Boolean =
        sourceOrigin == ORIGIN && mainFrame && sameWebView && currentUrl == expectedPage
}
