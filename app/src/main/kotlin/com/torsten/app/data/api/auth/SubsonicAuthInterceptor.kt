package com.torsten.app.data.api.auth

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/**
 * OkHttp interceptor that appends Subsonic token-auth parameters to every request.
 *
 * Auth is built here once and applied globally — callers never pass credentials
 * individually. The interceptor logs only the request path, never the full URL
 * (which contains the token and salt).
 */
internal class SubsonicAuthInterceptor(
    private val username: String,
    private val password: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Log path only — auth params in the URL must never appear in logs.
        Timber.tag("[API]").d("→ %s", originalRequest.url.encodedPath)

        val auth = SubsonicTokenAuth.buildAuthParams(username, password)

        val authenticatedUrl = originalRequest.url
            .newBuilder()
            .addQueryParameter("u", auth.username)
            .addQueryParameter("t", auth.token)
            .addQueryParameter("s", auth.salt)
            .addQueryParameter("v", SubsonicTokenAuth.API_VERSION)
            .addQueryParameter("c", SubsonicTokenAuth.CLIENT_ID)
            .addQueryParameter("f", "json")
            .build()

        val authenticatedRequest = originalRequest
            .newBuilder()
            .url(authenticatedUrl)
            .build()

        val response = chain.proceed(authenticatedRequest)
        Timber.tag("[API]").d("← %s %d", originalRequest.url.encodedPath, response.code)
        return response
    }
}
