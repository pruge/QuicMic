package com.pruge.quicmic

import android.content.Context
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * TOFU (Trust On First Use) pin store.
 *
 * Persists one SHA-256 certificate fingerprint per "host:port" in app-private
 * SharedPreferences. The fingerprint covers the raw DER encoding of the
 * server's leaf certificate — the exact bytes QuicMic's self-signed identity is
 * built from, so a regenerated certificate (e.g. after a LAN IP change) always
 * mismatches and triggers the re-confirm flow.
 */
class TofuStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Compute the pinned-format fingerprint ("AA:BB:…") of a certificate. */
    fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(separator = ":") { b -> "%02X".format(b) }
    }

    /** Stored fingerprint for this endpoint, or null on first use. */
    fun stored(host: String, port: Int): String? =
        prefs.getString(key(host, port), null)

    fun save(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), normalize(fingerprint)).apply()
    }

    fun forget(host: String, port: Int) {
        prefs.edit().remove(key(host, port)).apply()
    }

    /** All pinned endpoints as ("host:port", fingerprint) pairs. */
    fun entries(): List<Pair<String, String>> =
        prefs.all.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.sortedBy { it.first }

    companion object {
        private const val PREFS_FILE = "tofu"

        fun key(host: String, port: Int): String = "$host:$port"

        /**
         * Normalize any user/derived fingerprint representation into the canonical
         * uppercase colon-separated form so comparisons are representation-agnostic.
         */
        fun normalize(fingerprint: String): String =
            fingerprint.replace(":", "").replace(" ", "").uppercase().chunked(2).joinToString(":")
    }
}
