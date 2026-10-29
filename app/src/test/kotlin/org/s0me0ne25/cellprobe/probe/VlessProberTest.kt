// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dns
import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.core.probe.VlessProbeRules
import org.s0me0ne25.cellprobe.core.vless.VlessEntry
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * VLESS inbound probes against a local TLS server standing in for the
 * proxy, reached by IP with the inbound's SNI. Uses the JVM's TLS stack
 * instead of Conscrypt; its sockets also do I/O through the wrapped
 * socket's streams, so byte counting works the same way.
 */
class VlessProberTest {

    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
    private val coverCert = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName(SNI).build()
    private val tls = clientTrusting(root)
    private val timeouts = ProbeTimeouts(connect = 2.seconds, read = 1.seconds, call = 5.seconds)

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.useHttps(serverTls(coverCert))
        server.start(loopback, 0)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun clientTrusting(ca: HeldCertificate): TlsSetup {
        val certificates = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        return TlsSetup(certificates.sslSocketFactory(), certificates.trustManager)
    }

    private fun serverTls(certificate: HeldCertificate) =
        HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()

    private fun inbound(server: MockWebServer = this.server, sni: String = SNI) =
        VlessEntry(host = "127.0.0.1", port = server.port, sni = sni)

    private fun probe(
        entry: VlessEntry = inbound(),
        tlsSetup: TlsSetup = tls,
        budget: Duration = 5.seconds,
    ): ProbeOutcome = runBlocking {
        val factory = ProbeClientFactory({ tlsSetup }, Dns.SYSTEM, timeouts)
        val base = factory.create()
        VlessProber(emptyList(), { dns, sockets -> factory.inboundClient(base, dns, sockets) }, budget).probe(entry)
    }

    private fun body(size: Int) = MockResponse.Builder().body("x".repeat(size)).build()

    private fun redirect(location: String) = MockResponse.Builder().code(302).addHeader("Location", location).build()

    private fun requests() = List(server.requestCount) { server.takeRequest() }

    @Test
    fun `large page in one response is verified`() {
        server.enqueue(body(40_000))

        val outcome = probe()

        assertEquals(ProbeCode.OK, outcome.code)
        assertEquals(200, outcome.httpStatus)
        assertTrue(outcome.receivedBytes!! >= VlessProbeRules.TARGET_BYTES)
        val request = requests().single()
        assertEquals(listOf(SNI), request.handshakeServerNames)
        assertEquals("/", request.target)
    }

    @Test
    fun `host header carries the SNI without a port`() {
        server.enqueue(body(40_000))
        probe()

        val request = server.takeRequest()
        assertEquals(SNI, request.headers["Host"] ?: request.headers[":authority"])
        assertEquals(SNI, request.url.host)
    }

    @Test
    fun `small pages are repeated over one connection until the volume is reached`() {
        repeat(VlessProbeRules.MAX_REPEATS + 1) { server.enqueue(body(6_000)) }

        val outcome = probe()

        assertEquals(ProbeCode.OK, outcome.code)
        val requests = requests()
        assertTrue(requests.size in 2..VlessProbeRules.MAX_REPEATS, "requests: ${requests.size}")
        assertTrue(requests.all { it.connectionIndex == 0 })
    }

    @Test
    fun `too little data after all repeats is unverified`() {
        repeat(VlessProbeRules.MAX_REPEATS + 1) { server.enqueue(body(100)) }

        val outcome = probe()

        assertEquals(ProbeCode.OK_UNVERIFIED, outcome.code)
        assertEquals(VlessProbeRules.MAX_REPEATS + 1, server.requestCount)
        assertTrue(outcome.receivedBytes!! in 1 until VlessProbeRules.TARGET_BYTES)
    }

    @Test
    fun `same-host redirect is followed and the target repeated`() {
        server.enqueue(redirect("https://$SNI:${server.port}/en/"))
        repeat(VlessProbeRules.MAX_REPEATS + 1) { server.enqueue(body(10_000)) }

        assertEquals(ProbeCode.OK, probe().code)
        val targets = requests().map { it.target }
        assertEquals(listOf("/", "/en/"), targets.take(2))
        assertTrue(targets.drop(2).all { it == "/en/" } && targets.size > 2, "targets: $targets")
    }

    @Test
    fun `redirect to another host is not followed`() {
        repeat(VlessProbeRules.MAX_REPEATS + 1) { server.enqueue(redirect("https://elsewhere.example/")) }

        val outcome = probe()

        assertEquals(ProbeCode.OK_UNVERIFIED, outcome.code)
        assertEquals(302, outcome.httpStatus)
        assertTrue(requests().all { it.target == "/" && it.connectionIndex == 0 })
    }

    @Test
    fun `redirect chain is limited`() {
        repeat(20) { server.enqueue(redirect("/r$it")) }

        probe()

        val targets = requests().map { it.target }
        assertEquals(listOf("/", "/r0", "/r1", "/r2", "/r3", "/r4"), targets.take(VlessProbeRules.MAX_REDIRECTS + 1))
        // After the limit, the last address is repeated.
        assertTrue(targets.drop(VlessProbeRules.MAX_REDIRECTS + 1).all { it == "/r4" })
    }

    @Test
    fun `no second connection is opened when the server closes the first`() {
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.enqueue(MockResponse.Builder().headers(headersOf("Connection", "close")).body("small").build())
        server.enqueue(body(40_000))

        assertEquals(ProbeCode.OK_UNVERIFIED, probe().code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `HTTP 1 keep-alive connection is reused`() {
        server.protocols = listOf(Protocol.HTTP_1_1)
        repeat(VlessProbeRules.MAX_REPEATS + 1) { server.enqueue(body(6_000)) }

        assertEquals(ProbeCode.OK, probe().code)
        assertTrue(requests().all { it.connectionIndex == 0 && it.version == "HTTP/1.1" })
    }

    @Test
    fun `TLS server without fallback is reachable but unverified`() {
        // The handshake completes, then the server drops the connection
        // instead of answering the request.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertEquals(ProbeCode.OK_UNVERIFIED, probe().code)
    }

    @Test
    fun `transfer that stops after the first page is stalled`() {
        server.enqueue(body(10_000))
        server.enqueue(MockResponse.Builder().body(body = "x".repeat(10_000)).onResponseBody(SocketEffect.Stall).build())

        val outcome = probe()

        assertEquals(ProbeCode.STALLED, outcome.code)
        assertEquals(200, outcome.httpStatus)
    }

    @Test
    fun `no response after the handshake is stalled`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

        val outcome = probe()

        assertEquals(ProbeCode.STALLED, outcome.code)
        assertTrue(outcome.errorDetail!!.startsWith("SocketTimeoutException"), outcome.errorDetail)
    }

    @Test
    fun `time budget ends the volume check without a verdict of stall`() {
        val slow = MockResponse.Builder().body("x".repeat(64_000)).throttleBody(1_000, 250, TimeUnit.MILLISECONDS).build()
        server.enqueue(slow)

        val outcome = probe(budget = 2.seconds)

        assertEquals(ProbeCode.OK_UNVERIFIED, outcome.code)
    }

    @Test
    fun `self-signed certificate is invalid`() {
        val selfSigned = HeldCertificate.Builder().addSubjectAlternativeName(SNI).build()
        MockWebServer().use { other ->
            other.useHttps(serverTls(selfSigned))
            other.start(loopback, 0)
            other.enqueue(body(40_000))

            assertEquals(ProbeCode.CERT_INVALID, probe(inbound(other)).code)
        }
    }

    @Test
    fun `certificate not matching the SNI is invalid`() {
        assertEquals(ProbeCode.CERT_INVALID, probe(inbound(sni = "other.example.com")).code)
    }

    @Test
    fun `refused connection is a TCP reset`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val entry = VlessEntry(host = "127.0.0.1", port = closedPort, sni = SNI)
        assertEquals(ProbeCode.TCP_RESET, probe(entry).code)
    }

    @Test
    fun `cancellation aborts a blocked probe promptly`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val factory = ProbeClientFactory({ tls }, Dns.SYSTEM, ProbeTimeouts(read = 30.seconds, call = 60.seconds))
        val base = factory.create()
        val prober = VlessProber(emptyList(), { dns, sockets -> factory.inboundClient(base, dns, sockets) }, 60.seconds)

        val started = System.nanoTime()
        val result = withTimeoutOrNull(300) { prober.probe(inbound()) }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertNull(result)
        assertTrue(elapsedMillis < 5_000, "cancellation took $elapsedMillis ms")
    }

    private companion object {
        const val SNI = "www.example.com"
    }
}
