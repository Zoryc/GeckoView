package app.snapcast.addons

import android.content.Context
import androidx.annotation.StringRes
import app.snapcast.addons.install.InstallError
import app.snapcast.addons.status.DisabledReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Add-on management actions whose failures are reported through [AddonInstallState.Failed]. */
internal enum class AddonOperation {
    ENABLE,
    DISABLE,
    UNINSTALL,
    PRIVATE_BROWSING,
}

/** User-facing texts for install errors, disabled reasons and failed operations. */
internal class AddonMessages @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun installError(error: InstallError): String = context.getString(installErrorText(error))

    fun disabledReason(reason: DisabledReason): String = context.getString(
        when (reason) {
            DisabledReason.BLOCKLISTED -> R.string.addons_disabled_blocklisted
            DisabledReason.SOFT_BLOCKLISTED -> R.string.addons_disabled_soft_blocklisted
            DisabledReason.SIGNATURE -> R.string.addons_disabled_signature
            DisabledReason.INCOMPATIBLE -> R.string.addons_disabled_incompatible
            DisabledReason.APP -> R.string.addons_disabled_app
            DisabledReason.USER -> R.string.addons_disabled_user
        },
    )

    fun operationFailed(operation: AddonOperation, addonName: String): String = context.getString(
        when (operation) {
            AddonOperation.ENABLE -> R.string.addons_error_enable
            AddonOperation.DISABLE -> R.string.addons_error_disable
            AddonOperation.UNINSTALL -> R.string.addons_error_uninstall
            AddonOperation.PRIVATE_BROWSING -> R.string.addons_error_private_browsing
        },
        addonName,
    )

    @StringRes
    private fun installErrorText(error: InstallError): Int = when (error) {
        InstallError.NETWORK -> R.string.addons_error_network
        InstallError.CORRUPT -> R.string.addons_error_corrupt
        InstallError.FILE_ACCESS -> R.string.addons_error_file_access
        InstallError.NOT_SIGNED -> R.string.addons_error_not_signed
        InstallError.INVALID_DOMAIN -> R.string.addons_error_invalid_domain
        InstallError.BLOCKLISTED -> R.string.addons_error_blocklisted
        InstallError.SOFT_BLOCKED -> R.string.addons_error_soft_blocked
        InstallError.INCOMPATIBLE -> R.string.addons_error_incompatible
        InstallError.UNSUPPORTED_TYPE -> R.string.addons_error_unsupported_type
        InstallError.ADMIN_ONLY -> R.string.addons_error_admin_only
        InstallError.POSTPONED -> R.string.addons_error_postponed
        InstallError.UNSUPPORTED_SOURCE -> R.string.addons_error_unsupported_source
        InstallError.UNKNOWN -> R.string.addons_error_unknown
    }
}
