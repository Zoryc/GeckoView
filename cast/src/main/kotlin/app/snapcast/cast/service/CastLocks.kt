package app.snapcast.cast.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Wi-Fi and CPU locks held while casting, so the proxy keeps serving the receiver with the screen off.
 * Not reference counted; safe to acquire and release repeatedly.
 */
internal class CastLocks(context: Context) {
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(wifiLockMode(), LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private val wakeLock: PowerManager.WakeLock? = runCatching {
        context.applicationContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private var wakeLockAcquiredAt = 0L

    fun acquire() {
        runCatching { wifiLock?.takeUnless { it.isHeld }?.acquire() }
        acquireWakeLock()
    }

    /**
     * Extends the wake lock's timeout while playback continues (it is time-limited so a lost release cannot
     * drain the battery). Cheap to call often: it only renews once half of the timeout has passed.
     */
    fun refresh() {
        val lock = wakeLock ?: return
        val renewDue = SystemClock.elapsedRealtime() - wakeLockAcquiredAt > WAKE_LOCK_TIMEOUT_MS / 2
        if (lock.isHeld && renewDue) acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        runCatching {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLockAcquiredAt = SystemClock.elapsedRealtime()
        }
    }

    fun release() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    private companion object {
        const val LOCK_TAG = "Snapcast:Casting"
        const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        fun wifiLockMode(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
    }
}
