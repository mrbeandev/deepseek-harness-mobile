package com.labteto.dshmobile.ui.screens.main.commands

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.RewindCuts
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.data.CommandOutcome
import java.text.DateFormat
import java.util.Date

/**
 * The phone's port of `dsh-rewind-plugin/lib/client.js`'s picker.
 *
 * The web flow is: run the hidden `/rewind __candidates` subcommand, parse its text, list the
 * messages, and on pick offer *conversation only* / *conversation and code* — the second one
 * previewed through `/rewind preview @seq both`. The actual rewind is always sent as
 * `/rewind @<seq> <mode>`; a bare `/rewind` is never submitted from the UI.
 *
 * Text formats are the plugin's own and private to it; this mirrors its parsers byte for byte:
 * candidates are `candidates=N` then `seq\ttime\tpreview` lines; the preview impact list is
 * `restore:<path>` / `delete:<path>` lines.
 */
object RewindDecoration : CommandDecoration {
    override val names = setOf("rewind", "undo")

    /**
     * A picker only when the surface has a reachable user message. A fresh session has none, and
     * then — as upstream — the bare command goes to the host and fails with its own
     * "no user messages" message rather than an empty sheet.
     */
    override fun available(conversation: ConversationSnapshot?): Boolean {
        val nodes = conversation?.nodes ?: return false
        val hidden = RewindCuts.hiddenSeqs(nodes)
        return nodes.any { it is UserMessageNode && !it.isInjectedContext && it.sourceKind != "dsh-rewind" && it.seq !in hidden }
    }

    override val ui: CommandUiSpec = CommandUiSpec.PopupSelect(
        options = { ctx -> candidates(ctx).map { it.option() } },
        onSelect = { option, ctx ->
            val seq = option.id.toLongOrNull() ?: return@PopupSelect null
            modeStep(ctx, seq, (option.label as? UiText.Literal)?.text.orEmpty())
        },
    )

    data class Candidate(val seq: Long, val time: Long, val preview: String) {
        fun option() = SelectOption(
            id = seq.toString(),
            label = if (preview.isBlank()) res(R.string.rewind_no_text) else preview.ui(),
            badge = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time)),
        )
    }

    private suspend fun candidates(ctx: CommandContext): List<Candidate> {
        val text = when (val outcome = ctx.run("/rewind __candidates")) {
            is CommandOutcome.Ok -> outcome.text.orEmpty()
            is CommandOutcome.Failed -> throw IllegalStateException(outcome.message)
            is CommandOutcome.Unknown -> throw IllegalStateException("the rewind command is not registered on this host")
        }
        return parseCandidates(text)
    }

    /** Port of `rewindCandidatesFromHostText`. */
    fun parseCandidates(text: String): List<Candidate> {
        if (!text.startsWith(CANDIDATE_LIST_HEADER)) return emptyList()
        return text.lineSequence().drop(1).mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val seq = parts[0].toLongOrNull() ?: return@mapNotNull null
            val time = parts[1].toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            Candidate(seq, time, parts[2])
        }.toList()
    }

    /** Port of `parseImpactList`. */
    fun parseImpact(text: String): Pair<List<String>, List<String>> {
        val restores = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        for (line in text.lines()) {
            when {
                line.startsWith("restore:") -> restores.add(line.removePrefix("restore:"))
                line.startsWith("delete:") -> deletes.add(line.removePrefix("delete:"))
            }
        }
        return restores to deletes
    }

    /**
     * Step two: chat-only, or chat and files. The impact of the file restore is fetched while the
     * step is open, exactly as the web popover does, so the "both" row can say what it would touch
     * — or be withheld when nothing after the target is tracked.
     */
    private fun modeStep(ctx: CommandContext, seq: Long, preview: String): SelectStep {
        val chat = SelectOption(MODE_CHAT, res(R.string.rewind_mode_chat), res(R.string.rewind_mode_chat_hint))
        val checking = SelectOption(MODE_BOTH, res(R.string.rewind_mode_both), res(R.string.rewind_checking), enabled = false)
        val onSelect: suspend (SelectOption) -> SelectStep? = { option ->
            val mode = when (option.id) {
                MODE_CHAT -> "chat"
                MODE_BOTH -> "both"
                else -> null
            }
            if (mode != null) {
                val outcome = ctx.run("/rewind @$seq $mode")
                if (outcome is CommandOutcome.Failed) throw IllegalStateException(outcome.message)
                // Upstream refills the composer with the withdrawn message so it can be edited
                // and resent, which is the usual reason for a rewind.
                ctx.setDraft(preview)
            }
            null
        }
        val title = res(R.string.rewind_to, preview)
        return SelectStep(
            title = title,
            options = listOf(chat, checking),
            onSelect = onSelect,
            load = {
                val impact = runCatching { ctx.run("/rewind preview @$seq both") }.getOrNull()
                val (note, both) = when (impact) {
                    is CommandOutcome.Ok -> {
                        val (restores, deletes) = parseImpact(impact.text.orEmpty())
                        if (restores.isEmpty() && deletes.isEmpty()) {
                            res(R.string.rewind_no_changes) to null
                        } else {
                            val lines = restores.map { "+ $it" } + deletes.map { "− $it" }
                            lines.joinToString("\n").ui() to
                                SelectOption(MODE_BOTH, res(R.string.rewind_mode_both), res(R.string.rewind_mode_both_hint))
                        }
                    }
                    is CommandOutcome.Failed -> res(R.string.rewind_impact_failed, impact.message) to null
                    else -> res(R.string.rewind_impact_failed, "") to null
                }
                SelectStep(title = title, options = listOfNotNull(chat, both), note = note, onSelect = onSelect)
            },
        )
    }

    private const val CANDIDATE_LIST_HEADER = "candidates="
    private const val MODE_CHAT = "chat"
    private const val MODE_BOTH = "both"
}
