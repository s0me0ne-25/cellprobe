// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.s0me0ne25.cellprobe.core.certs.RussianStateCa
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import org.s0me0ne25.cellprobe.core.monitoring.MonitoringSettings
import java.io.File
import java.io.IOException

/** Export from one set of stores, import into a fresh one: a reinstall. */
class ConfigTransferTest {

    @TempDir
    lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun stop() = scope.cancel()

    private var imported: AppSettings? = null

    private inner class Device(name: String) {
        val dir = File(directory, name).apply { mkdirs() }
        val settings = SettingsRepository(
            DataStoreFactory.create(AppSettingsSerializer, scope = scope) { File(dir, "settings.json") },
        )
        val traffic = TrafficStore(DataStoreFactory.create(TrafficTotalsSerializer, scope = scope) { File(dir, "traffic.json") })
        val ca = RussianStateCaStore(File(dir, "ca.pem")) { url ->
            val name = if (url == RussianStateCa.ROOT_URL) "russian_trusted_root_ca" else "russian_trusted_sub_ca"
            javaClass.getResourceAsStream("/certs/$name.pem")?.readBytes() ?: throw IOException()
        }
        val transfer = ConfigTransfer(settings, traffic, ca, "0.0.1") { imported = it }
    }

    @Test
    fun `a fresh install gets everything back`() = runBlocking {
        val old = Device("old")
        old.settings.replace(AppSettings(monitoring = MonitoringSettings(enabled = true), detailedJournal = true))
        old.traffic.replace(TrafficTotals(day = "2026-11-06", dayBytes = 10, month = "2026-11", monthBytes = 100))
        old.ca.install()
        val text = old.transfer.export()

        val new = Device("new")
        assertNull(new.transfer.import(text))

        assertEquals(old.settings.snapshot(), new.settings.snapshot())
        assertEquals(old.traffic.snapshot(), new.traffic.snapshot())
        assertEquals(2, new.ca.anchors()?.size)
        assertEquals(true, imported?.monitoring?.enabled)
    }

    @Test
    fun `certificates the old phone did not have are removed`() = runBlocking {
        val text = Device("old").transfer.export()

        val new = Device("new")
        new.ca.install()
        assertNull(new.transfer.import(text))
        assertNull(new.ca.anchors())
    }

    @Test
    fun `a broken text changes nothing`() = runBlocking {
        val device = Device("one")
        device.settings.replace(AppSettings(detailedJournal = true))
        val result = device.transfer.import("""{"format": "cellprobe-config", "version": 1, "settings": {"detailedJournal": "yes"}}""")
        assertInstanceOf(ConfigImportError.Invalid::class.java, result)
        assertEquals(true, device.settings.snapshot().detailedJournal)
        assertNull(imported)
    }
}
