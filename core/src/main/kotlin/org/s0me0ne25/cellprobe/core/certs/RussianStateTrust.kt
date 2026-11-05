// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.certs

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * A trust manager that passes the checks on to [delegate], with the
 * socket or engine when [delegate] takes them: on Android the platform
 * trust manager uses them for the host-specific network security config.
 */
abstract class DelegatingTrustManager(protected val delegate: X509TrustManager) : X509ExtendedTrustManager() {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        (delegate as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, socket)
            ?: delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        (delegate as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, engine)
            ?: delegate.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    protected fun X509TrustManager.checkServer(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val extended = this as? X509ExtendedTrustManager
        if (extended != null && socket != null) extended.checkServerTrusted(chain, authType, socket) else checkServerTrusted(chain, authType)
    }

    protected fun X509TrustManager.checkServer(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        val extended = this as? X509ExtendedTrustManager
        if (extended != null && engine != null) extended.checkServerTrusted(chain, authType, engine) else checkServerTrusted(chain, authType)
    }
}

/**
 * The trust manager of every probe: [delegate] decides, and a chain it
 * rejects that comes from the Russian state CA fails with
 * [RussianStateCertificateException], so the probe says CERT_RU_CA.
 */
class RussianStateCaDetector(delegate: X509TrustManager) : DelegatingTrustManager(delegate) {

    /** The trust manager that decides. */
    val system: X509TrustManager get() = delegate

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        detect(chain) { delegate.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        detect(chain) { delegate.checkServer(chain, authType, socket) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        detect(chain) { delegate.checkServer(chain, authType, engine) }

    private inline fun detect(chain: Array<X509Certificate>, check: () -> Unit) {
        try {
            check()
        } catch (e: CertificateException) {
            if (RussianStateCa.issued(chain.toList())) throw RussianStateCertificateException(e)
            throw e
        }
    }
}

/**
 * Trusts what [system] trusts and, for the one entry it is built for,
 * chains to [anchors] as well: the Russian state CA (spec §2.3). A chain
 * that fails both is rejected with the error of the second check.
 */
class TrustAlsoAnchors(system: X509TrustManager, anchors: List<X509Certificate>) : DelegatingTrustManager(system) {

    private val extra: X509TrustManager = run {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            anchors.forEachIndexed { index, certificate -> setCertificateEntry("anchor$index", certificate) }
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        either({ delegate.checkServerTrusted(chain, authType) }, { extra.checkServerTrusted(chain, authType) })

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        either({ delegate.checkServer(chain, authType, socket) }, { extra.checkServer(chain, authType, socket) })

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        either({ delegate.checkServer(chain, authType, engine) }, { extra.checkServer(chain, authType, engine) })

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers + extra.acceptedIssuers

    private inline fun either(first: () -> Unit, second: () -> Unit) {
        try {
            first()
        } catch (_: CertificateException) {
            second()
        }
    }
}
