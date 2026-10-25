# Snapcast — requirements

Native Android browser (Kotlin) built on Mozilla GeckoView. While the user browses, the app detects video
streams on the page, shows them in a list behind a floating button, and casts the chosen video to a
Chromecast. Supports Firefox/Gecko WebExtensions, especially uBlock Origin. Inspired by "Web Video Cast".

## Tech stack
- Kotlin, minSdk 26, targetSdk latest, Gradle Kotlin DSL, version catalog
- UI: Jetpack Compose + Material 3 (dark theme by default)
- Engine: GeckoView, latest stable from maven.mozilla.org. One shared GeckoRuntime (Application-scoped).
- Casting: Google Cast SDK (`play-services-cast-framework`), Default Media Receiver
- Local proxy server: NanoHTTPD running on the phone
- Persistence: Room (history, bookmarks), DataStore (settings)
- Architecture: MVVM, Hilt, Coroutines/Flow

## Core features

### 1. Browser
- Address bar with URL/search (configurable search engine), back/forward/reload/stop
- Tabs (GeckoSession per tab), tab switcher, private tabs
- Progress bar, page title, HTTPS lock indicator
- History, bookmarks, home page with speed-dial bookmarks
- Desktop-mode toggle (user agent switch)
- Downloads, permission prompts, file pickers, fullscreen, popups (Navigation, Progress, Content,
  Prompt, Permission, MediaSession delegates)

### 2. Video detection
- Built-in WebExtension (bundled in assets, installed with `WebExtensionController.ensureBuiltIn`) that:
  - uses `webRequest` to flag media by URL pattern (.m3u8, .mpd, .mp4, .webm, .mkv, .ts) and by
    Content-Type (video/*, application/vnd.apple.mpegurl, application/x-mpegURL, application/dash+xml)
  - uses a content script to scan `<video>`/`<source>` elements, including ones added later
    (MutationObserver) and inside iframes (all_frames: true)
  - captures URL, MIME type, page URL, page title, Referer, request headers (cookies, user-agent,
    origin), size/duration if known, poster/thumbnail if available
  - sends results to the app via native messaging (`browser.runtime.connectNative` +
    `WebExtension.MessageDelegate`)
- Ignore ads/tiny segments: dedupe HLS segment requests under their master playlist, skip very short
  files, dedupe by URL
- For HLS master playlists, parse variants and show available qualities
- Detected videos are stored per tab and cleared on navigation to a new page

### 3. Video list UI
- Floating action button with a badge showing the number of detected videos; pulses when a new video is found
- Bottom sheet listing videos: thumbnail, title, type (HLS/DASH/MP4), quality, size/duration, source domain
- Per-video actions: Cast, Play on phone (Media3), Copy link, Open with another app, Add to queue

### 4. Chromecast
- Cast button in the top bar (MediaRouteButton) with device discovery
- Casting goes through the local proxy `http://<phone-LAN-IP>:<port>/stream/<id>`, forwarding requests
  to the real URL with the captured headers (Referer, cookies, user-agent)
- Proxy supports Range requests (seeking), HEAD, and streaming without buffering whole files
- HLS: rewrite playlist URLs (variants, segments, keys, subtitles) through the proxy; same for DASH
- CORS headers on proxy responses
- Option to cast directly (no proxy) when headers aren't needed
- Expanded controller + mini controller: play/pause, seek bar, ±10 s, volume, stop, playback speed
- Media notification and lock-screen controls while casting
- Queue: play next, reorder, remove
- Subtitles: detect .vtt/.srt on page, load a local subtitle file; convert SRT to WebVTT
- Foreground service while casting so the proxy isn't killed

### 5. WebExtension / add-on support
- Add-ons screen: list, enable/disable, uninstall, options page, popup (browser action) in a sheet
- Install from addons.mozilla.org: intercept the .xpi and install with `WebExtensionController.install`;
  show the permission prompt (`WebExtensionController.PromptDelegate`)
- One-tap "Install uBlock Origin" using the official AMO latest .xpi URL
- Signature enforcement stays ON
- uBlock Origin's toolbar icon/popup and dashboard work
- Extensions apply in private tabs if the user allows it

### 6. Settings
- Search engine, home page, desktop mode default
- Video detection: minimum file size, ignore list of domains
- Casting: proxy on/off, proxy port
- Clear cookies/cache/history

## Nice-to-have (after MVP)
- Bookmarks of favorite video sites on the home screen
- Cast local phone videos (via the same proxy)
- "Resume where you left off" for cast videos
- IPTV playlist (.m3u) import
- Picture-in-picture for local playback
- Other receivers later (DLNA, Roku, Fire TV) — casting layer behind a `CastTarget` interface

## Constraints
- Never bypass DRM (Widevine/EME). Detect DRM-protected streams and show a clear message.
- Phone and Chromecast must be on the same Wi-Fi; show a helpful error otherwise.
- Android 13+ notification permission; foreground service type `mediaPlayback`.
- Separate Gradle modules for `browser`, `detection`, `cast`, `proxy`, `addons`, `ui`.
- Unit tests for URL/MIME classification, HLS/DASH playlist rewriting, Range handling in the proxy.

## Test pages
- A page with a plain `<video src="...mp4">`
- A public HLS test stream (Apple / Mux sample .m3u8)
- A public DASH test stream
- A video inside an iframe
- A news site with ads (uBlock Origin)
