// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.s0me0ne25.cellprobe.core.certs.RussianStateCa
import java.io.File
import java.io.IOException

class RussianStateCaStoreTest {

    @TempDir
    lateinit var directory: File

    private val file get() = File(directory, "ca.pem")

    private fun bytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/certs/$name.pem")).readBytes()

    private val official = mapOf(
        RussianStateCa.ROOT_URL to bytes("russian_trusted_root_ca"),
        RussianStateCa.SUB_URL to bytes("russian_trusted_sub_ca"),
    )

    private fun store(files: Map<String, ByteArray> = official) =
        RussianStateCaStore(file) { url -> files[url] ?: throw IOException("404") }

    @Test
    fun `download installs the root and the intermediate`() = runTest {
        val store = store()
        assertNull(store.anchors())
        assertEquals(RussianStateCaResult.INSTALLED, store.install())
        assertEquals(2, store.anchors()?.size)
        assertEquals(RussianStateCa.ROOT_SHA256, RussianStateCa.sha256(store.anchors()!!.first()))
    }

    @Test
    fun `the root alone is enough`() = runTest {
        val store = store(mapOf(RussianStateCa.ROOT_URL to bytes("russian_trusted_root_ca")))
        assertEquals(RussianStateCaResult.INSTALLED, store.install())
        assertEquals(1, store.anchors()?.size)
    }

    @Test
    fun `a root with another fingerprint is rejected`() = runTest {
        val store = store(mapOf(RussianStateCa.ROOT_URL to bytes("fake_root")))
        assertEquals(RussianStateCaResult.WRONG_ROOT, store.install())
        assertFalse(file.exists())
    }

    @Test
    fun `no answer and no certificate are told apart`() = runTest {
        assertEquals(RussianStateCaResult.DOWNLOAD_FAILED, store(emptyMap()).install())
        val html = mapOf(RussianStateCa.ROOT_URL to "<html>Not found</html>".encodeToByteArray())
        assertEquals(RussianStateCaResult.UNREADABLE, store(html).install())
    }

    @Test
    fun `picked files go through the same checks`() = runTest {
        val store = store(emptyMap())
        assertEquals(RussianStateCaResult.WRONG_ROOT, store.import(listOf(bytes("russian_trusted_sub_ca"))))
        assertEquals(RussianStateCaResult.INSTALLED, store.import(listOf(bytes("russian_trusted_root_ca"))))
        // With the root installed, the intermediate alone is taken.
        assertEquals(RussianStateCaResult.INSTALLED, store.import(listOf(bytes("russian_trusted_sub_ca"))))
        assertEquals(2, store.anchors()?.size)
    }

    @Test
    fun `a tampered file is not trusted`() = runTest {
        val store = store()
        store.install()
        file.writeBytes(bytes("fake_root"))
        assertNull(store.anchors())
    }

    @Test
    fun `delete removes the file`() = runTest {
        val store = store()
        store.install()
        store.delete()
        assertFalse(file.exists())
        assertNull(store.anchors())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
