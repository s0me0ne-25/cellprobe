// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.EntryResult
import org.s0me0ne25.cellprobe.core.probe.ListProbeResult
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome

class NetworkClassifierTest {

    private val wifi = NetworkState(activeNetwork = NetworkType.WIFI)

    /** `+` reachable, `-` unreachable, `0` empty list. */
    private fun lists(domestic: Char, foreign: Char, vless: Char, banned: Char, provider: Char) = ListAvailability(
        domestic = domestic == '+',
        foreign = foreign == '+',
        vless = vless.orEmpty(),
        banned = banned == '+',
        provider = provider.orEmpty(),
    )

    private fun Char.orEmpty(): Boolean? = if (this == '0') null else this == '+'

    @ParameterizedTest(name = "{0}{1}{2}{3}{4} -> {5}")
    @CsvSource(
        // 1, 2, 3 (VLESS), 4, provider pages -> status
        "+, +, +, +, +, LEAK",
        "-, -, -, +, -, LEAK",
        "-, -, 0, +, 0, LEAK",
        "+, +, +, -, +, NORMAL",
        "-, -, +, -, -, NORMAL",
        "+, -, +, -, -, NORMAL",
        "+, +, -, -, +, VLESS_BLOCKED",
        "-, +, -, -, -, VLESS_BLOCKED",
        "+, +, 0, -, +, NORMAL_NO_VLESS",
        "-, +, 0, -, 0, NORMAL_NO_VLESS",
        "+, -, -, -, +, WHITELIST",
        "+, -, 0, -, 0, WHITELIST",
        "+, -, -, -, -, WHITELIST",
        "-, -, -, -, +, PAY",
        "-, -, 0, -, +, PAY",
        "-, -, -, -, -, NO_INTERNET",
        "-, -, 0, -, 0, NO_INTERNET",
        "-, -, -, -, 0, NO_INTERNET",
    )
    fun `ladder of the spec`(domestic: Char, foreign: Char, vless: Char, banned: Char, provider: Char, expected: NetworkStatus) {
        assertEquals(expected, NetworkClassifier.classify(wifi, lists(domestic, foreign, vless, banned, provider)))
    }

    @Test
    fun `every combination follows the ladder invariants`() {
        val values = listOf('+', '-')
        val optional = listOf('+', '-', '0')
        for (d in values) for (f in values) for (v in optional) for (b in values) for (p in optional) {
            val status = NetworkClassifier.classify(wifi, lists(d, f, v, b, p))
            val case = "$d$f$v$b$p -> $status"
            assertEquals(b == '+', status == NetworkStatus.LEAK, case)
            if (b == '-') {
                assertEquals(v == '+', status == NetworkStatus.NORMAL, case)
                assertTrue(status != NetworkStatus.PAY || (p == '+' && d == '-' && f == '-' && v != '+'), case)
                assertTrue(status != NetworkStatus.NORMAL_NO_VLESS || v == '0', case)
                assertTrue(status != NetworkStatus.VLESS_BLOCKED || v == '-', case)
            }
            assertFalse(status in setOf(NetworkStatus.USER_OFFLINE, NetworkStatus.AUTH_REQUIRED), case)
        }
    }

    @Test
    fun `captive portal wins and needs no probes`() {
        val portal = wifi.copy(captivePortal = true)
        assertFalse(NetworkClassifier.needsProbes(portal))
        assertEquals(NetworkStatus.AUTH_REQUIRED, NetworkClassifier.classify(portal, null))
        assertEquals(NetworkStatus.AUTH_REQUIRED, NetworkClassifier.classify(portal, lists('+', '+', '+', '+', '+')))
    }

    @Test
    fun `no network with airplane mode or data off is user offline`() {
        val none = NetworkState(activeNetwork = null)
        assertFalse(NetworkClassifier.needsProbes(none))
        assertEquals(NetworkStatus.USER_OFFLINE, NetworkClassifier.classify(none.copy(airplaneMode = true), null))
        assertEquals(NetworkStatus.USER_OFFLINE, NetworkClassifier.classify(none.copy(mobileDataEnabled = false), null))
    }

    @Test
    fun `no network with data on is no internet for now`() {
        assertEquals(NetworkStatus.NO_INTERNET, NetworkClassifier.classify(NetworkState(activeNetwork = null), null))
    }

    @Test
    fun `wifi in airplane mode is probed as usual`() {
        val state = wifi.copy(airplaneMode = true, mobileDataEnabled = false)
        assertTrue(NetworkClassifier.needsProbes(state))
        assertEquals(NetworkStatus.WHITELIST, NetworkClassifier.classify(state, lists('+', '-', '-', '-', '0')))
    }

    @Test
    fun `active network without list results is a programming error`() {
        assertThrows<IllegalArgumentException> { NetworkClassifier.classify(wifi, null) }
    }

    @Test
    fun `availability is built from list results`() {
        fun result(id: CheckListId, vararg codes: ProbeCode) =
            ListProbeResult(id, codes.map { EntryResult(Unit, ProbeOutcome(it, 0)) }, retried = false)

        val availability = ListAvailability.of(
            listOf(
                result(CheckListId.DOMESTIC, ProbeCode.OK, ProbeCode.SKIPPED),
                result(CheckListId.FOREIGN, ProbeCode.TLS_FAIL),
                result(CheckListId.VLESS),
                result(CheckListId.BANNED, ProbeCode.TCP_RESET),
                result(CheckListId.PROVIDER, ProbeCode.OK_UNVERIFIED),
            ),
        )

        assertEquals(ListAvailability(domestic = true, foreign = false, vless = null, banned = false, provider = true), availability)
        assertNull(ListAvailability.of(emptyList()).vless)
    }
}
