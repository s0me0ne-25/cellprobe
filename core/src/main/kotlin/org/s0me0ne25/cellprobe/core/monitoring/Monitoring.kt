// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.monitoring

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.s0me0ne25.cellprobe.core.NetworkState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Background monitoring settings (spec §8, §9.2). Intervals are stored in
 * seconds; new fields must have defaults so older settings keep loading.
 */
@Serializable
data class MonitoringSettings(
    val enabled: Boolean = false,
    val screenOnIntervalSeconds: Long = DEFAULT_SCREEN_ON.inWholeSeconds,
    val screenOffIntervalSeconds: Long = DEFAULT_SCREEN_OFF.inWholeSeconds,
) {
    val screenOnInterval: Duration
        get() = clamp(screenOnIntervalSeconds.seconds)

    val screenOffInterval: Duration
        get() = clamp(screenOffIntervalSeconds.seconds)

    fun interval(screenOn: Boolean): Duration = if (screenOn) screenOnInterval else screenOffInterval

    /**
     * How old the last check may be to show it after a restart: twice the
     * longest interval, so a running monitor has made at least one check
     * within it.
     */
    val lastCheckMaxAge: Duration
        get() = maxOf(screenOnInterval, screenOffInterval) * 2

    companion object {
        val MIN_INTERVAL: Duration = 30.seconds
        val MAX_INTERVAL: Duration = 60.minutes
        val DEFAULT_SCREEN_ON: Duration = 3.minutes
        val DEFAULT_SCREEN_OFF: Duration = 10.minutes

        /** Intervals offered in the settings. */
        val INTERVAL_CHOICES: List<Duration> =
            listOf(30.seconds, 1.minutes, 2.minutes, 3.minutes, 5.minutes, 10.minutes, 15.minutes, 30.minutes, 60.minutes)

        private fun clamp(interval: Duration) = interval.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
    }
}

/**
 * The monitoring cycle: a check, then a pause of the current interval,
 * measured from the end of the check. A new interval (the screen went off)
 * applies to the pause already running; an extra check request (the screen
 * went on, the network changed) ends the pause at once.
 *
 * A sleeping device does not run coroutine timers, so the end of each pause
 * is also handed to [onPause] to set a wake-up alarm at that moment of
 * [nowMillis]; the alarm comes back through [wakeUps]. The coroutine timer
 * stays as the fallback while the device is awake.
 */
object MonitoringLoop {

    suspend fun run(
        interval: StateFlow<Duration>,
        extraChecks: Flow<Unit>,
        nowMillis: () -> Long,
        onPause: (wakeAtMillis: Long) -> Unit = {},
        wakeUps: Flow<Unit> = emptyFlow(),
        check: suspend () -> Unit,
    ): Unit = coroutineScope {
        val extra = Channel<Unit>(Channel.CONFLATED)
        launch { extraChecks.collect { extra.trySend(Unit) } }
        while (true) {
            check()
            // A request that came while the check was running is served by it.
            extra.tryReceive()
            val finishedAt = nowMillis()
            pause@ while (isActive) {
                val remaining = interval.value - (nowMillis() - finishedAt).milliseconds
                if (remaining <= Duration.ZERO) break@pause
                onPause(finishedAt + interval.value.inWholeMilliseconds)
                val event = withTimeoutOrNull(remaining) {
                    merge(
                        extra.receiveAsFlow().map { Event.EXTRA_CHECK },
                        interval.drop(1).map { Event.RECOMPUTE },
                        // The alarm may come a little early or late: recompute.
                        wakeUps.map { Event.RECOMPUTE },
                    ).first()
                }
                when (event) {
                    null, Event.EXTRA_CHECK -> break@pause
                    Event.RECOMPUTE -> continue@pause
                }
            }
        }
    }

    private enum class Event { EXTRA_CHECK, RECOMPUTE }
}

/**
 * When a change of the default network calls for an extra check
 * (spec §3, §8): once the network has settled after a change, a loss or a
 * return, and at once when a captive portal flag is cleared.
 */
object NetworkEvents {

    /** A new network needs a moment: addresses, DNS, VPN reconnecting. */
    val SETTLE_DELAY: Duration = 5.seconds

    /** The delay before an extra check after [previous] became [current], or null if none is needed. */
    fun extraCheckDelay(previous: NetworkState, current: NetworkState): Duration? = when {
        previous.captivePortal && !current.captivePortal && current.networkId == previous.networkId &&
            current.activeNetwork != null -> Duration.ZERO
        previous.networkId != current.networkId -> SETTLE_DELAY
        previous.captivePortal != current.captivePortal -> SETTLE_DELAY
        else -> null
    }
}
