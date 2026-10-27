package app.snapcast.core.util

/** Helpers for Cookie request header values ("a=1; b=2"). */
object CookieHeaders {
    /**
     * Merges Cookie header values into one, a cookie per name: a later header's value for a name replaces an
     * earlier one's, so pass them oldest first. Within one header the first value of a name is kept, as servers
     * read it (browsers put the most specific cookie first when a name is set for several paths or domains).
     * Names keep the order they first appeared in. Null when empty.
     */
    fun merge(vararg headers: String?): String? {
        val byName = LinkedHashMap<String, String>()
        for (header in headers) {
            if (header.isNullOrBlank()) continue
            val seen = HashSet<String>()
            for (part in header.split(';')) {
                val pair = part.trim()
                if (pair.isEmpty()) continue
                val name = pair.substringBefore('=').trim()
                if (seen.add(name)) byName[name] = pair
            }
        }
        return byName.values.joinToString("; ").takeIf { it.isNotEmpty() }
    }

    /**
     * At most [maxChars] of per-host cookies (host names and values counted), for payloads with a size limit
     * such as an Intent: the hosts in [firstHosts] first, then the others newest first (maps list hosts oldest
     * first). Hosts that do not fit are left out whole.
     */
    fun bounded(cookiesByHost: Map<String, String>, firstHosts: Collection<String>, maxChars: Int): Map<String, String> {
        val first = firstHosts.mapTo(HashSet()) { it.lowercase() }
        val order = cookiesByHost.keys.filter { it.lowercase() in first } + cookiesByHost.keys.reversed().filter { it.lowercase() !in first }
        val result = LinkedHashMap<String, String>()
        var used = 0
        for (host in order) {
            val value = cookiesByHost.getValue(host)
            val cost = host.length + value.length
            if (used + cost > maxChars) continue
            result[host] = value
            used += cost
        }
        return result
    }
}
