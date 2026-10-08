package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.authorityOf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What the connect form's host field actually said.
 *
 * People paste what they have, and what they have is usually a URL: the line the harness printed,
 * the address bar of the working web GUI, a reverse proxy's `https://agent.home`. Handing that
 * string to DNS as a hostname is how "https://agent.home" came to be diagnosed as a computer that
 * does not exist (#6). This type keeps the three facts a URL can carry apart, so the caller can
 * decide what fills the gaps.
 */
data class HostInput(
    /** The bare host: an IPv4/IPv6 literal or a name, brackets and scheme stripped. */
    val host: String,
    /** A port the field itself named (`https://x:8443`, `x:3080`), or null. */
    val port: Int?,
    /** What the scheme said about TLS — `https://` true, `http://` false, no scheme null. */
    val useTls: Boolean?,
    val basePath: String = "",
)

/**
 * Read the host field leniently; null means nothing connectable was typed.
 *
 * Accepted: a bare host, `host:port`, an `http://` or `https://` URL with optional port and an
 * preserved proxy path, and IPv6 literals with or without brackets. A port named here is more explicit
 * than the separate port field (it was typed as part of an address, not left over from a previous
 * harness), so callers let it win. Any other scheme is refused rather than guessed at.
 */
internal fun parseHostInput(raw: String): HostInput? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null

    var useTls: Boolean? = null
    var rest = trimmed
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd >= 0) {
        useTls = when (trimmed.take(schemeEnd).lowercase()) {
            "http" -> false
            "https" -> true
            else -> return null
        }
        rest = trimmed.substring(schemeEnd + 3)
    }

    val path = rest.substringBefore('?').substringBefore('#').substringAfter('/', "")
    if (rest.substringBefore('/').contains('@') || rest.contains('\\') || rest.any { it.isWhitespace() }) return null
    val basePath = if (path.isEmpty()) "" else {
        val url = ("https://placeholder/" + path).toHttpUrlOrNull() ?: return null
        url.encodedPath.trimEnd('/')
    }
    rest = rest.takeWhile { it != '/' && it != '?' && it != '#' }
    if (rest.isBlank()) return null

    val host: String
    var port: Int? = null
    when {
        // Bracketed IPv6, the URL form: [::1] or [::1]:3080.
        rest.startsWith("[") -> {
            val close = rest.indexOf(']')
            if (close <= 1) return null
            host = rest.substring(1, close)
            val after = rest.substring(close + 1)
            when {
                after.isEmpty() -> {}
                after.startsWith(":") -> port = after.drop(1).toValidPort() ?: return null
                else -> return null
            }
        }
        // More than one colon and no brackets can only be a bare IPv6 literal — a port after an
        // unbracketed one is unparseable, which is exactly why URLs bracket them.
        rest.count { it == ':' } > 1 -> host = rest
        rest.contains(':') -> {
            host = rest.substringBefore(':')
            port = rest.substringAfter(':').toValidPort() ?: return null
        }
        else -> host = rest
    }
    if (host.isBlank() || host.any { it.isWhitespace() }) return null
    return HostInput(host = host, port = port, useTls = useTls, basePath = basePath)
}

private fun String.toValidPort(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }

/**
 * `host:port` as a URL requires it — an IPv6 literal goes back into brackets. The display
 * authority everywhere else stays bare; this form is only for building URLs.
 */
internal fun urlAuthority(host: String, port: Int): String = authorityOf(host, port)

/** The scheme-qualified base URL for one harness endpoint. */
internal fun harnessBaseUrl(host: String, port: Int, useTls: Boolean, basePath: String = ""): String =
    (if (useTls) "https://" else "http://") + urlAuthority(host, port) + basePath

internal fun endpointKey(host: String, port: Int, useTls: Boolean, basePath: String = ""): String =
    (if (useTls) "https://" else "") + urlAuthority(host, port) + basePath

/** A fully resolved endpoint the connect form will dial. */
data class ResolvedEndpoint(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val basePath: String,
) {
    val isLoopback: Boolean get() = host == "127.0.0.1" || host == "localhost" || host == "::1"
    val authority: String get() = endpointKey(host, port, useTls, basePath)
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls, basePath)
}

/** The harness web profile's default listen port. */
internal const val DEFAULT_HARNESS_PORT = 3080

/**
 * Turn the connect form's address field into something dialable; null when nothing usable was typed.
 *
 * The rules follow what people actually paste:
 * - `https://dsh.example.com` — a Cloudflare Tunnel (or any TLS reverse proxy): port 443.
 * - `http://192.168.1.20:3080/?token=…` — the harness's own startup line: the port it names.
 * - `192.168.1.20` or `localhost` with no scheme and no port — a local harness: plain HTTP on 3080.
 * - `dsh.example.com` with no scheme and no port — a public hostname, so a tunnel: HTTPS on 443.
 *   Names ending in `.local`/`.lan`/`.home` are LAN names and get the local default instead.
 * A port typed in the field always wins.
 */
fun resolveEndpoint(raw: String): ResolvedEndpoint? {
    val input = parseHostInput(raw) ?: return null
    val local = isLocalName(input.host)
    val useTls = input.useTls ?: (input.port == 443 || (input.port == null && !local))
    val port = input.port ?: when {
        input.useTls == true -> 443
        input.useTls == false -> if (local) DEFAULT_HARNESS_PORT else 80
        useTls -> 443
        else -> DEFAULT_HARNESS_PORT
    }
    return ResolvedEndpoint(input.host, port, useTls, input.basePath)
}

/**
 * The launch token carried in a pasted startup URL (`…/?token=abc`), or null.
 *
 * Lets someone paste the whole line the harness printed into the address field and be done.
 */
fun tokenInAddress(raw: String): String? {
    val marker = raw.indexOf("token=")
    if (marker < 0) return null
    return raw.substring(marker + "token=".length)
        .takeWhile { it != '&' && it != '#' && !it.isWhitespace() }
        .takeIf { it.isNotEmpty() }
}

/** IP literals, `localhost`, single-label names and conventional LAN suffixes. */
private fun isLocalName(host: String): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || h.contains(':')) return true
    if (h.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true } }) return true
    if (!h.contains('.')) return true
    return LOCAL_SUFFIXES.any { h.endsWith(it) }
}

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")
