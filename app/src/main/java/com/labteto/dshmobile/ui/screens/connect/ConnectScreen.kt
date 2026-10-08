package com.labteto.dshmobile.ui.screens.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.parseHostInput
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.WhaleMark
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Reach a harness: paste its address and the auth token it printed, tap Connect.
 *
 * The address is whatever is in front of the harness — a Cloudflare Tunnel hostname, a reverse
 * proxy, or the harness's own `host:port` on the LAN or via `adb reverse`. The token is the one
 * `dsh web` prints at startup; it is exchanged once for a session that survives harness restarts,
 * so a harness in the Recent list can be tapped straight back into.
 */
@Composable
fun ConnectScreen(
    onOpenSettings: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Also fires when the screen comes back from settings or a dropped connection, because each of
    // those removes this screen from composition; without it a Recent row keeps a stale verdict.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshRecent() }
    val colors = DsTheme.colors
    // Saveable: a rotation mid-connect used to wipe a hand-typed address.
    var address by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpacing.xlarge, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsIconButton(
                    icon = FeatherIcons.Tool,
                    contentDescription = stringResource(R.string.settings_title),
                    onClick = onOpenSettings,
                    tint = colors.labelTertiary,
                )
            }

            ConnectHeader()

            // ---- Address + token -----------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SectionHeader(stringResource(R.string.connect_manual_title))
                Text(
                    stringResource(R.string.connect_form_hint),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                )
                TextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.connect_url_hint), style = DsType.std14) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.connect_url_label)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    colors = connectFieldColors(),
                )
                TextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.connect_token_hint), style = DsType.std14) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.connect_sign_in_label)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    colors = connectFieldColors(),
                )
                DsButton(
                    text = stringResource(R.string.connect_button),
                    onClick = { viewModel.connect(address, token) },
                    enabled = !state.connecting && address.isNotBlank(),
                    variant = DsButtonVariant.Info,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Plain HTTP carries the session and every message in the clear; worth one line
                // whenever the address is not loopback and not https.
                val input = remember(address) { parseHostInput(address) }
                if (input != null && input.useTls == false && !isLoopbackHost(input.host)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = colors.warnTertiary,
                    ) {
                        Text(
                            stringResource(R.string.connect_security_banner),
                            style = DsType.small13,
                            color = colors.warnLabel,
                            modifier = Modifier.padding(DsSpacing.medium),
                        )
                    }
                }
            }

            if (state.connecting) ConnectProgressRow(state.stage, state.attempted)
            state.failure?.let { failure ->
                ConnectFailureBlock(
                    failure = failure,
                    attempted = state.attempted,
                    retrying = state.retrying,
                    onCancel = viewModel::cancelConnect,
                    onSignIn = { viewModel.setSignInOpen(true) },
                )
            }

            if (state.signInOpen) {
                LaunchTokenDialog(
                    signingIn = state.signingIn,
                    error = state.signInError,
                    onDismiss = { viewModel.setSignInOpen(false) },
                    onSubmit = viewModel::signIn,
                )
            }

            // ---- Recent ------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SectionHeader(stringResource(R.string.connect_remembered))
                if (state.remembered.isEmpty()) {
                    Text(
                        stringResource(R.string.connect_remembered_empty),
                        style = DsType.std14,
                        color = colors.labelCaption,
                    )
                } else {
                    state.remembered.forEach { saved ->
                        RecentHarnessCard(
                            host = saved,
                            probe = state.recentStatus[saved.authority],
                            onConnect = { viewModel.connectTo(saved) },
                            onForget = { viewModel.forget(saved) },
                        )
                    }
                }
            }

            // ---- Auto-connect ------------------------------------------------
            Column {
                SectionHeader(stringResource(R.string.connect_auto_title))
                ToggleRow(
                    label = stringResource(R.string.connect_auto_last),
                    checked = state.autoConnectLast,
                ) { viewModel.setAuto("last", !state.autoConnectLast) }
                ToggleRow(
                    label = stringResource(R.string.connect_auto_loopback),
                    checked = state.autoConnectLoopback,
                ) { viewModel.setAuto("loopback", !state.autoConnectLoopback) }
            }

            Spacer(Modifier.height(DsSpacing.large))
        }
    }
}

private fun isLoopbackHost(host: String): Boolean =
    host == "127.0.0.1" || host == "localhost" || host == "::1"

@Composable
private fun ConnectHeader() {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        WhaleMark(Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_long_name),
                style = DsType.large20,
                color = colors.labelPrimary,
            )
            Text(
                stringResource(R.string.connect_subtitle),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
    }
}

@Composable
private fun connectFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = DsTheme.colors.bgLayer1,
    unfocusedContainerColor = DsTheme.colors.bgLayer1,
    focusedIndicatorColor = DsTheme.colors.accent,
    unfocusedIndicatorColor = DsTheme.colors.borderL2,
    cursorColor = DsTheme.colors.accent,
)

@Composable
private fun RecentHarnessCard(
    host: HostConfig,
    probe: HostProbe?,
    onConnect: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = DsTheme.colors
    val reachable = probe as? HostProbe.Reachable
    val title = if (host.isLoopback) stringResource(R.string.connect_same_device) else host.name
    val home = reachable?.description?.home ?: host.lastHome

    DsCard(onClick = onConnect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(
                when (probe) {
                    is HostProbe.Reachable -> StateDotState.Done
                    HostProbe.Probing -> StateDotState.Running
                    HostProbe.Unreachable -> StateDotState.Idle
                    null -> StateDotState.Idle
                },
                size = 8.dp,
            )
            Spacer(Modifier.width(DsSpacing.compact))
            Text(
                title,
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (host.lastConnectedAt > 0L) {
                    relativeTime(host.lastConnectedAt)
                } else {
                    stringResource(R.string.connect_never)
                },
                style = DsType.caption11,
                color = colors.labelCaption,
                maxLines = 1,
            )
        }
        Text(
            listOfNotNull(host.displayAddress, home?.let { basename(it) }).joinToString(" · "),
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusLine(probe, home),
                style = DsType.caption11,
                color = if (probe is HostProbe.Unreachable) colors.labelCaption else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.common_delete),
                onClick = onForget,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
}

@Composable
private fun statusLine(probe: HostProbe?, home: String?): String = when {
    probe is HostProbe.Probing -> stringResource(R.string.connect_checking)
    probe is HostProbe.Unreachable -> stringResource(R.string.connect_unreachable)
    home != null -> stringResource(R.string.connect_harness_home, home)
    probe is HostProbe.Reachable -> stringResource(R.string.connect_reachable)
    else -> stringResource(R.string.common_loading)
}

/** What the connect attempt is doing, named, so a wait reads as a progress report. */
@Composable
private fun ConnectProgressRow(stage: ConnectStage, attempted: String?) {
    val colors = DsTheme.colors
    val label = when (stage) {
        ConnectStage.Validating -> stringResource(R.string.connect_stage_validating)
        ConnectStage.Reaching -> stringResource(R.string.connect_stage_reaching, attempted.orEmpty())
        ConnectStage.OpeningStreams -> stringResource(R.string.connect_stage_streams)
        ConnectStage.Verifying -> stringResource(R.string.connect_stage_verifying)
        ConnectStage.Connected -> stringResource(R.string.connect_stage_connected)
        ConnectStage.Idle -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Running, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.xsmall))
            Text(label, style = DsType.std14, color = colors.labelTertiary)
        }
        LinearProgressIndicator(
            progress = { stage.ordinal / (ConnectStage.entries.size - 1).toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.hoverSolid,
        )
    }
}

/** Why it did not connect, and what to do about it: one sentence of cause, one of action. */
@Composable
private fun ConnectFailureBlock(
    failure: ConnectFailure,
    attempted: String?,
    retrying: Boolean,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = DsTheme.colors
    val authority = attempted.orEmpty()
    val address = parseHostInput(authority)
    val port = address?.port ?: if (address?.useTls == true) 443 else 80
    val title = when {
        failure is ConnectFailure.TrustFence -> stringResource(R.string.connect_fail_fence_title)
        authority.isBlank() -> null
        else -> stringResource(R.string.connect_failed, authority)
    }
    val body = when (failure) {
        ConnectFailure.InvalidInput -> stringResource(R.string.connect_fail_invalid)
        ConnectFailure.Timeout -> stringResource(R.string.connect_fail_timeout, authority, port)
        ConnectFailure.Refused -> stringResource(R.string.connect_fail_refused, authority)
        ConnectFailure.TrustFence -> stringResource(R.string.connect_failed_fence)
        ConnectFailure.Unauthenticated -> stringResource(R.string.connect_fail_unauthenticated, authority)
        ConnectFailure.TokenRefused -> stringResource(R.string.connect_sign_in_refused)
        ConnectFailure.DnsFailure -> stringResource(R.string.connect_fail_dns, authority)
        ConnectFailure.NotAHarness -> stringResource(R.string.connect_fail_not_harness, authority)
        ConnectFailure.TlsFailure -> stringResource(R.string.connect_fail_tls, authority)
        ConnectFailure.StreamsBlocked -> stringResource(R.string.connect_fail_streams, authority)
        is ConnectFailure.Other -> stringResource(R.string.connect_fail_other, authority, failure.detail)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.warnTertiary,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Error, size = 8.dp)
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(title ?: body, style = DsType.std14, color = colors.warnLabel)
            }
            if (title != null) Text(body, style = DsType.small13, color = colors.warnLabel)
            // A remembered harness whose session lapsed is fixed here: paste the token it printed.
            if (failure is ConnectFailure.Unauthenticated) {
                DsButton(
                    text = stringResource(R.string.connect_sign_in),
                    onClick = onSignIn,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            if (retrying) {
                DsButton(
                    text = stringResource(R.string.connect_cancel),
                    onClick = onCancel,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

/** The launch-token prompt for a remembered harness whose session lapsed. */
@Composable
private fun LaunchTokenDialog(
    signingIn: Boolean,
    error: SignInError?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = DsTheme.colors
    var input by remember { mutableStateOf("") }
    DsDialog(title = stringResource(R.string.connect_sign_in_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.connect_sign_in_body),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        TextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            enabled = !signingIn,
            label = { Text(stringResource(R.string.connect_sign_in_label)) },
            colors = connectFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        val message = when (error) {
            SignInError.Refused -> stringResource(R.string.connect_sign_in_refused)
            SignInError.Unreachable -> stringResource(R.string.connect_sign_in_unreachable)
            SignInError.NoHost -> stringResource(R.string.connect_sign_in_no_host)
            null -> null
        }
        if (message != null) {
            Text(message, style = DsType.caption11, color = colors.warnLabel)
        }
        DsButton(
            text = stringResource(R.string.connect_sign_in),
            onClick = { onSubmit(input) },
            enabled = !signingIn && input.isNotBlank(),
            variant = DsButtonVariant.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Last path segment of a host cwd, so a card can name the project rather than print a full path. */
private fun basename(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
