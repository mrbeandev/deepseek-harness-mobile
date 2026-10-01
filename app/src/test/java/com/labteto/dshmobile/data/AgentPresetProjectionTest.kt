package com.labteto.dshmobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rows retain their last known preset until the control stream supplies a valid replacement.
 * Missing baseline keys must not behave like JSON null, which intentionally clears the chip.
 */
class AgentPresetProjectionTest {

    @Test
    fun `a string sets or replaces the preset`() {
        assertEquals("reviewer", agentPresetFromProjection(null, JsonPrimitive("reviewer")))
        assertEquals("reviewer", agentPresetFromProjection("coder", JsonPrimitive("reviewer")))
    }

    @Test
    fun `JSON null clears the preset`() {
        assertNull(agentPresetFromProjection("coder", JsonNull))
        assertNull(agentPresetFromProjection(null, JsonNull))
    }

    @Test
    fun `an absent key leaves the preset unchanged`() {
        assertEquals("coder", agentPresetFromProjection("coder", null))
        assertNull(agentPresetFromProjection(null, null))
    }

    @Test
    fun `non-string payloads read as absent`() {
        val payloads = listOf(
            JsonObject(mapOf("name" to JsonPrimitive("reviewer"))),
            JsonArray(listOf(JsonPrimitive("reviewer"))),
            JsonPrimitive(42),
            JsonPrimitive(true),
        )
        payloads.forEach { value ->
            assertEquals("coder", agentPresetFromProjection("coder", value))
            assertNull(agentPresetFromProjection(null, value))
        }
    }
}
