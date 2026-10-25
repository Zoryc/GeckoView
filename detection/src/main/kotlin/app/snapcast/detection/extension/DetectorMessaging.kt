package app.snapcast.detection.extension

import android.util.Log
import app.snapcast.core.detection.DetectorMessage
import app.snapcast.core.detection.DetectorProtocol
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

/** Receives what the detector's content scripts send for a tab. Called on the main thread. */
internal interface DetectorMessageSink {
    fun onPortConnected(tabId: String, isPrivate: Boolean, port: WebExtension.Port)

    fun onPortDisconnected(tabId: String, port: WebExtension.Port)

    fun onDetectorMessage(tabId: String, isPrivate: Boolean, message: DetectorMessage)
}

/**
 * Decodes one message posted by the extension. Port messages arrive as `org.json.JSONObject`s
 * (whose `toString()` is their JSON); strings are accepted too. Null for anything else or invalid.
 */
internal fun decodeDetectorMessage(message: Any): DetectorMessage? = DetectorProtocol.decode(message.toString())

/**
 * Message delegate of one tab's session: the top-frame content script connects with
 * `runtime.connectNative("snapcast")` and posts protocol messages on that port.
 */
internal class TabMessageDelegate(
    private val tabId: String,
    private val isPrivate: Boolean,
    private val sink: DetectorMessageSink,
) : WebExtension.MessageDelegate {
    override fun onConnect(port: WebExtension.Port) {
        port.setDelegate(
            object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, source: WebExtension.Port) {
                    decodeDetectorMessage(message)?.let { sink.onDetectorMessage(tabId, isPrivate, it) }
                }

                override fun onDisconnect(source: WebExtension.Port) {
                    sink.onPortDisconnected(tabId, source)
                }
            },
        )
        sink.onPortConnected(tabId, isPrivate, port)
    }

    /** `runtime.sendNativeMessage` fallback; the detector expects no answer. */
    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
        decodeDetectorMessage(message)?.let { sink.onDetectorMessage(tabId, isPrivate, it) }
        return null
    }
}

/**
 * Extension-level delegate for messages that belong to no session (the background page). The
 * detector does not send any; they are logged and ignored.
 */
internal object UnboundMessageDelegate : WebExtension.MessageDelegate {
    private const val TAG = "SnapcastDetection"

    override fun onConnect(port: WebExtension.Port) {
        Log.d(TAG, "Ignoring a detector port without a session")
        port.setDelegate(object : WebExtension.PortDelegate {})
    }

    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
        Log.d(TAG, "Ignoring a detector message without a session")
        return null
    }
}
