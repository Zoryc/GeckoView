First preview of Snapcast: a GeckoView browser that finds the videos on any page and casts them to your Chromecast.

### Install

Download **`snapcast-0.1-arm64-v8a.apk`** (almost every phone from the last few years) and open it on your phone. Android will ask you to allow installs from your browser or file manager.
Older 32-bit phones need `armeabi-v7a`; `x86_64` is for emulators and Chromebooks.

### What's in 0.1

- **Browser:** Mozilla GeckoView engine, with tabs, private tabs, history, bookmarks, a speed-dial home page, desktop mode, downloads and Firefox add-ons.
- **Video detection:** videos on the page appear behind the floating button. It covers MP4/WebM files and HLS and DASH streams, including videos inside iframes, and lists the qualities each stream offers.
- **Casting:**
  - **How it works:** casting goes through a small proxy on your phone that sends the site's cookies and referer for you, so streams that block other devices still play on your TV.
  - **Controls:** a seek bar, ±10 s, volume, speed, subtitles (SRT is converted to WebVTT) and a queue. You also get a media notification and lock-screen controls.
- **Local playback:** play on your phone instead, with picture-in-picture.
- **Add-ons:** uBlock Origin installs in one tap. Other add-ons install from addons.mozilla.org, and toolbar popups and options pages work.
- **Extras:** cast videos stored on your phone, import an IPTV `.m3u` playlist, and resume where you left off.

### Known limitations

- DRM-protected streams (Netflix, Disney+, …) are detected but can't be cast; the app explains why.
- Sites that stream audio and video separately (YouTube, for example) can't be cast with the Default Media Receiver.
- Your phone and Chromecast must be on the same Wi-Fi network.
- This preview is signed with a development key. A later release signed with a different key will need you to uninstall 0.1 first.
