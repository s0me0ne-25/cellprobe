// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import okhttp3.Call
import okhttp3.CipherSuite
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.TlsVersion
import org.s0me0ne25.cellprobe.core.probe.ProbeFailure
import org.s0me0ne25.cellprobe.core.probe.ProbePhase
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Timeouts of a single probe (spec: connect 5 s, read 5 s, whole request 10 s). */
data class ProbeTimeouts(
    val connect: Duration = 5.seconds,
    val read: Duration = 5.seconds,
    val call: Duration = 10.seconds,
)

/**
 * Creates one HTTP client per check cycle: every cycle starts from scratch,
 * with no pooled connections, no cache, no redirects and no retries.
 */
class ProbeClientFactory(
    private val tls: () -> TlsSetup,
    private val dns: Dns = Dns.SYSTEM,
    private val timeouts: ProbeTimeouts = ProbeTimeouts(),
) {

    fun create(): OkHttpClient {
        val tlsSetup = tls()
        return OkHttpClient.Builder()
            // Probes must go out directly, never through a system HTTP proxy.
            .proxy(Proxy.NO_PROXY)
            .dns(dns)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // Addresses of a host are tried one after another, not raced:
            // the phase of each attempt must be known to classify its failure.
            .fastFallback(false)
            .connectTimeout(timeouts.connect)
            .readTimeout(timeouts.read)
            .writeTimeout(timeouts.read)
            .callTimeout(timeouts.call)
            .connectionSpecs(listOf(CHROME_LIKE_TLS))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .sslSocketFactory(tlsSetup.socketFactory, tlsSetup.trustManager)
            .eventListenerFactory(PhaseTracking)
            .build()
    }

    /**
     * Derives the client for one VLESS inbound from the cycle's [base] client:
     * [dns] maps the SNI to the proxy, [sockets] counts received bytes, and
     * one connection is kept alive so that all requests of the volume check
     * share it.
     */
    fun inboundClient(base: OkHttpClient, dns: Dns, sockets: SocketFactory): OkHttpClient {
        val tlsSetup = tls()
        return base.newBuilder()
            .dns(dns)
            .socketFactory(sockets)
            .sslSocketFactory(tlsSetup.streamSocketFactory, tlsSetup.trustManager)
            .connectionPool(ConnectionPool(1, INBOUND_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            .build()
    }

    companion object {
        private const val INBOUND_KEEP_ALIVE_SECONDS = 30L

        /** TLS versions and cipher suites offered by Chrome for Android. */
        val CHROME_LIKE_TLS: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(
                CipherSuite.TLS_AES_128_GCM_SHA256,
                CipherSuite.TLS_AES_256_GCM_SHA384,
                CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
            )
            .build()
    }
}

/**
 * Mutable phase of one probe and its failed connection attempts, attached
 * to its requests as a tag.
 */
internal class PhaseHolder {
    @Volatile
    var phase: ProbePhase = ProbePhase.DNS

    private val connectFailures = CopyOnWriteArrayList<ProbeFailure>()

    fun connectFailed(error: IOException) {
        connectFailures += ProbeFailure(phase, error)
    }

    /** Failures of every connection attempt, then the call's own [callError]. */
    fun failures(callError: Throwable): List<ProbeFailure> = connectFailures + ProbeFailure(phase, callError)
}

/** Follows the connection phases of calls tagged with a [PhaseHolder]. */
internal object PhaseTracking : EventListener.Factory {

    override fun create(call: Call): EventListener {
        val holder = call.request().tag(PhaseHolder::class) ?: return EventListener.NONE
        return object : EventListener() {
            override fun dnsStart(call: Call, domainName: String) {
                holder.phase = ProbePhase.DNS
            }

            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                holder.phase = ProbePhase.CONNECT
            }

            override fun secureConnectStart(call: Call) {
                holder.phase = ProbePhase.TLS
            }

            override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                holder.phase = ProbePhase.REQUEST
            }

            override fun connectionAcquired(call: Call, connection: Connection) {
                holder.phase = ProbePhase.REQUEST
            }

            override fun responseHeadersEnd(call: Call, response: Response) {
                holder.phase = ProbePhase.BODY
            }

            override fun connectFailed(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?,
                ioe: IOException,
            ) {
                holder.connectFailed(ioe)
            }
        }
    }
}
