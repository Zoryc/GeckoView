package app.snapcast.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResumePolicyTest {
    private val hour = 3_600_000L

    @Test
    fun positionsInTheFirstTenSecondsAreNotRemembered() {
        assertThat(ResumePolicy.shouldRemember(0L, hour)).isFalse()
        assertThat(ResumePolicy.shouldRemember(9_999L, hour)).isFalse()
        assertThat(ResumePolicy.shouldRemember(10_000L, hour)).isTrue()
    }

    @Test
    fun positionsInTheLastThirtySecondsAreNotRemembered() {
        val duration = 300_000L // 95 % = 285 s, so the 30 s margin (270 s) applies first
        assertThat(ResumePolicy.shouldRemember(269_999L, duration)).isTrue()
        assertThat(ResumePolicy.shouldRemember(270_000L, duration)).isFalse()
        assertThat(ResumePolicy.shouldRemember(duration, duration)).isFalse()
        assertThat(ResumePolicy.shouldRemember(duration + 5_000L, duration)).isFalse()
    }

    @Test
    fun positionsAtOrBeyondNinetyFivePercentAreNotRemembered() {
        // 95 % of an hour is 3420 s, well before the 30 s end margin (3570 s).
        assertThat(ResumePolicy.shouldRemember(3_419_999L, hour)).isTrue()
        assertThat(ResumePolicy.shouldRemember(3_420_000L, hour)).isFalse()
    }

    @Test
    fun shortMediaIsNeverRemembered() {
        // Every position >= 10 s of a 40 s clip is inside the last 30 s.
        (0L..40_000L step 1_000L).forEach { position ->
            assertThat(ResumePolicy.shouldRemember(position, 40_000L)).isFalse()
        }
        assertThat(ResumePolicy.shouldRemember(14_999L, 45_000L)).isTrue()
        assertThat(ResumePolicy.shouldRemember(15_000L, 45_000L)).isFalse()
    }

    @Test
    fun unknownDurationIsNeverRemembered() {
        assertThat(ResumePolicy.shouldRemember(60_000L, 0L)).isFalse()
        assertThat(ResumePolicy.shouldRemember(60_000L, -1L)).isFalse()
        assertThat(ResumePolicy.shouldRemember(60_000L, Long.MIN_VALUE + 1)).isFalse()
    }

    @Test
    fun negativePositionIsNotRemembered() {
        assertThat(ResumePolicy.shouldRemember(-1L, hour)).isFalse()
    }
}
