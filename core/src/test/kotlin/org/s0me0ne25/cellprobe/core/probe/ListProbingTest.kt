// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry

class ListProbingTest {

    private val a = ResourceEntry("https://a.example")
    private val b = ResourceEntry("https://b.example")
    private val c = ResourceEntry("https://c.example")

    /** Scripted prober: each entry answers with a code after a delay, per round. */
    private class FakeProber(private val script: Map<String, List<Pair<Long, ProbeCode>>>) : EntryProber<ResourceEntry> {
        val calls = mutableMapOf<String, Int>()

        override suspend fun probe(entry: ResourceEntry): ProbeOutcome {
            val round = calls.merge(entry.url, 1, Int::plus)!!
            val steps = script.getValue(entry.url)
            val (delayMillis, code) = steps[(round - 1).coerceAtMost(steps.lastIndex)]
            delay(delayMillis)
            return ProbeOutcome(code, delayMillis)
        }
    }

    private fun codes(result: ListProbeResult<*>) = result.results.map { it.outcome.code }

    @Test
    fun `empty list is not available and not probed`() = runTest {
        val result = ListProbing.probeList(CheckListId.PROVIDER, emptyList(), FakeProber(emptyMap()))
        assertFalse(result.available)
        assertEquals(emptyList<EntryResult<ResourceEntry>>(), result.results)
    }

    @Test
    fun `first reachable entry cancels the others`() = runTest {
        val prober = FakeProber(
            mapOf(
                a.url to listOf(3_000L to ProbeCode.OK),
                b.url to listOf(100L to ProbeCode.OK),
                c.url to listOf(50L to ProbeCode.TCP_TIMEOUT),
            ),
        )
        val result = ListProbing.probeList(CheckListId.DOMESTIC, listOf(a, b, c), prober)

        assertTrue(result.available)
        assertFalse(result.retried)
        assertEquals(listOf(ProbeCode.SKIPPED, ProbeCode.OK, ProbeCode.TCP_TIMEOUT), codes(result))
        // The slow entry was cancelled, not awaited.
        assertEquals(100L, currentTime)
    }

    @Test
    fun `provider pages are all probed`() = runTest {
        val prober = FakeProber(
            mapOf(
                a.url to listOf(100L to ProbeCode.OK),
                b.url to listOf(2_000L to ProbeCode.OK),
            ),
        )
        val result = ListProbing.probeList(CheckListId.PROVIDER, listOf(a, b), prober)

        assertEquals(listOf(ProbeCode.OK, ProbeCode.OK), codes(result))
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun `list with no reachable entry is probed once more`() = runTest {
        val prober = FakeProber(
            mapOf(
                a.url to listOf(100L to ProbeCode.TLS_FAIL, 100L to ProbeCode.OK),
                b.url to listOf(100L to ProbeCode.TCP_RESET, 300L to ProbeCode.TCP_RESET),
            ),
        )
        val result = ListProbing.probeList(CheckListId.FOREIGN, listOf(a, b), prober)

        assertTrue(result.retried)
        assertTrue(result.available)
        assertEquals(listOf(ProbeCode.OK, ProbeCode.SKIPPED), codes(result))
        assertEquals(mapOf(a.url to 2, b.url to 2), prober.calls)
    }

    @Test
    fun `retry happens only once`() = runTest {
        val prober = FakeProber(mapOf(a.url to listOf(10L to ProbeCode.DNS_FAIL)))
        val result = ListProbing.probeList(CheckListId.BANNED, listOf(a), prober)

        assertTrue(result.retried)
        assertFalse(result.available)
        assertEquals(mapOf(a.url to 2), prober.calls)
    }

    @Test
    fun `unverified VLESS success counts as reachable`() = runTest {
        val prober = FakeProber(mapOf(a.url to listOf(10L to ProbeCode.OK_UNVERIFIED)))
        val result = ListProbing.probeList(CheckListId.VLESS, listOf(a), prober)
        assertTrue(result.available)
        assertFalse(result.retried)
    }

    @Test
    fun `VLESS inbounds are all probed`() = runTest {
        val prober = FakeProber(
            mapOf(
                a.url to listOf(100L to ProbeCode.OK),
                b.url to listOf(2_000L to ProbeCode.STALLED),
            ),
        )
        val result = ListProbing.probeList(CheckListId.VLESS, listOf(a, b), prober)

        assertEquals(listOf(ProbeCode.OK, ProbeCode.STALLED), codes(result))
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun `listener sees rounds and every entry`() = runTest {
        val events = mutableListOf<String>()
        val listener = object : ListProbeListener {
            override fun onRoundStarted(listId: CheckListId, round: Int) {
                events += "round $round"
            }

            override fun onEntryFinished(listId: CheckListId, index: Int, outcome: ProbeOutcome) {
                events += "$index ${outcome.code}"
            }
        }
        val prober = FakeProber(
            mapOf(
                a.url to listOf(10L to ProbeCode.OK),
                b.url to listOf(500L to ProbeCode.OK),
            ),
        )
        ListProbing.probeList(CheckListId.DOMESTIC, listOf(a, b), prober, listener)
        assertEquals(listOf("round 1", "0 OK", "1 SKIPPED"), events)
    }
}
