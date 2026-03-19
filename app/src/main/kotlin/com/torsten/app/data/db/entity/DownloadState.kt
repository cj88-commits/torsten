package com.torsten.app.data.db.entity

enum class DownloadState {
    NONE,
    QUEUED,
    DOWNLOADING,
    PARTIAL,
    COMPLETE,
    FAILED,
}
