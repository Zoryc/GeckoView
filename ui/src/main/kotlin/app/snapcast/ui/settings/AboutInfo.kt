package app.snapcast.ui.settings

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import org.mozilla.geckoview.GeckoSession

/** Versions shown in the About section. */
internal data class AboutInfo(
    /** "1.2.0 (12)", or null when the package info is unavailable. */
    val appVersion: String?,
    /** Gecko engine version parsed from GeckoView's default user agent, e.g. "158.0". */
    val engineVersion: String?,
) {
    companion object {
        fun read(context: Context): AboutInfo = AboutInfo(
            appVersion = appVersion(context),
            engineVersion = EngineVersion.fromUserAgent(GeckoSession.getDefaultUserAgent()),
        )

        private fun appVersion(context: Context): String? {
            val info = try {
                packageInfo(context)
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
            val code = PackageInfoCompat.getLongVersionCode(info)
            return info.versionName?.let { "$it ($code)" } ?: code.toString()
        }

        private fun packageInfo(context: Context): PackageInfo {
            val pm = context.packageManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
        }
    }
}

/** Third-party software shipped with the app and its license, for the open-source notice. */
internal data class OpenSourceComponent(val name: String, val license: String)

internal object OpenSourceNotices {
    private const val APACHE_2 = "Apache License 2.0"

    val COMPONENTS: List<OpenSourceComponent> = listOf(
        OpenSourceComponent("Mozilla GeckoView", "Mozilla Public License 2.0"),
        OpenSourceComponent("Android Jetpack (AndroidX, Compose, Media3, Room, DataStore)", APACHE_2),
        OpenSourceComponent("Material Design icons", APACHE_2),
        OpenSourceComponent("Dagger / Hilt", APACHE_2),
        OpenSourceComponent("Kotlin, kotlinx.coroutines, kotlinx.serialization", APACHE_2),
        OpenSourceComponent("OkHttp, Okio", APACHE_2),
        OpenSourceComponent("Coil", APACHE_2),
        OpenSourceComponent("NanoHTTPD", "BSD 3-Clause License"),
        OpenSourceComponent("Google Play services (Cast SDK)", "Android Software Development Kit License"),
    )
}
