package app.snapcast.addons.install

/**
 * Recognises add-on packages (.xpi) among navigations and downloads so they are installed instead of
 * being downloaded as files. Pure string logic (no android.net.Uri) so it is JVM-testable.
 */
internal object XpiUrls {
    const val XPI_MIME_TYPE = "application/x-xpinstall"

    private const val XPI_SUFFIX = ".xpi"
    private const val AMO_HOST = "addons.mozilla.org"
    private const val CONTENT_TYPE = "Content-Type"

    /**
     * addons.mozilla.org download endpoints, optionally behind a locale segment:
     * `/firefox/downloads/latest/<slug>/...` and `/firefox/downloads/file/<id>/...` (also `/android/...`).
     */
    private val AMO_DOWNLOAD_PATH = Regex(
        "^(?:/[a-z]{2,3}(?:-[a-z0-9]{2,8})?)?/(?:firefox|android)/downloads/(?:file|latest)/[^/].*",
        RegexOption.IGNORE_CASE,
    )

    /** True for http(s) URLs whose path ends with ".xpi" or that point at an AMO download endpoint. */
    fun isInstallUrl(url: String): Boolean {
        val parts = parse(url) ?: return false
        if (parts.path.endsWith(XPI_SUFFIX, ignoreCase = true)) return true
        return parts.host == AMO_HOST && AMO_DOWNLOAD_PATH.matches(parts.path)
    }

    /** True when a response is an add-on package, by Content-Type or by URL. Header names are case-insensitive. */
    fun isXpiResponse(url: String, headers: Map<String, String>): Boolean =
        isXpiMimeType(contentTypeOf(headers)) || isInstallUrl(url)

    /** True for `application/x-xpinstall`, ignoring case and parameters such as charset. */
    fun isXpiMimeType(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.equals(XPI_MIME_TYPE, ignoreCase = true) == true

    private fun contentTypeOf(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals(CONTENT_TYPE, ignoreCase = true) }?.value

    private class Parts(val host: String, val path: String)

    private fun parse(url: String): Parts? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd)
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
        val rest = trimmed.substring(schemeEnd + 3).substringBefore('#').substringBefore('?')
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "/" else rest.substring(slash)
        val host = hostOf(authority) ?: return null
        return Parts(host, path)
    }

    private fun hostOf(authority: String): String? {
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith('[')) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
    }
}
