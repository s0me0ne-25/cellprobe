// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import org.s0me0ne25.cellprobe.core.net.Hosts
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Rules of the VLESS inbound check (spec §5.2, §5.3).
 *
 * The probe connects to the proxy with the inbound's SNI and must collect
 * [TARGET_BYTES] in one TLS connection: regulatory firewalls are observed to
 * cut a connection off after ~16–20 KB, so a smaller transfer proves nothing.
 */
object VlessProbeRules {

    /** Bytes received on the socket, handshake included, that rule out a cut-off. */
    const val TARGET_BYTES: Long = 32L * 1024

    /** Same-origin redirects followed from the first request. */
    const val MAX_REDIRECTS = 5

    /** Extra GETs of the final address when the responses were too small. */
    const val MAX_REPEATS = 8

    /** Time limit of the whole probe, connection included. */
    val TIME_BUDGET: Duration = 10.seconds

    /**
     * Resolves a redirect [location] against [current] and returns the target
     * if it may be followed: `https`, the inbound's [sni] as host and the same
     * [port]. A redirect to another host is never followed: that request
     * would resolve through the normal DNS and reach the real site directly,
     * past the proxy, and report a false success.
     */
    fun redirectTarget(current: String, location: String, sni: String, port: Int): String? {
        val target = try {
            URI(current).resolve(location.trim())
        } catch (_: URISyntaxException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!target.scheme.equals("https", ignoreCase = true) || target.rawUserInfo != null) return null
        val host = target.host?.let(Hosts::normalize) ?: return null
        val targetPort = if (target.port == -1) DEFAULT_HTTPS_PORT else target.port
        if (host != sni || targetPort != port) return null
        val path = target.rawPath.orEmpty().ifEmpty { "/" }
        val query = target.rawQuery?.let { "?$it" }.orEmpty()
        return "https://${target.rawAuthority}$path$query"
    }

    /** Code when the transfer ended without an error. */
    fun completedCode(receivedBytes: Long): ProbeCode =
        if (receivedBytes >= TARGET_BYTES) ProbeCode.OK else ProbeCode.OK_UNVERIFIED

    /**
     * The failure that decides the outcome: the call's own failure once the
     * handshake has completed (the connection is established, earlier
     * attempts no longer matter), otherwise the most informative failure of
     * all connection attempts.
     */
    fun decisiveFailure(failures: List<ProbeFailure>): ProbeFailure {
        val last = failures.last()
        return if (last.phase.handshakeCompleted) last else ProbeErrorClassifier.mostInformative(failures)
    }

    /**
     * Code when the probe failed in [phase] with [error].
     *
     * Before the handshake has completed, the usual phase rules apply. After
     * it, the certificate has already passed strict validation, so the inbound
     * is reachable: only a transfer that stopped for longer than the read
     * timeout counts as a cut-off ([ProbeCode.STALLED]). A server that closes
     * or resets the connection, or answers with something that is not HTTP
     * (TLS without fallback), leaves the check [ProbeCode.OK_UNVERIFIED].
     */
    fun failureCode(phase: ProbePhase, error: Throwable, receivedBytes: Long): ProbeCode {
        if (!phase.handshakeCompleted) return ProbeErrorClassifier.classify(phase, error)
        if (receivedBytes >= TARGET_BYTES) return ProbeCode.OK
        val timedOut = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is SocketTimeoutException }
        return if (timedOut) ProbeCode.STALLED else ProbeCode.OK_UNVERIFIED
    }

    private val ProbePhase.handshakeCompleted: Boolean
        get() = this == ProbePhase.REQUEST || this == ProbePhase.BODY

    private const val DEFAULT_HTTPS_PORT = 443
    private const val MAX_CAUSE_DEPTH = 10
}
