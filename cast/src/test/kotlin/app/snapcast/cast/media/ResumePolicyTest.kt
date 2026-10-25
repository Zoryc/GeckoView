package app.snapcast.cast.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResumePolicyTest {

    @Test
    fun `an explicit start position always wins`() {
        assertThat(ResumePolicy.startPositionMs(requestedMs = 42_000, resumeEnabled = true, isLive = false, savedMs = 90_000))
            .isEqualTo(42_000)
        assertThat(ResumePolicy.startPositionMs(requestedMs = 0, resumeEnabled = true, isLive = false, savedMs = 90_000))
            .isEqualTo(0)
        assertThat(ResumePolicy.startPositionMs(requestedMs = -5, resumeEnabled = false, isLive = false, savedMs = null))
            .isEqualTo(0)
    }

    @Test
    fun `the saved position is used when resuming is enabled`() {
        assertThat(ResumePolicy.startPositionMs(requestedMs = null, resumeEnabled = true, isLive = false, savedMs = 90_000))
            .isEqualTo(90_000)
        assertThat(ResumePolicy.startPositionMs(requestedMs = null, resumeEnabled = true, isLive = false, savedMs = null))
            .isEqualTo(0)
    }

    @Test
    fun `resume is skipped when disabled or live`() {
        assertThat(ResumePolicy.startPositionMs(requestedMs = null, resumeEnabled = false, isLive = false, savedMs = 90_000))
            .isEqualTo(0)
        assertThat(ResumePolicy.startPositionMs(requestedMs = null, resumeEnabled = true, isLive = true, savedMs = 90_000))
            .isEqualTo(0)
    }

    @Test
    fun `saved positions are looked up only when they can be used`() {
        assertThat(ResumePolicy.shouldLookUp(requestedMs = null, resumeEnabled = true, isLive = false)).isTrue()
        assertThat(ResumePolicy.shouldLookUp(requestedMs = 1_000, resumeEnabled = true, isLive = false)).isFalse()
        assertThat(ResumePolicy.shouldLookUp(requestedMs = null, resumeEnabled = false, isLive = false)).isFalse()
        assertThat(ResumePolicy.shouldLookUp(requestedMs = null, resumeEnabled = true, isLive = true)).isFalse()
    }

    @Test
    fun `positions are saved only for finite public media while enabled`() {
        assertThat(ResumePolicy.shouldSave(true, isLive = false, isPrivate = false, positionMs = 60_000, durationMs = 600_000)).isTrue()
        assertThat(ResumePolicy.shouldSave(false, isLive = false, isPrivate = false, positionMs = 60_000, durationMs = 600_000)).isFalse()
        assertThat(ResumePolicy.shouldSave(true, isLive = true, isPrivate = false, positionMs = 60_000, durationMs = 600_000)).isFalse()
        assertThat(ResumePolicy.shouldSave(true, isLive = false, isPrivate = true, positionMs = 60_000, durationMs = 600_000)).isFalse()
        assertThat(ResumePolicy.shouldSave(true, isLive = false, isPrivate = false, positionMs = 60_000, durationMs = 0)).isFalse()
    }
}
