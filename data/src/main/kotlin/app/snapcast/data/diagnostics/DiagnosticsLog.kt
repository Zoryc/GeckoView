package app.snapcast.data.diagnostics

import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One recent failure: where it happened ("player", "cast", "poster"), what, with hosts instead of links, and the
 * [site] (host of the page it came from) so a report about one site never lists another site's failures.
 */
data class DiagnosticEvent(val atMs: Long, val source: String, val message: String, val site: String?)

/**
 * The last few failures (player, cast, posters) for the "Report a site problem" page. Kept in memory only and
 * never sent anywhere by itself; the user sees the events in the report before sharing it. Messages are reduced
 * to hosts on record: any link they contain is replaced by its host, so no path, query or token is kept.
 * Failures in private tabs are not recorded by callers.
 */
interface DiagnosticsLog {
    /** [pageUrl] is the page the failure belongs to; only its host is kept. */
    fun record(source: String, message: String, pageUrl: String?)

    /** Oldest first. */
    fun recent(): List<DiagnosticEvent>
}

@Singleton
internal class InMemoryDiagnosticsLog @Inject constructor() : DiagnosticsLog {
    private val events = ArrayDeque<DiagnosticEvent>()

    override fun record(source: String, message: String, pageUrl: String?) {
        val event = DiagnosticEvent(System.currentTimeMillis(), source, withoutLinks(message).take(MAX_MESSAGE), siteOf(pageUrl))
        synchronized(events) {
            events.addLast(event)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    override fun recent(): List<DiagnosticEvent> = synchronized(events) { events.toList() }

    internal companion object {
        private const val MAX_EVENTS = 20
        private const val MAX_MESSAGE = 300
        private val LINK = Regex("""(?i)\b[a-z][a-z0-9+.-]*://([^/\s?#'"]*)[^\s'"]*""")

        /** Host of [url] without "www.", lower case; null when it has none. */
        fun siteOf(url: String?): String? =
            url?.let { LINK.find(it)?.groupValues?.get(1) }?.substringAfterLast('@')?.substringBefore(':')?.lowercase()
                ?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

        /** [message] with every link replaced by its host (user info dropped). */
        fun withoutLinks(message: String): String =
            LINK.replace(message) { match -> match.groupValues[1].substringAfterLast('@').ifEmpty { "(link)" } }
    }
}
