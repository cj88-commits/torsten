package com.torsten.app.ui

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.torsten.app.data.api.auth.TrustAllCerts
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Configures the process-wide Coil [ImageLoader].
 *
 * Uses [OkHttpNetworkFetcherFactory] (from coil-network-okhttp) wired with a
 * custom [OkHttpClient] that:
 *  - Disables SSL certificate validation for private/Tailscale hosts so that
 *    self-signed certs on local Subsonic servers are accepted.
 *  - Adds a network interceptor that overwrites the response Cache-Control header
 *    to max-age=604800 (7 days), keeping cover art available offline long after
 *    any server-issued cache headers would have expired.
 *
 * Note: [OkHttpNetworkFetcherFactory] is a top-level Kotlin function in the
 * coil-network-okhttp artifact. In Coil 3.0.4 this artifact only ships a JVM jar
 * (no Android AAR), but Gradle falls back to the JVM jar for Android builds so the
 * function resolves on the compile classpath. At runtime the ServiceLoader registration
 * in the same jar is superseded by our explicit wiring here.
 *
 * Caching:
 *  - Memory cache : up to 20 % of available app memory
 *  - Disk cache   : up to 100 MB in the standard Coil image cache directory
 */
fun initCoilImageLoader(context: Context, cacheSizeMb: Int = 1024) {
    val okHttpClient = buildCoilOkHttpClient()
    SingletonImageLoader.setSafe { ctx ->
        ImageLoader.Builder(ctx)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(ctx, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.cacheDir.toOkioPath().resolve("image_cache"))
                    .maxSizeBytes(cacheSizeMb.toLong() * 1024 * 1024)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = okHttpClient))
            }
            .build()
    }
}

/**
 * Builds the [OkHttpClient] used exclusively by Coil for cover-art requests.
 *
 * TrustAllCerts is applied unconditionally because every cover-art URL points to
 * the same private/Tailscale Subsonic server. The Cache-Control interceptor is a
 * network interceptor so it runs on real responses (not cache hits) and overrides
 * whatever the server sends, guaranteeing 7-day browser/Coil disk-cache reuse.
 */
private fun buildCoilOkHttpClient(): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            response.newBuilder()
                .header("Cache-Control", "public, max-age=604800")
                .build()
        }
    TrustAllCerts.applyTrustAll(builder)
    return builder.build()
}
