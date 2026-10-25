package app.snapcast.cast.media

import app.snapcast.proxy.LocalSource
import app.snapcast.proxy.ProxiedStream
import app.snapcast.proxy.ProxiedSubtitle
import app.snapcast.proxy.ProxyStreamRequest
import app.snapcast.proxy.StreamProxy
import app.snapcast.proxy.SubtitleSource

/** The part of the local proxy that media building needs, bound to the address receivers connect to. */
internal interface MediaProxy {
    fun registerStream(request: ProxyStreamRequest): ProxiedStream

    fun registerSubtitle(source: SubtitleSource): ProxiedSubtitle

    fun registerLocal(source: LocalSource, fileNameHint: String): ProxiedStream

    /** Absolute URL of [path] as seen from the LAN, e.g. "http://192.168.1.20:8642/stream/ab12/index.m3u8". */
    fun url(path: String): String
}

/** [MediaProxy] over a running [StreamProxy], reachable at [host] (the phone's LAN IPv4 address). */
internal class LanMediaProxy(
    private val proxy: StreamProxy,
    private val host: String,
) : MediaProxy {
    override fun registerStream(request: ProxyStreamRequest): ProxiedStream = proxy.registerStream(request)

    override fun registerSubtitle(source: SubtitleSource): ProxiedSubtitle = proxy.registerSubtitle(source)

    override fun registerLocal(source: LocalSource, fileNameHint: String): ProxiedStream =
        proxy.registerLocal(source, fileNameHint)

    override fun url(path: String): String = proxy.url(host, path)
}
