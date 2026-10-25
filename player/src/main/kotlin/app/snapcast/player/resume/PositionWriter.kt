package app.snapcast.player.resume

import app.snapcast.data.playback.PlaybackPositionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes playback positions in submission order on [scope] (the application scope, so the last save from
 * `onStop`/`onDestroy` still completes after the activity is gone). Serializing matters: a periodic save
 * that ran after the "playback ended" clear would otherwise resurrect a finished video.
 *
 * Call [close] when the owner goes away; pending writes are still performed.
 */
internal class PositionWriter(
    private val repository: PlaybackPositionRepository,
    scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
) {
    private sealed interface Op {
        data class Save(val url: String, val positionMs: Long, val durationMs: Long) : Op
        data class Clear(val url: String) : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (op in ops) {
                try {
                    when (op) {
                        is Op.Save -> repository.save(op.url, op.positionMs, op.durationMs)
                        is Op.Clear -> repository.clear(op.url)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    /** Remembers [positionMs] of [url]; the repository drops positions too close to either end. */
    fun save(url: String, positionMs: Long, durationMs: Long) {
        ops.trySend(Op.Save(url, positionMs, durationMs))
    }

    /** Forgets the position of [url] (playback finished or the user started over). */
    fun clear(url: String) {
        ops.trySend(Op.Clear(url))
    }

    /** Stops accepting writes; already queued ones still run. */
    fun close() {
        ops.close()
    }
}
