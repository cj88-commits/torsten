package com.torsten.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torsten.app.data.db.entity.PendingStarEntity

@Dao
interface PendingStarDao {

    @Query("SELECT * FROM pending_stars WHERE targetId IN (:ids)")
    suspend fun getForIds(ids: List<String>): List<PendingStarEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PendingStarEntity)

    @Query("DELETE FROM pending_stars WHERE targetId = :targetId")
    suspend fun delete(targetId: String)
}
