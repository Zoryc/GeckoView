package app.snapcast.browser.web

import java.util.Base64
import org.mozilla.geckoview.WebRequestError

/** What went wrong with a failed load, in user terms. */
internal enum class LoadErrorKind {
    UNKNOWN_HOST,
    CONNECTION_REFUSED,
    TIMEOUT,
    CONNECTION_INTERRUPTED,
    OFFLINE,
    SECURITY,
    BAD_CERTIFICATE,
    HTTPS_ONLY,
    SAFE_BROWSING,
    MALFORMED_URI,
    UNKNOWN_PROTOCOL,
    REDIRECT_LOOP,
    FILE_NOT_FOUND,
    FILE_ACCESS_DENIED,
    CORRUPTED_CONTENT,
    UNSAFE_CONTENT_TYPE,
    CONTENT_CRASHED,
    PROXY,
    PORT_BLOCKED,
    LOCAL_NETWORK_DENIED,
    DATA_URI_TOO_LONG,
    UNKNOWN,
}

/** Builds the self-contained error page returned from `NavigationDelegate.onLoadError`. */
internal object ErrorPages {
    /** Specific error codes first, then the broad category. */
    fun kindOf(category: Int, code: Int): LoadErrorKind = when (code) {
        WebRequestError.ERROR_UNKNOWN_HOST -> LoadErrorKind.UNKNOWN_HOST
        WebRequestError.ERROR_CONNECTION_REFUSED -> LoadErrorKind.CONNECTION_REFUSED
        WebRequestError.ERROR_NET_TIMEOUT -> LoadErrorKind.TIMEOUT
        WebRequestError.ERROR_NET_INTERRUPT, WebRequestError.ERROR_NET_RESET -> LoadErrorKind.CONNECTION_INTERRUPTED
        WebRequestError.ERROR_OFFLINE -> LoadErrorKind.OFFLINE
        WebRequestError.ERROR_SECURITY_SSL -> LoadErrorKind.SECURITY
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_BAD_HSTS_CERT -> LoadErrorKind.BAD_CERTIFICATE
        WebRequestError.ERROR_HTTPS_ONLY -> LoadErrorKind.HTTPS_ONLY
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI,
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI,
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
        WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
        WebRequestError.ERROR_HARMFULADDON_URI,
        -> LoadErrorKind.SAFE_BROWSING
        WebRequestError.ERROR_MALFORMED_URI -> LoadErrorKind.MALFORMED_URI
        WebRequestError.ERROR_UNKNOWN_PROTOCOL, WebRequestError.ERROR_UNKNOWN_SOCKET_TYPE -> LoadErrorKind.UNKNOWN_PROTOCOL
        WebRequestError.ERROR_REDIRECT_LOOP -> LoadErrorKind.REDIRECT_LOOP
        WebRequestError.ERROR_FILE_NOT_FOUND -> LoadErrorKind.FILE_NOT_FOUND
        WebRequestError.ERROR_FILE_ACCESS_DENIED -> LoadErrorKind.FILE_ACCESS_DENIED
        WebRequestError.ERROR_CORRUPTED_CONTENT, WebRequestError.ERROR_INVALID_CONTENT_ENCODING -> LoadErrorKind.CORRUPTED_CONTENT
        WebRequestError.ERROR_UNSAFE_CONTENT_TYPE -> LoadErrorKind.UNSAFE_CONTENT_TYPE
        WebRequestError.ERROR_CONTENT_CRASHED -> LoadErrorKind.CONTENT_CRASHED
        WebRequestError.ERROR_PROXY_CONNECTION_REFUSED, WebRequestError.ERROR_UNKNOWN_PROXY_HOST -> LoadErrorKind.PROXY
        WebRequestError.ERROR_PORT_BLOCKED -> LoadErrorKind.PORT_BLOCKED
        WebRequestError.ERROR_LOCAL_NETWORK_ACCESS_DENIED -> LoadErrorKind.LOCAL_NETWORK_DENIED
        WebRequestError.ERROR_DATA_URI_TOO_LONG -> LoadErrorKind.DATA_URI_TOO_LONG
        else -> when (category) {
            WebRequestError.ERROR_CATEGORY_NETWORK -> LoadErrorKind.CONNECTION_INTERRUPTED
            WebRequestError.ERROR_CATEGORY_SECURITY -> LoadErrorKind.SECURITY
            WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> LoadErrorKind.SAFE_BROWSING
            WebRequestError.ERROR_CATEGORY_PROXY -> LoadErrorKind.PROXY
            WebRequestError.ERROR_CATEGORY_URI -> LoadErrorKind.MALFORMED_URI
            WebRequestError.ERROR_CATEGORY_CONTENT -> LoadErrorKind.CORRUPTED_CONTENT
            else -> LoadErrorKind.UNKNOWN
        }
    }

    /** True when trying the same URL again can help (not for blocked or malformed addresses). */
    fun isRetryable(kind: LoadErrorKind): Boolean = when (kind) {
        LoadErrorKind.SAFE_BROWSING,
        LoadErrorKind.MALFORMED_URI,
        LoadErrorKind.UNKNOWN_PROTOCOL,
        LoadErrorKind.PORT_BLOCKED,
        LoadErrorKind.DATA_URI_TOO_LONG,
        LoadErrorKind.UNSAFE_CONTENT_TYPE,
        -> false
        else -> true
    }

    /**
     * A small dark-themed HTML document. All text is escaped; the retry link is only offered for
     * http(s) URLs so the page can never navigate to script or data URIs.
     */
    fun html(title: String, message: String, failedUrl: String?, retryLabel: String?, codeLabel: String): String {
        val url = failedUrl?.takeIf { it.isNotBlank() }
        val retryHref = url?.takeIf { retryLabel != null && (UriParts.schemeOf(it) == "http" || UriParts.schemeOf(it) == "https") }
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<meta name=\"color-scheme\" content=\"dark\">")
            append("<title>").append(escape(title)).append("</title>")
            append("<style>")
            append("html,body{margin:0;background:#121417;color:#e6e8eb;font-family:sans-serif;}")
            append("main{max-width:560px;margin:0 auto;padding:12vh 24px 32px;}")
            append("h1{font-size:1.5em;font-weight:600;margin:0 0 12px;}")
            append("p{line-height:1.5;color:#b8bcc2;margin:0 0 16px;}")
            append(".url{font-family:monospace;font-size:.9em;color:#8ab4f8;word-break:break-all;}")
            append(".code{font-size:.8em;color:#7a7f87;}")
            append("a.retry{display:inline-block;margin-top:8px;padding:10px 20px;border-radius:20px;")
            append("background:#8ab4f8;color:#0b1a33;text-decoration:none;font-weight:600;}")
            append("</style></head><body><main>")
            append("<h1>").append(escape(title)).append("</h1>")
            append("<p>").append(escape(message)).append("</p>")
            if (url != null) append("<p class=\"url\">").append(escape(url)).append("</p>")
            append("<p class=\"code\">").append(escape(codeLabel)).append("</p>")
            if (retryHref != null && retryLabel != null) {
                append("<a class=\"retry\" href=\"").append(escape(retryHref)).append("\">")
                append(escape(retryLabel)).append("</a>")
            }
            append("</main></body></html>")
        }
    }

    /** Base64 keeps characters such as '#' and '%' from breaking the data: URI. */
    fun dataUri(html: String): String =
        "data:text/html;charset=utf-8;base64," + Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
