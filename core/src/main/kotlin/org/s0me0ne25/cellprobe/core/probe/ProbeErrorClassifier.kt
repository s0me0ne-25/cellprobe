// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

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
