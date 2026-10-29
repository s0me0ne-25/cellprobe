// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.s0me0ne25.cellprobe.core.probe.EntryProber
import org.s0me0ne25.cellprobe.core.probe.PageText
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.core.probe.ProbeErrorClassifier
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.core.probe.ProbePhase
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry

/**
 * Probes HTTPS resources of lists 1, 2, 4 and provider pages.
 *
 * Reachable means a valid TLS chain and any HTTP response; redirects are
 * not followed. The body is read only when a marker must be checked or
 * [readBody] is set (manual checks show page titles).
 */
class HttpsProber(
    private val client: OkHttpClient,
    private val headers: List<Pair<String, String>>,
    private val readBody: Boolean,
) : EntryProber<ResourceEntry> {

    override suspend fun probe(entry: ResourceEntry): ProbeOutcome = coroutineScope {
        val holder = PhaseHolder()
        val request = Request.Builder()
            .url(entry.url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .tag(PhaseHolder::class, holder)
            .build()
        val call = client.newCall(request)

        // Blocking I/O does not react to coroutine cancellation by itself:
        // cancelling the call closes its socket and unblocks the read.
        val cancelHook = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { execute(call, entry, holder) }
        } finally {
            cancelHook.cancel()
        }
    }

    private fun execute(call: Call, entry: ResourceEntry, holder: PhaseHolder): ProbeOutcome {
        val start = System.nanoTime()
        fun elapsedMillis() = (System.nanoTime() - start) / 1_000_000

        return try {
            call.execute().use { response ->
                holder.phase = ProbePhase.BODY
                val headersMillis = elapsedMillis()
                val marker = entry.marker
                if (marker == null && !readBody) {
                    return ProbeOutcome(ProbeCode.OK, headersMillis, response.code)
                }

                val decoded = BodyDecoding.decode(response.body.source(), response.header("Content-Encoding"))
                val bytes = BodyDecoding.readAtMost(decoded, PageText.BODY_LIMIT_BYTES)
                val text = PageText.decode(bytes, response.header("Content-Type"))
                val code = if (marker != null && !PageText.containsMarker(text, marker)) {
                    ProbeCode.CONTENT_MISMATCH
                } else {
                    ProbeCode.OK
                }
                ProbeOutcome(code, headersMillis, response.code, PageText.extractTitle(text))
            }
        } catch (e: Exception) {
            // Includes IOException from cancellation; the caller's coroutine
            // is cancelled then and this outcome is discarded.
            ProbeOutcome(
                code = ProbeErrorClassifier.classify(holder.phase, e),
                durationMillis = elapsedMillis(),
                errorDetail = ProbeErrorClassifier.describe(e),
            )
        }
    }
}
