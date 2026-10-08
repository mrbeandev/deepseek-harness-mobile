package com.labteto.dshmobile.ui.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.DiscoveryEngine
import com.labteto.dshmobile.connection.HarnessSessionStore
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.connection.ResolvedEndpoint
import com.labteto.dshmobile.connection.resolveEndpoint
import com.labteto.dshmobile.connection.tokenInAddress
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.dto.HostDescription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.UUID
import javax.inject.Inject

/** Whether a remembered harness is answering right now. */
sealed interface HostProbe {
    /** The probe is in flight. */
    data object Probing : HostProbe

    /** It answered; [description] is what it said, when it said anything. */
    data class Reachable(val description: HostDescription?) : HostProbe

    /** No answer — switched off, asleep, or the tunnel is down. */
    data object Unreachable : HostProbe
}

/** Why a launch-token exchange did not produce a browser session. */
enum class SignInError {
    /** The screen has no remembered host to sign in to — nothing was attempted yet. */
    NoHost,

    /** The harness answered and issued no session; the token is not the current process's. */
    Refused,

    /** The exchange never reached a harness. */
    Unreachable,
}

data class ConnectUiState(
    val remembered: List<HostConfig> = emptyList(),
    /** Liveness per remembered host, keyed by authority. Absent = not probed yet. */
    val recentStatus: Map<String, HostProbe> = emptyMap(),
    /** What the connect attempt is doing right now. */
    val stage: ConnectStage = ConnectStage.Idle,
    /** Why the last attempt failed, or null. */
    val failure: ConnectFailure? = null,
    /** The authority actually attempted, e.g. `https://dsh.example.com:443` — never the live field text. */
    val attempted: String? = null,
    /** The loop is still retrying in the background, so a cancel is worth offering. */
    val retrying: Boolean = false,
    /** The launch-token prompt (for a remembered host whose session lapsed) is showing. */
    val signInOpen: Boolean = false,
    /** An exchange is in flight. */
    val signingIn: Boolean = false,
    /** Why the last exchange did not produce a session, or null. */
    val signInError: SignInError? = null,
    val autoConnectLast: Boolean = true,
    val autoConnectLoopback: Boolean = true,
) {
    /**
     * Derived, never stored, so there is no latch to get stuck: every path ends in either
     * [ConnectStage.Connected] or a failure the screen displays.
     */
    val connecting: Boolean
        get() = stage != ConnectStage.Idle && stage != ConnectStage.Connected
}

/**
 * Drives the connect screen: one address (a Cloudflare Tunnel URL or a local `host:port`) plus the
 * auth token the harness printed at startup, and a list of harnesses already signed in to.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val discoveryEngine: DiscoveryEngine,
    private val hostsStore: HostsStore,
    private val harnessSessions: HarnessSessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    /**
     * Non-null while this ViewModel owns the outcome rather than the manager.
     *
     * Validation, the token exchange and the pre-flight probe happen here and can end the attempt
     * before the manager is ever engaged; everything from the handshake on belongs to the manager.
     * Set to null only when handing over, so a locally-decided result is not overwritten by a
     * manager still reporting the previous attempt.
     */
    private var localStage: ConnectStage? = null

    /** The liveness pass in flight, so the screen coming back during one does not start a rival. */
    private var probeJob: Job? = null

    init {
        viewModelScope.launch {
            connectionManager.state.collect { conn ->
                _state.update { current ->
                    val connected = conn.phase == ConnectionPhase.CONNECTED
                    val owned = localStage != null
                    current.copy(
                        stage = localStage ?: conn.stage,
                        failure = when {
                            connected -> null
                            owned -> current.failure
                            else -> conn.failure
                        },
                        attempted = current.attempted ?: conn.host?.authority,
                        retrying = !owned && !connected && conn.attempts > 0,
                        recentStatus = current.attempted
                            ?.takeIf { !owned && conn.failure != null }
                            ?.let { current.recentStatus + (it to HostProbe.Unreachable) }
                            ?: current.recentStatus,
                    )
                }
            }
        }
        viewModelScope.launch {
            val settings = hostsStore.settingsOnce()
            _state.update {
                it.copy(
                    autoConnectLast = settings.autoConnectLast,
                    autoConnectLoopback = settings.autoConnectLoopback,
                )
            }
            hostsStore.hosts.collect { hosts ->
                _state.update { it.copy(remembered = hosts) }
            }
        }
        viewModelScope.launch { autoConnect() }
        refreshRecent()
    }

    /**
     * Connect to the address in the form, signing in with [tokenInput] first when one was given.
     *
     * [address] takes what people paste: `https://dsh.example.com`, `192.168.1.20:3080`, or the
     * whole `http://…/?token=…` line the harness printed — in which case the token is read out of
     * it and [tokenInput] may be left empty. A token is optional for an address this phone has
     * signed in to before; the session cookie survives harness restarts.
     */
    fun connect(address: String, tokenInput: String) {
        val endpoint = resolveEndpoint(address)
        if (endpoint == null) {
            fail(ConnectFailure.InvalidInput, attempted = null)
            return
        }
        val token = tokenInput.trim().ifEmpty { tokenInAddress(address).orEmpty() }

        localStage = ConnectStage.Validating
        _state.update { it.copy(stage = ConnectStage.Validating, failure = null, attempted = endpoint.authority) }

        viewModelScope.launch {
            val existing = hostsStore.hosts.first().firstOrNull { it.baseUrl == endpoint.baseUrl }
            val hostId = existing?.id ?: UUID.randomUUID().toString()

            localStage = ConnectStage.Reaching
            _state.update { it.copy(stage = ConnectStage.Reaching) }

            if (token.isNotEmpty()) {
                when (val exchange = harnessSessions.pair(hostId, endpoint.baseUrl, token)) {
                    is SessionExchange.Granted -> Unit
                    is SessionExchange.Refused -> {
                        fail(ConnectFailure.TokenRefused, endpoint.authority)
                        return@launch
                    }
                    is SessionExchange.Unreachable -> {
                        fail(ConnectFailure.from(exchange.kind, exchange.message), endpoint.authority)
                        return@launch
                    }
                }
            }

            val probeConfig = existing ?: provisional(hostId, endpoint)
            val outcome = discoveryEngine.probeOutcome(
                host = endpoint.host,
                port = endpoint.port,
                timeouts = ProbeTimeouts.Manual,
                preflight = true,
                useTls = endpoint.useTls,
                config = probeConfig,
                basePath = endpoint.basePath,
            )
            if (outcome !is ProbeOutcome.Reachable) {
                fail(ConnectFailure.from(outcome), endpoint.authority)
                return@launch
            }
            val config = hostsStore.rememberHost(
                name = existing?.name ?: endpoint.host,
                host = endpoint.host,
                port = endpoint.port,
                isLoopback = endpoint.isLoopback,
                useTls = endpoint.useTls,
                description = outcome.description,
                basePath = endpoint.basePath,
                id = hostId,
            )
            connectTo(config)
        }
    }

    /** An unsaved record for probing an endpoint with whatever session was just stored for [id]. */
    private fun provisional(id: String, endpoint: ResolvedEndpoint) = HostConfig(
        id = id,
        name = endpoint.host,
        host = endpoint.host,
        port = endpoint.port,
        isLoopback = endpoint.isLoopback,
        useTls = endpoint.useTls,
        basePath = endpoint.basePath,
    )

    /**
     * Exchange a startup token for a remembered host whose session lapsed, then retry it.
     *
     * Reached from the Sign in button on a 401 failure after tapping a Recent card.
     */
    fun signIn(input: String) {
        val host = _state.value.let { current ->
            current.remembered.firstOrNull { it.authority == current.attempted }
        }
        if (host == null) {
            _state.update { it.copy(signInError = SignInError.NoHost) }
            return
        }
        _state.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch {
            val outcome = harnessSessions.pair(host.id, host.baseUrl, input)
            _state.update { current ->
                current.copy(
                    signingIn = false,
                    signInError = when (outcome) {
                        is SessionExchange.Granted -> null
                        is SessionExchange.Refused -> SignInError.Refused
                        is SessionExchange.Unreachable -> SignInError.Unreachable
                    },
                    signInOpen = outcome !is SessionExchange.Granted,
                )
            }
            if (outcome is SessionExchange.Granted) connectTo(host)
        }
    }

    /** Open or close the launch-token prompt. */
    fun setSignInOpen(open: Boolean) {
        _state.update { it.copy(signInOpen = open, signInError = null) }
    }

    /**
     * Probe every remembered host once, concurrently, so the Recent list can say which ones are up
     * before anything is tapped.
     */
    private suspend fun probeRemembered() {
        val hosts = hostsStore.hosts.first()
        if (hosts.isEmpty()) return
        _state.update { current ->
            current.copy(recentStatus = hosts.associate { it.authority to HostProbe.Probing })
        }
        supervisorScope {
            hosts.map { host ->
                async {
                    val outcome = runCatching {
                        discoveryEngine.probeOutcome(host.host, host.port, ProbeTimeouts.Manual,
                            useTls = host.useTls, config = host, basePath = host.basePath)
                    }.getOrNull()
                    val reachable = outcome as? ProbeOutcome.Reachable
                    _state.update { current ->
                        current.copy(
                            recentStatus = current.recentStatus + (
                                host.authority to (reachable?.let { HostProbe.Reachable(it.description) } ?: HostProbe.Unreachable)
                                ),
                        )
                    }
                    reachable?.description?.let { hostsStore.cacheDescription(host.id, it) }
                }
            }.awaitAll()
        }
    }

    /** Re-run the liveness pass whenever the screen is shown again. */
    fun refreshRecent() {
        if (probeJob?.isActive == true) return
        probeJob = viewModelScope.launch { probeRemembered() }
    }

    /** Connect without being asked: the last used harness, then a harness on this device. */
    private suspend fun autoConnect() {
        val settings = hostsStore.settingsOnce()
        if (settings.autoConnectLast) {
            val last = hostsStore.hosts.first().firstOrNull()
            if (last != null && harnessSessions.has(last.id)) {
                val outcome = discoveryEngine.probeOutcome(last.host, last.port, ProbeTimeouts.Manual,
                    useTls = last.useTls, config = last, basePath = last.basePath)
                if (outcome is ProbeOutcome.Reachable) {
                    connectTo(last)
                    return
                }
            }
        }
        if (settings.autoConnectLoopback) {
            val loopback = hostsStore.hosts.first().firstOrNull { it.isLoopback && harnessSessions.has(it.id) }
                ?: return
            val outcome = discoveryEngine.probeOutcome(loopback.host, loopback.port, ProbeTimeouts.Manual,
                useTls = loopback.useTls, config = loopback, basePath = loopback.basePath)
            if (outcome is ProbeOutcome.Reachable) connectTo(loopback)
        }
    }

    /** Stop a connect attempt that the loop would otherwise keep retrying every few seconds. */
    fun cancelConnect() {
        localStage = ConnectStage.Idle
        connectionManager.disconnect()
        _state.update { it.copy(stage = ConnectStage.Idle, failure = null, retrying = false) }
    }

    /** End the attempt with a reason, and mark the attempted host unreachable if there was one. */
    private fun fail(failure: ConnectFailure, attempted: String?) {
        localStage = ConnectStage.Idle
        _state.update {
            it.copy(
                stage = ConnectStage.Idle,
                failure = failure,
                attempted = attempted ?: it.attempted,
                retrying = false,
                recentStatus = attempted?.let { key -> it.recentStatus + (key to HostProbe.Unreachable) }
                    ?: it.recentStatus,
            )
        }
    }

    /** Connect to a remembered host; progress and failure arrive on the manager's state flow. */
    fun connectTo(host: HostConfig) {
        localStage = null
        _state.update {
            it.copy(
                stage = ConnectStage.OpeningStreams,
                failure = null,
                attempted = host.authority,
                retrying = false,
            )
        }
        viewModelScope.launch { connectionManager.connect(host) }
    }

    fun forget(host: HostConfig) {
        viewModelScope.launch { hostsStore.removeHost(host.id) }
    }

    fun setAuto(key: String, value: Boolean) {
        viewModelScope.launch {
            hostsStore.setSetting { current ->
                when (key) {
                    "last" -> current.copy(autoConnectLast = value)
                    else -> current.copy(autoConnectLoopback = value)
                }
            }
            val settings = hostsStore.settingsOnce()
            _state.update {
                it.copy(
                    autoConnectLast = settings.autoConnectLast,
                    autoConnectLoopback = settings.autoConnectLoopback,
                )
            }
        }
    }

    fun clearError() = _state.update { it.copy(failure = null) }
}
