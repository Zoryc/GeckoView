package app.snapcast.browser.web

import android.content.Context
import androidx.annotation.StringRes
import app.snapcast.browser.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Localized error pages as data: URIs for `NavigationDelegate.onLoadError`. */
@Singleton
internal class ErrorPageRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun render(failedUrl: String?, category: Int, code: Int): String {
        val kind = ErrorPages.kindOf(category, code)
        val texts = textsFor(kind)
        val html = ErrorPages.html(
            title = context.getString(texts.title),
            message = context.getString(texts.message),
            failedUrl = failedUrl,
            retryLabel = if (ErrorPages.isRetryable(kind)) context.getString(R.string.browser_error_retry) else null,
            codeLabel = context.getString(R.string.browser_error_code, code, category),
        )
        return ErrorPages.dataUri(html)
    }

    private class Texts(@StringRes val title: Int, @StringRes val message: Int)

    private fun textsFor(kind: LoadErrorKind): Texts = when (kind) {
        LoadErrorKind.UNKNOWN_HOST -> Texts(R.string.browser_error_unknown_host_title, R.string.browser_error_unknown_host_message)
        LoadErrorKind.CONNECTION_REFUSED -> Texts(R.string.browser_error_refused_title, R.string.browser_error_refused_message)
        LoadErrorKind.TIMEOUT -> Texts(R.string.browser_error_timeout_title, R.string.browser_error_timeout_message)
        LoadErrorKind.CONNECTION_INTERRUPTED -> Texts(R.string.browser_error_interrupted_title, R.string.browser_error_interrupted_message)
        LoadErrorKind.OFFLINE -> Texts(R.string.browser_error_offline_title, R.string.browser_error_offline_message)
        LoadErrorKind.SECURITY -> Texts(R.string.browser_error_security_title, R.string.browser_error_security_message)
        LoadErrorKind.BAD_CERTIFICATE -> Texts(R.string.browser_error_certificate_title, R.string.browser_error_certificate_message)
        LoadErrorKind.HTTPS_ONLY -> Texts(R.string.browser_error_https_only_title, R.string.browser_error_https_only_message)
        LoadErrorKind.SAFE_BROWSING -> Texts(R.string.browser_error_safe_browsing_title, R.string.browser_error_safe_browsing_message)
        LoadErrorKind.MALFORMED_URI -> Texts(R.string.browser_error_malformed_title, R.string.browser_error_malformed_message)
        LoadErrorKind.UNKNOWN_PROTOCOL -> Texts(R.string.browser_error_protocol_title, R.string.browser_error_protocol_message)
        LoadErrorKind.REDIRECT_LOOP -> Texts(R.string.browser_error_redirect_title, R.string.browser_error_redirect_message)
        LoadErrorKind.FILE_NOT_FOUND -> Texts(R.string.browser_error_file_not_found_title, R.string.browser_error_file_not_found_message)
        LoadErrorKind.FILE_ACCESS_DENIED -> Texts(R.string.browser_error_file_denied_title, R.string.browser_error_file_denied_message)
        LoadErrorKind.CORRUPTED_CONTENT -> Texts(R.string.browser_error_corrupted_title, R.string.browser_error_corrupted_message)
        LoadErrorKind.UNSAFE_CONTENT_TYPE -> Texts(R.string.browser_error_unsafe_type_title, R.string.browser_error_unsafe_type_message)
        LoadErrorKind.CONTENT_CRASHED -> Texts(R.string.browser_error_crashed_title, R.string.browser_error_crashed_message)
        LoadErrorKind.PROXY -> Texts(R.string.browser_error_proxy_title, R.string.browser_error_proxy_message)
        LoadErrorKind.PORT_BLOCKED -> Texts(R.string.browser_error_port_title, R.string.browser_error_port_message)
        LoadErrorKind.LOCAL_NETWORK_DENIED -> Texts(R.string.browser_error_local_network_title, R.string.browser_error_local_network_message)
        LoadErrorKind.DATA_URI_TOO_LONG -> Texts(R.string.browser_error_data_uri_title, R.string.browser_error_data_uri_message)
        LoadErrorKind.UNKNOWN -> Texts(R.string.browser_error_unknown_title, R.string.browser_error_unknown_message)
    }
}
