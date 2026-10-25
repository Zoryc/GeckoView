package app.snapcast.addons.status

import org.mozilla.geckoview.WebExtension.DisabledFlags

/** Why an add-on is disabled, from `WebExtension.MetaData.disabledFlags`, most severe first. */
internal enum class DisabledReason {
    BLOCKLISTED,
    SOFT_BLOCKLISTED,
    SIGNATURE,
    INCOMPATIBLE,
    APP,
    USER,
}

internal object DisabledReasons {
    private val FLAG_REASONS = listOf(
        DisabledFlags.BLOCKLIST to DisabledReason.BLOCKLISTED,
        DisabledFlags.SOFT_BLOCKLIST to DisabledReason.SOFT_BLOCKLISTED,
        DisabledFlags.SIGNATURE to DisabledReason.SIGNATURE,
        DisabledFlags.APP_VERSION to DisabledReason.INCOMPATIBLE,
        DisabledFlags.APP to DisabledReason.APP,
        DisabledFlags.USER to DisabledReason.USER,
    )

    /** Every reason set in [flags], most severe first. Unknown bits are ignored. */
    fun fromFlags(flags: Int): List<DisabledReason> =
        FLAG_REASONS.filter { (bit, _) -> (flags and bit) != 0 }.map { it.second }

    /** The reason worth explaining to the user: the most severe one that is not the user's own choice. */
    fun displayReason(flags: Int): DisabledReason? = fromFlags(flags).firstOrNull { it != DisabledReason.USER }
}
