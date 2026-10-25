package app.snapcast.browser.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadFileNamesTest {
    @Test
    fun `extended filename is percent-decoded with its charset`() {
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename*=UTF-8''%E2%82%AC%20rates.txt"))
            .isEqualTo("€ rates.txt")
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename*=iso-8859-1'en'%A3%20rates.csv"))
            .isEqualTo("£ rates.csv")
    }

    @Test
    fun `extended filename wins over plain filename in any order`() {
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename=\"fallback.txt\"; filename*=UTF-8''real.txt"))
            .isEqualTo("real.txt")
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename*=UTF-8''real.txt; filename=\"fallback.txt\""))
            .isEqualTo("real.txt")
    }

    @Test
    fun `malformed extended filename falls back to plain filename`() {
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename*=UTF-8''bad%ZZ.txt; filename=\"ok.txt\""))
            .isEqualTo("ok.txt")
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename*=no-quotes.txt; filename=ok.txt"))
            .isEqualTo("ok.txt")
    }

    @Test
    fun `quoted filenames keep semicolons and unescape quotes`() {
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename=\"a;b.txt\"; size=3")).isEqualTo("a;b.txt")
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename=\"say \\\"hi\\\".txt\"")).isEqualTo("say _hi_.txt")
    }

    @Test
    fun `token filenames, parameter case and missing disposition type`() {
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename=report.pdf")).isEqualTo("report.pdf")
        assertThat(DownloadFileNames.fromContentDisposition("ATTACHMENT; FILENAME=Report.PDF")).isEqualTo("Report.PDF")
        assertThat(DownloadFileNames.fromContentDisposition("filename=a.pdf")).isEqualTo("a.pdf")
        assertThat(DownloadFileNames.fromContentDisposition("inline;filename=movie.mp4")).isEqualTo("movie.mp4")
    }

    @Test
    fun `no usable filename gives null`() {
        assertThat(DownloadFileNames.fromContentDisposition(null)).isNull()
        assertThat(DownloadFileNames.fromContentDisposition("")).isNull()
        assertThat(DownloadFileNames.fromContentDisposition("attachment")).isNull()
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename=\"\"")).isNull()
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename=\"...\"")).isNull()
    }

    @Test
    fun `path components are stripped`() {
        assertThat(DownloadFileNames.fromContentDisposition("attachment; filename=\"../../etc/passwd\"")).isEqualTo("passwd")
        assertThat(DownloadFileNames.sanitize("C:\\Users\\me\\file.txt")).isEqualTo("file.txt")
    }

    @Test
    fun `sanitize replaces reserved and control characters and trims`() {
        assertThat(DownloadFileNames.sanitize("  a:b*c?.txt  ")).isEqualTo("a_b_c_.txt")
        assertThat(DownloadFileNames.sanitize("tab\there\u0001.txt")).isEqualTo("tab here_.txt")
        assertThat(DownloadFileNames.sanitize("..hidden..")).isEqualTo("hidden")
        assertThat(DownloadFileNames.sanitize("???")).isNull()
        assertThat(DownloadFileNames.sanitize("   ")).isNull()
        assertThat(DownloadFileNames.sanitize(null)).isNull()
    }

    @Test
    fun `long names are shortened but keep their extension`() {
        val name = "x".repeat(300) + ".mp4"
        val sanitized = DownloadFileNames.sanitize(name)!!
        assertThat(sanitized.length).isEqualTo(120)
        assertThat(sanitized).endsWith(".mp4")
        assertThat(DownloadFileNames.sanitize("y".repeat(300))!!.length).isEqualTo(120)
    }

    @Test
    fun `mime type is normalized`() {
        assertThat(DownloadFileNames.mimeTypeOf("Video/MP4; charset=binary")).isEqualTo("video/mp4")
        assertThat(DownloadFileNames.mimeTypeOf(" application/pdf ")).isEqualTo("application/pdf")
        assertThat(DownloadFileNames.mimeTypeOf("garbage")).isNull()
        assertThat(DownloadFileNames.mimeTypeOf("")).isNull()
        assertThat(DownloadFileNames.mimeTypeOf(null)).isNull()
    }

    @Test
    fun `content length parsing`() {
        assertThat(DownloadFileNames.contentLengthOf("12345")).isEqualTo(12345L)
        assertThat(DownloadFileNames.contentLengthOf(" 0 ")).isEqualTo(0L)
        assertThat(DownloadFileNames.contentLengthOf("-1")).isNull()
        assertThat(DownloadFileNames.contentLengthOf("abc")).isNull()
        assertThat(DownloadFileNames.contentLengthOf(null)).isNull()
    }

    @Test
    fun `extensions and numbered copies`() {
        assertThat(DownloadFileNames.extensionOf("Movie.MKV")).isEqualTo("mkv")
        assertThat(DownloadFileNames.extensionOf("README")).isNull()
        assertThat(DownloadFileNames.extensionOf(".profile")).isNull()
        assertThat(DownloadFileNames.extensionOf("trailing.")).isNull()
        assertThat(DownloadFileNames.numbered("a.txt", 1)).isEqualTo("a (1).txt")
        assertThat(DownloadFileNames.numbered("README", 2)).isEqualTo("README (2)")
    }
}
