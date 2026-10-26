package app.snapcast.addons.install

/** Where an add-on package comes from, which decides how it is handed to GeckoView. */
internal enum class InstallSourceKind {
    /** https (or http) URL, e.g. addons.mozilla.org: GeckoView downloads it. */
    REMOTE,

    /** A file:// URI GeckoView can read directly. */
    FILE,

    /** A content:// URI (file picker): copied into the cache first, then installed from the file. */
    CONTENT,

    /** Anything else (javascript:, data:, ...): rejected. */
    UNSUPPORTED,
}

internal object InstallSources {
    fun kindOf(url: String): InstallSourceKind {
        val trimmed = url.trim()
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return InstallSourceKind.UNSUPPORTED
        return when (trimmed.substring(0, colon).lowercase()) {
            "https", "http" -> if (trimmed.startsWith("://", colon)) InstallSourceKind.REMOTE else InstallSourceKind.UNSUPPORTED
            "file" -> InstallSourceKind.FILE
            "content" -> InstallSourceKind.CONTENT
            else -> InstallSourceKind.UNSUPPORTED
        }
    }

    /**
     * Sources a web page may make the app install from (a tapped link to a package): https only, since
     * over http anyone on the network could swap the package for another signed add-on.
     */
    fun isAllowedFromWeb(url: String): Boolean =
        kindOf(url) == InstallSourceKind.REMOTE && url.trim().startsWith("https://", ignoreCase = true)
}
