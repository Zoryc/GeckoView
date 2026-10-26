package app.snapcast.browser.tabs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashRecoveryTest {
    @Test
    fun `first crash restores automatically`() {
        assertThat(CrashRecovery.mayAutoRestore(lastCrashAt = null, now = 5_000L)).isTrue()
    }

    @Test
    fun `crash again within the window waits for the user`() {
        assertThat(CrashRecovery.mayAutoRestore(lastCrashAt = 10_000L, now = 10_000L)).isFalse()
        assertThat(CrashRecovery.mayAutoRestore(lastCrashAt = 10_000L, now = 12_500L)).isFalse()
        assertThat(CrashRecovery.mayAutoRestore(lastCrashAt = 10_000L, now = 10_000L + CrashRecovery.RELOAD_WINDOW_MS - 1)).isFalse()
    }

    @Test
    fun `crash long after the previous one restores automatically`() {
        assertThat(CrashRecovery.mayAutoRestore(lastCrashAt = 10_000L, now = 10_000L + CrashRecovery.RELOAD_WINDOW_MS)).isTrue()
        assertThat(CrashRecovery.mayAutoRestore(lastCrashAt = 10_000L, now = 10_000_000L)).isTrue()
    }

    @Test
    fun `clock going backwards does not block restoring`() {
        assertThat(CrashRecovery.mayAutoRestore(lastCrashAt = 10_000L, now = 1_000L)).isTrue()
    }
}
