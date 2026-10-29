// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import okhttp3.Dns
import org.s0me0ne25.cellprobe.core.net.Hosts
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * Resolves the inbound's SNI to the proxy server, so that the request for
 * `https://<sni>/` goes to the proxy with that SNI. Any other name fails:
 * a request must never reach a real site through the normal DNS.
 *
 * OkHttp resolves the name once per new connection, so only the first
 * lookup succeeds; a second one, made when the server closed the first
 * connection, throws [SecondConnectionException] before anything is sent.
 *
 * @param sni canonical SNI of the inbound.
 * @param proxyHost canonical host of the proxy: an IP literal or a domain
 *   resolved through [system] on every probe.
 */
internal class InboundDns(
    private val sni: String,
    private val proxyHost: String,
    private val system: Dns = Dns.SYSTEM,
) : Dns {

    private val resolved = AtomicBoolean(false)

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(sni, ignoreCase = true)) throw UnknownHostException("Not the inbound's SNI: $hostname")
        if (!resolved.compareAndSet(false, true)) throw SecondConnectionException()
        return if (Hosts.isIpLiteral(proxyHost)) {
            // A literal is parsed, not looked up.
            listOf(InetAddress.getByName(proxyHost.removeSurrounding("[", "]")))
        } else {
            system.lookup(proxyHost)
        }
    }
}

/** A probe tried to open a second connection to the inbound. */
internal class SecondConnectionException : IOException("The inbound probe allows one connection only")

/**
 * Creates plain sockets that count every byte received on them, TLS
 * handshake and protocol framing included: the volume a regulatory
 * firewall sees on the connection.
 *
 * The TLS socket must do its I/O through the wrapped socket's streams for
 * the count to work (see [TlsSetup.streamSocketFactory]).
 */
internal class CountingSocketFactory : SocketFactory() {

    private val received = AtomicLong()

    /** Bytes received on all sockets created by this factory. */
    val receivedBytes: Long
        get() = received.get()

    override fun createSocket(): Socket = CountingSocket(received)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

private class CountingSocket(private val counter: AtomicLong) : Socket() {

    private var countingInput: InputStream? = null

    @Synchronized
    override fun getInputStream(): InputStream =
        countingInput ?: CountingInputStream(super.getInputStream(), counter).also { countingInput = it }
}

private class CountingInputStream(input: InputStream, private val counter: AtomicLong) : FilterInputStream(input) {

    override fun read(): Int = super.read().also { if (it >= 0) counter.incrementAndGet() }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) counter.addAndGet(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) counter.addAndGet(it) }
}
