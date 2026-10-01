package com.labteto.dshmobile.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** One session's agent preset as of a position in its log. */
internal data class AgentPresetState(val asOfSeq: Int, val preset: String?)

/**
 * Decode one `agentPreset` projection value seen at [asOfSeq]: a JSON string is a preset and JSON
 * null clears it. Anything else, an absent key included, is no news and answers null, so a
 * baseline that omits the key or a malformed payload cannot erase the last known preset.
 */
internal fun agentPresetUpdate(asOfSeq: Int, value: JsonElement?): AgentPresetState? = when {
    value is JsonNull -> AgentPresetState(asOfSeq, null)
    value is JsonPrimitive && value.isString -> AgentPresetState(asOfSeq, value.content)
    else -> null
}

/**
 * Keep whichever state reflects the later log position.
 *
 * The list read and the control stream race: a `session/list` answer built before another
 * client's selection can arrive after that selection's control frame. Comparing positions keeps
 * the old preset from coming back. A tie goes to [incoming], the rule the open session's own
 * projections follow, so a re-read of the same position still lands.
 */
internal fun newerAgentPreset(held: AgentPresetState?, incoming: AgentPresetState): AgentPresetState =
    if (held == null || incoming.asOfSeq >= held.asOfSeq) incoming else held
