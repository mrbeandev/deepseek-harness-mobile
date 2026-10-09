package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import org.junit.Assert.*
import org.junit.Test

class ToolGroupsTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long) = UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "hello")), "user")
    private fun assistant(seq: Long) = AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", "done")))
    @Test fun `adjacent calls group without crossing visible message boundaries`() {
        val rows = groupToolRows(listOf(user(1), call(2), call(3), assistant(4), call(5), user(6)))
        assertEquals(5, rows.size)
        assertEquals(listOf(2L, 3L), (rows[1] as TranscriptRow.Tools).calls.map { it.seq })
        assertEquals(listOf(5L), (rows[3] as TranscriptRow.Tools).calls.map { it.seq })
    }
    @Test fun `group key remains first call as stream grows`() {
        assertEquals(groupToolRows(listOf(call(1))).first().seq, groupToolRows(listOf(call(1), call(2))).first().seq)
        assertTrue(groupToolRows(emptyList()).isEmpty())
    }
}
