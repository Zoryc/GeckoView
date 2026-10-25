package app.snapcast.proxy.server

import fi.iki.elonen.NanoHTTPD

/**
 * NanoHTTPD front end: one daemon thread per connection, every request handed to [handler].
 *
 * NanoHTTPD specifics relied on (2.3.1): `getUri()` is already percent-decoded, `getHeaders()` keys are
 * lower-case, and `getQueryParameterString()` is the raw query but is not reset between keep-alive requests,
 * so it is only trusted when the current request actually had parameters.
 */
internal class ProxyHttpServer(
    hostname: String?,
    port: Int,
    private val handler: ProxyHandler,
) : NanoHTTPD(hostname, port) {

    override fun serve(session: IHTTPSession): Response {
        val query = session.queryParameterString?.takeIf { session.parameters.isNotEmpty() }
        val request = ClientRequest(session.method, session.uri ?: "/", query, session.headers)
        return handler.serve(request)
    }

    /** Media and playlists are never compressed by the proxy. */
    override fun useGzipWhenAccepted(r: Response): Boolean = false
}
