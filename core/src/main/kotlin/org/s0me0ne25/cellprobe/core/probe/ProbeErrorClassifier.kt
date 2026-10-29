// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/** A failure of one connection attempt, or of the whole call, in [phase]. */
data class ProbeFailure(val phase: ProbePhase, val error: Throwable)

/**
 * Maps a probe failure to a [ProbeCode].
 *
 * The phase matters as much as the exception: a reset before TLS is a
 * blocked IP or port, a reset during the handshake is typically SNI
 * filtering, and a timeout while reading the body is a stalled transfer.
 */
object ProbeErrorClassifier {

    fun classify(phase: ProbePhase, error: Throwable): ProbeCode {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

        if (chain.any { it is UnknownHostException }) return ProbeCode.DNS_FAIL
        if (chain.any { it.isCertificateProblem() }) return ProbeCode.CERT_INVALID

        val timeout = chain.any { it is SocketTimeoutException }
        return when (phase) {
            ProbePhase.DNS -> ProbeCode.DNS_FAIL
            ProbePhase.CONNECT -> if (timeout) ProbeCode.TCP_TIMEOUT else ProbeCode.TCP_RESET
            ProbePhase.TLS -> ProbeCode.TLS_FAIL
            ProbePhase.REQUEST -> ProbeCode.HTTP_ERROR
            ProbePhase.BODY ->
                if (timeout || chain.any { it is SocketException }) ProbeCode.STALLED else ProbeCode.HTTP_ERROR
        }
    }

    /**
     * Picks the failure that tells the most about the resource.
     *
     * A host often has several addresses, and a failed connection attempt is
     * followed by the next address; the call then reports one of the errors,
     * not necessarily the meaningful one. A certificate rejected on the first
     * address says more than a refused connection on the second (an IPv6
     * address on a network without IPv6, say). The failure that got furthest
     * wins; among equals, the latest one.
     */
    fun mostInformative(failures: List<ProbeFailure>): ProbeFailure {
        require(failures.isNotEmpty()) { "No failures to choose from" }
        return failures.withIndex()
            .maxWith(compareBy({ progress(classify(it.value.phase, it.value.error)) }, { it.index }))
            .value
    }

    /** How far a connection got before failing with [code]. */
    private fun progress(code: ProbeCode): Int = when (code) {
        ProbeCode.DNS_FAIL -> 0
        ProbeCode.TCP_TIMEOUT, ProbeCode.TCP_RESET -> 1
        ProbeCode.TLS_FAIL -> 2
        ProbeCode.CERT_INVALID, ProbeCode.CERT_RU_CA -> 3
        ProbeCode.HTTP_ERROR -> 4
        ProbeCode.STALLED -> 5
        ProbeCode.OK, ProbeCode.OK_UNVERIFIED, ProbeCode.CONTENT_MISMATCH, ProbeCode.SKIPPED -> 6
    }

    /**
     * Short technical description of [error] and its causes, such as
     * `SSLHandshakeException: … ← CertPathValidatorException: Trust anchor
     * for certification path not found`. For the check log, not for the UI.
     */
    fun describe(error: Throwable): String = generateSequence(error) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .map { cause -> listOfNotNull(cause.javaClass.simpleName, cause.message?.trim()?.ifEmpty { null }).joinToString(": ") }
        .distinct()
        .joinToString(" ← ")
        .let { if (it.length > MAX_DETAIL_LENGTH) it.take(MAX_DETAIL_LENGTH - 1) + "…" else it }

    private fun Throwable.isCertificateProblem(): Boolean =
        this is CertificateException || this is CertPathValidatorException || this is SSLPeerUnverifiedException

    private const val MAX_CAUSE_DEPTH = 10
    private const val MAX_DETAIL_LENGTH = 400
}
