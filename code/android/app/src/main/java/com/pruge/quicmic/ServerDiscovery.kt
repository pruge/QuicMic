package com.pruge.quicmic

import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Finds the QuicMic server on the LAN.
 *
 * The server cannot be changed for this feature (D4), so there is no mDNS or
 * other advertisement: discovery is a bounded TCP probe of the phone's own /24
 * on the fixed HTTPS port, followed by an HTTPS GET of the open `/api/info`
 * endpoint to confirm the responder actually is a QuicMic server. The probe
 * trusts everything — that fetch carries no secret and conveys no trust — real
 * trust is established afterwards by the WebView TOFU flow.
 */
object ServerDiscovery {

    /** QuicMic's fixed HTTPS port (CLI default). */
    const val PORT = 8443

    private const val CONNECT_TIMEOUT_MS = 250
    private const val INFO_TIMEOUT_MS = 1_500
    private const val PROBE_POOL_SIZE = 48

    /**
     * Probe the current network's /24 for QuicMic servers. Blocking; returns
     * verified hosts in ascending order. Empty list when nothing answered.
     */
    fun discover(): List<String> {
        val base = localSubnetBase() ?: return emptyList()
        val pool: ExecutorService = Executors.newFixedThreadPool(PROBE_POOL_SIZE)
        try {
            val futures = mutableListOf<Future<String?>>()
            for (last in 1..254) {
                val host = "$base.$last"
                futures += pool.submit(Callable<String?> {
                    if (looksLikeQuicMic(host)) host else null
                })
            }
            return futures.mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * True when [host]:PORT accepts TCP and answers `/api/info` with the
     * expected QuicMic metadata shape.
     */
    fun looksLikeQuicMic(host: String): Boolean {
        if (!tcpOpen(host)) return false
        return try {
            val body = fetchInfoIgnoringTls(host) ?: return false
            // /api/info returns JSON containing these fields; enough to identify
            // the service without pulling in a JSON dependency.
            body.contains("\"cert_hash\"") && body.contains("\"wt_port\"")
        } catch (_: IOException) {
            false
        }
    }

    /** Best-effort local IPv4 subnet base ("192.168.1"), or null when unknown. */
    private fun localSubnetBase(): String? = try {
        DatagramSocket().use { socket ->
            // UDP connect only consults the routing table — no packet is sent,
            // and no permission beyond INTERNET is required.
            socket.connect(InetSocketAddress("192.0.2.1", 9))
            val addr = socket.localAddress
            if (addr is Inet4Address) addr.address.joinToString(".").substringBeforeLast(".") else null
        }
    } catch (_: IOException) {
        null
    }

    private fun tcpOpen(host: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS); true }
    } catch (_: IOException) {
        false
    }

    /** GET https://host:PORT/api/info trusting anything (identification only). */
    private fun fetchInfoIgnoringTls(host: String): String? {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), null)
        val url = java.net.URL("https", host, PORT, "/api/info")
        val https = url.openConnection() as HttpsURLConnection
        https.sslSocketFactory = ctx.socketFactory
        https.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        https.connectTimeout = INFO_TIMEOUT_MS
        https.readTimeout = INFO_TIMEOUT_MS
        return try {
            https.inputStream.bufferedReader().use { it.readText() }
        } finally {
            https.disconnect()
        }
    }
}
