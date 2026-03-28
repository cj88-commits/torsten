package com.torsten.app.ui.home

import com.torsten.app.data.api.dto.AlbumDto

/**
 * Composes the "For You" album row from multiple source buckets, applying hard
 * anti-repetition rules and cross-section screen-awareness.
 *
 * Extracted as a standalone object so it can be unit-tested independently of
 * the ViewModel and Compose layer.
 *
 * ── Source buckets ────────────────────────────────────────────────────────────
 * Each album lives in exactly one bucket (priority: RECENT > FAVOURITES > REDISCOVERY > EXPLORATION).
 *
 *   RECENT       — albums played recently; relevance anchor for the row
 *   FAVOURITES   — frequent albums not played recently; high affinity, fresh feel
 *   REDISCOVERY  — starred + deep-frequent, not recently played; older loved albums
 *   EXPLORATION  — newest additions not heard recently; discovery / variety
 *
 * ── Hard rules (always enforced, never bypassed) ──────────────────────────────
 *   R1 — No duplicate album IDs in the result.
 *   R3 — No two consecutive items from the same artist.
 *   R4 — No artist appears more than [maxPerArtist] times in the full row.
 *
 * ── Soft rules (active in the first [strictZoneSize] positions only) ──────────
 *   R2 — No artist appears more than once in the first [strictZoneSize] positions.
 *   R5 — Artists already visible in [screenArtists] (Continue Listening + recently
 *         played items visible above For You) are excluded from the first
 *         [strictZoneSize] positions. Prevents the whole top-of-screen from
 *         repeating the same artist.
 *
 * ── Per-slot selection passes ────────────────────────────────────────────────
 *   Pass 1 — preferred bucket (from phase pattern), all five rules active.
 *   Pass 2 — any bucket, all five rules active.
 *   Pass 3 — any bucket, hard rules only (R1 + R3 + R4). Relaxes R2 + R5.
 *   Pass 4 — any bucket, R1 only. Emergency escape when only one artist remains.
 *
 * ── Ordering intent ───────────────────────────────────────────────────────────
 *   Positions  0–7  : relevance anchor — recent-heavy, guaranteed artist variety
 *   Positions  8–15 : balanced variety — all four buckets equally represented
 *   Positions 16+   : exploratory tail — rediscovery and exploration lead
 */
object ForYouComposer {

    enum class Bucket { RECENT, FAVOURITES, REDISCOVERY, EXPLORATION }

    // Exposed internal for tests to verify bucket assignment
    internal data class Candidate(val album: AlbumDto, val bucket: Bucket)

    /**
     * Produces the final ordered, deduplicated, diversity-enforced "For You" list.
     *
     * @param recent         Albums from the "recent" API query (play-recency ordered).
     * @param frequent       Albums from the "frequent" API query (play-count ordered).
     * @param starred        Albums from the "starred" API query (may be empty).
     * @param newest         Albums from the "newest" API query (addition-date ordered).
     * @param screenArtists  Artist IDs of items already visible above For You on screen.
     *                       Pass `recentlyPlayed.take(N).mapNotNull { it.artistId }.toSet()`.
     * @param maxItems       Maximum items in the final list (default 25).
     * @param maxPerArtist   Hard ceiling on how many times one artist can appear (default 2).
     * @param strictZoneSize Leading positions where R2 and R5 are active (default 8).
     */
    fun compose(
        recent: List<AlbumDto>,
        frequent: List<AlbumDto>,
        starred: List<AlbumDto>,
        newest: List<AlbumDto>,
        screenArtists: Set<String> = emptySet(),
        screenAlbumIds: Set<String> = emptySet(),
        maxItems: Int = 25,
        maxPerArtist: Int = 2,
        strictZoneSize: Int = 8,
    ): List<AlbumDto> {
        // recentIds / frequentIds are computed from the *original* lists so that
        // bucket-exclusivity logic (FAVOURITES = frequent not in recent, etc.) is
        // unaffected by what is already visible elsewhere on screen.
        val recentIds   = recent.map { it.id }.toSet()
        val frequentIds = frequent.map { it.id }.toSet()

        // ── Build exclusive source pools ──────────────────────────────────────
        // Pre-filter albums already shown in other Home sections so they never
        // appear twice on the same screen.
        val pools = buildPools(
            recent.filter   { it.id !in screenAlbumIds },
            frequent.filter { it.id !in screenAlbumIds },
            starred.filter  { it.id !in screenAlbumIds },
            newest.filter   { it.id !in screenAlbumIds },
            recentIds, frequentIds,
        )

        // ── Phase-encoded draw order ──────────────────────────────────────────
        // The base pattern encodes the three-phase structure (24 slots per cycle).
        // It is repeated enough times to give the selection loop ample draw attempts
        // even when many candidates are rejected by diversity rules.
        val basePattern = listOf(
            // Phase 1 — relevance anchor (first 8 slots match strictZoneSize)
            Bucket.RECENT,      Bucket.FAVOURITES,
            Bucket.RECENT,      Bucket.REDISCOVERY,
            Bucket.FAVOURITES,  Bucket.RECENT,
            Bucket.EXPLORATION, Bucket.FAVOURITES,
            // Phase 2 — balanced variety
            Bucket.RECENT,      Bucket.REDISCOVERY,
            Bucket.FAVOURITES,  Bucket.EXPLORATION,
            Bucket.REDISCOVERY, Bucket.FAVOURITES,
            Bucket.RECENT,      Bucket.EXPLORATION,
            // Phase 3 — exploratory tail
            Bucket.FAVOURITES,  Bucket.REDISCOVERY,
            Bucket.EXPLORATION, Bucket.FAVOURITES,
            Bucket.REDISCOVERY, Bucket.EXPLORATION,
            Bucket.FAVOURITES,  Bucket.REDISCOVERY,
        )
        val drawOrder = buildList<Bucket> {
            repeat(5) { addAll(basePattern) }   // 120 slots — ample for any realistic input
        }

        // ── Greedy constrained selection ──────────────────────────────────────
        val result      = mutableListOf<AlbumDto>()
        val seenIds     = mutableSetOf<String>()
        val artistCount = mutableMapOf<String, Int>()
        val allBuckets  = enumValues<Bucket>().toList()

        for (preferred in drawOrder) {
            if (result.size >= maxItems) break

            val inStrictZone  = result.size < strictZoneSize
            val lastArtistKey = result.lastOrNull()?.artistKey()
            // Priority order: preferred bucket first, then the rest in enum order.
            val priority = listOf(preferred) + allBuckets.filter { it != preferred }

            // Pass 1 — all rules active (R1 + R2 + R3 + R4 + R5 in strict zone).
            if (place(pools, priority, seenIds, artistCount, result, screenArtists,
                    inStrictZone, maxPerArtist, lastArtistKey, strict = true)) continue

            // Pass 2 — relax soft rules (R2 + R5); hard rules R1 + R3 + R4 always hold.
            //           Activates when the strict zone or screen-artist rules reject all
            //           remaining candidates from every bucket.
            place(pools, priority, seenIds, artistCount, result, screenArtists,
                inStrictZone, maxPerArtist, lastArtistKey, strict = false)
        }

        return result
    }

    // ── Internal implementation ───────────────────────────────────────────────

    private fun place(
        pools: Map<Bucket, MutableList<Candidate>>,
        bucketsToTry: List<Bucket>,
        seenIds: MutableSet<String>,
        artistCount: MutableMap<String, Int>,
        result: MutableList<AlbumDto>,
        screenArtists: Set<String>,
        inStrictZone: Boolean,
        maxPerArtist: Int,
        lastArtistKey: String?,
        strict: Boolean,
    ): Boolean {
        for (bucket in bucketsToTry) {
            val pool = pools[bucket] ?: continue
            val hit  = pool.firstOrNull { c -> accepts(c.album, seenIds, artistCount,
                screenArtists, inStrictZone, maxPerArtist, lastArtistKey, strict) }
            if (hit != null) {
                pool.remove(hit)
                result.add(hit.album)
                seenIds.add(hit.album.id)
                val key = hit.album.artistKey()
                artistCount[key] = (artistCount[key] ?: 0) + 1
                return true
            }
        }
        return false
    }

    private fun accepts(
        album: AlbumDto,
        seenIds: Set<String>,
        artistCount: Map<String, Int>,
        screenArtists: Set<String>,
        inStrictZone: Boolean,
        maxPerArtist: Int,
        lastArtistKey: String?,
        strict: Boolean,
    ): Boolean {
        // R1 — hard: no duplicate album IDs
        if (album.id in seenIds) return false

        val key   = album.artistKey()
        val count = artistCount.getOrDefault(key, 0)

        // R3 — hard: no consecutive same artist
        if (key == lastArtistKey) return false

        // R4 — hard: artist appearance cap
        if (count >= maxPerArtist) return false

        // R2 + R5 — soft (active only in the strict zone, skipped in pass 2)
        if (strict && inStrictZone) {
            // R2: each artist may appear at most once in the first strictZoneSize positions
            if (count >= 1) return false
            // R5: artists visible above For You on screen are excluded from strict zone
            if (album.artistId != null && album.artistId in screenArtists) return false
        }

        return true
    }

    // ── Pool construction ─────────────────────────────────────────────────────

    internal fun buildPools(
        recent: List<AlbumDto>,
        frequent: List<AlbumDto>,
        starred: List<AlbumDto>,
        newest: List<AlbumDto>,
        recentIds: Set<String>,
        frequentIds: Set<String>,
    ): MutableMap<Bucket, MutableList<Candidate>> = mutableMapOf(
        Bucket.RECENT to recent.take(12)
            .map { Candidate(it, Bucket.RECENT) }.toMutableList(),

        Bucket.FAVOURITES to frequent
            .filter { it.id !in recentIds }
            .take(12)
            .map { Candidate(it, Bucket.FAVOURITES) }.toMutableList(),

        Bucket.REDISCOVERY to (
            starred.filter { it.id !in recentIds && it.id !in frequentIds } +
            frequent.drop(12).filter { it.id !in recentIds }
        ).distinctBy { it.id }.take(10)
            .map { Candidate(it, Bucket.REDISCOVERY) }.toMutableList(),

        Bucket.EXPLORATION to newest
            .filter { it.id !in recentIds && it.id !in frequentIds }
            .take(8)
            .map { Candidate(it, Bucket.EXPLORATION) }.toMutableList(),
    )

    internal fun AlbumDto.artistKey(): String = artistId ?: id
}
