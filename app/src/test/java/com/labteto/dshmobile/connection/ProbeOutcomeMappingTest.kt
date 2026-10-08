package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** 401 and 403 send the person to different places to fix it, so they must stay distinct. */
class ProbeOutcomeMappingTest {

    @Test
    fun `a harness keeps its own 401 and 403`() {
        assertEquals(ProbeOutcome.Unauthenticated, probeOutcomeOf(TransportFailure.UNAUTHENTICATED, detail = "401"))
        assertEquals(ProbeOutcome.TrustFence, probeOutcomeOf(TransportFailure.TRUST_FENCE, detail = "403"))
    }

    /** A throttle or a dead tunnel origin is a real harness address; its own wording is what to read. */
    @Test
    fun `an outcome with nothing more specific keeps the carrier's words`() {
        assertEquals(ProbeOutcome.Other("rate limited"), probeOutcomeOf(TransportFailure.RATE_LIMITED, detail = "rate limited"))
        assertEquals(ProbeOutcome.Other("502"), probeOutcomeOf(TransportFailure.UPSTREAM_DOWN, detail = "502"))
    }
}
