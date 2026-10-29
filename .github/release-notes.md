The video list shows which video the page is playing, casting through the local proxy works on more sites, expired links say so, and a stopped cast no longer looks like it is still playing.

### Install

Download **`snapcast-1.1.3-arm64-v8a.apk`** (almost every phone from the last few years) and open it on your phone. Older 32-bit phones need `armeabi-v7a`; `x86_64` is for emulators and Chromebooks.

**Updating from 0.1.1:** uninstall 0.1.1 first. Like 0.1.1, this version is signed with a temporary key, so Android refuses to install it over the previous one.

### What's new in 1.1.3

- **See which video the page is playing.** On streaming sites the list holds the episode, trailers, previews and ads. The video the site's player is playing (or that you paused) now comes first in the list, tagged **Playing on page** or **Paused on page**. A video with sound counts before a muted ad or preview.
- **Casting through the local proxy works where only "no proxy" worked before:**
  - playlists that start with an invisible byte order mark, use old Mac line breaks, or come compressed (gzip, deflate) are read correctly;
  - playlists that a server labels as video, and encryption keys that it labels as playlists, are recognised from their content;
  - playlists that build their links from variables (`EXT-X-DEFINE`, for example a token copied from the playlist's own link) now reach the right files;
  - when the phone can't connect to a video's server at all (a certificate it can't check, a blocked address), Snapcast casts the video directly instead, the way it plays with **Use local proxy** turned off.
- **Expired links are explained.** Many sites answer an expired link with a web page instead of the video. The phone player and the Chromecast used to show "malformed manifest" or a generic error. They now say the link has probably expired, and the player offers **Reload page**.
- **Stopping a cast clears it.** After **Stop**, the mini controller and the cast screen no longer show the stopped video with a Play button that did nothing.
- **Better video detection.** A DASH stream that a server labels as HLS (or the reverse) gets the right type, so it no longer fails to play on the phone. Streams with no file extension in their link now get their qualities listed.

### Known limitations

- DRM-protected streams (Netflix, Disney+, …) are detected but can't be cast; the app explains why.
- Sites that stream audio and video separately (YouTube, for example) can't be cast with the Default Media Receiver.
- Your phone and Chromecast must be on the same Wi-Fi network.
