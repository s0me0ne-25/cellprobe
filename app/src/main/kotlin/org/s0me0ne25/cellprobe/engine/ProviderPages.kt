// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.engine

import org.s0me0ne25.cellprobe.core.actions.ProviderPageLabels
import org.s0me0ne25.cellprobe.core.probe.CheckListId

/** A provider account page as the screens and notifications show it (spec §7.4). */
data class ProviderPage(
    val url: String,
    /** The host, see [ProviderPageLabels]. */
    val label: String,
    /** The page responded in the last check. */
    val responded: Boolean,
)

object ProviderPages {

    /**
     * The pages at [urls], in list order, marked by the results in
     * [sections]. Rows follow the list order; if the list changed since the
     * check, no page is marked.
     */
    fun of(urls: List<String>, sections: List<ListSection>): List<ProviderPage> {
        val labels = ProviderPageLabels.labels(urls)
        val rows = sections.firstOrNull { it.id == CheckListId.PROVIDER }?.rows?.takeIf { it.size == urls.size }
        return urls.mapIndexed { index, url ->
            ProviderPage(url, labels[index], responded = rows?.get(index)?.outcome?.code?.reachable == true)
        }
    }
}
