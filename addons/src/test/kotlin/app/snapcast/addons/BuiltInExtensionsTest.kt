package app.snapcast.addons

import app.snapcast.core.detection.DETECTOR_EXTENSION_ID
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BuiltInExtensionsTest {

    @Test
    fun `only gecko's built-in flag makes an add-on built in`() {
        assertThat(BuiltInExtensions.isBuiltIn(builtInFlag = true)).isTrue()
        assertThat(BuiltInExtensions.isBuiltIn(builtInFlag = false)).isFalse()
    }

    @Test
    fun `a downloaded add-on with the detector's id impersonates it`() {
        assertThat(BuiltInExtensions.impersonatesBuiltIn(DETECTOR_EXTENSION_ID, builtInFlag = false)).isTrue()
    }

    @Test
    fun `the real detector and other add-ons do not impersonate`() {
        assertThat(BuiltInExtensions.impersonatesBuiltIn(DETECTOR_EXTENSION_ID, builtInFlag = true)).isFalse()
        assertThat(BuiltInExtensions.impersonatesBuiltIn("uBlock0@raymondhill.net", builtInFlag = false)).isFalse()
        assertThat(BuiltInExtensions.impersonatesBuiltIn("other@snapcast.app", builtInFlag = true)).isFalse()
    }
}
