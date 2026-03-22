package com.torsten.app.data.repository

import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.datastore.ServerConfig
import timber.log.Timber

/**
 * Builds an instant mix from a seed track.
 *
 * Returns a list that starts with the seed track at index 0, followed by up to 49
 * similar tracks (50 total). Never throws — all API failures are swallowed so the
 * caller always gets back whatever was collected.
 *
 * Algorithm:
 *  Step 1: getSimilarSongs2(seedId, 50)          — most reliable single call
 *  Step 2: getSimilarArtists2(artistId, 15)       — up to 15 Last.fm similar artists
 *           + getTopSongs(artistName, 5) per artist — up to 75 additional tracks
 *  Step 3: Deduplicate by track ID (seed always excluded)
 *  Step 4: Shuffle, then apply per-artist diversity cap (anchor = 0, others max 2)
 *  Step 5: Genre fallback only if pool still < 10
 */
class InstantMixRepository(config: ServerConfig) {

    private val client = SubsonicApiClient(config)

    suspend fun buildMix(seedSong: SongDto): List<SongDto> {
        val tag = "[InstantMix]"
        val pool = mutableListOf<SongDto>()

        // ── Step 1: getSimilarSongs2 on the seed track — most reliable ────────
        val directSimilar = try {
            client.getSimilarSongs2(seedSong.id, count = 50)
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "getSimilarSongs2 failed for song %s", seedSong.id)
            emptyList()
        }
        pool.addAll(directSimilar)
        Timber.tag(tag).d("getSimilarSongs2 returned %d tracks", directSimilar.size)

        // ── Step 2: similar artists → top songs ───────────────────────────────
        val artistId = seedSong.artistId.orEmpty()
        if (artistId.isNotEmpty()) {
            val similarArtists = try {
                client.getSimilarArtists2(artistId, count = 15)
            } catch (e: Exception) {
                Timber.tag(tag).e(e, "getSimilarArtists2 failed for artist %s", artistId)
                emptyList()
            }
            Timber.tag(tag).d("getSimilarArtists2 returned %d artists", similarArtists.size)

            for (artist in similarArtists) {
                val topSongs = try {
                    client.getTopSongs(artist.name, count = 5)
                } catch (e: Exception) {
                    emptyList()
                }
                pool.addAll(topSongs)
                Timber.tag(tag).d("  %s: %d top songs", artist.name, topSongs.size)
            }
        }

        Timber.tag(tag).d("Pool size before dedup/filter: %d", pool.size)

        // ── Step 3: deduplicate, always exclude seed ──────────────────────────
        val seen = mutableSetOf(seedSong.id)
        val deduped = pool.filter { seen.add(it.id) }

        // ── Step 4: shuffle then diversity cap (anchor artist = 0, others ≤ 2) ─
        val shuffled = deduped.shuffled()
        val anchorArtistId = artistId
        val artistCount = mutableMapOf<String, Int>()
        val filtered = mutableListOf<SongDto>()
        for (track in shuffled) {
            val key = track.artistId?.takeIf { it.isNotEmpty() }
                ?: track.artist?.takeIf { it.isNotEmpty() }
                ?: "__unknown__"
            val limit = if (key == anchorArtistId && anchorArtistId.isNotEmpty()) 0 else 2
            val current = artistCount.getOrDefault(key, 0)
            if (current < limit) {
                filtered.add(track)
                artistCount[key] = current + 1
            }
            if (filtered.size >= 49) break
        }

        Timber.tag(tag).d("Pool after diversity filter: %d tracks", filtered.size)
        val breakdown = artistCount.entries
            .sortedByDescending { it.value }
            .take(10)
            .joinToString { "${it.key}×${it.value}" }
        Timber.tag(tag).d("Artist breakdown: %s", breakdown)

        // ── Step 5: genre fallback if thin ────────────────────────────────────
        if (filtered.size < 10) {
            val genre = seedSong.genre
            if (!genre.isNullOrBlank()) {
                val genreTracks = try {
                    client.getSongsByGenre(genre, count = 50)
                } catch (e: Exception) {
                    Timber.tag(tag).e(e, "getSongsByGenre fallback failed for \"%s\"", genre)
                    emptyList()
                }
                val genreFiltered = genreTracks
                    .filter { seen.add(it.id) }
                    .shuffled()
                    .take(15 - filtered.size)
                filtered.addAll(genreFiltered)
                Timber.tag(tag).d("Genre fallback added %d tracks", genreFiltered.size)
            }
        }

        // Seed at index 0, then mix
        val result = listOf(seedSong) + filtered
        Timber.tag(tag).d("Final mix: %d tracks (seed + %d similar)", result.size, filtered.size)
        return result
    }
}
