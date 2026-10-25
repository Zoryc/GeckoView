package app.snapcast.player.pip

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PipAspectRatioTest {

    @Test
    fun regularVideosKeepTheirSize() {
        assertThat(PipAspectRatio.of(1920, 1080)).isEqualTo(PipAspectRatio(1920, 1080))
        assertThat(PipAspectRatio.of(1080, 1920)).isEqualTo(PipAspectRatio(1080, 1920))
        assertThat(PipAspectRatio.of(640, 640)).isEqualTo(PipAspectRatio(640, 640))
    }

    @Test
    fun anamorphicPixelsWidenTheDisplayRatio() {
        // PAL 16:9 DVD: 720x576 stored, pixels 64:45 wide -> 1024x576.
        assertThat(PipAspectRatio.of(720, 576, 64f / 45f)).isEqualTo(PipAspectRatio(1024, 576))
    }

    @Test
    fun invalidPixelRatioIsTreatedAsSquare() {
        assertThat(PipAspectRatio.of(1280, 720, 0f)).isEqualTo(PipAspectRatio(1280, 720))
        assertThat(PipAspectRatio.of(1280, 720, Float.NaN)).isEqualTo(PipAspectRatio(1280, 720))
        assertThat(PipAspectRatio.of(1280, 720, -1f)).isEqualTo(PipAspectRatio(1280, 720))
    }

    @Test
    fun tooWideVideosAreClampedTo239By100() {
        assertThat(PipAspectRatio.of(3000, 1000)).isEqualTo(PipAspectRatio.MAX)
        assertThat(PipAspectRatio.of(2400, 1000)).isEqualTo(PipAspectRatio.MAX)
    }

    @Test
    fun tooTallVideosAreClampedTo100By239() {
        assertThat(PipAspectRatio.of(1000, 3000)).isEqualTo(PipAspectRatio.MIN)
        assertThat(PipAspectRatio.of(417, 1000)).isEqualTo(PipAspectRatio.MIN)
    }

    @Test
    fun limitsThemselvesAreAccepted() {
        assertThat(PipAspectRatio.of(2390, 1000)).isEqualTo(PipAspectRatio(2390, 1000))
        assertThat(PipAspectRatio.of(1000, 2390)).isEqualTo(PipAspectRatio(1000, 2390))
    }

    @Test
    fun roundingNeverPushesTheRatioOutOfBounds() {
        // 7 * 2.39 = 16.73; a 2.3857 pixel ratio rounds the width to 17 (2.43:1), which must clamp.
        val ratio = PipAspectRatio.of(7, 7, 16.7f / 7f)
        assertThat(ratio).isEqualTo(PipAspectRatio.MAX)
        for (height in 1..200) {
            for (width in listOf(1, height / 3, height * 2, height * 3)) {
                val result = PipAspectRatio.of(width, height, 1.17f) ?: continue
                val value = result.numerator.toDouble() / result.denominator
                assertThat(value).isAtMost(2.39)
                assertThat(value).isAtLeast(100.0 / 239.0)
            }
        }
    }

    @Test
    fun unknownSizeHasNoRatio() {
        assertThat(PipAspectRatio.of(0, 0)).isNull()
        assertThat(PipAspectRatio.of(1920, 0)).isNull()
        assertThat(PipAspectRatio.of(-1, 1080)).isNull()
        assertThat(PipAspectRatio.of(1, 1080, 0.1f)).isNull()
    }
}
