// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import okhttp3.Dns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import kotlin.concurrent.thread

class InboundSocketsTest {

    private val proxyAddress = InetAddress.getByName("192.0.2.10")

    @Test
    fun `SNI resolves to an IPv4 proxy without a lookup`() {
        val dns = InboundDns(sni = "www.example.com", proxyHost = "192.0.2.10", system = failingDns)
        assertEquals(listOf(proxyAddress), dns.lookup("www.example.com"))
    }

    @Test
    fun `SNI resolves to an IPv6 proxy`() {
        val dns = InboundDns(sni = "www.example.com", proxyHost = "[2001:db8::1]", system = failingDns)
        assertEquals(listOf(InetAddress.getByName("2001:db8::1")), dns.lookup("www.example.com"))
    }

    @Test
    fun `proxy domain is resolved through the system DNS`() {
        val system = Dns { host -> if (host == "proxy.example.net") listOf(proxyAddress) else throw UnknownHostException(host) }
        val dns = InboundDns(sni = "www.example.com", proxyHost = "proxy.example.net", system = system)
        assertEquals(listOf(proxyAddress), dns.lookup("www.example.com"))
    }

    @Test
    fun `other names never resolve`() {
        val dns = InboundDns(sni = "www.example.com", proxyHost = "192.0.2.10", system = Dns.SYSTEM)
        assertThrows<UnknownHostException> { dns.lookup("example.com") }
        assertThrows<UnknownHostException> { dns.lookup("localhost") }
    }

    @Test
    fun `only the first connection may resolve the SNI`() {
        val dns = InboundDns(sni = "www.example.com", proxyHost = "192.0.2.10", system = failingDns)
        dns.lookup("www.example.com")
        assertThrows<SecondConnectionException> { dns.lookup("www.example.com") }
    }

    @Test
    fun `received bytes are counted on the socket`() {
        val payload = ByteArray(10_000) { it.toByte() }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val writer = thread {
                server.accept().use { peer ->
                    peer.getOutputStream().write(payload)
                    peer.getOutputStream().write(payload, 0, 1)
                }
            }
            val sockets = CountingSocketFactory()
            sockets.createSocket().use { socket ->
                socket.connect(InetSocketAddress(server.inetAddress, server.localPort))
                val input = socket.getInputStream()
                input.read()
                input.readNBytes(5_000)
                input.readAllBytes()
            }
            writer.join()

            assertEquals(payload.size + 1L, sockets.receivedBytes)
        }
    }

    private val failingDns = Dns { host -> throw UnknownHostException("unexpected lookup of $host") }
}
