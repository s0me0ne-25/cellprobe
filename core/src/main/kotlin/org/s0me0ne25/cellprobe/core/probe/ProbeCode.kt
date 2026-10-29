// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

/** Result of probing one resource. */
enum class ProbeCode {
    /** Valid TLS and an HTTP response (any status); marker found if set. */
    OK,

    /** VLESS only: reachable, but too little data to check for a cut-off. */
    OK_UNVERIFIED,

    /** The host name could not be resolved. */
    DNS_FAIL,

    /** No answer to the TCP connection attempt. */
    TCP_TIMEOUT,

    /** The TCP connection was refused or reset before TLS started. */
    TCP_RESET,

    /** TCP connected, but the TLS handshake failed (reset, timeout, protocol error). */
    TLS_FAIL,

    /** The certificate is untrusted, expired or does not match the host. */
    CERT_INVALID,

    /** The chain is issued by the Russian state CA, not trusted for this entry. */
    CERT_RU_CA,

    /** TLS is up, but no usable HTTP response arrived. */
    HTTP_ERROR,

    /** The response started and the data flow stopped. */
    STALLED,

    /** A response arrived, but the marker is not in it. */
    CONTENT_MISMATCH,

    /** Not probed: another entry of the same list already answered. */
    SKIPPED,
    ;

    /** Whether the resource counts as reachable for the classifier. */
    val reachable: Boolean
        get() = this == OK || this == OK_UNVERIFIED
}

/** Connection phase a probe was in when it failed; decides the failure code. */
enum class ProbePhase {
    DNS,
    CONNECT,
    TLS,

    /** Connection is ready, waiting for the response headers. */
    REQUEST,

    /** Headers received, reading the body. */
    BODY,
}

/** Outcome of one probe. */
data class ProbeOutcome(
    val code: ProbeCode,
    /** Time until the response headers, or until the failure. */
    val durationMillis: Long,
    val httpStatus: Int? = null,
    /** `<title>` of the page, if the body was read. For diagnostics. */
    val pageTitle: String? = null,
)
