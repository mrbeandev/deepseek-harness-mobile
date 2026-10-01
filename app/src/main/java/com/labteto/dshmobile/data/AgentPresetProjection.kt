package com.labteto.dshmobile.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fold the preset's projection into a row without confusing no news with an explicit clear.
 *
 * Baselines can omit the key; only a JSON string or JSON null is a preset update. Unexpected
 * payloads are ignored so a malformed projection cannot erase the last known preset.
 */
internal fun agentPresetFromProjection(current: String?, value: JsonElement?): String? = when {
    value is JsonNull -> null
    value is JsonPrimitive && value.isString -> value.content
    else -> current
}
