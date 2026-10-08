package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a refused probe means.
 *
 * Free function so the status mapping is unit-testable without a device. [detail] is the carrier's
 * own wording, kept for the outcomes that have nothing more specific to say.
 */
internal fun probeOutcomeOf(kind: TransportFailure?, detail: String): ProbeOutcome =
    when (kind) {
        // 403: the harness `Host` fence refused this address — add it to `trustedHosts`.
        TransportFailure.TRUST_FENCE -> ProbeOutcome.TrustFence
        // 401: reachable and trusted, but this device has not exchanged the startup token yet.
        TransportFailure.UNAUTHENTICATED -> ProbeOutcome.Unauthenticated
        TransportFailure.REFUSED -> ProbeOutcome.Refused
        TransportFailure.TIMEOUT -> ProbeOutcome.Timeout
        TransportFailure.DNS -> ProbeOutcome.DnsFailure
        TransportFailure.UNREACHABLE -> ProbeOutcome.Unreachable
        // The probe sends no arguments, so nothing it sends can be too large: a 413 here means
        // whatever is listening is not the harness.
        TransportFailure.NOT_FOUND, TransportFailure.NOT_A_HARNESS, TransportFailure.TOO_LARGE ->
            ProbeOutcome.NotAHarness
        TransportFailure.TLS -> ProbeOutcome.TlsFailure
        // A throttle and a dead upstream (a tunnel whose origin is down answers 502) both describe
        // a real harness address, so neither may read as "not a harness". Their own carrier
        // wording already says which, so it is what gets shown.
        TransportFailure.RATE_LIMITED, TransportFailure.UPSTREAM_DOWN,
        TransportFailure.OTHER, null,
        -> ProbeOutcome.Other(detail)
    }

/**
 * Probes a harness endpoint the user named — a Cloudflare Tunnel URL, a reverse proxy, or a local
 * `host:port` — and reports what answered.
 */
@Singleton
class DiscoveryEngine @Inject constructor(
    private val clientFactory: HarnessClientFactory,
) {
    /**
     * Probe one authority; null when it is not a harness.
     *
     * A remembered endpoint is probed through its own record, which carries its session cookie.
     */
    suspend fun probe(
        host: String,
        port: Int,
        timeouts: ProbeTimeouts = ProbeTimeouts.Manual,
        useTls: Boolean = false,
        config: HostConfig? = null,
    ): HostDescription? = (
        probeOutcome(host, port, timeouts, useTls = useTls, config = config) as? ProbeOutcome.Reachable
        )?.description

    /**
     * Probe one authority and keep the reason it failed.
     *
     * With [preflight] the connect deadline is enforced by a raw socket before the HTTP call. That
     * is not redundant: Android can surface a kernel connect timeout as a plain `ConnectException`
     * naming ETIMEDOUT, which blurs "refused" into "timed out" — and those two are exactly what
     * separates "the firewall is dropping this" from "the harness is still bound to loopback".
     */
    suspend fun probeOutcome(
        host: String,
        port: Int,
        timeouts: ProbeTimeouts = ProbeTimeouts.Manual,
        preflight: Boolean = false,
        useTls: Boolean = false,
        config: HostConfig? = null,
        basePath: String = "",
    ): ProbeOutcome = withContext(Dispatchers.IO) {
        if (preflight) {
            preflight(host, port, timeouts.connectMs)?.let { return@withContext it }
        }
        // Timeouts go through the transport's own parameters: it rebuilds the client it is handed,
        // so anything applied to a builder here would be silently replaced by the 30s default —
        // which is why this probe used to be able to block for thirty seconds.
        //
        // A remembered endpoint is probed through its own record, which carries the transport, the
        // pin and the credential together; a bare address has only the scheme the caller inferred.
        val client = if (config != null) {
            clientFactory.clientFor(config, timeouts)
        } else {
            clientFactory.anonymousClient(harnessBaseUrl(host, port, useTls, basePath), timeouts)
        }
        // `host.describe` was the probe through 0.1.1: one call that proved the endpoint spoke the
        // protocol and described it in the same breath. 0.1.2 has no such call, so the two jobs
        // separate. `session/canOpenWorkspacePath` is the reachability proof — no arguments, and
        // the Session Controller that serves it is composed by every deployment, so a 404 here
        // really does mean "not a harness" rather than "that service is not installed".
        when (val result = client.sessionCanOpenWorkspacePath()) {
            is RpcResult.Ok -> ProbeOutcome.Reachable(describeHome(client, config))
            is RpcResult.Err ->
                probeOutcomeOf(TransportFailures.of(result.error), result.error.message)
        }
    }

    /**
     * The host home, for a host worth a second call.
     *
     * Only the ready frame publishes this fact for certain, and a probe does not open the mux. A
     * directory listing carries the same value, so a named host spends one extra request to fill
     * in its subtitle. A deployment whose picker cannot browse simply answers an error,
     * and the host stays reachable with an unknown home.
     */
    private suspend fun describeHome(client: DshApiClient, config: HostConfig?): HostDescription? {
        if (config == null) return null
        return (client.hostListDirectory() as? RpcResult.Ok)?.value?.home?.let { HostDescription(it) }
    }

    /**
     * One raw TCP connect with our own deadline, so a drop is distinguishable from a refusal.
     * Returns null when the socket opened — the HTTP call then decides what is listening.
     */
    private fun preflight(host: String, port: Int, connectMs: Long): ProbeOutcome? = try {
        // Resolve explicitly: InetSocketAddress(String, Int) yields an *unresolved* address on a
        // DNS failure rather than throwing, which would surface later as a confusing socket error.
        val address = InetAddress.getByName(host)
        Socket().use { it.connect(InetSocketAddress(address, port), connectMs.toInt()) }
        null
    } catch (e: SocketTimeoutException) {
        ProbeOutcome.Timeout
    } catch (e: UnknownHostException) {
        ProbeOutcome.DnsFailure
    } catch (e: NoRouteToHostException) {
        ProbeOutcome.Unreachable
    } catch (e: ConnectException) {
        ProbeOutcome.Refused
    } catch (e: IOException) {
        when (TransportFailures.classify(e)) {
            TransportFailure.TIMEOUT -> ProbeOutcome.Timeout
            TransportFailure.REFUSED -> ProbeOutcome.Refused
            TransportFailure.UNREACHABLE -> ProbeOutcome.Unreachable
            else -> ProbeOutcome.Other(e.message ?: "connection failed")
        }
    }
}
