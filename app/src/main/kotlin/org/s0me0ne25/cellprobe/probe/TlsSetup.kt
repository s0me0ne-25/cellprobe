// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import org.conscrypt.Conscrypt
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** TLS socket factory and the trust manager that validates its peers. */
class TlsSetup(
    val socketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
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
         */
        fun conscrypt(): TlsSetup {
            val trustManager = platformTrustManager()
            val context = SSLContext.getInstance("TLS", Conscrypt.newProvider())
            context.init(null, arrayOf(trustManager), null)
            return TlsSetup(context.socketFactory, trustManager)
        }

        private fun platformTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
