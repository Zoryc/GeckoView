package app.snapcast

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.snapcast.core.browser.UrlInputResolver
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.ui.ExternalRequest
import app.snapcast.ui.SnapcastApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * Single activity hosting the Compose UI. It is an AppCompatActivity (FragmentActivity) because the
 * Cast MediaRouteButton and device chooser are fragment dialogs that need an AppCompat theme.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** Buffered so a request from the launching intent is delivered once the UI starts collecting. */
    private val externalRequests = Channel<ExternalRequest>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A relaunch from Recents (after Back finished the activity, a force-stop or a reboot) replays the
        // task's original VIEW/SEND/notification intent; it is not a new request, so do not open it again.
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) handleIntent(intent)
        setContent {
            SnapcastApp(externalRequests = externalRequests.receiveAsFlow())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val request = ExternalRequestParser.parse(
            action = intent.action,
            dataString = intent.dataString,
            // EXTRA_TEXT is a CharSequence: rich-text senders put a Spanned there, which getStringExtra drops.
            sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            searchQuery = intent.getCharSequenceExtra(SearchManager.QUERY)?.toString(),
            openCastController = intent.getBooleanExtra(EXTRA_OPEN_CAST_CONTROLLER, false),
            // The parser only keeps http(s) results of toUrl and searches for anything else, so other apps
            // cannot open file:, data:, about:, view-source:, moz-extension: or resource: URLs.
            toUrl = { input -> UrlInputResolver.resolve(input, settingsRepository.settings.value.searchEngine) },
            searchUrl = { query -> UrlInputResolver.searchUrl(query, settingsRepository.settings.value.searchEngine) },
        ) ?: return
        externalRequests.trySend(request)
    }

    companion object {
        /** Set by the casting notification to open the expanded controller. */
        const val EXTRA_OPEN_CAST_CONTROLLER = "app.snapcast.extra.OPEN_CAST_CONTROLLER"
    }
}
