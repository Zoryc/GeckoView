package app.snapcast.core.browser

import app.snapcast.core.model.SearchEngine

/** Turns what the user typed in the address bar into a URL to load. */
object UrlInputResolver {
    /**
     * - Full URLs with a scheme (http, https, about, moz-extension, data, file, view-source, resource) pass through.
     * - Host-like input ("example.com", "example.com/path", "localhost:8080", "192.168.1.10", "[::1]") gets "https://"
     *   (or "http://" for localhost / private IPv4 addresses).
     * - Anything else (contains spaces, no dot, etc.) becomes a search with [engine], query URL-encoded.
     */
    fun resolve(input: String, engine: SearchEngine): String = TODO()

    fun searchUrl(query: String, engine: SearchEngine): String = TODO()

    fun looksLikeUrl(input: String): Boolean = TODO()

    /** Text to show in the address bar for [url] (strips "https://", keeps search terms readable). */
    fun displayText(url: String): String = TODO()
}
