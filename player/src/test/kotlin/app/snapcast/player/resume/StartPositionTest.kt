package app.snapcast.player.resume

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StartPositionTest {

    @Test
    fun explicitStartWinsOverSavedPosition() {
        assertThat(StartPosition.decide(explicitMs = 42_000L, resumeEnabled = true, savedMs = 90_000L))
            .isEqualTo(StartPosition(42_000L, resumed = false))
    }

    @Test
    fun explicitStartAtZeroUsesTheDefaultPosition() {
        assertThat(StartPosition.decide(explicitMs = 0L, resumeEnabled = true, savedMs = 90_000L))
            .isEqualTo(StartPosition.DEFAULT)
        assertThat(StartPosition.decide(explicitMs = -5L, resumeEnabled = false, savedMs = null))
            .isEqualTo(StartPosition.DEFAULT)
    }

    @Test
    fun savedPositionIsResumedWhenEnabled() {
        assertThat(StartPosition.decide(explicitMs = null, resumeEnabled = true, savedMs = 90_000L))
            .isEqualTo(StartPosition(90_000L, resumed = true))
    }

    @Test
    fun savedPositionIsIgnoredWhenResumeIsDisabled() {
        assertThat(StartPosition.decide(explicitMs = null, resumeEnabled = false, savedMs = 90_000L))
            .isEqualTo(StartPosition.DEFAULT)
    }

    @Test
    fun missingOrZeroSavedPositionStartsAtTheDefaultPosition() {
        assertThat(StartPosition.decide(explicitMs = null, resumeEnabled = true, savedMs = null)).isEqualTo(StartPosition.DEFAULT)
        assertThat(StartPosition.decide(explicitMs = null, resumeEnabled = true, savedMs = 0L)).isEqualTo(StartPosition.DEFAULT)
    }

    @Test
    fun savedPositionIsOnlyLookedUpWhenItCanBeUsed() {
        assertThat(StartPosition.needsSavedPosition(explicitMs = null, resumeEnabled = true)).isTrue()
        assertThat(StartPosition.needsSavedPosition(explicitMs = null, resumeEnabled = false)).isFalse()
        assertThat(StartPosition.needsSavedPosition(explicitMs = 1_000L, resumeEnabled = true)).isFalse()
    }

    @Test
    fun onlyOnDemandMediaWithKnownDurationIsPersisted() {
        val timeUnset = Long.MIN_VALUE + 1 // Media3's C.TIME_UNSET
        assertThat(StartPosition.isPersistable(durationMs = 600_000L, isLive = false)).isTrue()
        assertThat(StartPosition.isPersistable(durationMs = 600_000L, isLive = true)).isFalse()
        assertThat(StartPosition.isPersistable(durationMs = timeUnset, isLive = false)).isFalse()
        assertThat(StartPosition.isPersistable(durationMs = 0L, isLive = false)).isFalse()
    }
}
