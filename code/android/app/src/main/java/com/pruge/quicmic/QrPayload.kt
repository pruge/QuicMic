package com.pruge.quicmic

/**
 * Parser for the QuicMic pairing QR payload: `https://<host>:<port>#<pin>`.
 *
 * The same URL the desktop server renders into the terminal QR code (see the
 * server's QR pairing docs) is what lands in the scanner, so one parse covers
 * IPv4, bracketed IPv6 literals and pasted URLs alike. The PIN stays in the
 * fragment — it is never sent to the server over HTTP.
 */
data class QrPayload(val host: String, val port: Int, val pin: String) {

    /** The WebView load target: scheme + authority + the PIN as the hash. */
    fun toUrl(): String = "https://$host:$port#$pin"

    companion object {

        /**
         * Parse a scanned or hand-entered value into a [QrPayload], or null
         * when it does not describe a QuicMic server URL with a PIN fragment.
         * Accepts an optional `https://` prefix and tolerates whitespace.
         */
        fun parse(raw: String): QrPayload? {
            val text = raw.trim()
            if (!text.startsWith("https://")) return null
            val rest = text.removePrefix("https://")
            // Fragment carries the PIN; everything before it is the authority.
            val slash = rest.indexOf('/')
            val authorityAndFragment = if (slash >= 0) rest.substring(0, slash) else rest
            val hashIdx = authorityAndFragment.indexOf('#')
            if (hashIdx < 0) return null
            val authority = authorityAndFragment.substring(0, hashIdx)
            val pin = authorityAndFragment.substring(hashIdx + 1)
            // The web UI pairs any non-empty hash, but QuicMic's PINs are six digits;
            // require that so a stray QR never triggers pairing.
            if (!pin.matches(Regex("\\d{6}"))) return null
            val host: String
            val portStr: String
            if (authority.startsWith("[")) {
                // Bracketed IPv6 literal: [fe80::1]:8443
                val close = authority.indexOf(']')
                if (close < 0) return null
                host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                if (!after.startsWith(":")) return null
                portStr = after.substring(1)
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon < 0) return null
                host = authority.substring(0, colon)
                portStr = authority.substring(colon + 1)
            }
            val port = portStr.toIntOrNull() ?: return null
            if (port !in 1..65535 || host.isEmpty()) return null
            return QrPayload(host, port, pin)
        }
    }
}
