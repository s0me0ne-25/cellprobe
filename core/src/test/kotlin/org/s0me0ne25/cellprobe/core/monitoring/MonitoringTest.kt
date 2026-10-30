// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.monitoring

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.NetworkState
import org.s0me0ne25.cellprobe.core.NetworkType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// The virtual test clock (currentTime, advanceTimeBy) is still experimental.
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringTest {

    @Test
    fun `defaults follow the spec`() {
        val settings = MonitoringSettings()
        assertEquals(false, settings.enabled)
        assertEquals(3.minutes, settings.interval(screenOn = true))
        assertEquals(10.minutes, settings.interval(screenOn = false))
        assertEquals(20.minutes, settings.lastCheckMaxAge)
    }

    @Test
    fun `intervals are kept within the allowed range`() {
        val settings = MonitoringSettings(screenOnIntervalSeconds = 1, screenOffIntervalSeconds = 100_000)
        assertEquals(30.seconds, settings.screenOnInterval)
        assertEquals(60.minutes, settings.screenOffInterval)
        assertEquals(120.minutes, settings.lastCheckMaxAge)
    }

    @Test
    fun `settings survive a round trip and old files load`() {
        val json = Json { ignoreUnknownKeys = true }
        val settings = MonitoringSettings(enabled = true, screenOnIntervalSeconds = 60, screenOffIntervalSeconds = 900)
        assertEquals(settings, json.decodeFromString(MonitoringSettings.serializer(), json.encodeToString(MonitoringSettings.serializer(), settings)))
        assertEquals(MonitoringSettings(), json.decodeFromString(MonitoringSettings.serializer(), "{}"))
    }

    /** Runs the loop with checks that take [checkMillis]; returns the check start times. */
    private fun TestScope.loop(
        interval: MutableStateFlow<Duration>,
        extra: MutableSharedFlow<Unit> = MutableSharedFlow(),
        checkMillis: Long = 1_000,
    ): Pair<MutableList<Long>, Job> {
        val starts = mutableListOf<Long>()
        val job = launch {
            MonitoringLoop.run(interval, extra, nowMillis = { currentTime }) {
                starts += currentTime
                delay(checkMillis)
            }
        }
        return starts to job
    }

    @Test
    fun `checks repeat with the interval measured from the end of a check`() = runTest {
        val (starts, job) = loop(MutableStateFlow(60.seconds))

        advanceTimeBy(130_000)
        runCurrent()

        assertEquals(listOf(0L, 61_000L, 122_000L), starts)
        job.cancelAndJoin()
    }

    @Test
    fun `a longer interval applies to the pause already running`() = runTest {
        val interval = MutableStateFlow(60.seconds)
        val (starts, job) = loop(interval)

        advanceTimeBy(30_000)
        interval.value = 10.minutes // The screen went off.
        advanceTimeBy(10.minutes.inWholeMilliseconds)
        runCurrent()

        assertEquals(listOf(0L, 601_000L), starts)
        job.cancelAndJoin()
    }

    @Test
    fun `a shorter interval that has already passed starts a check at once`() = runTest {
        val interval = MutableStateFlow(10.minutes)
        val (starts, job) = loop(interval)

        advanceTimeBy(5.minutes.inWholeMilliseconds)
        interval.value = 3.minutes
        runCurrent()

        assertEquals(listOf(0L, 300_000L), starts)
        job.cancelAndJoin()
    }

    @Test
    fun `an extra check ends the pause`() = runTest {
        val extra = MutableSharedFlow<Unit>()
        val (starts, job) = loop(MutableStateFlow(10.minutes), extra)

        advanceTimeBy(20_000)
        extra.emit(Unit) // The screen went on.
        runCurrent()

        assertEquals(listOf(0L, 20_000L), starts)
        job.cancelAndJoin()
    }

    @Test
    fun `an extra request during a check does not cause another one`() = runTest {
        val extra = MutableSharedFlow<Unit>()
        val (starts, job) = loop(MutableStateFlow(10.minutes), extra, checkMillis = 5_000)

        advanceTimeBy(2_000)
        extra.emit(Unit)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(0L), starts)
        job.cancelAndJoin()
    }

    @Test
    fun `each pause asks for a wake-up at its end and a wake-up ends it`() = runTest {
        val wakeAt = mutableListOf<Long>()
        val wakeUps = MutableSharedFlow<Unit>()
        var now = 0L
        val starts = mutableListOf<Long>()
        val job = launch {
            MonitoringLoop.run(
                interval = MutableStateFlow(10.minutes),
                extraChecks = MutableSharedFlow(),
                nowMillis = { now },
                check = { starts += now },
                onPause = { wakeAt += it },
                wakeUps = wakeUps,
            )
        }
        runCurrent()
        assertEquals(listOf(600_000L), wakeAt)

        // The device slept: the coroutine timer did not run, the alarm did.
        now = 600_000
        wakeUps.emit(Unit)
        runCurrent()

        assertEquals(listOf(0L, 600_000L), starts)
        assertEquals(listOf(600_000L, 1_200_000L), wakeAt)
        job.cancelAndJoin()
    }

    @Test
    fun `an early wake-up only reschedules`() = runTest {
        val wakeAt = mutableListOf<Long>()
        val wakeUps = MutableSharedFlow<Unit>()
        var now = 0L
        val starts = mutableListOf<Long>()
        val job = launch {
            MonitoringLoop.run(MutableStateFlow(10.minutes), MutableSharedFlow(), { now }, { wakeAt += it }, wakeUps) { starts += now }
        }
        runCurrent()
        now = 590_000
        wakeUps.emit(Unit)
        runCurrent()

        assertEquals(listOf(0L), starts)
        assertEquals(listOf(600_000L, 600_000L), wakeAt)
        job.cancelAndJoin()
    }

    private val mobile = NetworkState(activeNetwork = NetworkType.MOBILE, networkId = 101)

    @Test
    fun `network change, loss and return call for a check after settling`() {
        val wifi = NetworkState(activeNetwork = NetworkType.WIFI, networkId = 102)
        val none = NetworkState(activeNetwork = null)
        assertEquals(NetworkEvents.SETTLE_DELAY, NetworkEvents.extraCheckDelay(mobile, wifi))
        assertEquals(NetworkEvents.SETTLE_DELAY, NetworkEvents.extraCheckDelay(mobile, none))
        assertEquals(NetworkEvents.SETTLE_DELAY, NetworkEvents.extraCheckDelay(none, mobile))
    }

    @Test
    fun `cleared captive portal calls for a check at once`() {
        val portal = mobile.copy(captivePortal = true)
        assertEquals(Duration.ZERO, NetworkEvents.extraCheckDelay(portal, mobile))
        assertEquals(NetworkEvents.SETTLE_DELAY, NetworkEvents.extraCheckDelay(mobile, portal))
    }

    @Test
    fun `other updates of the same network need no check`() {
        assertEquals(null, NetworkEvents.extraCheckDelay(mobile, mobile))
        assertEquals(null, NetworkEvents.extraCheckDelay(mobile, mobile.copy(airplaneMode = true)))
    }
}
