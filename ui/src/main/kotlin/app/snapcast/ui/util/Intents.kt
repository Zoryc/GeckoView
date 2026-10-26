package app.snapcast.ui.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.net.URISyntaxException

private const val TAG = "SnapcastUi"

/** Outcome of [openExternalUri]. */
sealed interface ExternalOpenResult {
    data object Launched : ExternalOpenResult

    /** No app handled the link but the page provided a web fallback to load instead. */
    data class Fallback(val url: String) : ExternalOpenResult

    data object NotHandled : ExternalOpenResult
}

/**
 * Starts an activity for a link the engine cannot handle (intent:, mailto:, tel:, market:, app links).
 * Intent URIs are sanitized (browsable category, no explicit component/selector, no file: data) so pages
 * cannot target private components or local files. Malformed URIs and failed launches never throw. Falls
 * back to `S.browser_fallback_url`, then to the Play Store page of the intent's package.
 */
fun openExternalUri(context: Context, uri: String): ExternalOpenResult {
    val intent = try {
        Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
    } catch (e: URISyntaxException) {
        Log.w(TAG, "Malformed external URI", e)
        null
    } catch (e: IllegalArgumentException) {
        // NumberFormatException from malformed i./l./f./launchFlags= values; parseUri does not wrap it.
        Log.w(TAG, "Malformed external URI", e)
        null
    }
    if (intent != null) {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        // Pages must not hand file: URIs to other apps (FileUriExposedException, and it would expose local
        // files); such intents only get the web/market fallbacks, as in Chrome.
        val localFile = intent.data?.scheme.equals("file", ignoreCase = true)
        if (!localFile && tryStart(context, intent)) return ExternalOpenResult.Launched
    }
    IntentUriParser.fallbackUrl(uri)?.let { return ExternalOpenResult.Fallback(it) }
    val pkg = intent?.`package` ?: IntentUriParser.packageName(uri)
    if (pkg != null) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        if (tryStart(context, market)) return ExternalOpenResult.Launched
    }
    return ExternalOpenResult.NotHandled
}

/** Opens a downloaded file (content:// URI) with a matching app. Returns false when none is installed. */
fun openDownloadedFile(context: Context, contentUri: String): Boolean {
    val uri = Uri.parse(contentUri)
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return tryStart(context, intent)
}

/** Shows the Android share sheet for [text]. Returns false when it could not be shown. */
fun shareText(context: Context, text: String, subject: String? = null, chooserTitle: String? = null): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    return tryStart(context, Intent.createChooser(send, chooserTitle))
}

/** Offers [url] to other apps (video players) with its MIME type. Returns false when no app accepts it. */
fun openWithOtherApp(context: Context, url: String, mimeType: String?, chooserTitle: String?): Boolean {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mimeType ?: "video/*")
    return tryStart(context, Intent.createChooser(view, chooserTitle))
}

/**
 * Copies [text] to the clipboard. Returns true when the caller should confirm the copy itself
 * (Android 13+ shows its own clipboard confirmation).
 */
fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}

private fun tryStart(context: Context, intent: Intent): Boolean {
    if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: RuntimeException) {
        // SecurityException, FileUriExposedException (file:// data with targetSdk >= N) and binder failures
        // such as an oversized intent (TransactionTooLargeException wrapped in a RuntimeException).
        Log.w(TAG, "Could not start activity", e)
        false
    }
}
