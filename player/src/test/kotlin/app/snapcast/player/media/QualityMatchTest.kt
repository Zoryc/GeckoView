package app.snapcast.player.media

import app.snapcast.player.PlaybackQuality
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QualityMatchTest {
    private val ladder = listOf(
        QualityMatch.Rendition(640, 360, 800_000),
        QualityMatch.Rendition(1280, 720, 2_500_000),
        QualityMatch.Rendition(1920, 1080, 5_000_000),
    )

    @Test
    fun picksTheSameResolution() {
        assertThat(QualityMatch.pick(ladder, PlaybackQuality(640, 360, 800_000))).isEqualTo(0)
        assertThat(QualityMatch.pick(ladder, PlaybackQuality(null, 720, null))).isEqualTo(1)
    }

    @Test
    fun usesTheBitrateWhenOnlyItIsKnown() {
        assertThat(QualityMatch.pick(ladder, PlaybackQuality(bandwidth = 4_800_000))).isEqualTo(2)
        val noSizes = ladder.map { it.copy(width = -1, height = -1) }
        assertThat(QualityMatch.pick(noSizes, PlaybackQuality(1280, 720, 2_400_000))).isEqualTo(1)
    }

    @Test
    fun picksTheNearestWhenTheExactOneIsMissing() {
        assertThat(QualityMatch.pick(ladder, PlaybackQuality(854, 480, null))).isEqualTo(0)
        assertThat(QualityMatch.pick(ladder, PlaybackQuality(1080, 1920, null))).isEqualTo(2)
    }

    @Test
    fun nothingToMatch() {
        assertThat(QualityMatch.pick(emptyList(), PlaybackQuality(640, 360, null))).isNull()
        assertThat(QualityMatch.pick(ladder, PlaybackQuality())).isNull()
    }
}
