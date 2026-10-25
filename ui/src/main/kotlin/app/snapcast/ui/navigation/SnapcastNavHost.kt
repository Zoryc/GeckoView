package app.snapcast.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import app.snapcast.ui.addons.AddonsScreen
import app.snapcast.ui.bookmarks.BookmarksScreen
import app.snapcast.ui.browser.BrowserNavigation
import app.snapcast.ui.browser.BrowserScreen
import app.snapcast.ui.cast.CastControllerScreen
import app.snapcast.ui.cast.QueueScreen
import app.snapcast.ui.history.HistoryScreen
import app.snapcast.ui.local.LocalMediaScreen
import app.snapcast.ui.settings.SettingsScreen
import app.snapcast.ui.tabs.TabsScreen

private const val TRANSITION_MS = 260

/** Pops back to the browser (the start destination), or navigates there if it is not on the stack. */
internal fun NavHostController.showBrowser() {
    if (!popBackStack(Routes.BROWSER, inclusive = false)) {
        navigate(Routes.BROWSER) { launchSingleTop = true }
    }
}

/** Opens [route] on top of the current destination, never twice in a row. */
internal fun NavHostController.open(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * The app's navigation graph. [onOpenUrl] loads a URL in the selected tab; the graph then returns to the
 * browser.
 */
@Composable
internal fun SnapcastNavHost(
    navController: NavHostController,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Guarded so a double tap during the exit transition cannot pop the browser itself.
    val back: () -> Unit = {
        if (navController.currentDestination?.route != Routes.BROWSER) navController.popBackStack()
    }
    val openUrl: (String) -> Unit = { url ->
        onOpenUrl(url)
        navController.showBrowser()
    }
    NavHost(
        navController = navController,
        startDestination = Routes.BROWSER,
        modifier = modifier,
        enterTransition = {
            fadeIn(tween(TRANSITION_MS)) +
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_MS)) { it / 8 }
        },
        exitTransition = { fadeOut(tween(TRANSITION_MS / 2)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = {
            fadeOut(tween(TRANSITION_MS / 2)) +
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_MS)) { it / 8 }
        },
    ) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                navigation = BrowserNavigation(
                    openTabs = { navController.open(Routes.TABS) },
                    openHistory = { navController.open(Routes.HISTORY) },
                    openBookmarks = { navController.open(Routes.BOOKMARKS) },
                    openSettings = { navController.open(Routes.SETTINGS) },
                    openAddons = { navController.open(Routes.ADDONS) },
                    openCastController = { navController.open(Routes.CAST) },
                    openLocalMedia = { navController.open(Routes.LOCAL) },
                ),
            )
        }
        composable(Routes.TABS) {
            TabsScreen(onClose = back)
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = back, onOpenUrl = openUrl)
        }
        composable(Routes.BOOKMARKS) {
            BookmarksScreen(onBack = back, onOpenUrl = openUrl)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back)
        }
        composable(Routes.ADDONS) {
            AddonsScreen(onBack = back, onOpenUrl = openUrl)
        }
        composable(Routes.CAST) {
            CastControllerScreen(onBack = back, onOpenQueue = { navController.open(Routes.QUEUE) })
        }
        composable(Routes.QUEUE) {
            QueueScreen(onBack = back)
        }
        composable(Routes.LOCAL) {
            LocalMediaScreen(onBack = back, onOpenUrl = openUrl)
        }
    }
}
