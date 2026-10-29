// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class VlessProbeRulesTest {

    private val sni = "www.example.com"
    private val root = "https://www.example.com/"

    private fun redirect(location: String, current: String = root, port: Int = 443) =
        VlessProbeRules.redirectTarget(current, location, sni, port)

    @Test
    fun `same-host redirects are followed`() {
        assertEquals("https://www.example.com/en/", redirect("/en/"))
        assertEquals("https://www.example.com/en/?a=1", redirect("https://www.example.com/en/?a=1"))
        assertEquals("https://www.example.com/a/c", redirect("c", current = "https://www.example.com/a/b"))
    }

    @Test
    fun `host comparison is canonical`() {
        assertEquals("https://WWW.Example.com./x", redirect("https://WWW.Example.com./x"))
        assertEquals("https://www.example.com:443/x", redirect("https://www.example.com:443/x"))
    }

    @Test
    fun `fragment is dropped and an empty path becomes the root`() {
        assertEquals("https://www.example.com/x", redirect("/x#top"))
        assertEquals("https://www.example.com/", redirect("https://www.example.com"))
    }

    @Test
    fun `redirect to another host is not followed`() {
        assertNull(redirect("https://example.com/"))
        assertNull(redirect("https://cdn.example.com/"))
        assertNull(redirect("//elsewhere.example/"))
    }

    @Test
    fun `redirect to another port or scheme is not followed`() {
        assertNull(redirect("https://www.example.com:8443/"))
        assertNull(redirect("http://www.example.com/"))
        assertNull(redirect("/x", current = "https://www.example.com:8443/", port = 443))
        assertEquals("https://www.example.com:8443/x", redirect("/x", current = "https://www.example.com:8443/", port = 8443))
    }

    @Test
    fun `broken or suspicious locations are not followed`() {
        assertNull(redirect("https://user@www.example.com/"))
        assertNull(redirect("https://www.example.com/a b"))
        assertNull(redirect("javascript:alert(1)"))
    }

    @Test
    fun `completed transfer is verified only with enough bytes`() {
        assertEquals(ProbeCode.OK, VlessProbeRules.completedCode(VlessProbeRules.TARGET_BYTES))
        assertEquals(ProbeCode.OK_UNVERIFIED, VlessProbeRules.completedCode(VlessProbeRules.TARGET_BYTES - 1))
        assertEquals(ProbeCode.OK_UNVERIFIED, VlessProbeRules.completedCode(0))
    }

    @Test
    fun `failures before the handshake completes follow the phase rules`() {
        assertEquals(ProbeCode.DNS_FAIL, failure(ProbePhase.DNS, UnknownHostException("proxy")))
        assertEquals(ProbeCode.TCP_TIMEOUT, failure(ProbePhase.CONNECT, SocketTimeoutException()))
        assertEquals(ProbeCode.TCP_RESET, failure(ProbePhase.CONNECT, SocketException("refused")))
        assertEquals(ProbeCode.TLS_FAIL, failure(ProbePhase.TLS, SSLHandshakeException("reset")))
        assertEquals(ProbeCode.CERT_INVALID, failure(ProbePhase.TLS, SSLPeerUnverifiedException("self-signed")))
    }

    @Test
    fun `transfer that stops after the handshake is stalled`() {
        assertEquals(ProbeCode.STALLED, failure(ProbePhase.REQUEST, SocketTimeoutException("timeout")))
        assertEquals(ProbeCode.STALLED, failure(ProbePhase.BODY, IOException(SocketTimeoutException("timeout")), 18_000))
    }

    @Test
    fun `closed connection after the handshake is unverified`() {
        assertEquals(ProbeCode.OK_UNVERIFIED, failure(ProbePhase.REQUEST, IOException("unexpected end of stream")))
        assertEquals(ProbeCode.OK_UNVERIFIED, failure(ProbePhase.REQUEST, EOFException()))
        assertEquals(ProbeCode.OK_UNVERIFIED, failure(ProbePhase.BODY, SocketException("Connection reset")))
    }

    @Test
    fun `time budget running out is not a stall`() {
        assertEquals(ProbeCode.OK_UNVERIFIED, failure(ProbePhase.BODY, InterruptedIOException("timeout"), 20_000))
    }

    @Test
    fun `enough bytes win over a late failure`() {
        assertEquals(ProbeCode.OK, failure(ProbePhase.BODY, SocketTimeoutException(), VlessProbeRules.TARGET_BYTES))
    }

    private fun failure(phase: ProbePhase, error: Throwable, receivedBytes: Long = 5_000) =
        VlessProbeRules.failureCode(phase, error, receivedBytes)
}
