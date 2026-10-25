package app.snapcast.browser.delegates

import app.snapcast.browser.hooks.BrowserHooks
import app.snapcast.browser.tabs.TabHost
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/** Tracks whether a tab plays media and forwards media metadata to observers. */
internal class TabMediaSessionDelegate(
    private val host: TabHost,
    private val hooks: BrowserHooks,
) : MediaSession.Delegate {

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        // Activation alone does not mean playback; onPlay follows when media starts.
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        setPlaying(session, false)
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        setPlaying(session, true)
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        setPlaying(session, false)
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        setPlaying(session, false)
    }

    override fun onMetadata(session: GeckoSession, mediaSession: MediaSession, meta: MediaSession.Metadata) {
        val tab = host.tabOf(session) ?: return
        hooks.notify { it.onMediaMetadata(tab.id, meta.title, meta.artist, meta.album) }
    }

    private fun setPlaying(session: GeckoSession, playing: Boolean) {
        val tab = host.tabOf(session) ?: return
        if (tab.state.isMediaPlaying != playing) host.update(tab) { it.copy(isMediaPlaying = playing) }
    }
}
