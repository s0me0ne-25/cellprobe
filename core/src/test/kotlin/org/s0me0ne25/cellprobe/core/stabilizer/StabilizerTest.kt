// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.stabilizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkStatus.AUTH_REQUIRED
import org.s0me0ne25.cellprobe.core.NetworkStatus.LEAK
import org.s0me0ne25.cellprobe.core.NetworkStatus.NORMAL
import org.s0me0ne25.cellprobe.core.NetworkStatus.NORMAL_NO_VLESS
import org.s0me0ne25.cellprobe.core.NetworkStatus.NO_INTERNET
import org.s0me0ne25.cellprobe.core.NetworkStatus.PAY
import org.s0me0ne25.cellprobe.core.NetworkStatus.USER_OFFLINE
import org.s0me0ne25.cellprobe.core.NetworkStatus.VLESS_BLOCKED
import org.s0me0ne25.cellprobe.core.NetworkStatus.WHITELIST
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.core.NetworkType.MOBILE
import org.s0me0ne25.cellprobe.core.NetworkType.WIFI
import org.s0me0ne25.cellprobe.core.stabilizer.Stabilizer.RECHECK_DELAY
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Scenarios of spec §6 on a virtual clock. */
class StabilizerTest {

    /** Feeds checks to the stabilizer, keeping the state and the virtual time. */
    private class Run(start: NetworkStatus = NORMAL, network: NetworkType? = MOBILE) {
        var now = 0L
        var state = StabilizerState()
        val notices = mutableListOf<Notice>()
        var lastDecision: Decision

        init {
            lastDecision = check(start, network)
        }

        fun check(status: NetworkStatus, network: NetworkType? = MOBILE): Decision {
            val decision = Stabilizer.observe(state, Observation(status, network.takeIf { status != USER_OFFLINE }, now))
            state = decision.state
            decision.notice?.let(notices::add)
            lastDecision = decision
            return decision
        }

        fun after(duration: Duration): Run {
            now += duration.inWholeMilliseconds
            return this
        }

        /** A status seen twice in a row, 15 s apart: a confirmed transition. */
        fun change(status: NetworkStatus, network: NetworkType? = MOBILE): Notice? {
            val before = notices.size
            check(status, network)
            after(RECHECK_DELAY).check(status, network)
            return notices.drop(before).lastOrNull()
        }
    }

    @Test
    fun `the first check is the baseline and announces nothing`() {
        val run = Run(WHITELIST)
        assertEquals(WHITELIST, run.state.confirmed)
        assertEquals(WHITELIST, run.state.announced)
        assertTrue(run.notices.isEmpty())
        assertNull(run.lastDecision.recheckAfter)
    }

    @Test
    fun `a one-off failure asks for a recheck and is dropped`() {
        val run = Run(NORMAL)
        val first = run.after(3.minutes).check(NO_INTERNET)
        assertEquals(RECHECK_DELAY, first.recheckAfter)
        assertEquals(NORMAL, run.state.confirmed)
        assertEquals(NO_INTERNET, run.state.candidate?.status)

        val recheck = run.after(RECHECK_DELAY).check(NORMAL)
        assertNull(recheck.recheckAfter)
        assertNull(run.state.candidate)
        assertEquals(NORMAL, run.state.confirmed)
        assertTrue(run.notices.isEmpty())
    }

    @Test
    fun `a confirmed stricter status alerts loudly`() {
        val run = Run(NORMAL)
        val notice = run.after(3.minutes).change(WHITELIST)
        assertEquals(Notice.StatusChanged(WHITELIST, MOBILE, Volume.LOUD), notice)
        assertEquals(WHITELIST, run.state.confirmed)
        assertEquals(WHITELIST, run.state.announced)
    }

    @Test
    fun `a softer status is an ordinary notification`() {
        val run = Run(WHITELIST)
        assertEquals(Notice.StatusChanged(NORMAL, MOBILE, Volume.NORMAL), run.after(3.minutes).change(NORMAL))
    }

    @Test
    fun `a second stricter status within 10 minutes is ordinary`() {
        val run = Run(NORMAL)
        assertEquals(Volume.LOUD, run.after(3.minutes).change(VLESS_BLOCKED)?.volume)
        assertEquals(Volume.NORMAL, run.after(3.minutes).change(WHITELIST)?.volume)
        // The cooldown is over.
        run.after(5.minutes).change(VLESS_BLOCKED)
        assertEquals(Volume.LOUD, run.after(11.minutes).change(PAY)?.volume)
    }

    @Test
    fun `three different candidates in a row end with the strictest`() {
        val run = Run(NORMAL)
        run.after(3.minutes).check(VLESS_BLOCKED)
        run.after(RECHECK_DELAY).check(PAY)
        val third = run.after(RECHECK_DELAY).check(WHITELIST)
        assertEquals(RECHECK_DELAY, third.recheckAfter)
        run.after(RECHECK_DELAY).check(VLESS_BLOCKED)

        assertEquals(PAY, run.state.confirmed)
        assertNull(run.state.candidate)
        assertEquals(PAY, (run.notices.single() as Notice.StatusChanged).status)
    }

    @Test
    fun `standard blocking with and without VLESS is one status`() {
        val run = Run(NORMAL)
        val decision = run.after(3.minutes).check(NORMAL_NO_VLESS)
        assertNull(decision.recheckAfter)
        assertEquals(NORMAL_NO_VLESS, run.state.confirmed)
        assertTrue(run.notices.isEmpty())
    }

    @Test
    fun `captive portal and user offline are accepted at once`() {
        val run = Run(NORMAL, WIFI)
        val portal = run.after(3.minutes).check(AUTH_REQUIRED, WIFI)
        assertNull(portal.recheckAfter)
        assertEquals(AUTH_REQUIRED, run.state.confirmed)
        assertEquals(Volume.LOUD, portal.notice?.volume)

        val offline = run.after(1.minutes).check(USER_OFFLINE)
        assertNull(offline.recheckAfter)
        assertNull(offline.notice)
        assertEquals(USER_OFFLINE, run.state.confirmed)
        assertEquals(AUTH_REQUIRED, run.state.announced)
    }

    @Test
    fun `back from user offline with the same status says nothing`() {
        val run = Run(WHITELIST)
        run.after(3.minutes).check(USER_OFFLINE)
        val back = run.after(10.minutes).check(WHITELIST)
        assertNull(back.recheckAfter)
        assertNull(back.notice)
        assertEquals(WHITELIST, run.state.confirmed)
        assertTrue(run.state.transitions.isEmpty())
    }

    @Test
    fun `back from user offline with another status announces it`() {
        val run = Run(NORMAL)
        run.after(3.minutes).check(USER_OFFLINE)
        val notice = run.after(10.minutes).change(WHITELIST)
        assertEquals(Notice.StatusChanged(WHITELIST, MOBILE, Volume.LOUD), notice)
        assertEquals(listOf(Transition(run.now, NORMAL, WHITELIST)), run.state.transitions)
    }

    @Test
    fun `going offline and back does not count as flapping`() {
        val run = Run(NORMAL)
        repeat(6) {
            run.after(1.minutes).check(USER_OFFLINE)
            run.after(1.minutes).check(NORMAL)
        }
        assertFalse(run.state.unstable)
        assertTrue(run.notices.isEmpty())
    }

    @Test
    fun `flapping turns into UNSTABLE, then updates silently, then stabilizes`() {
        val run = Run(NORMAL)
        assertEquals(Volume.LOUD, run.after(1.minutes).change(WHITELIST)?.volume)
        assertEquals(Volume.NORMAL, run.after(1.minutes).change(NORMAL)?.volume)
        assertEquals(Volume.NORMAL, run.after(1.minutes).change(WHITELIST)?.volume)

        val unstable = run.after(1.minutes).change(NORMAL)
        assertEquals(Notice.Unstable(listOf(NORMAL, WHITELIST), Volume.LOUD), unstable)
        assertTrue(run.state.unstable)

        val update = run.after(1.minutes).change(PAY)
        assertEquals(Notice.Unstable(listOf(NORMAL, WHITELIST, PAY), Volume.SILENT), update)

        // Not yet 15 minutes without a change.
        run.after(14.minutes).check(PAY)
        assertTrue(run.state.unstable)

        val noticesBefore = run.notices.size
        run.after(2.minutes).check(PAY)
        assertFalse(run.state.unstable)
        assertEquals(Notice.Stabilized(PAY, MOBILE), run.notices.drop(noticesBefore).single())
        assertTrue(run.state.transitions.isEmpty())
        assertEquals(PAY, run.state.announced)
    }

    @Test
    fun `transitions older than 30 minutes do not count`() {
        val run = Run(NORMAL)
        run.after(1.minutes).change(WHITELIST)
        run.after(12.minutes).change(NORMAL)
        run.after(12.minutes).change(WHITELIST)
        run.after(12.minutes).change(NORMAL)
        assertFalse(run.state.unstable)
        assertEquals(3, run.state.transitions.size)
    }

    @Test
    fun `a stricter status on a new network type alerts loudly`() {
        // Out of Wi-Fi coverage with the VPN client off.
        val run = Run(LEAK, WIFI)
        assertEquals(Notice.StatusChanged(NORMAL, MOBILE, Volume.LOUD), run.after(1.minutes).change(NORMAL, MOBILE))
        // And back home: a softening.
        assertEquals(Notice.StatusChanged(LEAK, WIFI, Volume.NORMAL), run.after(30.minutes).change(LEAK, WIFI))
    }

    @Test
    fun `a new network type resets the count`() {
        val run = Run(NORMAL, WIFI)
        run.after(1.minutes).change(WHITELIST, WIFI)
        run.after(1.minutes).change(NORMAL, WIFI)
        run.after(1.minutes).change(WHITELIST, WIFI)

        // The fourth change comes with a new network type: no UNSTABLE.
        run.after(1.minutes).change(PAY, MOBILE)
        assertFalse(run.state.unstable)
        assertEquals(1, run.state.transitions.size)
    }

    @Test
    fun `the same status on another network type changes nothing visible`() {
        val run = Run(NORMAL, WIFI)
        val decision = run.after(1.minutes).check(NORMAL, MOBILE)
        assertNull(decision.notice)
        assertTrue(run.notices.isEmpty())

        assertEquals(Volume.LOUD, run.after(1.minutes).change(WHITELIST, MOBILE)?.volume)
    }

    @Test
    fun `a new network type ends UNSTABLE`() {
        val run = Run(NORMAL, WIFI)
        repeat(2) {
            run.after(1.minutes).change(WHITELIST, WIFI)
            run.after(1.minutes).change(NORMAL, WIFI)
        }
        assertTrue(run.state.unstable)

        val decision = run.after(1.minutes).check(NORMAL, MOBILE)
        assertFalse(run.state.unstable)
        assertEquals(Notice.Stabilized(NORMAL, MOBILE), decision.notice)
    }

    @Test
    fun `leak is a softening from any mode and leaving it a tightening`() {
        val run = Run(NO_INTERNET)
        assertEquals(Notice.StatusChanged(LEAK, MOBILE, Volume.NORMAL), run.after(1.minutes).change(LEAK))
        // While it lasts, nothing more.
        assertNull(run.after(3.minutes).check(LEAK).notice)
        assertEquals(Notice.StatusChanged(NORMAL, MOBILE, Volume.LOUD), run.after(3.minutes).change(NORMAL))
    }

    @Test
    fun `a check that never came back still lets the next one confirm`() {
        val run = Run(NORMAL)
        run.after(1.minutes).check(WHITELIST)
        // The recheck was lost (the process was killed); the next regular check confirms.
        val notice = run.after(3.minutes).change(WHITELIST)
        assertEquals(WHITELIST, run.state.confirmed)
        assertEquals(Volume.LOUD, notice?.volume)
        assertEquals(1, run.notices.size)
    }

    @Test
    fun `the state survives serialization`() {
        val run = Run(NORMAL)
        run.after(1.minutes).check(WHITELIST)
        val json = kotlinx.serialization.json.Json.encodeToString(StabilizerState.serializer(), run.state)
        assertEquals(run.state, kotlinx.serialization.json.Json.decodeFromString(StabilizerState.serializer(), json))
    }
}
