package com.labteto.dshmobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The host field takes what people actually have, which is usually a pasted URL: the line the
 * harness printed, or a reverse proxy's address bar. Before this parser existed, the field's text
 * went to DNS verbatim — so "https://agent.home" was reported as a computer that does not exist,
 * with advice about ipconfig (#6).
 */
class HostInputTest {

    @Test
    fun `a bare host says nothing about port or tls`() {
        assertEquals(HostInput("192.168.1.20", null, null), parseHostInput("192.168.1.20"))
        assertEquals(HostInput("agent.home", null, null), parseHostInput("agent.home"))
        assertEquals(HostInput("localhost", null, null), parseHostInput(" localhost "))
    }

    @Test
    fun `a scheme decides tls in either direction`() {
        assertEquals(HostInput("agent.home", null, true), parseHostInput("https://agent.home"))
        assertEquals(HostInput("agent.home", null, false), parseHostInput("http://agent.home"))
        assertEquals(HostInput("agent.home", null, true), parseHostInput("HTTPS://agent.home"))
    }

    @Test
    fun `a port typed into the host field is kept`() {
        assertEquals(HostInput("192.168.1.20", 3080, null), parseHostInput("192.168.1.20:3080"))
        assertEquals(HostInput("agent.home", 8443, true), parseHostInput("https://agent.home:8443"))
        assertEquals(HostInput("127.0.0.1", 3080, false), parseHostInput("http://127.0.0.1:3080"))
    }

    @Test
    fun `connection failure addresses keep the proxy path out of the port`() {
        assertEquals(HostInput("10.0.2.2", 3192, null, "/dsh"), parseHostInput("10.0.2.2:3192/dsh"))
        assertEquals(HostInput("10.0.2.2", 3192, false, "/dsh"), parseHostInput("http://10.0.2.2:3192/dsh/"))
        assertEquals(HostInput("::1", 3192, true, "/dsh"), parseHostInput("https://[::1]:3192/dsh"))
    }

    @Test
    fun `prefixed endpoint identity is distinct from its root and other prefixes`() {
        val root = HostConfig("root-id", "proxy", "relay.test", 443, useTls = true)
        val prefixed = root.copy(id = "prefix-id", basePath = "/team")
        org.junit.Assert.assertNotEquals(root.baseUrl, prefixed.baseUrl)
        org.junit.Assert.assertNotEquals(root.authority, prefixed.authority)
        org.junit.Assert.assertNotEquals(prefixed.baseUrl, prefixed.copy(basePath = "/other").baseUrl)
        assertEquals("https://relay.test:443/team", prefixed.baseUrl)
    }

    /** The harness prints its URL with a trailing slash, and the GUI's address bar carries paths. */
    @Test
    fun `paths queries and fragments are not part of the host`() {
        assertEquals(HostInput("192.168.1.20", 3080, false), parseHostInput("http://192.168.1.20:3080/"))
        assertEquals(HostInput("agent.home", null, true, "/chat"), parseHostInput("https://agent.home/chat?x=1#top"))
        assertEquals(HostInput("agent.home", null, null, "/chat"), parseHostInput("agent.home/chat"))
    }

    @Test
    fun `ipv6 literals survive with and without brackets`() {
        assertEquals(HostInput("::1", null, null), parseHostInput("::1"))
        assertEquals(HostInput("::1", null, null), parseHostInput("[::1]"))
        assertEquals(HostInput("::1", 3080, null), parseHostInput("[::1]:3080"))
        assertEquals(HostInput("fe80::1", 3080, false), parseHostInput("http://[fe80::1]:3080"))
    }

    @Test
    fun `unusable input is refused rather than guessed at`() {
        assertNull(parseHostInput(""))
        assertNull(parseHostInput("   "))
        assertNull(parseHostInput("ftp://agent.home"))
        assertNull(parseHostInput("https://"))
        assertNull(parseHostInput("agent.home:notaport"))
        assertNull(parseHostInput("agent.home:70000"))
        assertNull(parseHostInput("agent.home:0"))
        assertNull(parseHostInput("[::1"))
        assertNull(parseHostInput("[]:3080"))
        assertNull(parseHostInput("[::1]3080"))
        assertNull(parseHostInput("http:///chat"))
        assertNull(parseHostInput("a b"))
    }

    @Test
    fun `url authorities bracket ipv6 literals and leave everything else alone`() {
        assertEquals("192.168.1.20:3080", urlAuthority("192.168.1.20", 3080))
        assertEquals("agent.home:443", urlAuthority("agent.home", 443))
        assertEquals("[::1]:3080", urlAuthority("::1", 3080))
    }

    @Test
    fun `base urls carry the scheme the endpoint needs`() {
        assertEquals("http://192.168.1.20:3080", harnessBaseUrl("192.168.1.20", 3080, useTls = false))
        assertEquals("https://agent.home:443", harnessBaseUrl("agent.home", 443, useTls = true))
        assertEquals("http://[::1]:3080", harnessBaseUrl("::1", 3080, useTls = false))
    }

    // ---- resolveEndpoint: what the connect form dials for what was pasted ----------------------

    @Test
    fun `a bare public hostname is a tunnel so it gets https on 443`() {
        assertEquals(ResolvedEndpoint("dsh.example.com", 443, true, ""), resolveEndpoint("dsh.example.com"))
        assertEquals(ResolvedEndpoint("dsh.example.com", 443, true, ""), resolveEndpoint("https://dsh.example.com/"))
    }

    @Test
    fun `a bare local address is a harness so it gets http on 3080`() {
        assertEquals(ResolvedEndpoint("192.168.1.20", 3080, false, ""), resolveEndpoint("192.168.1.20"))
        assertEquals(ResolvedEndpoint("localhost", 3080, false, ""), resolveEndpoint("localhost"))
        assertEquals(ResolvedEndpoint("desktop.local", 3080, false, ""), resolveEndpoint("desktop.local"))
        assertEquals(ResolvedEndpoint("desktop", 3080, false, ""), resolveEndpoint("desktop"))
    }

    @Test
    fun `an explicit port or scheme always wins`() {
        assertEquals(ResolvedEndpoint("192.168.1.20", 8080, false, ""), resolveEndpoint("192.168.1.20:8080"))
        assertEquals(ResolvedEndpoint("agent.home", 443, true, ""), resolveEndpoint("agent.home:443"))
        assertEquals(ResolvedEndpoint("agent.example.com", 80, false, ""), resolveEndpoint("http://agent.example.com"))
        assertEquals(ResolvedEndpoint("agent.home", 3080, false, ""), resolveEndpoint("http://agent.home"))
        assertEquals(ResolvedEndpoint("dsh.example.com", 8443, true, "/dsh"), resolveEndpoint("https://dsh.example.com:8443/dsh/"))
    }

    /** The whole startup line can be pasted; the token rides along and the query is dropped. */
    @Test
    fun `the printed startup url resolves and its token is readable`() {
        val line = "http://127.0.0.1:3080/?token=abc123"
        assertEquals(ResolvedEndpoint("127.0.0.1", 3080, false, ""), resolveEndpoint(line))
        assertEquals("abc123", tokenInAddress(line))
        assertEquals("abc123", tokenInAddress("dsh web: http://192.168.1.20:3080/?token=abc123&x=1"))
        assertNull(tokenInAddress("https://dsh.example.com"))
    }

    @Test
    fun `nothing usable yields null`() {
        assertNull(resolveEndpoint(""))
        assertNull(resolveEndpoint("ftp://x"))
    }
}
