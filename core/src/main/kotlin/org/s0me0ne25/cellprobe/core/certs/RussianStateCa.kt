// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.certs

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * The Russian state CA, "УЦ Минцифры" (spec §2.3): recognizing the
 * certificates it issued, and checking downloaded copies of its root and
 * intermediate certificates.
 */
object RussianStateCa {

    /**
     * SHA-256 of the DER encoding of "Russian Trusted Root CA" (RSA 4096,
     * serial 0x1000, valid 2022-03-01 to 2032-02-27). Checked against three
     * independent copies: the file on gu-st.ru linked from gosuslugi.ru/crt,
     * a copy published on GitHub in 2022 (cocaman/russian_root_ca, byte for
     * byte the same), and the pin of the Ruthenium browser.
     */
    const val ROOT_SHA256 = "d26d2d0231b7c39f92cc738512ba54103519e4405d68b5bd703e9788ca8ecf31"

    /** Official copies, linked from gosuslugi.ru/crt. RSA only: BoringSSL has no GOST. */
    const val ROOT_URL = "https://gu-st.ru/content/lending/russian_trusted_root_ca_pem.crt"
    const val SUB_URL = "https://gu-st.ru/content/lending/russian_trusted_sub_ca_pem.crt"

    private const val COUNTRY = "RU"
    private const val ORGANIZATION = "The Ministry of Digital Development and Communications"

    /** Whether any certificate of [chain] was issued by the Russian state CA. */
    fun issued(chain: List<X509Certificate>): Boolean = chain.any { isCa(it.issuerX500Principal) }

    /**
     * Whether [principal] names the Russian state CA: its root or any of its
     * intermediates, which all carry the name of the ministry.
     */
    fun isCa(principal: X500Principal): Boolean {
        val attributes = DistinguishedNames.attributes(principal.getName(X500Principal.RFC2253))
        return attributes["C"] == COUNTRY && attributes["O"] == ORGANIZATION
    }

    /** Certificates in [bytes], PEM or DER, one or several. */
    fun parse(bytes: ByteArray): List<X509Certificate> =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
            .filterIsInstance<X509Certificate>()

    fun sha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    /**
     * Accepts the root of [certificates] if its fingerprint is [ROOT_SHA256],
     * and the intermediates the root has signed. Anything else is dropped.
     */
    fun verify(certificates: List<X509Certificate>): Verification {
        val root = certificates.firstOrNull { sha256(it) == ROOT_SHA256 } ?: return Verification.NoRoot
        val intermediates = certificates
            .filter { it != root && it.issuerX500Principal == root.subjectX500Principal && it.basicConstraints >= 0 }
            .filter { signedBy(it, root) }
            .distinctBy(::sha256)
        return Verification.Verified(root, intermediates)
    }

    private fun signedBy(certificate: X509Certificate, issuer: X509Certificate): Boolean = try {
        certificate.verify(issuer.publicKey)
        true
    } catch (_: java.security.GeneralSecurityException) {
        false
    }

    sealed interface Verification {
        /** The root, and the intermediates it has signed (possibly none). */
        data class Verified(val root: X509Certificate, val intermediates: List<X509Certificate>) : Verification {
            val all: List<X509Certificate> get() = listOf(root) + intermediates
        }

        /** No certificate has the fingerprint of the root. */
        data object NoRoot : Verification
    }
}

/**
 * A certificate chain was rejected, and it comes from the Russian state CA:
 * the probe result is CERT_RU_CA, not CERT_INVALID.
 */
class RussianStateCertificateException(cause: CertificateException) :
    CertificateException("Issued by the Russian state CA", cause)

/** Splitting RFC 2253 names; javax.naming is not available on Android. */
internal object DistinguishedNames {

    /** Attribute types to values, e.g. `O` to the organization; on repeated types, the first. */
    fun attributes(rfc2253: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (rdn in split(rfc2253, ',')) {
            for (attribute in split(rdn, '+')) {
                val equals = attribute.indexOf('=')
                if (equals <= 0) continue
                val type = attribute.substring(0, equals).trim().uppercase()
                result.putIfAbsent(type, unescape(attribute.substring(equals + 1).trim()))
            }
        }
        return result
    }

    /** Splits on [separator] not escaped with a backslash. */
    private fun split(value: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (char in value) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> {
                    current.append(char)
                    escaped = true
                }
                char == separator -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        parts += current.toString()
        return parts
    }

    private fun unescape(value: String): String {
        val result = StringBuilder()
        var escaped = false
        for (char in value) {
            if (escaped) {
                result.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else {
                result.append(char)
            }
        }
        return result.toString()
    }
}
