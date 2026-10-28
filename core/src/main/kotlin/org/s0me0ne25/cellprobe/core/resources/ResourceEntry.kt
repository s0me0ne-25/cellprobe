// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.resources

import kotlinx.serialization.Serializable

/**
 * One probe target of an HTTPS resource list.
 *
 * @property url canonical https URL, see [UrlNormalizer.normalize].
 * @property marker optional substring the response body must contain.
 * @property allowRuCa trust the Russian state root CA for this entry only.
 *   Not editable yet; always false until the per-entry CA feature lands.
 */
@Serializable
data class ResourceEntry(
    val url: String,
    val marker: String? = null,
    val allowRuCa: Boolean = false,
) {
    /** Unicode host plus optional port and path, for lists in the UI. */
    val displayName: String
        get() = UrlNormalizer.displayName(url)
}

/** Validation errors for the entry editor form. */
sealed interface EntryInputError {
    data class InvalidUrl(val error: UrlError) : EntryInputError
    data object MarkerTooLong : EntryInputError
}

sealed interface EntryInputResult {
    data class Valid(val entry: ResourceEntry) : EntryInputResult
    data class Invalid(val error: EntryInputError) : EntryInputResult
}

object EntryInput {

    const val MAX_MARKER_LENGTH = 200

    /** Validates raw form input and builds an entry from it. */
    fun parse(rawUrl: String, rawMarker: String): EntryInputResult {
        val url = when (val result = UrlNormalizer.normalize(rawUrl)) {
            is UrlParseResult.Valid -> result.url
            is UrlParseResult.Invalid ->
                return EntryInputResult.Invalid(EntryInputError.InvalidUrl(result.error))
        }
        val marker = rawMarker.trim().ifEmpty { null }
        if (marker != null && marker.length > MAX_MARKER_LENGTH) {
            return EntryInputResult.Invalid(EntryInputError.MarkerTooLong)
        }
        return EntryInputResult.Valid(ResourceEntry(url = url, marker = marker))
    }
}
