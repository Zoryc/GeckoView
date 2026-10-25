package app.snapcast.player.resume

import app.snapcast.data.playback.PlaybackPosition
import app.snapcast.data.playback.PlaybackPositionRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PositionWriterTest {

    private class RecordingRepository : PlaybackPositionRepository {
        val calls = mutableListOf<String>()
        var failNextSave = false

        override suspend fun get(url: String): PlaybackPosition? = null

        override suspend fun save(url: String, positionMs: Long, durationMs: Long) {
            if (failNextSave) {
                failNextSave = false
                throw IOException("disk full")
            }
            calls += "save $url $positionMs/$durationMs"
        }

        override suspend fun clear(url: String) {
            calls += "clear $url"
        }

        override suspend fun clearAll() {
            calls += "clearAll"
        }
    }

    /** Foreground scope on the test scheduler: advanceUntilIdle() does not wait for backgroundScope work. */
    private fun TestScope.writerScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    @Test
    fun writesRunInSubmissionOrder() = runTest {
        val repository = RecordingRepository()
        val writer = PositionWriter(repository, writerScope())

        writer.save("https://a.example/v.mp4", 60_000L, 600_000L)
        writer.clear("https://a.example/v.mp4")
        writer.save("https://b.example/v.mp4", 30_000L, 300_000L)
        advanceUntilIdle()

        assertThat(repository.calls).containsExactly(
            "save https://a.example/v.mp4 60000/600000",
            "clear https://a.example/v.mp4",
            "save https://b.example/v.mp4 30000/300000",
        ).inOrder()
    }

    @Test
    fun aFailedWriteIsReportedAndLaterWritesStillRun() = runTest {
        val repository = RecordingRepository().apply { failNextSave = true }
        val errors = mutableListOf<Throwable>()
        val writer = PositionWriter(repository, writerScope()) { errors += it }

        writer.save("https://a.example/v.mp4", 60_000L, 600_000L)
        writer.save("https://a.example/v.mp4", 65_000L, 600_000L)
        advanceUntilIdle()

        assertThat(errors.map { it.message }).containsExactly("disk full")
        assertThat(repository.calls).containsExactly("save https://a.example/v.mp4 65000/600000")
    }

    @Test
    fun writesQueuedBeforeCloseAreStillPerformed() = runTest {
        val repository = RecordingRepository()
        val writer = PositionWriter(repository, writerScope())

        writer.save("https://a.example/v.mp4", 60_000L, 600_000L)
        writer.close()
        writer.clear("https://a.example/v.mp4")
        advanceUntilIdle()

        assertThat(repository.calls).containsExactly("save https://a.example/v.mp4 60000/600000")
    }
}
