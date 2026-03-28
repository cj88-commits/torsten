package com.torsten.app.data.recommendation

import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.dao.ArtistMbidCacheDao
import com.torsten.app.data.db.dao.SongDao
import com.torsten.app.data.db.entity.ArtistMbidCacheEntity
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.ListenBrainzClient
import com.torsten.app.data.metabrainz.MusicBrainzClient
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

class InstantMixRepositoryV2(
    private val serverConfigStore: ServerConfigStore,
    private val songDao: SongDao,
    private val artistMbidCacheDao: ArtistMbidCacheDao,
    private val musicBrainzClient: MusicBrainzClient,
    private val listenBrainzClient: ListenBrainzClient,
) {
    private val currentMixToken = AtomicInteger(0)

    /**
     * Builds an instant mix seeded by [seedSong].
     *
     * LB Radio supplies a set of similar-artist recordings (keyed by artist MBID, no track
     * titles). We resolve each similar artist's name against the local Room catalogue.
     * Falls back to Subsonic getSimilarSongs2 if LB is unavailable or yields < 10 matches.
     * A second Subsonic call is made when the combined pool is still thin (< 30 songs).
     *
     * [artistName] must be supplied by the caller (not stored on SongEntity).
     * Seed is always at index 0; result is always exactly 20 tracks (seed + 19).
     */
    suspend fun getMix(seedSong: SongEntity, artistName: String): List<SongEntity> {
        val myToken = currentMixToken.incrementAndGet()
        val tag = "[InstantMix]"

        // ── 1. Resolve MBID ───────────────────────────────────────────────────
        val mbid = resolveMbid(seedSong.artistId, artistName)
        Timber.tag(tag).d("MBID for '$artistName': $mbid")

        if (currentMixToken.get() != myToken) return emptyList()

        // ── 2. Fetch LB Radio candidates ──────────────────────────────────────
        // Response is { artistMbid: [{recording_mbid, similar_artist_name, ...}] }
        // No recording titles — matching is by similar_artist_name against local DB.
        val lbEntries = if (mbid != null) {
            listenBrainzClient.getArtistRadio(mbid)
        } else {
            emptyList()
        }

        if (currentMixToken.get() != myToken) return emptyList()

        // ── 3. Match by similar artist name against local catalogue ───────────
        // Take up to 2 per LB artist for intra-step diversity; no total cap here —
        // the full pool is diversity-capped in step 5.
        val usedSongIds = mutableSetOf(seedSong.id)
        val pool = mutableListOf<SongEntity>()

        val similarArtistNames = lbEntries
            .map { it.similarArtistName }
            .filter { it.isNotEmpty() }
            .distinct()

        for (similarArtist in similarArtistNames) {
            val artistSongs = songDao.getSongsByArtistName(similarArtist)
                .filter { it.id !in usedSongIds }
                .shuffled()
                .take(2)
            for (song in artistSongs) {
                pool.add(song)
                usedSongIds.add(song.id)
            }
        }

        val lbMatchCount = pool.size
        Timber.tag(tag).d("LB candidates: ${lbEntries.size}")
        Timber.tag(tag).d("LB matched locally: $lbMatchCount")

        // ── 4. Subsonic fallback if LB empty or pool still < 19 ──────────────
        // We need 19 companion songs (seed + 19 = 20 total). Any LB result count
        // below 19 means Subsonic must supplement — the old threshold of 10 was
        // the root cause of mixes returning 14 tracks (13 LB matches → no fallback).
        val needsFallback = lbEntries.isEmpty() || lbMatchCount < 19
        Timber.tag(tag).d(
            "Fallback triggered: $needsFallback (lbEmpty=${lbEntries.isEmpty()}, lbMatched=$lbMatchCount)",
        )

        // Shared across all Subsonic batches so stubs are never re-upserted across calls.
        val entityMap = mutableMapOf<String, SongEntity>()
        val config = serverConfigStore.serverConfig.first()
        val client = if (config.isConfigured) SubsonicApiClient(config) else null

        if (needsFallback && client != null && currentMixToken.get() == myToken) {
            // ── 4a. First Subsonic batch ──────────────────────────────────────
            val firstBatch = runCatching { client.getSimilarSongs2(seedSong.id, 50) }
                .getOrElse { emptyList() }
            Timber.tag(tag).d("Subsonic batch-1: ${firstBatch.size} songs")
            firstBatch.groupBy { it.artist ?: "unknown" }.entries
                .sortedByDescending { it.value.size }.take(6)
                .forEach { (artist, songs) ->
                    Timber.tag(tag).d("  Subsonic artist='$artist' count=${songs.size}")
                }
            upsertAndMerge(firstBatch, usedSongIds, entityMap, pool)
            Timber.tag(tag).d("Pool after batch-1 dedup: ${pool.size}")

            // ── 4b. Second batch when pool is still thin ──────────────────────
            // Prefer a non-seed-artist song as the second-batch seed when
            // batch-1 was dominated by the seed artist (> 50 %). This breaks
            // the self-referential loop where getSimilarSongs2 keeps returning
            // songs by the same artist (common on Navidrome for niche artists
            // whose Last.fm "similar tracks" data is sparse or self-referential).
            if (pool.size < 30 && currentMixToken.get() == myToken) {
                val seedArtistInPool = pool.count { it.artistId == seedSong.artistId }
                val seedRatio = seedArtistInPool.toFloat() / pool.size.coerceAtLeast(1)
                Timber.tag(tag).d(
                    "Seed artist ratio after batch-1: ${"%.0f".format(seedRatio * 100)}%% " +
                        "($seedArtistInPool/${pool.size})",
                )

                val secondSeed = if (seedRatio > 0.5f) {
                    // Pool is seed-artist-heavy — pick the first non-seed song to
                    // fetch a complementary similar-songs set from a different artist.
                    val alternate = pool.firstOrNull { it.artistId != seedSong.artistId }
                    if (alternate != null) {
                        Timber.tag(tag).d("Batch-2 alternate seed: '${alternate.id}' (non-seed artist)")
                    } else {
                        Timber.tag(tag).d("No non-seed alternate available — pool entirely seed artist")
                    }
                    alternate ?: pool.firstOrNull()
                } else {
                    pool.firstOrNull()
                }

                if (secondSeed != null) {
                    val secondBatch = runCatching { client.getSimilarSongs2(secondSeed.id, 50) }
                        .getOrElse { emptyList() }
                    Timber.tag(tag).d("Subsonic batch-2 (seed=${secondSeed.id}): ${secondBatch.size} candidates")
                    upsertAndMerge(secondBatch, usedSongIds, entityMap, pool)
                    Timber.tag(tag).d("Pool after batch-2: ${pool.size}")
                } else {
                    Timber.tag(tag).d("Pool thin but no second seed available — skipping batch-2")
                }
            }
        }

        // ── 4c. Random songs fallback ─────────────────────────────────────────
        // If the pool is still smaller than 20 or dominated by the seed artist
        // (> 50 %), call getRandomSongs to inject library-wide variety. This
        // guarantees a full 20-track mix for niche artists (e.g. Eva Cassidy)
        // where getSimilarSongs2 is self-referential and returns only the seed
        // artist's own songs.
        val poolSeedCount = pool.count { it.artistId == seedSong.artistId }
        val poolSeedRatio = poolSeedCount.toFloat() / pool.size.coerceAtLeast(1)
        Timber.tag(tag).d(
            "Pool after Subsonic: size=${pool.size}, seed ratio=${"%.0f".format(poolSeedRatio * 100)}%% ($poolSeedCount/${pool.size})",
        )
        if ((pool.size < 20 || poolSeedRatio > 0.5f) && client != null && currentMixToken.get() == myToken) {
            val randomBatch = runCatching { client.getRandomSongs(count = 50) }.getOrElse { emptyList() }
            Timber.tag(tag).d("Random fallback: ${randomBatch.size} candidates")
            upsertAndMerge(randomBatch, usedSongIds, entityMap, pool)
            Timber.tag(tag).d("Pool after random fallback: ${pool.size}")
        }

        if (currentMixToken.get() != myToken) return emptyList()

        // ── 5. Diversity cap + seed-artist companion limit + backfill ─────────
        // Pool order: LB-matched songs first (ranked by LB), then Subsonic in server order,
        // then random fallback songs.
        val poolList = pool.toList()

        val capped = InstantMixSelector.applyDiversityCap(poolList, target = 19, initialCap = 5, relaxedCap = 8)
        Timber.tag(tag).d("After diversity cap: ${capped.size}/${poolList.size} songs")
        capped.groupBy { it.artistId }.entries
            .sortedByDescending { it.value.size }.take(6)
            .forEach { (artistId, songs) ->
                Timber.tag(tag).d("  Capped artistId='$artistId' count=${songs.size}")
            }

        // Enforce seed-artist cap on the companion pool: allow max 3 seed-artist companions
        // here (seed itself will add 1 more at position 0 = 4 total in the final mix).
        val cappedWithSeedLimit = InstantMixSelector.enforceSeedArtistCap(
            capped, seedSong.artistId, maxSeedArtist = 3,
        )
        Timber.tag(tag).d(
            "After seed-artist companion cap: ${cappedWithSeedLimit.size} songs " +
                "(${cappedWithSeedLimit.count { it.artistId == seedSong.artistId }} seed-artist companions)",
        )

        // Fill to target-1 prioritising non-seed songs. By listing non-seed songs first in
        // the uncapped pool, fillToTarget naturally prefers variety when refilling.
        val nonSeedFirst = poolList.filter { it.artistId != seedSong.artistId } +
            poolList.filter { it.artistId == seedSong.artistId }
        val filled = InstantMixSelector.fillToTarget(cappedWithSeedLimit, nonSeedFirst, target = 19)
        Timber.tag(tag).d("After fillToTarget: ${filled.size} songs")

        // ── 6. Final assembly with artist interleaving ────────────────────────
        val result = InstantMixSelector.interleaveMix(seedSong, filled, target = 20)
        Timber.tag(tag).d(
            "Final mix: ${result.size} songs " +
                "(seed artist: ${result.count { it.artistId == seedSong.artistId }})",
        )
        return result
    }

    /**
     * Resolves [dtos] against Room (existing entities take precedence; unknowns become stubs),
     * upserts genuinely new stubs, then appends all resolved entities to [pool].
     * [usedIds] and [entityMap] are updated in place; songs already in [usedIds] are skipped.
     * Songs already in [entityMap] (from a previous batch) are also skipped to avoid
     * duplicate upserts.
     */
    private suspend fun upsertAndMerge(
        dtos: List<com.torsten.app.data.api.dto.SongDto>,
        usedIds: MutableSet<String>,
        entityMap: MutableMap<String, SongEntity>,
        pool: MutableList<SongEntity>,
    ) {
        val now = System.currentTimeMillis()
        val newStubs = mutableListOf<SongEntity>()

        for (dto in dtos) {
            if (dto.id in usedIds || dto.id in entityMap) continue
            val existing = songDao.getById(dto.id)
            if (existing != null) {
                entityMap[dto.id] = existing
            } else {
                // Gson bypasses Kotlin null-safety; guard title explicitly.
                @Suppress("SENSELESS_COMPARISON")
                val safeTitle = if (dto.title == null) "" else dto.title
                val stub = SongEntity(
                    id = dto.id,
                    albumId = dto.albumId.orEmpty(),
                    artistId = dto.artistId.orEmpty(),
                    title = safeTitle,
                    trackNumber = dto.track ?: 0,
                    discNumber = dto.discNumber ?: 1,
                    duration = dto.duration ?: 0,
                    bitRate = dto.bitRate,
                    suffix = dto.suffix,
                    contentType = dto.contentType,
                    starred = dto.starred != null,
                    localFilePath = null,
                    lastUpdated = now,
                    artistName = dto.artist.orEmpty(),
                    albumArtistName = dto.albumArtist.orEmpty(),
                )
                entityMap[dto.id] = stub
                newStubs.add(stub)
            }
        }

        if (newStubs.isNotEmpty()) {
            songDao.upsertAll(newStubs)
            Timber.tag("[InstantMix]").d("Upserted ${newStubs.size} new stubs (${entityMap.size - newStubs.size} already cached)")
        }

        for (dto in dtos) {
            if (dto.id in usedIds) continue
            val entity = entityMap[dto.id] ?: continue
            pool.add(entity)
            usedIds.add(dto.id)
        }
    }

    private suspend fun resolveMbid(artistId: String, artistName: String): String? {
        val cached = artistMbidCacheDao.getByArtistId(artistId)
        if (cached != null && cached.isValid()) return cached.mbid

        val mbid = musicBrainzClient.getArtistMbid(artistName) ?: return null
        artistMbidCacheDao.upsert(
            ArtistMbidCacheEntity(artistId = artistId, mbid = mbid, cachedAt = System.currentTimeMillis()),
        )
        return mbid
    }
}
