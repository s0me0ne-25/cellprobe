// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.engine

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.stabilizer.Observation
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.data.StabilizerStore
import org.s0me0ne25.cellprobe.notify.TransitionNotifications
import kotlin.time.Duration

/** The confirmed status of the stabilizer and the candidate waiting for a recheck, if any. */
data class Confirmation(
    val status: NetworkStatus,
    val networkType: NetworkType?,
    val checkedAtMillis: Long,
    /** A different status seen by the last check, being confirmed. */
    val pending: NetworkStatus?,
)

/**
 * Runs the stabilizer (spec §6) on the checks made while monitoring is on:
 * keeps the confirmed status, posts transition notifications and asks the
 * monitoring service for rechecks. Without monitoring it stays out of the
 * way: a manual check shows its own result and notifies no one.
 */
class StatusTracker(
    private val store: StabilizerStore,
    private val settings: SettingsRepository,
    private val notifications: TransitionNotifications,
) {
    private val mutableRechecks = MutableSharedFlow<Duration>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Delays after which the monitoring service should run a recheck. */
    val rechecks: SharedFlow<Duration> = mutableRechecks.asSharedFlow()

    /** Null while monitoring is off or before its first check. */
    val confirmation: Flow<Confirmation?> = combine(
        store.state,
        settings.monitoring.map { it.enabled }.distinctUntilChanged(),
    ) { state, enabled ->
        val status = state.confirmed
        val checkedAt = state.observedAtMillis
        if (!enabled || status == null || checkedAt == null) {
            null
        } else {
            Confirmation(status, state.confirmedNetwork, checkedAt, state.candidate?.status)
        }
    }.distinctUntilChanged()

    /** @param sections the results of the check, for the provider pages of a "Pay" notification. */
    suspend fun onCheckFinished(result: CheckResult, sections: List<ListSection>) {
        if (!settings.monitoring.first().enabled) return
        val decision = store.observe(Observation(result.status, result.networkType, result.finishedAtMillis))
        decision.recheckAfter?.let(mutableRechecks::tryEmit)
        val notice = decision.notice ?: return
        val urls = settings.resourceList(ResourceListId.PROVIDER).first().entries.map { it.url }
        notifications.show(notice, ProviderPages.of(urls, sections))
    }

    /** Monitoring was turned on: the old state is stale, the first check is a new baseline. */
    suspend fun start() {
        store.reset()
    }

    /** Monitoring was turned off: a transition notification would only go stale. */
    fun stop() {
        notifications.cancel()
    }
}
