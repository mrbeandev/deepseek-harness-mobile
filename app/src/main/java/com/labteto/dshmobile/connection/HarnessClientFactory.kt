package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.RemoteStreamMux
import com.labteto.dshmobile.core.wire.WsChannel
import com.labteto.dshmobile.core.wire.dto.REMOTE_STREAM_MUX_PATH
import com.labteto.dshmobile.core.wire.resolveHarnessUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a [DshApiClient] is built.
 *
 * The harness authenticates every `/api` call and the mux upgrade against the browser-session
 * cookie this device obtained by exchanging the startup token. Both the unary transport and the mux
 * must carry the same cookie, because the connection loop needs both inside one generation — so the
 * assembly happens here rather than being split across the manager and the discovery engine.
 *
 * TLS, when the endpoint is `https://` (a Cloudflare Tunnel or reverse proxy), is validated against
 * the platform trust store like any browser would.
 */
@Singleton
class HarnessClientFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessions: HarnessSessionStore,
) {
    /** The harness browser session for [config], or null when this device has not signed in. */
    private suspend fun cookieFor(config: HostConfig): String? = sessions.cookie(config.id)

    /**
     * A client for [config], carrying its session cookie.
     *
     * [timeouts] is for probes; the live connection takes the transport's own 30s defaults, because
     * a long `session/page` on a big session is not a stalled request.
     */
    suspend fun clientFor(config: HostConfig, timeouts: ProbeTimeouts? = null): DshApiClient =
        DshApiClient(
            transport = OkHttpRpcTransport(
                baseUrl = config.baseUrl,
                client = okHttpClient,
                connectTimeoutMs = timeouts?.connectMs ?: DEFAULT_TIMEOUT_MS,
                readTimeoutMs = timeouts?.readMs ?: DEFAULT_TIMEOUT_MS,
                cookie = cookieFor(config),
            ),
        )

    /**
     * The mux carrying every stream of one connection generation.
     *
     * Separate from [clientFor] because its lifetime is the generation's, not the client's: the
     * connection loop builds a new one per attempt and closes it when the generation ends.
     */
    suspend fun muxFor(config: HostConfig): RemoteStreamMux {
        val cookie = cookieFor(config)
        val url = resolveHarnessUrl(config.baseUrl, REMOTE_STREAM_MUX_PATH).toString()
        return RemoteStreamMux { sink -> WsChannel(url, okHttpClient, sink, cookie = cookie) }
    }

    /** A client for an address nothing is remembered about yet — deliberately unauthenticated. */
    fun anonymousClient(baseUrl: String, timeouts: ProbeTimeouts): DshApiClient = DshApiClient(
        transport = OkHttpRpcTransport(
            baseUrl = baseUrl,
            client = okHttpClient,
            connectTimeoutMs = timeouts.connectMs,
            readTimeoutMs = timeouts.readMs,
        ),
    )

    private companion object {
        /** The transport's own default, restated so a null [ProbeTimeouts] is explicit rather than magic. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
