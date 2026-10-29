// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import org.s0me0ne25.cellprobe.core.probe.EntryProber
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.core.probe.ProbeErrorClassifier
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.core.probe.VlessProbeRules
import org.s0me0ne25.cellprobe.core.vless.VlessEntry
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.time.Duration

/**
 * Probes a VLESS inbound (spec §5.2): requests `https://<sni>:<port>/` from
 * the proxy server with the inbound's SNI and strict certificate validation.
 *
 * A REALITY server relays an unauthenticated client to its target site, a
 * TLS server presents its own certificate and serves its fallback site or
 * closes the connection. Either way a completed handshake with a valid
 * certificate means the inbound is reachable; then the probe tries to receive
 * [VlessProbeRules.TARGET_BYTES] over that one connection, following
 * same-host redirects and repeating the final request, to tell a working
 * inbound from one cut off after the first few kilobytes.
 *
 * @param clientFor builds the client for one inbound from its DNS mapping
 *   and byte-counting socket factory.
 */
class VlessProber(
    private val headers: List<Pair<String, String>>,
    private val clientFor: (Dns, SocketFactory) -> OkHttpClient,
    private val timeBudget: Duration = VlessProbeRules.TIME_BUDGET,
) : EntryProber<VlessEntry> {

    override suspend fun probe(entry: VlessEntry): ProbeOutcome = coroutineScope {
        val sockets = CountingSocketFactory()
        // The DNS mapping allows one connection only: the cut-off threshold
        // is per connection, so the whole check must go through one.
        val client = clientFor(InboundDns(entry.sni, entry.host), sockets)
        val session = Session(entry, client, sockets)

        // Blocking I/O does not react to coroutine cancellation by itself:
        // cancelling the current call closes its socket and unblocks the read.
        val cancelHook = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                session.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    session.run()
                } finally {
                    // Closing a TLS connection sends close_notify, which is
                    // network I/O: never on the caller's (main) thread.
                    client.connectionPool.evictAll()
                }
            }
        } finally {
            cancelHook.cancel()
        }
    }

    private inner class Session(
        private val entry: VlessEntry,
        private val client: OkHttpClient,
        private val sockets: CountingSocketFactory,
    ) {
        private val holder = PhaseHolder()
        private val start = System.nanoTime()

        @Volatile
        private var currentCall: Call? = null

        @Volatile
        private var cancelled = false

        @Volatile
        private var done = false

        private var headersMillis: Long? = null
        private var lastStatus: Int? = null

        /** Aborts a running probe; does nothing once it has finished. */
        fun cancel() {
            if (done) return
            cancelled = true
            currentCall?.cancel()
        }

        fun run(): ProbeOutcome = try {
            runChecks()
        } finally {
            done = true
        }

        private fun runChecks(): ProbeOutcome {
            var url = HttpUrl.Builder().scheme("https").host(entry.sni).port(entry.port).build()
            var redirects = 0
            var repeats = 0

            while (true) {
                val remainingMillis = timeBudget.inWholeMilliseconds - elapsedMillis()
                if (remainingMillis <= 0) return finished(VlessProbeRules.completedCode(sockets.receivedBytes))

                val call = client.newCall(request(url))
                call.timeout().timeout(remainingMillis, TimeUnit.MILLISECONDS)
                currentCall = call
                if (cancelled) call.cancel()

                val location = try {
                    exchange(call)
                } catch (e: Exception) {
                    // The connection cannot be reused (the server closed it or
                    // asked to): the volume check is over, nothing went wrong.
                    if (e.causes().any { it is SecondConnectionException }) {
                        return finished(VlessProbeRules.completedCode(sockets.receivedBytes))
                    }
                    // Includes IOException from cancellation; the caller's
                    // coroutine is cancelled then and this outcome is discarded.
                    return finished(
                        VlessProbeRules.failureCode(holder.phase, e, sockets.receivedBytes),
                        ProbeErrorClassifier.describe(e),
                    )
                }
                if (sockets.receivedBytes >= VlessProbeRules.TARGET_BYTES) return finished(ProbeCode.OK)

                // Redirects are followed only before the repeats start.
                val next = location
                    .takeIf { repeats == 0 && redirects < VlessProbeRules.MAX_REDIRECTS }
                    ?.let { VlessProbeRules.redirectTarget(url.toString(), it, entry.sni, entry.port) }
                    ?.toHttpUrlOrNull()
                when {
                    next != null -> {
                        redirects++
                        url = next
                    }
                    repeats < VlessProbeRules.MAX_REPEATS -> repeats++
                    else -> return finished(VlessProbeRules.completedCode(sockets.receivedBytes))
                }
            }
        }

        /** Performs one request, reads its body; returns the redirect location, if any. */
        private fun exchange(call: Call): String? = call.execute().use { response ->
            if (headersMillis == null) headersMillis = elapsedMillis()
            lastStatus = response.code
            drain(response.body.source())
            if (response.isRedirect) response.header("Location") else null
        }

        /** Reads and discards the raw body until it ends or enough bytes were received. */
        private fun drain(source: BufferedSource) {
            val buffer = Buffer()
            while (sockets.receivedBytes < VlessProbeRules.TARGET_BYTES) {
                if (source.read(buffer, READ_CHUNK) == -1L) return
                buffer.clear()
            }
        }

        private fun request(url: HttpUrl): Request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            // The SNI without a port, as a browser would send it to the cover site.
            .header("Host", entry.sni)
            .tag(PhaseHolder::class, holder)
            .build()

        private fun finished(code: ProbeCode, errorDetail: String? = null) = ProbeOutcome(
            code = code,
            durationMillis = headersMillis ?: elapsedMillis(),
            httpStatus = lastStatus,
            receivedBytes = sockets.receivedBytes,
            errorDetail = errorDetail,
        )

        private fun elapsedMillis() = (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val READ_CHUNK = 8L * 1024
        const val MAX_CAUSE_DEPTH = 10

        fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)
    }
}
