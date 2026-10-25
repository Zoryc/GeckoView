package app.snapcast.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsInputTest {
    @Test
    fun `valid ports pass`() {
        assertThat(PortValidation.validate("8642")).isEqualTo(PortCheck.Valid(8642))
        assertThat(PortValidation.validate(" 1024 ")).isEqualTo(PortCheck.Valid(1024))
        assertThat(PortValidation.validate("65535")).isEqualTo(PortCheck.Valid(65535))
    }

    @Test
    fun `invalid ports report why`() {
        assertThat(PortValidation.validate("")).isEqualTo(PortCheck.Invalid(PortError.EMPTY))
        assertThat(PortValidation.validate("   ")).isEqualTo(PortCheck.Invalid(PortError.EMPTY))
        assertThat(PortValidation.validate("80a")).isEqualTo(PortCheck.Invalid(PortError.NOT_A_NUMBER))
        assertThat(PortValidation.validate("-1")).isEqualTo(PortCheck.Invalid(PortError.NOT_A_NUMBER))
        assertThat(PortValidation.validate("1023")).isEqualTo(PortCheck.Invalid(PortError.OUT_OF_RANGE))
        assertThat(PortValidation.validate("65536")).isEqualTo(PortCheck.Invalid(PortError.OUT_OF_RANGE))
        assertThat(PortValidation.validate("99999999999")).isEqualTo(PortCheck.Invalid(PortError.OUT_OF_RANGE))
    }

    @Test
    fun `domains are normalized to bare hosts`() {
        assertThat(DomainValidation.normalize("Ads.Example.COM")).isEqualTo("ads.example.com")
        assertThat(DomainValidation.normalize("https://ads.example.com:8080/path?q=1#x")).isEqualTo("ads.example.com")
        assertThat(DomainValidation.normalize("user@tracker.example.net")).isEqualTo("tracker.example.net")
        assertThat(DomainValidation.normalize("*.doubleclick.net")).isEqualTo("doubleclick.net")
        assertThat(DomainValidation.normalize("www.example.org.")).isEqualTo("example.org")
        assertThat(DomainValidation.normalize("bücher.de")).isEqualTo("xn--bcher-kva.de")
        assertThat(DomainValidation.normalize("192.168.1.20")).isEqualTo("192.168.1.20")
    }

    @Test
    fun `invalid domains are rejected`() {
        assertThat(DomainValidation.normalize("")).isNull()
        assertThat(DomainValidation.normalize("localhost")).isNull()
        assertThat(DomainValidation.normalize("not a domain")).isNull()
        assertThat(DomainValidation.normalize("exa_mple.com")).isNull()
        assertThat(DomainValidation.normalize("-bad.example.com")).isNull()
        assertThat(DomainValidation.normalize("example.123")).isNull()
        assertThat(DomainValidation.normalize("300.1.1.1")).isNull()
        assertThat(DomainValidation.normalize("[::1]")).isNull()
        assertThat(DomainValidation.normalize("example.com:port")).isNull()
    }

    @Test
    fun `domain check detects duplicates after normalization`() {
        val existing = setOf("doubleclick.net", "adnxs.com")
        assertThat(DomainValidation.check("https://www.DoubleClick.net/", existing))
            .isEqualTo(DomainCheck.Duplicate("doubleclick.net"))
        assertThat(DomainValidation.check("ads.example.com", existing)).isEqualTo(DomainCheck.Valid("ads.example.com"))
        assertThat(DomainValidation.check("nope", existing)).isEqualTo(DomainCheck.Invalid)
    }

    @Test
    fun `size options map stored values to the closest choice`() {
        assertThat(DetectionOptions.sizeOptionFor(0)).isEqualTo(0L)
        assertThat(DetectionOptions.sizeOptionFor(512L * 1024)).isEqualTo(512L * 1024)
        assertThat(DetectionOptions.sizeOptionFor(300L * 1024)).isEqualTo(256L * 1024)
        assertThat(DetectionOptions.sizeOptionFor(4L * 1024 * 1024)).isEqualTo(5L * 1024 * 1024)
        assertThat(DetectionOptions.sizeOptionFor(500L * 1024 * 1024)).isEqualTo(20L * 1024 * 1024)
        assertThat(DetectionOptions.sizeOptionFor(-10)).isEqualTo(0L)
        DetectionOptions.MIN_SIZE_BYTES.forEach { assertThat(DetectionOptions.sizeOptionFor(it)).isEqualTo(it) }
    }

    @Test
    fun `duration options map stored values to the closest choice`() {
        assertThat(DetectionOptions.durationOptionFor(5)).isEqualTo(5)
        assertThat(DetectionOptions.durationOptionFor(12)).isEqualTo(10)
        assertThat(DetectionOptions.durationOptionFor(120)).isEqualTo(30)
        assertThat(DetectionOptions.durationOptionFor(-3)).isEqualTo(0)
        DetectionOptions.MIN_DURATION_SEC.forEach { assertThat(DetectionOptions.durationOptionFor(it)).isEqualTo(it) }
    }

    @Test
    fun `home page input accepts web addresses only`() {
        assertThat(HomePageInput.normalize("example.com")).isEqualTo("https://example.com")
        assertThat(HomePageInput.normalize(" https://news.example.com/today ")).isEqualTo("https://news.example.com/today")
        assertThat(HomePageInput.normalize("http://192.168.1.2:8080")).isEqualTo("http://192.168.1.2:8080")
        assertThat(HomePageInput.normalize("cat videos")).isNull()
        assertThat(HomePageInput.normalize("about:blank")).isNull()
        assertThat(HomePageInput.normalize("")).isNull()
    }

    @Test
    fun `engine version is read from the user agent`() {
        val ua = "Mozilla/5.0 (Android 14; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0"
        assertThat(EngineVersion.fromUserAgent(ua)).isEqualTo("158.0")
        assertThat(EngineVersion.fromUserAgent("Mozilla/5.0 (Android 10; Mobile; rv:157.0.1) Gecko/157.0")).isEqualTo("157.0.1")
        assertThat(EngineVersion.fromUserAgent("SomethingElse/1.0")).isNull()
    }
}
