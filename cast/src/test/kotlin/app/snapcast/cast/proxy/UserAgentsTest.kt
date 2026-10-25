package app.snapcast.cast.proxy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserAgentsTest {

    @Test
    fun `builds the GeckoView mobile user agent`() {
        assertThat(UserAgents.geckoViewMobile("14"))
            .isEqualTo("Mozilla/5.0 (Android 14; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0")
        assertThat(UserAgents.geckoViewMobile("8.1.0")).startsWith("Mozilla/5.0 (Android 8; Mobile;")
        assertThat(UserAgents.geckoViewMobile(null)).contains("(Android 14; Mobile;")
    }
}
