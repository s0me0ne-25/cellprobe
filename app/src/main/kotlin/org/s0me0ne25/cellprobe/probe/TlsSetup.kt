// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import org.conscrypt.Conscrypt
import org.s0me0ne25.cellprobe.core.certs.RussianStateCaDetector
import org.s0me0ne25.cellprobe.core.certs.TrustAlsoAnchors
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** TLS socket factories and the trust manager that validates their peers. */
class TlsSetup(
    val socketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
    /**
     * Creates TLS sockets that do all I/O through the streams of the plain
     * socket they wrap, so that bytes can be counted there (VLESS inbounds).
     */
    val streamSocketFactory: SSLSocketFactory = socketFactory,
    /** Socket factories on the same TLS stack for another trust manager. */
    private val factoryFor: (X509TrustManager) -> SSLSocketFactory = { trust ->
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
    },
) {
    /**
     * The same TLS, trusting [anchors] besides: the Russian state CA for one
     * entry that allows it (spec §2.3). Built anew for every such request.
     */
    fun trustingAlso(anchors: List<X509Certificate>): TlsSetup {
        val trust = TrustAlsoAnchors(systemTrust(), anchors)
        return TlsSetup(factoryFor(trust), trust, factoryFor = factoryFor)
    }

    /** The trust manager without the detector of the Russian state CA. */
    private fun systemTrust(): X509TrustManager = (trustManager as? RussianStateCaDetector)?.system ?: trustManager

    companion object {

        /**
         * Handshakes run on the bundled Conscrypt (BoringSSL): TLS 1.3 and the
         * same behaviour on every Android version. Certificates are checked by
         * the platform trust manager, which honours network_security_config
         * (system roots plus the bundled extra roots).
         *
         * Conscrypt is deliberately not installed as a global security
         * provider: that would also replace the platform's trust manager
         * factory and bypass network_security_config.
         *
         * A chain the platform rejects that comes from the Russian state CA
         * is reported as such ([RussianStateCaDetector]): CERT_RU_CA.
         *
         * By default Conscrypt layers TLS over a plain socket by reading its
         * file descriptor directly. The stream factory uses Conscrypt's engine
         * socket instead, which reads and writes the plain socket's streams.
         */
        fun conscrypt(): TlsSetup {
            val trustManager = RussianStateCaDetector(platformTrustManager())
            val provider = Conscrypt.newProvider()
            fun newFactory(trust: X509TrustManager): SSLSocketFactory = SSLContext.getInstance("TLS", provider)
                .apply { init(null, arrayOf(trust), null) }
                .socketFactory
            val streamFactory = newFactory(trustManager).also { Conscrypt.setUseEngineSocket(it, true) }
            return TlsSetup(newFactory(trustManager), trustManager, streamFactory, ::newFactory)
        }

        private fun platformTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
