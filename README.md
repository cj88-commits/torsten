# Torsten

A self-hosted music player for Android, built for [Subsonic](http://www.subsonic.org/)-compatible servers (Navidrome, Airsonic, Subsonic, etc.).

---

## What it is

Torsten is a dark, album-first Android client for your self-hosted music library. It connects to any Subsonic-compatible server, syncs your catalogue locally, and lets you stream or download your music — no streaming service required.

---

## Navigation

Five bottom-bar tabs: **Home**, **Search**, **Explore**, **Library**, **Queue**. A persistent mini-player sits above the tab bar whenever something is loaded.

---

## Features

### Home

- **Continue Listening** hero card — jumps straight back into the last album you were playing
- **Recently Played**, **New Additions**, and **Most Played** horizontal shelf rows, each with a "See all" link to a full paginated list
- **For You** — a denser shelf of personalised recommendations based on your listening history
- **Starred** preview — inline list of your starred tracks with cover thumbnails and a context menu on each; "See all" navigates to the full Starred Tracks screen
- Pull-to-refresh reloads the entire feed
- Settings shortcut in the top-right corner

### Search

- Full-text search across tracks, albums, and artists simultaneously
- **Recent searches** history with individual and bulk-clear removal
- **Browse by genre** grid shown while the search field is empty — no typing required to find a genre
- Long-press or three-dot menu on any track opens a context sheet: Play next / Add to playlist / Start instant mix / Go to artist / Go to album

### Explore

- Full-screen discovery view with two large cards: one randomly selected artist, one randomly selected album from your library
- Each card shows full-bleed cover art with a gradient overlay, the name, and a **Play** button
- Artist card plays the artist's top tracks (ranked by ListenBrainz listen counts)
- Album card plays the full album in track order
- Shuffle icon in the header re-rolls both cards instantly

### Library

Four sub-tabs accessible via a tab row at the top of the Library screen:

- **Albums** — sortable grid: A–Z, by year, recently played, starred only, or downloaded only
- **Artists** — alphabetical list with a fast-scroll index
- **Playlists** — full list with track count and total duration
- **Random** — shuffled album discovery grid; re-shuffles each time the tab is tapped

#### Album Detail

- Split header: cover art + metadata (artist, track count, total duration, year)
- Tappable artist name → artist detail screen
- Star / unstar the album
- **Resume dialog** — remembers playback position and offers to continue from that point
- Per-track context menu: Play next / Add to playlist / Start instant mix
- Per-track star / unstar
- Download button with live progress (queued → % → complete); long-press to cancel an in-progress download or delete a completed one

#### Artist Detail

- Full-bleed hero image sourced from Last.fm / MusicBrainz
- **Top Tracks** section — up to 5 tracks ranked by ListenBrainz global listen count; play them all or start from any individual track
- Play artist / Shuffle artist buttons that queue the artist's top tracks
- Full album grid below the hero; tap any album to go to its detail screen

#### Playlist Detail

- Complete track listing with duration
- Swipe-to-remove tracks from a playlist
- Add any track to an existing playlist from its context menu
- Create new playlists

### Starred Tracks

- Dedicated full-screen list of all starred songs with cover thumbnails
- Tap to play from that track through the rest of the starred list
- Context menu on each track: Play next / Add to playlist / Start instant mix / Go to artist / Go to album

### Now Playing

- Full-screen player with large cover art and a colour-adaptive blurred background
- Marquee-scrolling song title; tappable artist name and album title
- Star / unstar the current track
- Seek bar with both played and buffered position indicators
- Skip next / previous (previous restarts the track if more than 3 s in; skips to the previous track otherwise)
- **Quality badge** — shows the actual format and bitrate being delivered (e.g. FLAC, Opus 128 kbps)
- **Instant mix button** (shuffle icon) — builds a 20-track mix seeded by the current song
- Queue button — navigates to the queue screen

### Queue

- **Priority queue** — tracks added via "Play next", shown above the background sequence; drag-to-reorder and swipe-to-remove
- **Background sequence** — the current album, playlist, or instant mix context
- Queue tab badge shows the number of tracks waiting in the priority queue

### Instant Mix

A multi-source, diversity-aware algorithm that always produces exactly 20 tracks (seed at index 0 + 19 companions):

1. **MusicBrainz MBID lookup** — resolves the seed artist's MBID; cached locally for 7 days
2. **ListenBrainz Radio** (`/lb-radio/artist/<mbid>`) — fetches up to 10 similar artists and matches their names against your local catalogue (up to 2 tracks per LB artist)
3. **Subsonic `getSimilarSongs2` fallback** (batch 1, up to 50 candidates) — triggered when LB yields fewer than 19 local matches
4. **Subsonic batch 2** — if the pool is still under 30 tracks and batch 1 was dominated (> 50 %) by the seed artist, a non-seed song is used as the second seed to break self-referential loops
5. **Random songs fallback** (`getRandomSongs`) — injected when the pool is under 20 tracks or still seed-artist-heavy; seed artist is excluded from random candidates to guarantee variety
6. **Diversity interleaving** — seed-artist songs are spread at fixed positions (0, 1, 4, 7, 10, 13, 16, 19); all other artist slots are filled with unique artists, so no non-seed artist appears twice

Available from: the Now Playing shuffle button, and from the context menu on any track in Home, Search, Album Detail, Artist Detail, Playlist Detail, and Starred Tracks.

### Artist Top Tracks

Used in both Artist Detail and the Explore screen. Resolution order:

1. Check local cache (valid for 24 hours)
2. Resolve artist MBID via MusicBrainz (cached 7 days)
3. Fetch top-recording listen counts from ListenBrainz popularity API
4. Fuzzy-match recording names against your local library by normalised title
5. Local fallback (alphabetical) if MusicBrainz / ListenBrainz are unavailable

### Playback

- Powered by **Media3 ExoPlayer** with a `MediaSessionService` — playback persists across app restarts and continues in the background
- Lock screen controls and notification playback controls
- **ReplayGain** album normalisation (toggle in Settings)
- Configurable streaming quality per network type:
  - WiFi: original quality, or Opus transcoding at 128 / 256 / 320 kbps
  - Mobile: original, or Opus / MP3 / AAC transcoding at a configurable bitrate cap
- Offline playback from locally downloaded files when no connection is available

### Downloads

- Per-album download with live percentage progress
- Configurable download format and bitrate (transcoded or original)
- WiFi-only download option
- Per-album download state: none → queued → downloading → complete / failed / partial
- Long-press the album download button to cancel or delete
- Clear all downloads from Settings

### Stars & Favourites

- Star / unstar songs and albums from any screen
- Starred albums appear in the Library "Starred" sort view; starred songs appear on the Home screen and in Starred Tracks
- Optimistic local update with server sync; stars queued and retried automatically when offline

### Scrobbling

- Submits now-playing notifications and full scrobbles via the Subsonic API (Last.fm-compatible)
- Enable or disable from Settings

### Image Cache

- Cover art cached to disk with a configurable size limit (250 MB – 2 GB)
- Stable cache keys ensure art renders correctly in offline / flight mode
- Clear the cache and change the limit from Settings

### Connectivity

- Self-signed TLS certificates on private hosts (Tailscale, local network) accepted automatically
- Online / offline detection — streams when online, falls back to downloads when offline
- Snackbar warning when attempting to stream a track that has not been downloaded while offline

---

## Requirements

- Android 8.0 (API 26) or later
- A running Subsonic-compatible server:
  - [Navidrome](https://www.navidrome.org/) *(recommended — full feature support including instant mix)*
  - [Airsonic-Advanced](https://github.com/airsonic-advanced/airsonic-advanced)
  - [Subsonic](http://www.subsonic.org/) 6.0+
  - Any server implementing the Subsonic REST API v1.16.1+

Instant mix and artist top tracks use the public [MusicBrainz](https://musicbrainz.org/) and [ListenBrainz](https://listenbrainz.org/) APIs — no account required. Quality depends on those services having data for the relevant artists.

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

Instant mix works best with a server that has Last.fm integration enabled (`getSimilarSongs2`). On Navidrome this is automatic. ListenBrainz Radio provides the primary signal independently of your server.

---

## Known limitations

- No podcast support — the app is music-only
- Cover art cache size changes take effect on the next app launch
- Transcoding quality options depend on your server's transcoding capabilities
- Instant mix and artist top tracks quality depends on MusicBrainz / ListenBrainz having data for the seed artist

---

## License

Apache License 2.0. See the [LICENSE](LICENSE) file for details.
