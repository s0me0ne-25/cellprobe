// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import org.conscrypt.Conscrypt
import java.security.KeyStore
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
) {
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
         * By default Conscrypt layers TLS over a plain socket by reading its
         * file descriptor directly. The stream factory uses Conscrypt's engine
         * socket instead, which reads and writes the plain socket's streams.
         */
        fun conscrypt(): TlsSetup {
            val trustManager = platformTrustManager()
            val provider = Conscrypt.newProvider()
            fun newFactory(): SSLSocketFactory = SSLContext.getInstance("TLS", provider)
                .apply { init(null, arrayOf(trustManager), null) }
                .socketFactory
            val streamFactory = newFactory().also { Conscrypt.setUseEngineSocket(it, true) }
            return TlsSetup(newFactory(), trustManager, streamFactory)
        }

        private fun platformTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
