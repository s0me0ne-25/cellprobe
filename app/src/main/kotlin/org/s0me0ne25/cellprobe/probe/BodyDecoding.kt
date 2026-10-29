// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import com.squareup.zstd.okio.zstdDecompress
import okio.Buffer
import okio.BufferedSource
import okio.GzipSource
import okio.InflaterSource
import okio.Source
import okio.buffer
import okio.source
import org.brotli.dec.BrotliInputStream
import org.s0me0ne25.cellprobe.core.probe.WebViewHeaders
import java.net.ProtocolException
import java.util.zip.Inflater

/**
 * Decodes response bodies for every encoding we advertise in
 * [WebViewHeaders.ACCEPT_ENCODING]. We send that header ourselves to look
 * like a browser, so OkHttp's transparent gzip handling is off.
 */
internal object BodyDecoding {

    /** Wraps [source] in decoders for [contentEncoding] (applied in order, so decoded in reverse). */
    fun decode(source: BufferedSource, contentEncoding: String?): Source {
        val encodings = contentEncoding.orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "identity" }

        var result: Source = source
        for (encoding in encodings.asReversed()) {
            result = when (encoding) {
                "gzip", "x-gzip" -> GzipSource(result)
                "deflate" -> InflaterSource(result, Inflater())
                "br" -> BrotliInputStream(result.buffer().inputStream()).source()
                "zstd" -> result.zstdDecompress()
                else -> throw ProtocolException("Unsupported Content-Encoding: $encoding")
            }
        }
        return result
    }

    /** Reads at most [limit] decoded bytes. */
    fun readAtMost(source: Source, limit: Int): ByteArray {
        val buffer = Buffer()
        while (buffer.size < limit) {
            if (source.read(buffer, limit - buffer.size) == -1L) break
        }
        return buffer.readByteArray()
    }
}
