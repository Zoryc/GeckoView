package app.snapcast.detection.extension

import app.snapcast.core.detection.DetectorMessage
import app.snapcast.core.detection.DrmMessage
import app.snapcast.core.detection.HelloMessage
import app.snapcast.core.detection.MediaMessage
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertIs
import org.junit.Test
import org.mozilla.geckoview.WebExtension

class DetectorMessagingTest {
    /** A port that records the delegate instead of registering engine listeners. */
    private class FakePort : WebExtension.Port() {
        var portDelegate: WebExtension.PortDelegate? = null

        override fun setDelegate(delegate: WebExtension.PortDelegate?) {
            portDelegate = delegate
        }
    }

    private class FakeSender : WebExtension.MessageSender()

    private class RecordingSink : DetectorMessageSink {
        val events = mutableListOf<String>()
        val messages = mutableListOf<DetectorMessage>()
        var connected: WebExtension.Port? = null

        override fun onPortConnected(tabId: String, isPrivate: Boolean, port: WebExtension.Port) {
            events += "connect $tabId $isPrivate"
            connected = port
        }

        override fun onPortDisconnected(tabId: String, port: WebExtension.Port) {
            events += "disconnect $tabId"
        }

        override fun onDetectorMessage(tabId: String, isPrivate: Boolean, message: DetectorMessage) {
            events += "message $tabId $isPrivate"
            messages += message
        }
    }

    @Test
    fun `decodes protocol messages from their JSON text`() {
        val media = assertIs<MediaMessage>(
            decodeDetectorMessage(
                """{"type":"media","items":[{"url":"https://cdn.example/a.mp4","source":"dom","size":6442450944,""" +
                    """"timestamp":1729000000000,"requestHeaders":{"Referer":"https://site.example/"},"tracks":[],"extra":1}]}""",
            ),
        )
        assertThat(media.items.single().size).isEqualTo(6_442_450_944L)
        assertThat(media.items.single().requestHeaders).containsExactly("Referer", "https://site.example/")
        assertThat(decodeDetectorMessage("""{"type":"drm"}""")).isEqualTo(DrmMessage())
    }

    @Test
    fun `malformed and unknown messages are dropped`() {
        assertThat(decodeDetectorMessage("""{"type":"unknown"}""")).isNull()
        assertThat(decodeDetectorMessage("not json")).isNull()
        assertThat(decodeDetectorMessage(42)).isNull()
    }

    @Test
    fun `a connecting port is handed to the sink and its messages are decoded for the tab`() {
        val sink = RecordingSink()
        val delegate = TabMessageDelegate("tab-1", isPrivate = true, sink = sink)
        val port = FakePort()

        delegate.onConnect(port)
        assertThat(sink.connected).isSameInstanceAs(port)
        val portDelegate = checkNotNull(port.portDelegate)

        portDelegate.onPortMessage("""{"type":"hello","url":"https://site.example/"}""", port)
        portDelegate.onPortMessage("""{"type":"bogus"}""", port)
        portDelegate.onDisconnect(port)

        assertThat(sink.events).containsExactly("connect tab-1 true", "message tab-1 true", "disconnect tab-1").inOrder()
        assertThat(sink.messages).containsExactly(HelloMessage(url = "https://site.example/"))
    }

    @Test
    fun `one-off native messages are accepted too and need no answer`() {
        val sink = RecordingSink()
        val delegate = TabMessageDelegate("tab-2", isPrivate = false, sink = sink)

        val answer = delegate.onMessage("snapcast", """{"type":"drm","keySystem":"com.widevine.alpha"}""", FakeSender())

        assertThat(answer).isNull()
        assertThat(sink.messages).containsExactly(DrmMessage(keySystem = "com.widevine.alpha"))
    }
}
