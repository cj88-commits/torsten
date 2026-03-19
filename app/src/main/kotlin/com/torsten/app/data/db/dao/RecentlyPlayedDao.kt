package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.torsten.app.data.db.entity.RecentlyPlayedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyPlayedDao {

    @Query("SELECT * FROM recently_played ORDER BY playedAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<RecentlyPlayedEntity>>

    @Transaction
    suspend fun insertAndTrim(entry: RecentlyPlayedEntity) {
        insert(entry)
        trimToLimit()
    }

    @Insert
    suspend fun insert(entry: RecentlyPlayedEntity)

    // Keeps only the 50 most-recent rows; deletes any older ones.
    @Query(
        "DELETE FROM recently_played WHERE id NOT IN " +
            "(SELECT id FROM recently_played ORDER BY playedAt DESC LIMIT 50)",
    )
    suspend fun trimToLimit()
}
