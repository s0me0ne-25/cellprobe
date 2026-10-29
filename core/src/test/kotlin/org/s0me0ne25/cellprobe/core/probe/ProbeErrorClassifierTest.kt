// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.zip.ZipException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class ProbeErrorClassifierTest {

    private fun classify(phase: ProbePhase, error: Throwable) = ProbeErrorClassifier.classify(phase, error)

    @Test
    fun `unknown host is a DNS failure in any phase`() {
        assertEquals(ProbeCode.DNS_FAIL, classify(ProbePhase.DNS, UnknownHostException("x")))
        assertEquals(ProbeCode.DNS_FAIL, classify(ProbePhase.CONNECT, IOException(UnknownHostException("x"))))
    }

    @Test
    fun `timeout while resolving is a DNS failure`() {
        assertEquals(ProbeCode.DNS_FAIL, classify(ProbePhase.DNS, SocketTimeoutException()))
    }

    @Test
    fun `connect phase`() {
        assertEquals(ProbeCode.TCP_TIMEOUT, classify(ProbePhase.CONNECT, SocketTimeoutException("connect timed out")))
        assertEquals(ProbeCode.TCP_RESET, classify(ProbePhase.CONNECT, ConnectException("ECONNREFUSED")))
        assertEquals(ProbeCode.TCP_RESET, classify(ProbePhase.CONNECT, SocketException("Network is unreachable")))
    }

    @Test
    fun `certificate problems win over the phase`() {
        val untrusted = SSLHandshakeException("untrusted").apply { initCause(CertificateException(CertPathValidatorException())) }
        assertEquals(ProbeCode.CERT_INVALID, classify(ProbePhase.TLS, untrusted))
        assertEquals(ProbeCode.CERT_INVALID, classify(ProbePhase.TLS, SSLPeerUnverifiedException("Hostname not verified")))
    }

    @Test
    fun `other handshake failures are TLS failures`() {
        assertEquals(ProbeCode.TLS_FAIL, classify(ProbePhase.TLS, SSLHandshakeException("Connection reset by peer")))
        assertEquals(ProbeCode.TLS_FAIL, classify(ProbePhase.TLS, SocketTimeoutException("Read timed out")))
        assertEquals(ProbeCode.TLS_FAIL, classify(ProbePhase.TLS, EOFException()))
    }

    @Test
    fun `no headers after the handshake is an HTTP error`() {
        assertEquals(ProbeCode.HTTP_ERROR, classify(ProbePhase.REQUEST, SocketTimeoutException()))
        assertEquals(ProbeCode.HTTP_ERROR, classify(ProbePhase.REQUEST, IOException("unexpected end of stream")))
    }

    @Test
    fun `body phase`() {
        assertEquals(ProbeCode.STALLED, classify(ProbePhase.BODY, SocketTimeoutException("timeout")))
        assertEquals(ProbeCode.STALLED, classify(ProbePhase.BODY, SocketException("Connection reset")))
        assertEquals(ProbeCode.HTTP_ERROR, classify(ProbePhase.BODY, ZipException("Not in GZIP format")))
    }
}
