package com.example

import java.net.InetAddress
import java.net.URI

/**
 * Validates URLs and IP destinations to protect against Server-Side Request Forgery (SSRF)
 * targeting loopback, link-local, and cloud metadata endpoints.
 *
 * NOTE: Legitimate private IPTV servers on local networks (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
 * are permitted and NOT blocked.
 */
internal object NetworkSecurityValidator {

    private val BLOCKED_HOSTNAMES = setOf(
        "localhost",
        "metadata.google.internal",
        "instance-data",
        "169.254.169.254",
        "169.254.170.2"
    )

    /**
     * Verifies that the URL is safe to fetch via HTTP/HTTPS.
     * Throws IllegalArgumentException or SecurityException if unsafe.
     */
    fun assertSafeUrl(urlString: String?) {
        require(!urlString.isNullOrBlank()) { "الرابط فارغ" }
        val trimmed = urlString.trim()
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("عنوان الرابط غير صالح: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "بروتوكول الرابط غير مسموح (يجب أن يكون http أو https)"
        }

        val host = uri.host?.lowercase()
        require(!host.isNullOrBlank()) { "عنوان المضيف في الرابط فارغ" }

        // 1. Check blocked hostnames
        if (BLOCKED_HOSTNAMES.any { host == it || host.endsWith(".$it") }) {
            throw SecurityException("تم رفض الاتصال بعنوان محلي أو محظور: $host")
        }

        // 2. If host is an IP or resolves to loopback / link-local metadata
        try {
            val address = InetAddress.getByName(host)
            if (isBlockedAddress(address)) {
                throw SecurityException("تم حظر الاتصال بالعنوان: ${address.hostAddress}")
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            // DNS resolution failures will be caught at connection time
        }
    }

    /**
     * Returns true if the address is loopback (127.0.0.0/8, ::1)
     * or IPv4 Link-Local / Cloud Metadata (169.254.0.0/16).
     *
     * Private RFC 1918 subnets (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
     * are NOT blocked to allow local IPTV box servers.
     */
    fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return true

        val bytes = address.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF

            // 127.0.0.0/8 loopback
            if (b0 == 127) return true

            // 169.254.0.0/16 Link-Local / Cloud Metadata (e.g. AWS/GCP 169.254.169.254)
            if (b0 == 169 && b1 == 254) return true

            // 0.0.0.0/8 current network
            if (b0 == 0) return true
        }

        return false
    }
}
