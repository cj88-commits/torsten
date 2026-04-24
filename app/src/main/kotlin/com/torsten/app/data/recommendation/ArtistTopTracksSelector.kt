package com.torsten.app.data.recommendation

import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.LbRecordingCandidate
import timber.log.Timber

object ArtistTopTracksSelector {

    /**
     * Normalise a title for matching:
     *  1. NFD Unicode decomposition — strips diacritics so "Kärleken" and "Karleken" both
     *     become "karleken". Handles Swedish/Nordic ä→a, ö→o, å→a, etc.
     *  2. Lowercase
     *  3. Strip parenthetical/bracketed content
     *  4. Strip featured-artist credit (requires whitespace before feat/ft/featuring)
     *  5. Strip any remaining non-ASCII characters and collapse whitespace
     */
    fun normaliseTitle(title: String): String {
        // Step 1: decompose diacritics and strip combining marks (NFD → base ASCII)
        var s = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")   // \p{M} = any combining/diacritic mark
        s = s.lowercase()
        s = s.replace(Regex("\\(.*?\\)"), "")
        s = s.replace(Regex("\\[.*?\\]"), "")
        // Require whitespace before feat/ft to avoid matching words like "fattiga" or "after"
        s = s.replace(Regex("\\s+(?:feat(?:uring|\\.)?|ft\\.?)\\b.*"), "")
        s = s.replace(Regex("[^a-z0-9 ]"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /**
     * Match LB candidates against local songs using exact normalised title match.
     *
     * When a title exists across multiple albums, all matching versions are included
     * so [selectTopFive] can pick the most album-diverse combination.
     * Each LB title is matched at most once (deduped by normalised title).
     */
    fun matchCandidates(
        lbCandidates: List<LbRecordingCandidate>,
        localSongs: List<SongEntity>,
    ): List<SongEntity> {
        Timber.d("[ArtistTop] matchCandidates: ${lbCandidates.size} LB candidates, ${localSongs.size} local songs")
        Timber.d("[ArtistTop] LB top 10 candidates: ${lbCandidates.take(10).map { it.trackName }}")

        val normToSongs = localSongs.groupBy { normaliseTitle(it.title) }
        val usedNormTitles = mutableSetOf<String>()
        val usedSongIds = mutableSetOf<String>()
        val results = mutableListOf<SongEntity>()

        for ((index, candidate) in lbCandidates.withIndex()) {
            val normTitle = normaliseTitle(candidate.trackName)
            if (!usedNormTitles.add(normTitle)) {
                Timber.d("[ArtistTop]   [%2d] SKIP-DUP  '%s' → '%s'", index, candidate.trackName, normTitle)
                continue
            }
            val matches = normToSongs[normTitle]
            if (matches == null) {
                Timber.d("[ArtistTop]   [%2d] NO-MATCH  '%s' → '%s'", index, candidate.trackName, normTitle)
                continue
            }
            Timber.d(
                "[ArtistTop]   [%2d] MATCHED   '%s' → '%s' (%d album version(s))",
                index, candidate.trackName, normTitle, matches.size,
            )
            for (song in matches) {
                if (usedSongIds.add(song.id)) {
                    results.add(song)
                }
            }
        }

        Timber.d("[ArtistTop] matchCandidates: ${results.size} songs matched from ${lbCandidates.size} candidates")
        return results
    }

    /**
     * From a ranked list (output of [matchCandidates]), pick up to 5 songs.
     *
     * Songs are grouped by normalised title so that the same track on multiple albums
     * is treated as one entry — the version on the most album-diverse album is preferred.
     *
     * Pass 1 (LB diversity): iterate LB-ranked title groups; from each group pick the
     *   first song whose album hasn't appeared in the result yet.
     * Pass 2 (LB fill): iterate groups again; add the first unused song regardless of album.
     * Pass 3 (fill diversity): if still < 5, one song per album from [allArtistSongs].
     * Pass 4 (fill relax): if still < 5, any song from [allArtistSongs] not already in result.
     */
    fun selectTopFive(
        lbRanked: List<SongEntity>,
        allArtistSongs: List<SongEntity> = emptyList(),
        artistName: String = "",
    ): List<SongEntity> {
        Timber.d("[ArtistTop] selectTopFive: artistName=$artistName lbRanked=${lbRanked.size} allArtistSongs=${allArtistSongs.size}")

        // Build title groups preserving LB rank order
        val groupOrder = mutableListOf<String>()
        val groupMap = mutableMapOf<String, MutableList<SongEntity>>()
        for (song in lbRanked) {
            val norm = normaliseTitle(song.title)
            if (norm !in groupMap) {
                groupMap[norm] = mutableListOf()
                groupOrder.add(norm)
            }
            groupMap[norm]!!.add(song)
        }
        val orderedGroups = groupOrder.map { groupMap[it]!! }

        val seenAlbumIds = mutableSetOf<String>()
        val result = mutableListOf<SongEntity>()
        val resultIds = mutableSetOf<String>()

        // Pass 1: one per title group, prefer unseen album
        for (group in orderedGroups) {
            if (result.size >= 5) break
            val pick = group.firstOrNull { it.albumId !in seenAlbumIds } ?: continue
            seenAlbumIds.add(pick.albumId)
            result.add(pick)
            resultIds.add(pick.id)
        }

        // Pass 2: fill remaining groups (allow same album)
        for (group in orderedGroups) {
            if (result.size >= 5) break
            val pick = group.firstOrNull { it.id !in resultIds } ?: continue
            seenAlbumIds.add(pick.albumId)
            result.add(pick)
            resultIds.add(pick.id)
        }

        // Pass 3 & 4: fill from allArtistSongs
        if (result.size < 5 && allArtistSongs.isNotEmpty()) {
            val fillCandidates = allArtistSongs
                .filter { it.id !in resultIds }
                .sortedWith(compareBy({ it.albumId }, { it.discNumber }, { it.trackNumber }))

            // Pass 3: diversity fill
            for (song in fillCandidates) {
                if (result.size >= 5) break
                if (seenAlbumIds.add(song.albumId)) {
                    result.add(song)
                    resultIds.add(song.id)
                }
            }

            // Pass 4: relaxed fill
            if (result.size < 5) {
                val seenIds = result.map { it.id }.toMutableSet()
                for (song in allArtistSongs) {
                    if (result.size >= 5) break
                    if (seenIds.add(song.id)) {
                        result.add(song)
                    }
                }
            }
        }

        Timber.d("[ArtistTop] selectTopFive: final=${result.map { it.title }}")
        return result
    }

    /**
     * From a ranked list, pick up to 20 unique tracks (deduped by normalised title)
     * for the full playback queue.
     */
    fun selectFullQueue(rankedSongs: List<SongEntity>): List<SongEntity> {
        val seenNormTitles = mutableSetOf<String>()
        return rankedSongs
            .filter { seenNormTitles.add(normaliseTitle(it.title)) }
            .take(20)
    }

    /**
     * Build a playback queue for when the user taps one of the top-5 tracks.
     *
     * Layout:
     *   [0..4]       topFive in original (ranked) order — no rotation
     *   [5..target-1] remaining artist songs, shuffled, excluding top-five
     *
     * @return Pair of (fullQueue, startIndex) where startIndex is the position of
     *         [tappedSong] in fullQueue.
     */
    fun buildTopTrackQueue(
        tappedSong: SongEntity,
        topFive: List<SongEntity>,
        allArtistSongs: List<SongEntity>,
        target: Int = 20,
    ): Pair<List<SongEntity>, Int> {
        val topFiveIds = topFive.map { it.id }.toSet()
        val remaining = allArtistSongs
            .filter { it.id !in topFiveIds }
            .shuffled()
        val fullQueue = (topFive + remaining).take(target)
        val startIndex = fullQueue.indexOfFirst { it.id == tappedSong.id }.coerceAtLeast(0)
        return Pair(fullQueue, startIndex)
    }
}
