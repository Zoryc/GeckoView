package app.snapcast.proxy.rewrite

/** Maps upstream URLs to root-relative proxy paths, so rewritten playlists work whatever host the receiver used. */
internal interface ProxyUrlMapper {
    /** Proxy path serving exactly [absoluteUrl] (an http(s) URL). */
    fun exact(absoluteUrl: String): String

    /**
     * Proxy path prefix, ending with "/", under which a relative reference `rest` is served as
     * [baseDir] resolved with `rest`. [baseDir] is an absolute http(s) URL ending with "/" and without placeholders.
     */
    fun base(baseDir: String): String
}
