package com.torsten.app.data.recommendation

import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.LbRecordingCandidate
import timber.log.Timber

data class MatchedSong(
    val song: SongEntity,
    val lbCandidateTitle: String,
    val lbRank: Int,
)

object CatalogueMatcher {

    private val SKIP_WORDS = setOf(
        "live", "acoustic", "wembley", "remix", "edition", "demo", "remaster",
    )

    fun match(
        candidates: List<LbRecordingCandidate>,
        songs: List<SongEntity>,
    ): List<MatchedSong> {
        val usedSongIds = mutableSetOf<String>()
        val results = mutableListOf<MatchedSong>()

        candidates.forEachIndexed { index, candidate ->
            val normCandidate = normalise(candidate.trackName)
            val rank = index + 1

            val match = songs
                .filter { it.id !in usedSongIds }
                .mapNotNull { song ->
                    val tier = matchTier(normCandidate, normalise(song.title))
                        ?: return@mapNotNull null
                    Triple(song, tier, preferenceScore(song.title))
                }
                .sortedWith(compareBy({ it.second }, { it.third }))
                .firstOrNull()

            if (match == null) {
                Timber.tag("[ArtistTop]").d("Unmatched [rank $rank]: '${candidate.trackName}'")
                return@forEachIndexed
            }

            usedSongIds.add(match.first.id)
            results.add(MatchedSong(song = match.first, lbCandidateTitle = candidate.trackName, lbRank = rank))
            Timber.tag("[ArtistTop]").d(
                "Matched: '${candidate.trackName}' → '${match.first.title}' (tier=${match.second}) [rank $rank]",
            )
        }

        return results
    }

    private fun matchTier(normA: String, normB: String): Int? = when {
        normA == normB -> 1
        normA.length >= 8 && normB.length >= 8 &&
            (normA.contains(normB) || normB.contains(normA)) -> 2
        trigramJaccard(normA, normB) >= 0.55f -> 3
        else -> null
    }

    private fun preferenceScore(title: String): Int {
        val lower = title.lowercase()
        return if (SKIP_WORDS.any { lower.contains(it) }) title.length + 10_000 else title.length
    }

    internal fun normalise(input: String): String {
        var s = input.lowercase()
        s = s.replace(Regex("\\(.*?\\)"), "")
        s = s.replace(Regex("\\[.*?\\]"), "")
        s = s.replace(Regex("\\bfe?a?t\\.?.*"), "")
        s = s.replace(Regex("[^a-z0-9 ]"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun trigrams(s: String): Set<String> {
        if (s.length < 3) return if (s.isNotEmpty()) setOf(s) else emptySet()
        return (0..s.length - 3).map { s.substring(it, it + 3) }.toSet()
    }

    internal fun trigramJaccard(a: String, b: String): Float {
        val ta = trigrams(a)
        val tb = trigrams(b)
        if (ta.isEmpty() && tb.isEmpty()) return 1f
        val intersection = ta.intersect(tb).size
        val union = (ta + tb).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }
}
