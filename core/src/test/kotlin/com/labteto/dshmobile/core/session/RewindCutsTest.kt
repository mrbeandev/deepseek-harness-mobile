package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rewind cut rule, against the event shapes `dsh-rewind-plugin` writes.
 *
 * The harness serves the withdrawn messages to clients unchanged; the plugin's browser half hides
 * them from the command ledger. The phone has to make the same decision from the same rows.
 */
class RewindCutsTest {
    private fun user(seq: Long) = UserMessageNode(seq, "m$seq", listOf(ChatBlock("text", "hello $seq")), "user")
    private fun assistant(seq: Long) = AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", "reply $seq")))
    private fun run(seq: Long, id: String, args: String, name: String = "rewind") = CommandNode(
        seq, "command/run",
        Json.parseToJsonElement("""{"commandId":"$id","name":"$name","args":"$args","source":{"kind":"user"}}"""),
    )
    private fun done(seq: Long, id: String, kind: String = "success", marker: Long? = null) = CommandNode(
        seq, "command/done",
        Json.parseToJsonElement(
            """{"commandId":"$id","kind":"$kind","text":"x"${marker?.let { ",\"sourceEventSeq\":$it" } ?: ""}}""",
        ),
    )
    private fun marker(seq: Long) = UserMessageNode(seq, "k$seq", listOf(ChatBlock("text", "(empty message)")), "dsh-rewind")

    @Test
    fun `a targeted rewind hides the target through its marker and its own rows`() {
        val nodes = listOf(
            user(10), assistant(11), user(20), assistant(21), user(30), assistant(31),
            run(40, "c1", "@20 chat"), marker(41), done(42, "c1", marker = 41),
        )
        assertEquals(setOf(20L, 21L, 30L, 31L, 40L, 42L), RewindCuts.hiddenSeqs(nodes))
    }

    /** What the phone used to send: no target, so nothing is cut even though the host says "withdrawn". */
    @Test
    fun `a bare rewind cuts nothing and its marker is not a divider`() {
        val nodes = listOf(user(10), assistant(11), run(40, "c1", ""), marker(41), done(42, "c1", marker = 41))
        assertEquals(setOf(40L, 41L, 42L), RewindCuts.hiddenSeqs(nodes))
    }

    @Test
    fun `candidate and preview invocations are housekeeping`() {
        val nodes = listOf(
            user(10), run(20, "c1", "__candidates"), done(21, "c1"),
            run(22, "c2", "preview @10 both"), done(23, "c2"),
        )
        assertEquals(setOf(20L, 21L, 22L, 23L), RewindCuts.hiddenSeqs(nodes))
    }

    @Test
    fun `a failed rewind hides nothing beyond its own done row`() {
        val nodes = listOf(user(10), run(20, "c1", "@10 chat"), done(21, "c1", kind = "error"))
        assertEquals(setOf(21L), RewindCuts.hiddenSeqs(nodes))
    }

    @Test
    fun `undo is the same command and overlapping cuts coalesce`() {
        val nodes = listOf(
            user(10), user(20), user(30),
            run(40, "c1", "@20 chat", name = "undo"), marker(41), done(42, "c1", marker = 41),
            run(50, "c2", "@10 both"), marker(51), done(52, "c2", marker = 51),
        )
        assertEquals(listOf(RewindCuts.Cut(10, 52)), RewindCuts.coalesce(listOf(RewindCuts.Cut(20, 41), RewindCuts.Cut(10, 51), RewindCuts.Cut(42, 52))))
        // Only the newest cut's marker (51) survives as the divider; 41 sits inside the newer cut.
        assertEquals(setOf(10L, 20L, 30L, 40L, 41L, 42L, 50L, 52L), RewindCuts.hiddenSeqs(nodes))
    }

    /** The marker is a surface replacement, which the fold normally drops; this one must become a row. */
    @Test
    fun `the rewind marker folds to a user node while an ordinary replacement does not`() {
        val marker = SessionEventEnvelope(
            "user/message", 41, 41,
            Json.parseToJsonElement("""{"content":[{"type":"text","text":"(empty message)"}],"source":{"kind":"dsh-rewind"},"role":"user","id":"k"}"""),
            surfaceIntent = Json.parseToJsonElement("""{"op":"replace","startSeq":20,"endSeq":30}"""), sourceEventSeqs = listOf(20),
        )
        val compaction = marker.copy(seq = 42, data = Json.parseToJsonElement("""{"content":[{"type":"text","text":"summary"}],"source":{"kind":"compaction"},"role":"user","id":"c"}"""))
        val snapshot = EventFold("s").fold(listOf(marker, compaction))
        assertEquals(listOf(41L), snapshot.nodes.map { it.seq })
        assertEquals("dsh-rewind", (snapshot.nodes.single() as UserMessageNode).sourceKind)
    }

    @Test
    fun `target parsing accepts only the seq form`() {
        assertEquals(754L, RewindCuts.targetSeqOf("@754 chat"))
        assertEquals(null, RewindCuts.targetSeqOf("2 chat"))
        assertEquals(null, RewindCuts.targetSeqOf(""))
    }
}
