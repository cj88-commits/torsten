package com.recordcollection.app.data.datastore

/**
 * Server connection credentials persisted in DataStore.
 *
 * [serverUrl] accepts both HTTP and HTTPS, with or without a path prefix,
 * e.g. "http://192.168.1.10:4533" or "https://music.example.com/navidrome".
 */
data class ServerConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    val usesPlainHttp: Boolean
        get() = serverUrl.trimStart().lowercase().startsWith("http://")
}
