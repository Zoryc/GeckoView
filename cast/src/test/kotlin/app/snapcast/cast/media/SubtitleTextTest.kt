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
    fun `format and name come from the file name`() {
        assertThat(SubtitleText.formatFromFileName("movie.en.SRT")).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleText.formatFromFileName("movie.vtt")).isEqualTo(SubtitleFormat.VTT)
        assertThat(SubtitleText.formatFromFileName("movie.txt")).isEqualTo(SubtitleFormat.UNKNOWN)
        assertThat(SubtitleText.formatFromFileName(null)).isEqualTo(SubtitleFormat.UNKNOWN)
        assertThat(SubtitleText.trackName("movie.en.srt", "Subtitles")).isEqualTo("movie.en")
        assertThat(SubtitleText.trackName(null, "Subtitles")).isEqualTo("Subtitles")
    }
}
