// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.resources.ListEditError
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.ResourceListState
import org.s0me0ne25.cellprobe.core.resources.UrlError
import org.s0me0ne25.cellprobe.core.vless.VlessError

/** UI texts for core enums. */

@StringRes
fun ResourceListId.titleRes(): Int = when (this) {
    ResourceListId.DOMESTIC -> R.string.list_domestic_title
    ResourceListId.FOREIGN -> R.string.list_foreign_title
    ResourceListId.BANNED -> R.string.list_banned_title
    ResourceListId.PROVIDER -> R.string.list_provider_title
}

@StringRes
fun ResourceListId.descriptionRes(): Int = when (this) {
    ResourceListId.DOMESTIC -> R.string.list_domestic_description
    ResourceListId.FOREIGN -> R.string.list_foreign_description
    ResourceListId.BANNED -> R.string.list_banned_description
    ResourceListId.PROVIDER -> R.string.list_provider_description
}

@StringRes
fun UrlError.messageRes(): Int = when (this) {
    UrlError.EMPTY -> R.string.url_error_empty
    UrlError.NOT_HTTPS -> R.string.url_error_not_https
    UrlError.CREDENTIALS_NOT_ALLOWED -> R.string.url_error_credentials
    UrlError.INVALID_HOST -> R.string.url_error_invalid_host
    UrlError.INVALID_PORT -> R.string.url_error_invalid_port
    UrlError.MALFORMED -> R.string.url_error_malformed
}

@StringRes
fun ListEditError.messageRes(): Int = when (this) {
    ListEditError.DUPLICATE -> R.string.list_error_duplicate
    ListEditError.WOULD_BE_EMPTY -> R.string.list_error_would_be_empty
    ListEditError.NOT_FOUND -> R.string.list_error_not_found
    ListEditError.NO_PRESET -> R.string.list_error_no_preset
}

@StringRes
fun VlessError.messageRes(): Int = when (this) {
    VlessError.EMPTY -> R.string.vless_error_empty
    VlessError.UNSUPPORTED_SCHEME -> R.string.vless_error_unsupported_scheme
    VlessError.MULTIPLE_LINKS -> R.string.vless_error_multiple_links
    VlessError.MALFORMED -> R.string.vless_error_malformed
    VlessError.INVALID_HOST -> R.string.vless_error_invalid_host
    VlessError.INVALID_PORT -> R.string.vless_error_invalid_port
    VlessError.MISSING_SNI -> R.string.vless_error_missing_sni
    VlessError.INVALID_SNI -> R.string.vless_error_invalid_sni
    VlessError.INVALID_FINGERPRINT -> R.string.vless_error_invalid_fingerprint
    VlessError.UNSUPPORTED_SECURITY -> R.string.vless_error_unsupported_security
    VlessError.UNSUPPORTED_TRANSPORT -> R.string.vless_error_unsupported_transport
    VlessError.LABEL_TOO_LONG -> R.string.vless_error_label_too_long
}

/** "5 entries · preset", "3 entries · customized", "empty". */
@Composable
fun listSummary(state: ResourceListState): String {
    val count = state.entries.size
    if (count == 0) return stringResource(R.string.list_summary_empty)
    val countText = pluralStringResource(R.plurals.list_entries_count, count, count)
    if (state.id.preset == null) return countText
    val origin = stringResource(
        if (state.customized) R.string.list_summary_customized else R.string.list_summary_preset,
    )
    return "$countText · $origin"
}
