package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures

/**
 * Why a connection attempt did not succeed, at the level a person can act on.
 *
 * One step above [ProbeOutcome]: the probe knows what the socket did, this knows what to tell
 * someone standing between a phone and a computer. Deliberately free of Android imports so the whole
 * mapping is unit-testable — the app's tests are plain JVM, with no Robolectric.
 */
sealed interface ConnectFailure {

    /** The address or port was not usable as typed. */
    data object InvalidInput : ConnectFailure

    /** Nothing answered — dropped packets. Firewall, or a router isolating wireless clients. */
    data object Timeout : ConnectFailure

    /** Actively refused — the computer is there, the harness is not listening on that port. */
    data object Refused : ConnectFailure

    /**
     * The harness answered and its `Host` trust fence rejected this address (HTTP 403). For a
     * Cloudflare Tunnel the fix is adding the tunnel hostname to `trustedHosts`.
     */
    data object TrustFence : ConnectFailure

    /**
     * The harness answered and has no browser session for this client (HTTP 401).
     *
     * Harness 0.1.2 authenticates its whole `/api` surface, so a device that has not exchanged a
     * launch token is refused before any method runs. Separate from [TrustFence] because the fix
     * is on the phone (exchange a token from the harness's startup URL) rather than in the
     * harness's trusted-host list.
     */
    data object Unauthenticated : ConnectFailure

    /**
     * The harness was reached but did not accept the auth token: it rotates on every harness
     * restart, so this is almost always a token from an earlier run.
     */
    data object TokenRefused : ConnectFailure

    /** The name did not resolve on this network. */
    data object DnsFailure : ConnectFailure

    /** Something is listening, but it is not a harness. */
    data object NotAHarness : ConnectFailure

    /** The TLS handshake failed — an untrusted certificate, or `https://` to a plain-HTTP server. */
    data object TlsFailure : ConnectFailure

    /** The API answered but the event streams would not open. */
    data object StreamsBlocked : ConnectFailure

    /** Anything else; [detail] is the carrier's own words. */
    data class Other(val detail: String) : ConnectFailure

    companion object {

        /** Map a pre-flight probe outcome. */
        fun from(outcome: ProbeOutcome): ConnectFailure = when (outcome) {
            is ProbeOutcome.Reachable -> Other("")
            ProbeOutcome.TrustFence -> TrustFence
            ProbeOutcome.Unauthenticated -> Unauthenticated
            ProbeOutcome.Refused -> Refused
            ProbeOutcome.Timeout -> Timeout
            ProbeOutcome.DnsFailure -> DnsFailure
            // No route is a different-network problem; "nothing answered" is the honest reading.
            ProbeOutcome.Unreachable -> Timeout
            ProbeOutcome.NotAHarness -> NotAHarness
            ProbeOutcome.TlsFailure -> TlsFailure
            is ProbeOutcome.Other -> Other(outcome.detail)
        }

        /** Map a failure from inside the connection loop's readiness handshake. */
        fun from(failure: GenerationFailure): ConnectFailure = when (failure) {
            is GenerationFailure.MuxTimedOut -> StreamsBlocked
            is GenerationFailure.MuxFailed -> fromKind(failure.kind, failure.message, StreamsBlocked)
            // The ready frame replaced `host.describe` as the last handshake step, so this is where
            // "reached it, could not finish" now lands.
            is GenerationFailure.ReadyFailed -> fromKind(
                TransportFailures.of(failure.error),
                failure.error.message,
                Other(failure.error.message),
            )
        }

        /** Map a raw transport failure, e.g. from the token exchange. */
        fun from(kind: TransportFailure, message: String?): ConnectFailure =
            fromKind(kind, message, Other(message.orEmpty()))

        private fun fromKind(
            kind: TransportFailure?,
            message: String?,
            fallback: ConnectFailure,
        ): ConnectFailure = when (kind) {
            TransportFailure.TRUST_FENCE -> TrustFence
            TransportFailure.UNAUTHENTICATED -> Unauthenticated
            TransportFailure.REFUSED -> Refused
            TransportFailure.TIMEOUT, TransportFailure.UNREACHABLE -> Timeout
            TransportFailure.DNS -> DnsFailure
            // A no-argument call cannot be refused as too large, so 413 is something that is not
            // the harness. A throttle and a dead upstream, by contrast, are real harness addresses
            // and carry wording that says so, so they keep their message instead of being flattened
            // into "not a harness" and sending the person to re-check an address that was right.
            TransportFailure.NOT_FOUND, TransportFailure.NOT_A_HARNESS, TransportFailure.TOO_LARGE -> NotAHarness
            TransportFailure.TLS -> TlsFailure
            TransportFailure.RATE_LIMITED, TransportFailure.UPSTREAM_DOWN, TransportFailure.OTHER ->
                message?.takeIf { it.isNotBlank() }?.let { Other(it) } ?: fallback
            null -> fallback
        }
    }
}
