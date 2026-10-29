// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Follows the system state while the lists are probed (spec §3). If the
 * network goes away mid-check (airplane mode, all networks off, loss of
 * service), waiting for every request to time out would end in a false
 * NO_INTERNET: the check is cut short and the status follows from the
 * system state instead.
 */
object ConnectivityWatch {

    /**
     * A loss is confirmed by a second reading after this delay: when the
     * default network changes (mobile data to Wi-Fi, say) there may be a
     * moment without any.
     */
    val CONFIRMATION_DELAY: Duration = 2.seconds

    /** How a check ended. */
    sealed interface Outcome<out T> {
        data class Completed<T>(val value: T) : Outcome<T>

        /** Probing was cut short; [state] no longer allows probes. */
        data class Interrupted(val state: NetworkState) : Outcome<Nothing>
    }

    /**
     * Suspends until [readings] report a state that does not allow probes
     * ([NetworkClassifier.needsProbes]) and a fresh reading [readAgain] after
     * [confirmation] still says so. Returns that state.
     */
    suspend fun awaitLoss(
        readings: Flow<NetworkState>,
        readAgain: suspend () -> NetworkState,
        confirmation: Duration = CONFIRMATION_DELAY,
    ): NetworkState = readings
        .filter { !NetworkClassifier.needsProbes(it) }
        .map {
            delay(confirmation)
            readAgain()
        }
        .first { !NetworkClassifier.needsProbes(it) }

    /**
     * The current state if it does not allow probes and still does not after
     * [confirmation]; null otherwise.
     */
    suspend fun confirmedLoss(
        read: suspend () -> NetworkState,
        confirmation: Duration = CONFIRMATION_DELAY,
    ): NetworkState? {
        if (NetworkClassifier.needsProbes(read())) return null
        delay(confirmation)
        return read().takeIf { !NetworkClassifier.needsProbes(it) }
    }

    /**
     * Runs [work] until it completes or [loss] returns, whichever comes
     * first; the other one is cancelled.
     *
     * Requests often fail at once when the network goes away, so [work] may
     * well finish before [loss] has confirmed it. [lossAfterWork] is asked
     * after [work] completes (typically [confirmedLoss]): a state it returns
     * still makes the outcome [Outcome.Interrupted].
     */
    suspend fun <T> runUntilLoss(
        work: suspend () -> T,
        loss: suspend () -> NetworkState,
        lossAfterWork: suspend () -> NetworkState? = { null },
    ): Outcome<T> {
        val first = coroutineScope {
            val working = async { work() }
            val losing = async { loss() }
            select<Outcome<T>> {
                working.onAwait { value ->
                    losing.cancel()
                    Outcome.Completed(value)
                }
                losing.onAwait { state ->
                    working.cancel()
                    Outcome.Interrupted(state)
                }
            }
        }
        if (first is Outcome.Interrupted) return first
        return lossAfterWork()?.let { Outcome.Interrupted(it) } ?: first
    }
}
