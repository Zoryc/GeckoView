package app.snapcast.ui.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Runtime permissions worth asking for right before casting. */
internal object CastPermissions {
    /**
     * android.Manifest.permission.ACCESS_LOCAL_NETWORK: from Android 17 (API 37) apps targeting it need it for
     * any local network traffic, including the receiver's connections to the phone's proxy.
     */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val LOCAL_NETWORK_SDK = 37

    /**
     * The permissions to request before casting: local network access on Android 17+ whenever it is missing
     * (casting through the proxy cannot work without it), and the notification permission on Android 13+
     * when [askNotifications] and it is missing (the casting notification and lock-screen controls).
     */
    fun missing(context: Context, askNotifications: Boolean): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK && !granted(context, ACCESS_LOCAL_NETWORK)) add(ACCESS_LOCAL_NETWORK)
        if (askNotifications &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Runs an action that casts through the local proxy. On Android 17+ local network access is requested
 * first when missing; the action continues whatever the answer (the cast then explains what is missing).
 */
@Composable
internal fun rememberLocalNetworkGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending
        pending = null
        action?.invoke()
    }
    return remember(launcher, context) {
        { action: () -> Unit ->
            val missing = CastPermissions.missing(context, askNotifications = false)
            if (missing.isEmpty()) {
                action()
            } else {
                pending = action
                launcher.launch(missing.toTypedArray())
            }
        }
    }
}
