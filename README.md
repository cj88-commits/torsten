# Torsten

A self-hosted music player for Android, built for [Subsonic](http://www.subsonic.org/)-compatible servers (Navidrome, Airsonic, Subsonic, etc.).

---

## What it is

Torsten is a dark, album-first Android client for your self-hosted music library. It connects to any Subsonic-compatible server, syncs your catalogue locally, and lets you stream or download your music — no streaming service required.

---

## Features

### Home
- Personalised feed with time-of-day greeting
- **Recently Played**, **New Additions**, and **Most Played** horizontal shelf rows
- **Genre chips** for quick browsing — tap any genre to see its albums
- "See all" links to full paginated album lists for each shelf

### Library
- **Album grid** sorted A–Z, by year, recently played, starred, or downloaded only
- **Artist list** with alphabetical index
- **Artist detail** — hero image pulled from Last.fm/MusicBrainz, full album grid
- **Genre screen** — album grid filtered to a single genre
- **Random** — shuffled album discovery view, re-shuffles on tab re-tap

### Search
- Full-text search across tracks, albums, and artists simultaneously
- **Recent searches** history with individual and bulk removal
- **Browse by genre** grid on the idle state — no need to type to find a genre
- Long-press or menu button on any track opens a context sheet (Play next / Add to playlist / Start instant mix)

### Album detail
- Split header: cover art + metadata (artist, track count, duration, year)
- Tappable artist name → artist detail screen
- Per-album star / unstar
- **Resume dialog** — remembers where you left off and offers to continue from that point
- Per-track context menu: Play next, Add to playlist, Start instant mix
- Per-track star / unstar
- Download button with live progress (queued → % → complete)
- Long-press download button to cancel an in-progress download or delete a completed one

### Playlists
- Full playlist list with track count and duration
- Playlist detail with complete track listing
- Swipe-to-remove tracks from a playlist
- Add any track to an existing playlist from its context menu
- Create new playlists

### Now Playing
- Full-screen player with large cover art
- Marquee-scrolling song title, tappable artist name, tappable album title
- Star / unstar the current track
- Seek bar with both played and buffered position indicators
- Skip next / previous (previous restarts the track if more than 3 s in; seeks to previous track otherwise)
- **Quality badge** — shows the actual format and bitrate being delivered (e.g. FLAC, Opus 128kbps)
- **Instant mix button** (shuffle icon) — builds a 50-track mix from the current song
- Queue button — navigates to the queue screen

### Queue
- **Priority queue** — tracks added via "Play next", shown above the background sequence; supports drag-to-reorder and swipe-to-remove
- **Background sequence** — the current album or instant mix context
- Queue tab badge shows the number of tracks waiting in the priority queue

### Instant Mix
Built using the Navidrome / Last.fm multi-step approach:
1. `getSimilarSongs2` on the seed track (most reliable single call, up to 50 candidates)
2. `getSimilarArtists2` for up to 15 similar artists, then `getTopSongs` per artist (up to 75 more candidates)
3. Deduplicate by track ID; seed track always excluded from results
4. Shuffle, then apply a per-artist diversity cap — the seed artist gets no additional slots, all others get at most 2 tracks each
5. Genre fallback via `getSongsByGenre` if the pool is still under 10 tracks after filtering
6. Final mix: seed track at index 0 + up to 49 similar tracks (50 total)

Available from the Now Playing shuffle button, and from the context sheet on any track in Search, Album detail, and Playlist detail.

### Playback
- Powered by **Media3 ExoPlayer** with a `MediaSessionService` — playback persists across app restarts and continues in the background
- Lock screen and notification playback controls
- **ReplayGain** album normalisation (toggle in Settings)
- Configurable streaming quality per network type:
  - WiFi: original quality or Opus transcoding at 128 / 256 / 320 kbps
  - Mobile: original, or Opus / MP3 / AAC transcoding with configurable bitrate cap
- Offline playback from locally downloaded files when no connection is available

### Downloads
- Per-album download with live percentage progress
- Configurable download format and bitrate (transcoded or original)
- WiFi-only download option
- Per-album download state: none → queued → downloading → complete / failed / partial
- Long-press album download button to cancel or delete
- Clear all downloads from Settings

### Stars & Favourites
- Star / unstar songs and albums from any screen
- Starred albums appear in the Library "Starred" sort view
- Optimistic local update with server sync; if offline, the star change is queued and synced automatically when back online

### Scrobbling
- Submits now-playing notifications and full scrobbles via the Subsonic API (Last.fm-compatible)
- Enable or disable from Settings

### Image cache
- Cover art cached to disk with configurable size limit (250 MB – 2 GB)
- Stable cache keys ensure cover art renders correctly in offline / flight mode
- Clear cache and change the limit from Settings

### Connectivity
- Self-signed TLS certificates on private hosts (Tailscale, local network) accepted automatically
- Online / offline detection — streams when online, plays downloads when offline
- Snackbar warning when attempting to stream an un-downloaded track while offline

---

## Requirements

- Android 8.0 (API 26) or later
- A running Subsonic-compatible server:
  - [Navidrome](https://www.navidrome.org/) *(recommended — full feature support including instant mix)*
  - [Airsonic-Advanced](https://github.com/airsonic-advanced/airsonic-advanced)
  - [Subsonic](http://www.subsonic.org/) 6.0+
  - Any server implementing the Subsonic REST API v1.16.1+

---

## Installation

1. Download the latest APK from the [Releases](https://github.com/cj88-commits/torsten/releases) page.
2. On your Android device, enable **Install unknown apps** for your browser or file manager.
3. Open the APK and follow the on-screen prompts.
4. Launch Torsten, enter your server URL, username, and password, then tap **Test connection**.

---

## Building from source

**Prerequisites**

- Android Studio Ladybug (2024.2) or later
- JDK 17
- Android SDK with API 35 platform installed

**Steps**

```bash
git clone https://github.com/cj88-commits/torsten.git
cd torsten
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To produce a signed release build, create `keystore.properties` in the project root:

```properties
storeFile=/path/to/your.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Then run:

```bash
./gradlew assembleRelease
```

---

## Server compatibility

| Server | Status |
|---|---|
| Navidrome | Tested — full feature support |
| Airsonic-Advanced | Should work |
| Subsonic 6.0+ | Should work |
| Jellyfin (Subsonic plugin) | Should work |

Instant mix requires a server with Last.fm integration enabled (`getSimilarArtists2` / `getTopSongs`). On Navidrome this is automatic; other servers may vary.

---

## Known limitations

- No podcast support — the app is music-only
- Cover art cache size changes take effect on the next app launch
- Transcoding quality options depend on your server's transcoding capabilities
- Instant mix quality depends on Last.fm having data for the seed artist

---

## License

Apache License 2.0. See the [LICENSE](LICENSE) file for details.
