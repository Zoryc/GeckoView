# Snapcast architecture

Snapcast is a GeckoView-based Android browser that detects videos on web pages and casts them to a
Chromecast through a local proxy that replays the browser's request headers.

## Modules

| Module | Kind | Package | Responsibility |
|---|---|---|---|
| `:core` | Kotlin/JVM | `app.snapcast.core` | Models (`DetectedVideo`, `AppSettings`, ...), media classification, HLS/DASH parsing, DRM signals, SRT→WebVTT, M3U, URL helpers, detector wire protocol, per-tab `VideoCollector`. No Android APIs. |
| `:proxy` | Kotlin/JVM | `app.snapcast.proxy` | `StreamProxy`: NanoHTTPD server + OkHttp upstream. Header forwarding, Range/HEAD/OPTIONS, streaming, HLS/DASH rewriting, CORS, signed sub-resource URLs, local files, subtitles. |
| `:data` | Android lib | `app.snapcast.data` | Room (history, bookmarks, playback positions), DataStore (settings, saved tabs), shared OkHttpClient and `@ApplicationScope` CoroutineScope, `AppInitializer` contract. |
| `:browser` | Android lib | `app.snapcast.browser` | `GeckoRuntimeProvider` (one runtime per process), `BrowserTabs` (`TabManager`: one `GeckoSession` per tab, private tabs, delegates: Navigation, Progress, Content, Prompt, Permission, MediaSession, History), downloads, hooks for other modules (`SessionObserver`, `LoadInterceptor`, `ExternalResponseInterceptor`). |
| `:detection` | Android lib | `app.snapcast.detection` | Bundled WebExtension (`src/main/assets/extensions/snapcast_detector/`) installed with `ensureBuiltIn`, native messaging, `DetectedVideoRepository` (per-tab lists, HLS/DASH inspection, DRM flags, filters). |
| `:addons` | Android lib | `app.snapcast.addons` | `AddonsRepository`: list/enable/disable/uninstall, install from AMO (.xpi interception) and one-tap uBlock Origin, permission prompts, browser-action popups, options pages, extension-opened tabs. |
| `:cast` | Android lib | `app.snapcast.cast` | `CastTarget` abstraction + Chromecast implementation (Cast SDK, Default Media Receiver), `CastController` facade, proxy lifecycle, `CastPlaybackService` (mediaPlayback foreground service, media notification, lock-screen controls, Wi-Fi/wake locks), queue, subtitles, resume positions. |
| `:player` | Android lib | `app.snapcast.player` | `PlayerActivity` (Media3 ExoPlayer, HLS/DASH/progressive, header replay, side-loaded subtitles, picture-in-picture, resume) and `PlayerLauncher`. |
| `:ui` | Android lib | `app.snapcast.ui` | Jetpack Compose + Material 3 screens and Hilt ViewModels: browser, tabs, home/speed dial, history, bookmarks, settings, add-ons, video sheet, cast controllers, queue, prompts. |
| `:app` | Android app | `app.snapcast` | `SnapcastApplication` (Hilt), `MainActivity` (FragmentActivity + AppCompat theme, needed by MediaRouteButton), manifest, themes, startup. |

Dependency direction: `app → ui → {browser, detection, addons, cast, player} → data → core`; `cast → proxy → core`;
`detection → browser`; `addons → browser`. Feature modules never depend on `ui` or `app`.

## Cross-module rules

* **Contracts first.** Public interfaces live in the files listed below. Implementations are internal to their module and bound with Hilt `@Binds` in a module-local `@Module @InstallIn(SingletonComponent::class)`.
  * core: every file under `core/src/main/kotlin`
  * proxy: `ProxyApi.kt`, `StreamProxy.kt` (public signatures)
  * data: `AppInitializer`, `di/Qualifiers.kt`, `history/`, `bookmarks/`, `settings/`, `playback/`, `tabs/` interfaces
  * browser: `GeckoRuntimeProvider`, `BrowserTabs`, `TabState`, `BrowserPrompt`, `BrowserEvent`, `SessionObserver` (+ `LoadInterceptor`, `ExternalResponseInterceptor`)
  * detection: `DetectedVideoRepository`
  * addons: `AddonsRepository` (+ models)
  * cast: `CastTarget`, `CastController` (+ models)
  * player: `PlaybackRequest`, `PlayerLauncher`
* **Startup.** Modules contribute `AppInitializer`s (`@Binds @IntoSet`). `SnapcastApplication` runs them on the main thread, sorted by `priority`, **only in the main process** (GeckoView spawns child processes that also create the Application). The browser module's initializer creates the `GeckoRuntime` (`PRIORITY_RUNTIME`); detection/addons use `PRIORITY_EXTENSIONS`.
* **Hooks instead of dependencies.** The browser module exposes `Set<SessionObserver>`, `Set<LoadInterceptor>`, `Set<ExternalResponseInterceptor>` multibindings (declared with `@Multibinds` so they may be empty). Detection and addons contribute implementations. To avoid Dagger cycles, `TabManager` injects `dagger.Lazy<Set<...>>` and hook implementations inject `dagger.Lazy<BrowserTabs>`.
* **Threading.** GeckoView objects (`GeckoRuntime`, `GeckoSession`, `WebExtension`, delegates) are used on the main thread. `GeckoResult` callbacks run on the thread that created them (main). Use `Dispatchers.Main.immediate` for engine calls, `Dispatchers.IO` for network/disk. Repositories expose `StateFlow`/`Flow`.
* **Shared infrastructure** comes from `:data`: `OkHttpClient` (singleton), `@ApplicationScope CoroutineScope`.
* **Settings** are read through `SettingsRepository.settings: StateFlow<AppSettings>`.

## Video detection pipeline

```
 web page ──webRequest (background.js)──┐
          ──<video>/<source>/<track>     │  tabs.sendMessage(frameId 0)
            scan + MutationObserver      ▼
            (content.js, all frames) ─► top-frame content.js ─connectNative("snapcast")─► Kotlin PortDelegate
                                                                                         │  DetectorProtocol.decode
                                                                                         ▼
                                          VideoCollector (per tab) ─► HLS/DASH inspection ─► DetectedVideoRepository
```

* `background.js` watches `webRequest.onSendHeaders` (request headers incl. Cookie) and `onHeadersReceived`
  (Content-Type / Content-Length / Content-Range), classifies by URL pattern **and** Content-Type, drops
  obvious segments, keeps per-tab cookies by host, buffers reports until the tab's top-frame content
  script says `hello`, and resets a tab's buffer on each `main_frame` request.
* Content scripts in every frame scan media elements (including later additions and `src` changes) and
  report through the background page; only the top frame talks to the app, so every message is tied to
  the right `GeckoSession` (`MessageSender.session`).
* Native side: `session.webExtensionController.setMessageDelegate(extension, delegate, "snapcast")` per
  session; the delegate's `onConnect(port)` installs a `PortDelegate`. A tab's list is reset on
  `SessionObserver.onPageStart`.
* DRM: EME permission requests (`PERMISSION_MEDIA_KEY_SYSTEM_ACCESS`, surfaced as
  `SessionObserver.onMediaKeySystemRequested`), the `encrypted` DOM event, HLS `SAMPLE-AES` keys with a
  DRM KEYFORMAT and DASH `ContentProtection` all mark videos as DRM-protected. DRM content is never cast
  or played; the UI explains why.

## Casting pipeline

```
DetectedVideo ─► CastController.cast() ─► StreamProxy.registerStream(url, headers, cookiesByHost)
              ─► http://<phone-wifi-ip>:<port>/stream/<id>/index.m3u8 ─► MediaInfo ─► RemoteMediaClient.load
Chromecast ─GET─► proxy ─OkHttp + captured headers─► origin; playlists rewritten so variants, segments,
keys, subtitles and DASH templates also hit the proxy (signed /u/ and /b/ routes).
```

* The proxy runs while a cast session is active, inside `CastPlaybackService` (foreground service type
  `mediaPlayback`) which also holds Wi-Fi + wake locks and owns the media notification/MediaSession.
* "Cast directly" skips the proxy when no headers are needed (or when the proxy is disabled).

## Reference material

* GeckoView 158 public API: `geckoview-api.txt` (exact signatures). Sources and the official example app
  are mirrored at `https://raw.githubusercontent.com/mozilla-firefox/firefox/FIREFOX_RELEASE_158_END/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/<Class>.java`
  and `.../mobile/android/geckoview_example/src/main/java/org/mozilla/geckoview_example/`.
* Versions: `gradle/libs.versions.toml`.
