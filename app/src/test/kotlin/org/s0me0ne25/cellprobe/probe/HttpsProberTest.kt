// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dns
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.DeflaterSink
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.core.probe.WebViewHeaders
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.zip.Deflater
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end probes against a local TLS server. Uses the JVM's TLS stack
 * instead of Conscrypt, which only runs on Android; everything else is the
 * production code path.
 */
class HttpsProberTest {

    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
    private val localhostCert = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
    private val trustingClient = clientTrusting(root)

    private val server = MockWebServer()
    private val timeouts = ProbeTimeouts(connect = 2.seconds, read = 1.seconds, call = 5.seconds)

    @BeforeEach
    fun setUp() {
        server.useHttps(serverTls(localhostCert))
        server.start()
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

    private fun probe(
        entry: ResourceEntry,
        readBody: Boolean = true,
        tls: TlsSetup = trustingClient,
        dns: Dns = Dns.SYSTEM,
    ): ProbeOutcome = runBlocking {
        val client = ProbeClientFactory({ tls }, dns, timeouts).create()
        HttpsProber(client, WebViewHeaders.navigation(USER_AGENT, listOf("ru-RU")), readBody).probe(entry)
    }

    private fun entry(server: MockWebServer = this.server, marker: String? = null) =
        ResourceEntry(server.url("/").toString(), marker)

    private fun html(title: String) = "<html><head><title>$title</title></head><body>…</body></html>"

    @Test
    fun `plain response is reachable and the title is read`() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(html("Привет")).build())

        val outcome = probe(entry())

        assertEquals(ProbeCode.OK, outcome.code)
        assertEquals(200, outcome.httpStatus)
        assertEquals("Привет", outcome.pageTitle)
    }

    @Test
    fun `request carries the WebView headers`() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        probe(entry())

        val request = server.takeRequest()
        assertEquals(USER_AGENT, request.headers["User-Agent"])
        assertEquals(WebViewHeaders.ACCEPT_ENCODING, request.headers["Accept-Encoding"])
        assertEquals("navigate", request.headers["Sec-Fetch-Mode"])
        assertEquals("ru-RU,ru;q=0.9", request.headers["Accept-Language"])
    }

    @Test
    fun `marker decides between OK and CONTENT_MISMATCH`() {
        server.enqueue(MockResponse.Builder().body(html("Yandex")).build())
        server.enqueue(MockResponse.Builder().body(html("Something else")).build())

        assertEquals(ProbeCode.OK, probe(entry(marker = "yandex")).code)
        assertEquals(ProbeCode.CONTENT_MISMATCH, probe(entry(marker = "yandex")).code)
    }

    @Test
    fun `gzip body is decoded`() {
        val body = Buffer()
        GzipSink(body).buffer().use { it.writeUtf8(html("Gzip ok")) }
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(body).build())

        assertEquals("Gzip ok", probe(entry()).pageTitle)
    }

    @Test
    fun `deflate body is decoded`() {
        val body = Buffer()
        DeflaterSink(body, Deflater()).buffer().use { it.writeUtf8(html("Deflate ok")) }
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "deflate").body(body).build())

        assertEquals("Deflate ok", probe(entry()).pageTitle)
    }

    @Test
    fun `brotli body is decoded`() {
        val body = Buffer().write(BROTLI_SAMPLE)
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "br").body(body).build())

        assertEquals("Brotli ok", probe(entry()).pageTitle)
    }

    @Test
    fun `unknown encoding is an HTTP error`() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "compress").body("x").build())
        assertEquals(ProbeCode.HTTP_ERROR, probe(entry()).code)
    }

    @Test
    fun `redirects count as a response and are not followed`() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://elsewhere.example/").build())

        val outcome = probe(entry())

        assertEquals(ProbeCode.OK, outcome.code)
        assertEquals(302, outcome.httpStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `without marker and body reading only headers matter`() {
        server.enqueue(MockResponse.Builder().body(html("Not read")).build())

        val outcome = probe(entry(), readBody = false)

        assertEquals(ProbeCode.OK, outcome.code)
        assertNull(outcome.pageTitle)
    }

    @Test
    fun `certificate from an untrusted CA is invalid`() {
        server.enqueue(MockResponse.Builder().body("x").build())
        val otherRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("Other Root").build()

        assertEquals(ProbeCode.CERT_INVALID, probe(entry(), tls = clientTrusting(otherRoot)).code)
    }

    @Test
    fun `certificate for another host is invalid`() {
        val wrongHost = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("example.com").build()
        MockWebServer().use { other ->
            other.useHttps(serverTls(wrongHost))
            other.start()
            other.enqueue(MockResponse.Builder().body("x").build())

            assertEquals(ProbeCode.CERT_INVALID, probe(entry(other)).code)
        }
    }

    @Test
    fun `non-TLS peer is a TLS failure`() {
        MockWebServer().use { plain ->
            plain.start()
            plain.enqueue(MockResponse.Builder().body("x").build())
            val url = plain.url("/").newBuilder().scheme("https").build()

            assertEquals(ProbeCode.TLS_FAIL, probe(ResourceEntry(url.toString())).code)
        }
    }

    @Test
    fun `refused connection is a TCP reset`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertEquals(ProbeCode.TCP_RESET, probe(ResourceEntry("https://localhost:$closedPort/")).code)
    }

    @Test
    fun `unresolvable host is a DNS failure`() {
        val failingDns = Dns { hostname -> throw UnknownHostException(hostname) }
        assertEquals(ProbeCode.DNS_FAIL, probe(entry(), dns = failingDns).code)
    }

    @Test
    fun `no response headers after the handshake is an HTTP error`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        assertEquals(ProbeCode.HTTP_ERROR, probe(entry()).code)
    }

    @Test
    fun `body that stops flowing is stalled`() {
        server.enqueue(MockResponse.Builder().body(html("never")).onResponseBody(SocketEffect.Stall).build())
        assertEquals(ProbeCode.STALLED, probe(entry()).code)
    }

    @Test
    fun `cancellation aborts a blocked probe promptly`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val client = ProbeClientFactory({ trustingClient }, Dns.SYSTEM, ProbeTimeouts(read = 30.seconds, call = 60.seconds)).create()
        val prober = HttpsProber(client, emptyList(), readBody = false)

        val started = System.nanoTime()
        val result = withTimeoutOrNull(300) { prober.probe(entry()) }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertNull(result)
        assertTrue(elapsedMillis < 5_000, "cancellation took $elapsedMillis ms")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"

        /** Brotli-compressed `<html><head><title>Brotli ok</title></head></html>`. */
        private val BROTLI_SAMPLE = byteArrayOf(
            27, 49, 0, 64, -100, 7, 118, 44, 103, -102, -110, 45, 105, -79, -41, 60, 66, 104, 111, -14, 101, 77,
            -82, 64, -24, -38, 39, -115, -83, 13, 108, -64, -127, 75, 86, -90, -77, -33, -104, 12, 40, 101, -24,
            81, 5, -38, -13, -89, 59, 7, 2, -120, 6,
        )
    }
}
