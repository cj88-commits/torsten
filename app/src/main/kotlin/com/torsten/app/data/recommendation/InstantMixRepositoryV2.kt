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

        if (needsFallback && currentMixToken.get() == myToken) {
            val config = serverConfigStore.serverConfig.first()
            if (config.isConfigured) {
                val client = SubsonicApiClient(config)
                // Shared across both batches so the second call never re-upserts the first's stubs.
                val entityMap = mutableMapOf<String, SongEntity>()

                // ── 4a. First Subsonic batch ──────────────────────────────────
                val firstBatch = runCatching { client.getSimilarSongs2(seedSong.id, 50) }
                    .getOrElse { emptyList() }
                Timber.tag(tag).d("Subsonic batch-1 candidates: ${firstBatch.size}")
                upsertAndMerge(firstBatch, usedSongIds, entityMap, pool)
                Timber.tag(tag).d("After dedup pool size: ${pool.size}")

                // ── 4b. Second batch when pool is still thin ──────────────────
                // Seed on the top LB-matched song (first in pool) if available;
                // otherwise fall back to first Subsonic result. The second batch
                // uses a different seed to retrieve a complementary similar-songs set.
                if (pool.size < 30 && currentMixToken.get() == myToken) {
                    val secondSeed = pool.firstOrNull()
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
        }

        if (currentMixToken.get() != myToken) return emptyList()

        // ── 5. Diversity cap + backfill ───────────────────────────────────────
        // Pool order: LB-matched songs first (ranked by LB), then Subsonic in server order.
        val poolList = pool.toList()

        val capped = InstantMixSelector.applyDiversityCap(poolList, target = 19, initialCap = 5, relaxedCap = 8)
        Timber.tag(tag).d("After diversity cap pool size: ${capped.size}")

        val filled = InstantMixSelector.fillToTarget(capped, poolList, target = 19)
        Timber.tag(tag).d("After fillToTarget pool size: ${filled.size}")

        // ── 6. Final assembly with artist interleaving ────────────────────────
        val result = InstantMixSelector.interleaveMix(seedSong, filled, target = 20)
        Timber.tag(tag).d("Final mix size: ${result.size} (should always be 20)")
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
