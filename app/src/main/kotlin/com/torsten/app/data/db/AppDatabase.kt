package com.torsten.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.torsten.app.data.db.dao.AlbumDao
import com.torsten.app.data.db.dao.ArtistDao
import com.torsten.app.data.db.dao.ArtistMbidCacheDao
import com.torsten.app.data.db.dao.ArtistTopTracksCacheDao
import com.torsten.app.data.db.dao.PendingStarDao
import com.torsten.app.data.db.dao.PlaybackPositionDao
import com.torsten.app.data.db.dao.SongDao
import com.torsten.app.data.db.dao.SyncMetadataDao
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.ArtistEntity
import com.torsten.app.data.db.entity.ArtistMbidCacheEntity
import com.torsten.app.data.db.entity.ArtistTopTracksCacheEntity
import com.torsten.app.data.db.entity.PendingStarEntity
import com.torsten.app.data.db.entity.PlaybackPositionEntity
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.db.entity.SyncMetadataEntity

// Schema migrations: fallbackToDestructiveMigration() is intentional for this app.
// All data (artists, albums, songs) is re-synced from the Subsonic server on next launch,
// so dropping the database on a version bump is acceptable. If user-generated data (e.g.
// custom playlists) is added in future, named Migration objects will be required.

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        SongEntity::class,
        PlaybackPositionEntity::class,
        SyncMetadataEntity::class,
        PendingStarEntity::class,
        ArtistMbidCacheEntity::class,
        ArtistTopTracksCacheEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun songDao(): SongDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun pendingStarDao(): PendingStarDao
    abstract fun artistMbidCacheDao(): ArtistMbidCacheDao
    abstract fun artistTopTracksCacheDao(): ArtistTopTracksCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "torsten.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
