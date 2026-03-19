package com.torsten.app.data.api.auth

import okhttp3.OkHttpClient
import timber.log.Timber
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Identifies hosts that should have SSL certificate validation bypassed.
 *
 * This covers:
 *   - Tailscale addresses    (100.64.0.0/10)
 *   - RFC-1918 private space (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
 *   - Loopback               (127.x.x.x)
 *
 * WARNING: trust-all TLS is a deliberate trade-off for private-network servers that
 * use self-signed certificates. Do not apply to public internet endpoints.
 */
internal object TrustAllCerts {

    private val PRIVATE_HOST_PATTERNS = listOf(
        Regex("""^100\.\d+\.\d+\.\d+$"""),           // Tailscale
        Regex("""^10\.\d+\.\d+\.\d+$"""),             // RFC-1918 class A
        Regex("""^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$"""), // RFC-1918 class B
        Regex("""^192\.168\.\d+\.\d+$"""),             // RFC-1918 class C
        Regex("""^127\.\d+\.\d+\.\d+$"""),             // Loopback
    )

    /** Returns true if [host] is a private/Tailscale IP that warrants trust-all SSL. */
    fun isPrivateHost(host: String): Boolean =
        PRIVATE_HOST_PATTERNS.any { it.matches(host) }

    /**
     * Applies a trust-all [X509TrustManager] to [builder].
     * Call only when the target host is a known private/Tailscale address.
     */
    fun applyTrustAll(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        Timber.tag("[API]").w("SSL certificate validation disabled for private/Tailscale host")
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
    }
}
