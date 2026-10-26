Bug fixes for thumbnails, video quality and refused links, and a way to report sites that don't work.

### Install

Download **`snapcast-0.1.1-arm64-v8a.apk`** (almost every phone from the last few years) and open it on your phone. Older 32-bit phones need `armeabi-v7a`; `x86_64` is for emulators and Chromebooks.

**Updating from 0.1:** uninstall 0.1 first. 0.1 was signed with a temporary development key, so Android refuses to install 0.1.1 over it.

### What's new in 0.1.1

- **Thumbnails load on more sites.** Posters are requested the way the page requested them, so sites that block images linked from elsewhere no longer refuse them. Snapcast also finds posters that video players draw outside the usual place. When a video has no poster at all, the list shows a frame of the video instead, captured only while the list is open.
- **Correct quality labels.** Streams that adapt their quality now show the best quality they offer ("Up to 1080p"). The label no longer shows the low quality the page's player happened to start with. **Choose quality** now sets the quality for Cast, Play on phone, the queue and subtitles.
- **Fewer "access refused" (HTTP 403) errors.** Snapcast sends the same headers and cookies as the browser:
  - it no longer adds a Referer the page didn't send;
  - videos found only in the page get the site's cookies;
  - cookies a video server sets during playback are sent back to it.
- **Expired links:** when a link has expired, the player offers **Reload page**. Snapcast reloads the page and plays the same video again with a fresh link.
- **Report a site problem** (browser menu or the bottom of the video list): describe what went wrong, preview the report, then open it on GitHub or share it. It never includes cookies, tokens, video links or anything from private tabs.

### Known limitations

- DRM-protected streams (Netflix, Disney+, …) are detected but can't be cast; the app explains why.
- Sites that stream audio and video separately (YouTube, for example) can't be cast with the Default Media Receiver.
- Your phone and Chromecast must be on the same Wi-Fi network.
