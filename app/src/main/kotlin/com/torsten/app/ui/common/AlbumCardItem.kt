package com.torsten.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.DownloadState

// ─── Palette ──────────────────────────────────────────────────────────────────

val DarkBackground = Color(0xFF0A0A0A)
private val CardBackground = Color(0xFF111111)
private val PlaceholderIconColor = Color(0xFF333333)
private val DownloadBadgeColor = Color(0xFF4CAF50)

// ─── Cover art image request helper ──────────────────────────────────────────

/**
 * Builds a Coil [ImageRequest] for cover art with a stable cache key.
 *
 * Subsonic cover art URLs contain auth parameters (u, t, s, v, c) that include a freshly-
 * generated salt every session. Using the full URL as a cache key means every session
 * produces a different key, so Coil always misses the disk cache offline.
 *
 * This helper forces a stable key — `"cover_art_<coverArtId>"` — for both memory and disk
 * cache, regardless of the auth params in the URL. When offline it also disables network
 * access so Coil reads only from the disk cache without attempting a failing HTTP request.
 */
fun coverArtImageRequest(
    context: Context,
    url: String?,
    coverArtId: String?,
    isOnline: Boolean,
): ImageRequest? {
    if (url == null) return null
    return ImageRequest.Builder(context)
        .data(url)
        .apply {
            if (coverArtId != null) {
                // Extract the `size` query parameter from the URL so that different requested
                // sizes (e.g. 150px thumbnail vs 300px grid vs 600px detail) get distinct cache
                // entries and do not overwrite each other.  Falls back to a size-agnostic key
                // when the URL has no size parameter.
                val size = Uri.parse(url).getQueryParameter("size")
                val stableKey = if (size != null) "cover_art_${coverArtId}_$size"
                                else "cover_art_$coverArtId"
                memoryCacheKey(stableKey)
                diskCacheKey(stableKey)
            }
            if (!isOnline) networkCachePolicy(CachePolicy.DISABLED)
        }
        .build()
}

// ─── Public composable ────────────────────────────────────────────────────────

/**
 * Album card used in both the main grid and the Artist Detail screen.
 * Title and subtitle are rendered as a gradient overlay at the bottom of the art.
 */
@Composable
fun AlbumCardItem(
    album: AlbumEntity,
    coverArtUrl: String?,
    isOnline: Boolean,
    onClick: (albumId: String, albumTitle: String) -> Unit,
    onOfflineBlocked: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = album.artistName,
    showDownloadBadge: Boolean = true,
) {
    val isDownloaded = album.downloadState == DownloadState.COMPLETE
    val isDimmed = !isOnline && !isDownloaded

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isDimmed) 0.5f else 1f)
            .clickable {
                if (isDimmed) onOfflineBlocked()
                else onClick(album.id, album.title)
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            AlbumCoverArt(
                coverArtUrl = coverArtUrl,
                coverArtId = album.coverArtId,
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
                isOnline = isOnline,
            )

            // Gradient + text overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.35f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                    )
                    .padding(horizontal = 5.dp, vertical = 5.dp),
            ) {
                Column {
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Downloaded badge — rendered last so it sits on top of the gradient overlay.
            // Previously this lived inside AlbumCoverArt, which has a lower z-order than
            // the gradient Box above and caused the badge to appear behind it.
            if (isDownloaded && showDownloadBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .size(18.dp)
                        .background(DownloadBadgeColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Downloaded",
                        tint = Color.White,
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape),
                    )
                }
            }
        }
    }
}

// ─── Network album card (for API-fetched AlbumDto grids) ─────────────────────

/**
 * Album grid card for API-fetched albums (AlbumDto). Used by GenreScreen and AlbumListScreen
 * where no offline/download state tracking is needed.
 */
@Composable
fun NetworkAlbumCard(
    albumId: String,
    albumTitle: String,
    albumArtist: String,
    coverArtId: String?,
    coverArtUrl: String?,
    onClick: (albumId: String, albumTitle: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(albumId, albumTitle) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            AlbumCoverArt(
                coverArtUrl = coverArtUrl,
                coverArtId = coverArtId,
                contentDescription = albumTitle,
                modifier = Modifier.fillMaxSize(),
                isOnline = true,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.35f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                    )
                    .padding(horizontal = 5.dp, vertical = 5.dp),
            ) {
                Column {
                    Text(
                        text = albumTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (albumArtist.isNotEmpty()) {
                        Text(
                            text = albumArtist,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ─── Cover art with placeholder ───────────────────────────────────────────────

@Composable
fun AlbumCoverArt(
    coverArtUrl: String?,
    coverArtId: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isOnline: Boolean = true,
) {
    val context = LocalContext.current
    val imageModel = coverArtImageRequest(context, coverArtUrl, coverArtId, isOnline)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CardBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = null,
                tint = PlaceholderIconColor,
                modifier = Modifier.fillMaxSize(0.45f),
            )
        }

        AsyncImage(
            model = imageModel,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
