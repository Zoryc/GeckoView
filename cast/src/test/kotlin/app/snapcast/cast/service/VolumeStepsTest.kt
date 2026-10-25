package app.snapcast.cast.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VolumeStepsTest {

    @Test
    fun `levels map to twenty steps`() {
        assertThat(VolumeSteps.toSteps(0.0)).isEqualTo(0)
        assertThat(VolumeSteps.toSteps(0.5)).isEqualTo(10)
        assertThat(VolumeSteps.toSteps(1.0)).isEqualTo(20)
        assertThat(VolumeSteps.toSteps(0.33)).isEqualTo(7)
    }

    @Test
    fun `out of range levels are clamped`() {
        assertThat(VolumeSteps.toSteps(1.7)).isEqualTo(20)
        assertThat(VolumeSteps.toSteps(-0.2)).isEqualTo(0)
        assertThat(VolumeSteps.toSteps(Double.NaN)).isEqualTo(0)
    }

    @Test
    fun `steps map back to levels`() {
        assertThat(VolumeSteps.toLevel(0)).isEqualTo(0.0)
        assertThat(VolumeSteps.toLevel(5)).isEqualTo(0.25)
        assertThat(VolumeSteps.toLevel(20)).isEqualTo(1.0)
        assertThat(VolumeSteps.toLevel(25)).isEqualTo(1.0)
    }

    @Test
    fun `adjusting moves one step and stays in range`() {
        assertThat(VolumeSteps.adjust(10, 1)).isEqualTo(11)
        assertThat(VolumeSteps.adjust(10, -1)).isEqualTo(9)
        assertThat(VolumeSteps.adjust(10, 0)).isEqualTo(10)
        assertThat(VolumeSteps.adjust(20, 1)).isEqualTo(20)
        assertThat(VolumeSteps.adjust(0, -3)).isEqualTo(0)
    }
}
