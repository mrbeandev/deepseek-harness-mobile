package com.labteto.dshmobile.connection

import kotlinx.serialization.Serializable

/**
 * One remembered harness endpoint: a Cloudflare Tunnel / reverse-proxy URL, or a local address.
 *
 * The `last*` fields cache what the harness said about itself so a Recent card can describe it
 * before its liveness probe lands. Every field defaults, because the whole list is persisted as one
 * JSON blob whose decode failure is swallowed: a field without a default would silently wipe every
 * remembered host on upgrade. Records written by older builds may still carry relay fields; the
 * decoder ignores unknown keys, so they simply fall away.
 *
 * The harness session cookie is deliberately **not** here; it lives in [HarnessSessionStore],
 * keyed by [id].
 */
@Serializable
data class HostConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val isLoopback: Boolean = false,
    /**
     * Speak TLS to this endpoint. The harness itself never serves HTTPS — this is for whatever sits
     * in front of it: a Cloudflare Tunnel (`https://dsh.example.com`) or a reverse proxy.
     */
    val useTls: Boolean = false,
    val basePath: String = "",
    val lastConnectedAt: Long = 0L,
    /** The host account's home directory, as of the last successful connection. */
    val lastHome: String? = null,
) {
    /** Endpoint identity and display form, including TLS and the reverse-proxy root. */
    val authority: String get() = endpointKey(host, port, useTls, basePath)
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls, basePath)

    /** What a card prints: the authority, scheme-qualified only when it is not the plain default. */
    val displayAddress: String get() = authority

    /** Whether traffic to this endpoint travels in the clear. */
    val isPlaintext: Boolean get() = !useTls
}

/** Read endpoint-specific history first, retaining the pre-prefix key as an upgrade fallback. */
internal fun HostConfig.rememberedSession(saved: Map<String, String>): String? =
    saved[baseUrl] ?: saved["$host:$port"]

/** App-level persisted settings (DataStore). */
data class AppSettings(
    val autoConnectLast: Boolean = true,
    val autoConnectLoopback: Boolean = true,
    val keepConnectedInBackground: Boolean = false,
    val notifyTurnComplete: Boolean = true,
    val notifyGoal: Boolean = true,
    val notifyNeedsAction: Boolean = true,
    val themePreference: String = "system", // light | dark | system
    val localeOverride: String? = null, // null = system
    /**
     * Whether to ask GitHub for the latest release on start.
     *
     * The only request this app makes to anything other than the harness the user pointed it at,
     * so it is worth being able to switch off — on a restricted network, or by anyone who would
     * rather it stayed local-only.
     */
    val updateCheckEnabled: Boolean = true,
    /** A release the user has already declined, so it is offered once rather than every launch. */
    val dismissedUpdate: String? = null,
)
