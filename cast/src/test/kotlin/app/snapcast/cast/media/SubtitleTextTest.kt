package app.snapcast.cast.media

import app.snapcast.core.model.SubtitleFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleTextTest {

    @Test
    fun `UTF-8 text is decoded and its BOM removed`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Déjà vu".toByteArray(Charsets.UTF_8)
        assertThat(SubtitleText.decode(bytes)).isEqualTo("Déjà vu")
    }

    @Test
    fun `invalid UTF-8 falls back to windows-1252`() {
        val bytes = "Déjà vu – ok".toByteArray(charset("windows-1252"))
        assertThat(SubtitleText.decode(bytes)).isEqualTo("Déjà vu – ok")
    }

    @Test
    fun `UTF-16 with a BOM is decoded`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Déjà vu".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Déjà vu".toByteArray(Charsets.UTF_16BE)
        assertThat(SubtitleText.decode(le)).isEqualTo("Déjà vu")
        assertThat(SubtitleText.decode(be)).isEqualTo("Déjà vu")
    }

    @Test
    fun `a UTF-16 SRT file decodes to parseable text`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + srt.toByteArray(Charsets.UTF_16LE)
        assertThat(SubtitleText.decode(bytes)).isEqualTo(srt)
        assertThat(SubtitleText.decode(byteArrayOf(0xFF.toByte()))).isEqualTo("ÿ")
    }

    @Test
    fun `format and name come from the file name`() {
        assertThat(SubtitleText.formatFromFileName("movie.en.SRT")).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleText.formatFromFileName("movie.vtt")).isEqualTo(SubtitleFormat.VTT)
        assertThat(SubtitleText.formatFromFileName("movie.txt")).isEqualTo(SubtitleFormat.UNKNOWN)
        assertThat(SubtitleText.formatFromFileName(null)).isEqualTo(SubtitleFormat.UNKNOWN)
        assertThat(SubtitleText.trackName("movie.en.srt", "Subtitles")).isEqualTo("movie.en")
        assertThat(SubtitleText.trackName(null, "Subtitles")).isEqualTo("Subtitles")
    }
}
