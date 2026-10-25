package app.snapcast.addons.status

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mozilla.geckoview.WebExtension.DisabledFlags

class DisabledReasonsTest {

    @Test
    fun `each flag maps to its reason`() {
        assertThat(DisabledReasons.fromFlags(DisabledFlags.BLOCKLIST)).containsExactly(DisabledReason.BLOCKLISTED)
        assertThat(DisabledReasons.fromFlags(DisabledFlags.SOFT_BLOCKLIST)).containsExactly(DisabledReason.SOFT_BLOCKLISTED)
        assertThat(DisabledReasons.fromFlags(DisabledFlags.SIGNATURE)).containsExactly(DisabledReason.SIGNATURE)
        assertThat(DisabledReasons.fromFlags(DisabledFlags.APP_VERSION)).containsExactly(DisabledReason.INCOMPATIBLE)
        assertThat(DisabledReasons.fromFlags(DisabledFlags.APP)).containsExactly(DisabledReason.APP)
        assertThat(DisabledReasons.fromFlags(DisabledFlags.USER)).containsExactly(DisabledReason.USER)
    }

    @Test
    fun `combined flags are ordered by severity`() {
        val flags = DisabledFlags.USER or DisabledFlags.SIGNATURE or DisabledFlags.BLOCKLIST
        assertThat(DisabledReasons.fromFlags(flags)).containsExactly(
            DisabledReason.BLOCKLISTED,
            DisabledReason.SIGNATURE,
            DisabledReason.USER,
        ).inOrder()
    }

    @Test
    fun `the user's own choice needs no explanation`() {
        assertThat(DisabledReasons.displayReason(DisabledFlags.USER)).isNull()
        assertThat(DisabledReasons.displayReason(DisabledFlags.USER or DisabledFlags.APP_VERSION))
            .isEqualTo(DisabledReason.INCOMPATIBLE)
    }

    @Test
    fun `no flags or unknown bits give no reason`() {
        assertThat(DisabledReasons.fromFlags(0)).isEmpty()
        assertThat(DisabledReasons.displayReason(0)).isNull()
        assertThat(DisabledReasons.fromFlags(1 shl 20)).isEmpty()
    }
}
