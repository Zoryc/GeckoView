package app.snapcast.ui.settings

import app.snapcast.core.browser.UrlInputResolver
import app.snapcast.core.model.SearchEngine
import app.snapcast.core.util.UrlUtils
import java.net.IDN
import java.util.Locale
import kotlin.math.abs

/** Choices offered for the video detection thresholds. */
internal object DetectionOptions {
    private const val KB = 1024L
    private const val MB = 1024L * KB

    /** Minimum progressive file size; 0 turns the filter off. */
    val MIN_SIZE_BYTES: List<Long> = listOf(0L, 256 * KB, 512 * KB, 1 * MB, 5 * MB, 20 * MB)

    /** Minimum duration in seconds; 0 turns the filter off. */
    val MIN_DURATION_SEC: List<Int> = listOf(0, 3, 5, 10, 30)

    /** The option closest to [bytes] (exact for values written by this screen; negatives map to "off"). */
    fun sizeOptionFor(bytes: Long): Long = MIN_SIZE_BYTES.minBy { abs(it - bytes.coerceAtLeast(0)) }

    /** The option closest to [seconds] (exact for values written by this screen; negatives map to "off"). */
    fun durationOptionFor(seconds: Int): Int = MIN_DURATION_SEC.minBy { abs(it - seconds.coerceAtLeast(0)) }
}

internal enum class PortError { EMPTY, NOT_A_NUMBER, OUT_OF_RANGE }

internal sealed interface PortCheck {
    data class Valid(val port: Int) : PortCheck

    data class Invalid(val error: PortError) : PortCheck
}

/** Validation of the proxy port typed by the user. Ports below 1024 need root on Android. */
internal object PortValidation {
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    fun validate(text: String): PortCheck {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return PortCheck.Invalid(PortError.EMPTY)
        if (!trimmed.all { it in '0'..'9' }) return PortCheck.Invalid(PortError.NOT_A_NUMBER)
        val port = trimmed.toIntOrNull() ?: return PortCheck.Invalid(PortError.OUT_OF_RANGE)
        return if (port in MIN_PORT..MAX_PORT) PortCheck.Valid(port) else PortCheck.Invalid(PortError.OUT_OF_RANGE)
    }
}

internal sealed interface DomainCheck {
    /** [domain] is the normalized host to store. */
    data class Valid(val domain: String) : DomainCheck

    data object Invalid : DomainCheck

    data class Duplicate(val domain: String) : DomainCheck
}

/**
 * Normalizes what the user typed in the ignored-domains editor to a bare host: scheme, user info, port, path,
 * a leading "*." / "www." and a trailing dot are dropped, internationalized names are converted to their
 * ASCII (punycode) form. Accepts registrable host names with at least two labels and IPv4 addresses.
 */
internal object DomainValidation {
    private val SCHEME = Regex("^[a-z][a-z0-9+.\\-]*://")
    private val LABEL = Regex("[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?")
    private val TOP_LEVEL = Regex("[a-z]{2,63}|xn--[a-z0-9\\-]{1,59}")
    private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
    private const val MAX_HOST_LENGTH = 253

    fun check(input: String, existing: Collection<String>): DomainCheck {
        val domain = normalize(input) ?: return DomainCheck.Invalid
        val duplicate = existing.any { normalize(it) == domain }
        return if (duplicate) DomainCheck.Duplicate(domain) else DomainCheck.Valid(domain)
    }

    /** The bare host for [input], or null when it is not a valid domain name or IPv4 address. */
    fun normalize(input: String): String? {
        var host = input.trim().lowercase(Locale.ROOT)
        host = host.replace(SCHEME, "")
        host = host.substringBefore('/').substringBefore('?').substringBefore('#')
        host = host.substringAfterLast('@')
        if (host.startsWith('[')) return null
        if (host.count { it == ':' } == 1) {
            val port = host.substringAfter(':')
            if (port.isNotEmpty() && !port.all { it in '0'..'9' }) return null
            host = host.substringBefore(':')
        }
        host = host.removePrefix("*.").trimStart('.').trimEnd('.').removePrefix("www.")
        if (host.isEmpty()) return null
        host = toAscii(host) ?: return null
        if (host.length > MAX_HOST_LENGTH) return null
        IPV4.matchEntire(host)?.let { match ->
            return if (match.groupValues.drop(1).all { it.toInt() <= 255 }) host else null
        }
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return null
        if (!TOP_LEVEL.matches(labels.last())) return null
        return host
    }

    private fun toAscii(host: String): String? = try {
        IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Validation of a custom home page. */
internal object HomePageInput {
    /** Absolute http(s) URL for [text] ("example.com" becomes "https://example.com"), or null. */
    fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !UrlInputResolver.looksLikeUrl(trimmed)) return null
        // Address-like input never becomes a search, so the engine passed here is never used.
        val url = UrlInputResolver.resolve(trimmed, SearchEngine.DUCKDUCKGO)
        return url.takeIf { UrlUtils.isHttp(it) && UrlUtils.hostOf(it) != null }
    }
}

/** Extracts the Gecko engine version from a GeckoView user agent string. */
internal object EngineVersion {
    private val FIREFOX = Regex("Firefox/(\\d+(?:\\.\\d+)*)")
    private val REVISION = Regex("rv:(\\d+(?:\\.\\d+)*)")

    /** "158.0" for "Mozilla/5.0 (Android 14; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0", null if absent. */
    fun fromUserAgent(userAgent: String): String? =
        (FIREFOX.find(userAgent) ?: REVISION.find(userAgent))?.groupValues?.get(1)
}
