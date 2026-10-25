package app.snapcast.detection.inspect

/** Reads the size of a progressive file from the headers of a HEAD or `Range: bytes=0-0` response. */
internal object ProgressiveProbe {
    private val CONTENT_RANGE_TOTAL = Regex("^\\s*bytes\\s+(?:\\d+\\s*-\\s*\\d+|\\*)\\s*/\\s*(\\d+)\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Total size (and the MIME type when it is a video type), or null when the response does not tell:
     * not 2xx, an error page (text, HTML, JSON, image), an encoded body or a partial response without a total.
     */
    fun fromResponse(
        code: Int,
        contentLength: String?,
        contentRange: String?,
        contentType: String?,
        contentEncoding: String?,
    ): InspectionResult? {
        if (code !in 200..299) return null
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (mime != null && isNotMedia(mime)) return null
        val size = totalFromContentRange(contentRange)
            ?: if (code == 206 || !isIdentity(contentEncoding)) null else contentLength?.trim()?.toLongOrNull()
        val known = size?.takeIf { it > 0 } ?: return null
        return InspectionResult(sizeBytes = known, mimeType = mime?.takeIf { it.startsWith("video/") })
    }

    /** The total of a Content-Range value such as "bytes 0-0/12345" (the range may be unknown); null when absent or unknown. */
    fun totalFromContentRange(value: String?): Long? =
        value?.let { CONTENT_RANGE_TOTAL.find(it) }?.groupValues?.get(1)?.toLongOrNull()

    private fun isIdentity(contentEncoding: String?): Boolean =
        contentEncoding.isNullOrBlank() || contentEncoding.trim().equals("identity", ignoreCase = true)

    private fun isNotMedia(mime: String): Boolean =
        mime.startsWith("text/") || mime.startsWith("image/") || mime == "application/json" || mime.endsWith("html") ||
            (mime.endsWith("+xml") && mime != "application/dash+xml")
}
