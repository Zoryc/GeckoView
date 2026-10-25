package app.snapcast.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.snapcast.ui.navigation.AppViewModel
import app.snapcast.ui.navigation.Routes
import app.snapcast.ui.navigation.SnapcastNavHost
import app.snapcast.ui.navigation.open
import app.snapcast.ui.navigation.showBrowser
import app.snapcast.ui.prompts.AppOverlays
import app.snapcast.ui.theme.SnapcastTheme
import kotlinx.coroutines.flow.Flow

/**
 * Root of the Compose UI, called by the app's MainActivity (a FragmentActivity with an AppCompat theme).
 * Applies [SnapcastTheme] from the user's settings, hosts the navigation graph and the app-wide overlays
 * (web prompts, add-on install prompts, extension popups) and routes [externalRequests]:
 * [ExternalRequest.OpenUrl] opens a new tab and shows the browser, [ExternalRequest.OpenCastController]
 * shows the expanded cast controller.
 */
@Composable
fun SnapcastApp(externalRequests: Flow<ExternalRequest>, modifier: Modifier = Modifier) {
    val appViewModel: AppViewModel = hiltViewModel()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()

    SnapcastTheme(themeMode = settings.themeMode) {
        val navController = rememberNavController()

        LaunchedEffect(externalRequests, navController) {
            externalRequests.collect { request ->
                when (request) {
                    is ExternalRequest.OpenUrl -> {
                        appViewModel.openInNewTab(request.url, request.private)
                        navController.showBrowser()
                    }
                    ExternalRequest.OpenCastController -> navController.open(Routes.CAST)
                }
            }
        }

        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                SnapcastNavHost(
                    navController = navController,
                    onOpenUrl = appViewModel::openInSelectedTab,
                    modifier = Modifier.fillMaxSize(),
                )
                AppOverlays()
            }
        }
    }
}
