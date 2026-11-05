// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.certs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.certs.RussianStateCa.Verification
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The real root and intermediate of the Russian state CA, and a fake
 * hierarchy with the same names but other keys (`src/test/resources/certs`).
 */
class RussianStateCaTest {

    private fun bytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/certs/$name.pem")).readBytes()
    private fun cert(name: String): X509Certificate = RussianStateCa.parse(bytes(name)).single()

    private val root = cert("russian_trusted_root_ca")
    private val sub = cert("russian_trusted_sub_ca")
    private val fakeRoot = cert("fake_root")
    private val fakeSub = cert("fake_sub")
    private val ministryLeaf = cert("ministry_leaf")
    private val otherRoot = cert("other_root")
    private val otherLeaf = cert("other_leaf")

    @Test
    fun `the pinned fingerprint is the real root`() {
        assertEquals(RussianStateCa.ROOT_SHA256, RussianStateCa.sha256(root))
    }

    @Test
    fun `the real root and intermediate are accepted`() {
        val verified = assertInstanceOf(Verification.Verified::class.java, RussianStateCa.verify(listOf(sub, root)))
        assertSame(root, verified.root)
        assertEquals(listOf(sub), verified.intermediates)
    }

    @Test
    fun `a root with the same name but another key is rejected`() {
        assertEquals(Verification.NoRoot, RussianStateCa.verify(listOf(fakeRoot, fakeSub)))
        assertEquals(Verification.NoRoot, RussianStateCa.verify(listOf(sub)))
    }

    @Test
    fun `an intermediate not signed by the real root is dropped`() {
        val verified = assertInstanceOf(Verification.Verified::class.java, RussianStateCa.verify(listOf(root, fakeSub, ministryLeaf)))
        assertEquals(emptyList<X509Certificate>(), verified.intermediates)
    }

    @Test
    fun `PEM, DER and several certificates in one file are read`() {
        assertEquals(2, RussianStateCa.parse(bytes("russian_trusted_root_ca") + bytes("russian_trusted_sub_ca")).size)
        val der = Base64.getMimeDecoder().decode(
            bytes("russian_trusted_root_ca").decodeToString().lines().filterNot { it.startsWith("-----") }.joinToString(""),
        )
        assertEquals(RussianStateCa.ROOT_SHA256, RussianStateCa.sha256(RussianStateCa.parse(der).single()))
    }

    @Test
    fun `garbage is not a certificate`() {
        assertThrows(CertificateException::class.java) { RussianStateCa.parse("<html>404</html>".encodeToByteArray()) }
    }

    @Test
    fun `certificates are recognized by the ministry in the issuer`() {
        assertTrue(RussianStateCa.issued(listOf(sub)))
        assertTrue(RussianStateCa.issued(listOf(ministryLeaf, fakeSub)))
        assertFalse(RussianStateCa.issued(listOf(otherLeaf)))
        assertFalse(RussianStateCa.issued(listOf(otherRoot)))
    }

    @Test
    fun `escaped commas in names do not confuse the parser`() {
        val attributes = DistinguishedNames.attributes("""CN=A\, B,O=The Ministry,C=RU""")
        assertEquals("A, B", attributes["CN"])
        assertEquals("The Ministry", attributes["O"])
        assertEquals("RU", attributes["C"])
    }

    private fun trustManager(vararg anchors: X509Certificate): X509TrustManager {
        val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null, null)
            anchors.forEachIndexed { index, certificate -> setCertificateEntry("a$index", certificate) }
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    @Test
    fun `the detector marks rejected chains of the Russian state CA`() {
        val detector = RussianStateCaDetector(trustManager(otherRoot))
        assertThrows(RussianStateCertificateException::class.java) {
            detector.checkServerTrusted(arrayOf(ministryLeaf, fakeSub), "RSA")
        }
        // Other rejected chains stay ordinary certificate errors.
        val otherError = assertThrows(CertificateException::class.java) {
            RussianStateCaDetector(trustManager(fakeRoot)).checkServerTrusted(arrayOf(otherLeaf), "RSA")
        }
        assertFalse(otherError is RussianStateCertificateException)
        // Trusted chains pass.
        detector.checkServerTrusted(arrayOf(otherLeaf), "RSA")
    }

    @Test
    fun `extra anchors are trusted alongside the system ones`() {
        val trust = TrustAlsoAnchors(trustManager(otherRoot), listOf(fakeRoot))
        trust.checkServerTrusted(arrayOf(ministryLeaf, fakeSub), "RSA")
        trust.checkServerTrusted(arrayOf(otherLeaf), "RSA")
        assertTrue(trust.acceptedIssuers.contains(fakeRoot))
        assertTrue(trust.acceptedIssuers.contains(otherRoot))

        // Without the extra anchor the same chain is rejected.
        assertThrows(CertificateException::class.java) {
            TrustAlsoAnchors(trustManager(otherRoot), listOf(root)).checkServerTrusted(arrayOf(ministryLeaf, fakeSub), "RSA")
        }
    }
}
