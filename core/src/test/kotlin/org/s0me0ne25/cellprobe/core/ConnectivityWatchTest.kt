// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.ConnectivityWatch.Outcome
import kotlin.time.Duration.Companion.seconds

// currentTime and advanceTimeBy of the virtual test clock are still experimental.
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityWatchTest {

    private val online = NetworkState(activeNetwork = NetworkType.MOBILE)
    private val airplane = NetworkState(activeNetwork = null, airplaneMode = true)
    private val noService = NetworkState(activeNetwork = null)
    private val wifi = NetworkState(activeNetwork = NetworkType.WIFI)

    @Test
    fun `loss is reported after the confirmation delay`() = runTest {
        val loss = ConnectivityWatch.awaitLoss(flowOf(online, airplane), readAgain = { airplane })

        assertEquals(airplane, loss)
        assertEquals(ConnectivityWatch.CONFIRMATION_DELAY.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `a moment without a network during a switch is not a loss`() = runTest {
        val readings = MutableSharedFlow<NetworkState>()
        var current = noService
        var result: NetworkState? = null
        val watching = launch { result = ConnectivityWatch.awaitLoss(readings, readAgain = { current }) }

        advanceTimeBy(1)
        readings.emit(noService)
        current = wifi // Wi-Fi took over before the confirmation.
        advanceTimeBy(ConnectivityWatch.CONFIRMATION_DELAY.inWholeMilliseconds + 1)
        assertEquals(null, result)

        current = airplane
        readings.emit(airplane)
        advanceTimeBy(ConnectivityWatch.CONFIRMATION_DELAY.inWholeMilliseconds + 1)
        assertEquals(airplane, result)
        watching.join()
    }

    @Test
    fun `captive portal appearing mid-check also stops the probes`() = runTest {
        val portal = wifi.copy(captivePortal = true)
        assertEquals(portal, ConnectivityWatch.awaitLoss(flowOf(portal), readAgain = { portal }))
    }

    @Test
    fun `work that finishes first wins and the watch is cancelled`() = runTest {
        val watchCancelled = CompletableDeferred<Boolean>()
        val outcome = ConnectivityWatch.runUntilLoss(
            work = {
                delay(1_000)
                "results"
            },
            loss = {
                try {
                    awaitCancellation()
                } finally {
                    watchCancelled.complete(true)
                }
            },
        )

        assertEquals(Outcome.Completed("results"), outcome)
        assertTrue(watchCancelled.await())
    }

    @Test
    fun `loss cuts the work short`() = runTest {
        var workFinished = false
        val outcome = ConnectivityWatch.runUntilLoss(
            work = {
                delay(30.seconds)
                workFinished = true
                "results"
            },
            loss = {
                delay(3.seconds)
                noService
            },
        )

        assertEquals(Outcome.Interrupted(noService), outcome)
        assertFalse(workFinished)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `work that fails fast after the loss is still interrupted`() = runTest {
        // Airplane mode: every request fails at once, long before the watch
        // has confirmed the loss.
        var current = online
        val outcome = ConnectivityWatch.runUntilLoss(
            work = {
                current = airplane
                "all failed"
            },
            loss = { awaitCancellation() },
            lossAfterWork = { ConnectivityWatch.confirmedLoss(read = { current }) },
        )

        assertEquals(Outcome.Interrupted(airplane), outcome)
        assertEquals(ConnectivityWatch.CONFIRMATION_DELAY.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `network still there after the work means completed`() = runTest {
        val outcome = ConnectivityWatch.runUntilLoss(
            work = { "results" },
            loss = { awaitCancellation() },
            lossAfterWork = { ConnectivityWatch.confirmedLoss(read = { online }) },
        )

        assertEquals(Outcome.Completed("results"), outcome)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `confirmed loss ignores a moment without a network`() = runTest {
        var readings = 0
        val loss = ConnectivityWatch.confirmedLoss(read = { if (readings++ == 0) noService else wifi })
        assertEquals(null, loss)
    }
}
