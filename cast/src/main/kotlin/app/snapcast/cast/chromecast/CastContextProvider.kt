package app.snapcast.cast.chromecast

import android.content.Context
import android.util.Log
import app.snapcast.data.di.ApplicationScope
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Obtains the shared [CastContext] asynchronously. Devices without (up-to-date) Google Play services end
 * up [State.Unavailable] instead of crashing.
 */
@Singleton
internal class CastContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Initializing : State

        data class Ready(val castContext: CastContext) : State

        data object Unavailable : State
    }

    private val started = AtomicBoolean(false)
    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The context when ready, without waiting. */
    val castContextOrNull: CastContext? get() = (_state.value as? State.Ready)?.castContext

    /** Starts initialization once; safe to call repeatedly from any thread. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // CastContext.getSharedInstance must be called on the main thread.
        scope.launch(Dispatchers.Main.immediate) {
            _state.value = try {
                if (isPlayServicesAvailable()) {
                    State.Ready(CastContext.getSharedInstance(context, Dispatchers.IO.asExecutor()).await())
                } else {
                    Log.i(TAG, "Google Play services unavailable; casting disabled")
                    State.Unavailable
                }
            } catch (e: CancellationException) {
                // A cancelled Task also surfaces as CancellationException; only rethrow if we were cancelled.
                ensureActive()
                State.Unavailable
            } catch (e: Exception) {
                Log.w(TAG, "Cast framework unavailable", e)
                State.Unavailable
            } catch (e: LinkageError) {
                Log.w(TAG, "Cast framework unavailable", e)
                State.Unavailable
            }
        }
    }

    /** Waits for initialization; null when casting is unavailable on this device. */
    suspend fun await(): CastContext? {
        start()
        return (state.first { it !is State.Initializing } as? State.Ready)?.castContext
    }

    private fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private companion object {
        const val TAG = "SnapcastCast"
    }
}
