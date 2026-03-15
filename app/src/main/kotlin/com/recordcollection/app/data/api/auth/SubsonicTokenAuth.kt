package com.recordcollection.app.data.api.auth

import java.security.MessageDigest

/**
 * Subsonic token-auth (API v1.13+).
 *
 * The client generates a random salt on every request and computes:
 *   token = MD5(password + salt)
 *
 * Then appends to every request URL:
 *   u=<username>  t=<token>  s=<salt>  v=1.16.1  c=recordcollection  f=json
 *
 * NEVER log token, salt, or password values.
 */
internal object SubsonicTokenAuth {

    const val API_VERSION = "1.16.1"
    const val CLIENT_ID = "recordcollection"

    private val SALT_CHARS = ('a'..'z') + ('0'..'9')

    data class AuthParams(
        val username: String,
        val token: String,
        val salt: String,
    )

    fun buildAuthParams(username: String, password: String): AuthParams {
        val salt = generateSalt()
        val token = md5(password + salt)
        return AuthParams(username = username, token = token, salt = salt)
    }

    /**
     * Returns URL query string fragment with all required auth + client params.
     * Safe to append with & after existing query string.
     */
    fun AuthParams.asQueryString(): String =
        "u=${username.urlEncoded()}&t=$token&s=$salt&v=$API_VERSION&c=$CLIENT_ID&f=json"

    private fun generateSalt(length: Int = 12): String =
        (1..length).map { SALT_CHARS.random() }.joinToString("")

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
