package com.labteto.dshmobile.ui.screens.main.commands

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.screens.main.ChatSearchField
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Resolve [UiText] against resources. */
@Composable
internal fun UiText.resolve(): String = when (this) {
    is UiText.Literal -> text
    is UiText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
}

/**
 * The shared popup-select shell: the phone's version of ui-commands' `PopupSelectController`
 * plus its overlay.
 *
 * One sheet serves every decoration. It loads the first step's rows through the spec, filters
 * them against a local search, and on a pick either closes (the step returned nothing) or shows
 * the next step. A step may carry its own loader, which replaces it when the data lands — that is
 * how rewind's mode step first shows "checking…" and then the impact list. Failures are shown in
 * place with a retry, never as a toast the user has to chase.
 */
@Composable
internal fun PopupSelectSheet(
    command: String,
    spec: CommandUiSpec.PopupSelect,
    context: CommandContext,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var step by remember { mutableStateOf<SelectStep?>(null) }
    var options by remember { mutableStateOf<List<SelectOption>>(emptyList()) }
    var note by remember { mutableStateOf<UiText?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }

    // First step: the spec's own options.
    LaunchedEffect(revision) {
        if (step != null) return@LaunchedEffect
        loading = true; error = null
        try {
            options = spec.options(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }
    // Later steps: rows are immediate; a loader, when present, swaps the step once it settles.
    LaunchedEffect(step) {
        val current = step ?: return@LaunchedEffect
        options = current.options; note = current.note; search = ""
        val load = current.load ?: return@LaunchedEffect
        loading = true
        try {
            val next = load()
            if (step === current) { step = next.copy(load = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    suspend fun pick(option: SelectOption) {
        submitting = true; error = null
        try {
            val next = step?.onSelect?.invoke(option) ?: spec.onSelect(option, context)
            if (next == null) onDismiss() else step = next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            submitting = false
        }
    }

    val title = step?.title?.resolve() ?: "/$command"
    val visible = remember(options, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) options else options.filter { o -> o.id.contains(q) || (o.label as? UiText.Literal)?.text?.lowercase()?.contains(q) == true }
    }
    val scope = rememberCoroutineScope()

    DsBottomSheet(
        title = title,
        onDismiss = onDismiss,
        trailing = { DsIconButton(Icons.Filled.Close, stringResource(R.string.common_close), onDismiss) },
    ) {
        if (step == null && options.size > 8) ChatSearchField(search) { search = it }
        if (loading || submitting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.borderL1)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            note?.let { Text(it.resolve(), style = DsType.small13, color = colors.labelSecondary) }
            error?.let { message ->
                DsCard {
                    Text(message, style = DsType.small13, color = colors.error)
                    DsButton(
                        stringResource(R.string.common_retry),
                        { if (step == null) revision++ else step = step?.let { it.copy() } },
                        variant = DsButtonVariant.Outline,
                    )
                }
            }
            if (!loading && error == null && visible.isEmpty()) {
                Text(stringResource(R.string.ux_b_no_matches), style = DsType.std14, color = colors.labelTertiary)
            }
            visible.forEach { option ->
                val clickable = option.enabled && !submitting
                Row(
                    Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget)
                        .then(if (clickable) Modifier.clickable { scope.launch { pick(option) } } else Modifier)
                        .padding(DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label.resolve(),
                            style = DsType.std14,
                            color = if (clickable) colors.labelPrimary else colors.labelTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        option.detail?.let {
                            Text(it.resolve(), style = DsType.caption11, color = colors.labelTertiary)
                        }
                    }
                    option.badge?.let { Text(it, style = DsType.caption11, color = colors.labelCaption) }
                }
            }
        }
    }
}
