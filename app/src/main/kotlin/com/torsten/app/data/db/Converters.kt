package com.torsten.app.data.db

import androidx.room.TypeConverter
import com.torsten.app.data.db.entity.DownloadState

class Converters {

    @TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name

    @TypeConverter
    fun toDownloadState(value: String): DownloadState =
        DownloadState.valueOf(value)
}
