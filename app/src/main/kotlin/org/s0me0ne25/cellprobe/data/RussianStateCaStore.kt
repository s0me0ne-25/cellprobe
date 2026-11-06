// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.core.certs.RussianStateCa
import org.s0me0ne25.cellprobe.core.certs.RussianStateCa.Verification
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64

/** How installing the certificates of the Russian state CA went. */
enum class RussianStateCaResult {
    INSTALLED,

    /** gu-st.ru could not be reached or did not answer with a file. */
    DOWNLOAD_FAILED,

    /** No certificate could be read from the files. */
    UNREADABLE,

    /** No certificate has the fingerprint of the root: rejected. */
    WRONG_ROOT,
}

/**
 * The root and intermediate certificates of the Russian state CA (spec
 * §2.3), kept in [file] (in `noBackupFilesDir`) while any entry trusts them.
 * Checked against the pinned fingerprint when installed and again on every
 * read, so a damaged or replaced file is never trusted.
 *
 * @param fetch downloads a URL, throwing [IOException] on failure.
 */
class RussianStateCaStore(
    private val file: File,
    private val fetch: suspend (url: String) -> ByteArray,
) {
    private val mutex = Mutex()

    /** The verified root and intermediates, or null if not installed. */
    suspend fun anchors(): List<X509Certificate>? = mutex.withLock { withContext(Dispatchers.IO) { read() } }

    /** Downloads the root and the intermediate from gu-st.ru, unless installed already. */
    suspend fun install(): RussianStateCaResult = mutex.withLock {
        if (withContext(Dispatchers.IO) { read() } != null) return@withLock RussianStateCaResult.INSTALLED
        val root = try {
            fetch(RussianStateCa.ROOT_URL)
        } catch (_: IOException) {
            return@withLock RussianStateCaResult.DOWNLOAD_FAILED
        }
        // Servers send their intermediate themselves: without it the root still does.
        val sub = try {
            fetch(RussianStateCa.SUB_URL)
        } catch (_: IOException) {
            null
        }
        withContext(Dispatchers.IO) { store(listOfNotNull(root, sub)) }
    }

    /**
     * Installs certificates from files the user picked (spec §2.3, the
     * fallback when gu-st.ru fails). The same checks as for the download;
     * an intermediate alone is taken if the root is installed already.
     */
    suspend fun import(files: List<ByteArray>): RussianStateCaResult = mutex.withLock {
        withContext(Dispatchers.IO) { store(files, keep = read().orEmpty()) }
    }

    /**
     * Replaces the certificates with [certificates] (configuration import),
     * checked like a download; null removes them. False if rejected.
     */
    suspend fun replace(certificates: List<X509Certificate>?): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (certificates == null) {
                file.delete()
                return@withContext true
            }
            val verified = RussianStateCa.verify(certificates) as? Verification.Verified ?: return@withContext false
            try {
                write(verified.all)
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    /** Removes the certificates: no entry trusts them any more. */
    suspend fun delete() = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete() }
        Unit
    }

    private fun read(): List<X509Certificate>? {
        if (!file.exists()) return null
        val certificates = try {
            RussianStateCa.parse(file.readBytes())
        } catch (_: CertificateException) {
            return null
        } catch (_: IOException) {
            return null
        }
        return (RussianStateCa.verify(certificates) as? Verification.Verified)?.all
    }

    private fun store(files: List<ByteArray>, keep: List<X509Certificate> = emptyList()): RussianStateCaResult {
        val parsed = files.flatMap { bytes ->
            try {
                RussianStateCa.parse(bytes)
            } catch (_: CertificateException) {
                emptyList()
            }
        }
        if (parsed.isEmpty()) return RussianStateCaResult.UNREADABLE
        val verified = RussianStateCa.verify(keep + parsed) as? Verification.Verified ?: return RussianStateCaResult.WRONG_ROOT
        return try {
            write(verified.all)
            RussianStateCaResult.INSTALLED
        } catch (_: IOException) {
            RussianStateCaResult.UNREADABLE
        }
    }

    /** Writes a new file and moves it in place, so a crash never leaves half of one. */
    private fun write(certificates: List<X509Certificate>) {
        val pem = certificates.joinToString("") { certificate ->
            "-----BEGIN CERTIFICATE-----\n" + PEM_ENCODER.encodeToString(certificate.encoded) + "\n-----END CERTIFICATE-----\n"
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(pem)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Cannot move ${temporary.name} into place")
        }
    }

    private companion object {
        val PEM_ENCODER: Base64.Encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
    }
}
