// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkStatusTest {

    @Test
    fun `whitelist is stricter than normal`() {
        assertTrue(NetworkStatus.WHITELIST.isStricterThan(NetworkStatus.NORMAL))
        assertFalse(NetworkStatus.NORMAL.isStricterThan(NetworkStatus.WHITELIST))
    }

    @Test
    fun `pay and captive portal are equally strict`() {
        assertFalse(NetworkStatus.PAY.isStricterThan(NetworkStatus.AUTH_REQUIRED))
        assertFalse(NetworkStatus.AUTH_REQUIRED.isStricterThan(NetworkStatus.PAY))
    }

    @Test
    fun `statuses outside the scale are never compared`() {
        for (status in NetworkStatus.entries) {
            assertFalse(NetworkStatus.LEAK.isStricterThan(status))
            assertFalse(status.isStricterThan(NetworkStatus.USER_OFFLINE))
        }
    }
}
