package app.snapcast.data.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticsLogTest {
    @Test
    fun linksAreReducedToTheirHost() {
        assertThat(InMemoryDiagnosticsLog.withoutLinks("Failed: https://user:pw@cdn.example.net:8443/hls/master.m3u8?token=abc#x (403)"))
            .isEqualTo("Failed: cdn.example.net:8443 (403)")
        assertThat(InMemoryDiagnosticsLog.withoutLinks("'http://a.example/x?y' and content://media/7"))
            .isEqualTo("'a.example' and media")
        assertThat(InMemoryDiagnosticsLog.withoutLinks("no link here")).isEqualTo("no link here")
    }

    @Test
    fun keepsOnlyTheLatestEvents() {
        val log = InMemoryDiagnosticsLog()
        repeat(25) { log.record("player", "event $it https://cdn.example/s/$it.ts") }
        val recent = log.recent()
        assertThat(recent).hasSize(20)
        assertThat(recent.first().message).isEqualTo("event 5 cdn.example")
        assertThat(recent.last().message).isEqualTo("event 24 cdn.example")
    }
}
