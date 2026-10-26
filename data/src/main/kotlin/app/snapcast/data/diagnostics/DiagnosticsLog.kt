package app.snapcast.data.diagnostics

import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/** One recent failure: where it happened ("player", "cast", "poster") and what, with hosts instead of links. */
data class DiagnosticEvent(val atMs: Long, val source: String, val message: String)

/**
 * The last few failures (player, cast, posters) for the "Report a site problem" page. Kept in memory only and
 * never sent anywhere by itself; the user sees the events in the report before sharing it. Messages are reduced
 * to hosts on record: any link they contain is replaced by its host, so no path, query or token is kept.
 * Failures in private tabs are not recorded by callers.
 */
interface DiagnosticsLog {
    fun record(source: String, message: String)

    /** Oldest first. */
    fun recent(): List<DiagnosticEvent>
}

@Singleton
internal class InMemoryDiagnosticsLog @Inject constructor() : DiagnosticsLog {
    private val events = ArrayDeque<DiagnosticEvent>()

    override fun record(source: String, message: String) {
        val event = DiagnosticEvent(System.currentTimeMillis(), source, withoutLinks(message).take(MAX_MESSAGE))
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

        /** [message] with every link replaced by its host (user info dropped). */
        fun withoutLinks(message: String): String =
            LINK.replace(message) { match -> match.groupValues[1].substringAfterLast('@').ifEmpty { "(link)" } }
    }
}
