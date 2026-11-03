// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.stabilizer

import kotlinx.serialization.Serializable
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The status found by one check. */
data class Observation(val status: NetworkStatus, val networkType: NetworkType?, val timeMillis: Long)

/** A status seen but not confirmed yet. */
@Serializable
data class Candidate(
    val status: NetworkStatus,
    val networkType: NetworkType? = null,
    /** Rechecks requested for it and the candidates before it in a row. */
    val rechecks: Int,
    /** Every candidate of the row, oldest first. */
    val seen: List<NetworkStatus>,
)

/** A confirmed change of status, counted for flapping. */
@Serializable
data class Transition(val timeMillis: Long, val from: NetworkStatus, val to: NetworkStatus)

/**
 * Everything the stabilizer remembers (spec §6). Persisted, so a restart of
 * the process neither repeats nor loses a notification.
 */
@Serializable
data class StabilizerState(
    /** What the screens and the service notification show; null before the first check. */
    val confirmed: NetworkStatus? = null,
    val confirmedNetwork: NetworkType? = null,
    val observedAtMillis: Long? = null,
    val candidate: Candidate? = null,
    /** The status of the last transition notification; never [NetworkStatus.USER_OFFLINE]. */
    val announced: NetworkStatus? = null,
    /** Network type the [transitions] were counted on. */
    val countedNetwork: NetworkType? = null,
    /** Confirmed transitions within [Stabilizer.FLAPPING_WINDOW], oldest first. */
    val transitions: List<Transition> = emptyList(),
    val lastTransitionMillis: Long? = null,
    /** The meta-status UNSTABLE: the network keeps switching. */
    val unstable: Boolean = false,
    val lastLoudMillis: Long? = null,
)

/** How a notification alerts. */
enum class Volume {
    /** Heads-up, sound and vibration. */
    LOUD,

    /** Sound, no heads-up. */
    NORMAL,

    /** Updates the shown notification without alerting. */
    SILENT,
}

/** A transition notification to show; one at a time, each replacing the previous one. */
sealed interface Notice {
    val volume: Volume

    data class StatusChanged(
        val status: NetworkStatus,
        val networkType: NetworkType?,
        override val volume: Volume,
    ) : Notice

    /** The network keeps switching between [statuses], in the order they appeared. */
    data class Unstable(val statuses: List<NetworkStatus>, override val volume: Volume) : Notice

    /** UNSTABLE is over: [status] has held. */
    data class Stabilized(val status: NetworkStatus, val networkType: NetworkType?) : Notice {
        override val volume: Volume get() = Volume.NORMAL
    }
}

/**
 * The result of one observation: the new state, a recheck to request after
 * [recheckAfter] if not null, and a notification to show if any.
 */
data class Decision(val state: StabilizerState, val recheckAfter: Duration? = null, val notice: Notice? = null)

/**
 * The anti-spam state machine of spec §6: confirms a new status with a
 * quick recheck, turns flapping into the meta-status UNSTABLE, and picks
 * how loud each transition notification is.
 */
object Stabilizer {

    /** Delay of the recheck that confirms a candidate. */
    val RECHECK_DELAY: Duration = 15.seconds

    /** Candidates in a row before the strictest of them is taken. */
    const val MAX_RECHECKS = 3

    /** Confirmed transitions within [FLAPPING_WINDOW] that make the network UNSTABLE. */
    const val FLAPPING_TRANSITIONS = 4
    val FLAPPING_WINDOW: Duration = 30.minutes

    /** UNSTABLE ends when the status has not changed for this long. */
    val STABLE_AFTER: Duration = 15.minutes

    /** A stricter status alerts loudly at most once in this period. */
    val LOUD_COOLDOWN: Duration = 10.minutes

    fun observe(state: StabilizerState, observation: Observation): Decision {
        val now = observation.timeMillis
        val current = state.copy(observedAtMillis = now)
        val confirmed = current.confirmed
            ?: return Decision(baseline(current, observation))
        val status = observation.status
        val network = observation.networkType

        if (status in IMMEDIATE) return confirm(current.copy(candidate = null), status, network, now)

        // Back from USER_OFFLINE to the status announced before it: nothing to tell.
        if (confirmed == NetworkStatus.USER_OFFLINE && sameMode(status, current.announced)) {
            return confirm(current.copy(candidate = null), status, network, now)
        }

        if (sameMode(status, confirmed)) {
            // A candidate, if any, was a one-off failure.
            val kept = current.copy(candidate = null, confirmed = status, confirmedNetwork = network)
            return settle(kept, network, now)
        }

        val candidate = current.candidate
        return when {
            candidate == null ->
                Decision(current.copy(candidate = Candidate(status, network, 1, listOf(status))), RECHECK_DELAY)
            sameMode(candidate.status, status) -> confirm(current.copy(candidate = null), status, network, now)
            candidate.rechecks >= MAX_RECHECKS -> {
                val strictest = (candidate.seen + status).withIndex()
                    .maxWith(compareBy({ rank(it.value) }, { it.index })).value
                confirm(current.copy(candidate = null), strictest, network, now)
            }
            else -> Decision(
                current.copy(candidate = Candidate(status, network, candidate.rechecks + 1, candidate.seen + status)),
                RECHECK_DELAY,
            )
        }
    }

    /** "Same mode" for the user: standard blocking with and without VLESS entries configured. */
    fun sameMode(a: NetworkStatus?, b: NetworkStatus?): Boolean = a != null && b != null && group(a) == group(b)

    /**
     * Severity for comparisons. [NetworkStatus.LEAK] is outside the scale and
     * ranks lowest: the freest status, so moving to it is a softening from any
     * mode (a VPN on the router is no reason to alert), and leaving it is a
     * tightening.
     */
    private fun rank(status: NetworkStatus): Int = status.severity ?: -1

    private fun group(status: NetworkStatus) =
        if (status == NetworkStatus.NORMAL_NO_VLESS) NetworkStatus.NORMAL else status

    /** Statuses accepted without a recheck: the system reports them itself. */
    private val IMMEDIATE = setOf(NetworkStatus.AUTH_REQUIRED, NetworkStatus.USER_OFFLINE)

    /** The first check: what the service notification shows from now on; nothing to announce. */
    private fun baseline(state: StabilizerState, observation: Observation): StabilizerState {
        val online = observation.status != NetworkStatus.USER_OFFLINE
        return state.copy(
            confirmed = observation.status,
            confirmedNetwork = observation.networkType,
            announced = observation.status.takeIf { online },
            countedNetwork = observation.networkType,
        )
    }

    /** A network type other than the one transitions were counted on starts the count again and ends UNSTABLE. */
    private fun switchNetwork(state: StabilizerState, network: NetworkType?): StabilizerState =
        if (network == null || network == state.countedNetwork) {
            state
        } else {
            state.copy(countedNetwork = network, transitions = emptyList(), unstable = false)
        }

    /** The status holds: UNSTABLE may end. */
    private fun settle(state: StabilizerState, network: NetworkType?, now: Long): Decision {
        val status = checkNotNull(state.confirmed)
        val switched = switchNetwork(state, network)
        val endedBySwitch = state.unstable && !switched.unstable
        val calm = switched.unstable && switched.candidate == null && status != NetworkStatus.USER_OFFLINE &&
            now - (switched.lastTransitionMillis ?: now) >= STABLE_AFTER.inWholeMilliseconds
        if (!endedBySwitch && !calm) return Decision(switched)
        val stable = switched.copy(unstable = false, transitions = emptyList(), announced = status)
        return Decision(stable, notice = Notice.Stabilized(status, network))
    }

    private fun confirm(state: StabilizerState, status: NetworkStatus, network: NetworkType?, now: Long): Decision {
        val previous = state.confirmed
        val confirmed = state.copy(confirmed = status, confirmedNetwork = network)
        if (sameMode(status, previous)) return settle(confirmed, network, now)
        // The user went offline: no notification, and it does not count as flapping.
        if (status == NetworkStatus.USER_OFFLINE) return Decision(confirmed)

        val wasUnstable = confirmed.unstable
        val switched = switchNetwork(confirmed, network)

        if (sameMode(status, switched.announced)) {
            // Back to what was announced (after USER_OFFLINE): only the end of UNSTABLE is news.
            val notice = if (wasUnstable && !switched.unstable) Notice.Stabilized(status, network) else null
            return Decision(switched.copy(announced = status), notice = notice)
        }

        val window = FLAPPING_WINDOW.inWholeMilliseconds
        // After USER_OFFLINE the change is from the status announced before it.
        val from = switched.announced ?: checkNotNull(previous)
        val transitions = (switched.transitions + Transition(now, from, status))
            .filter { now - it.timeMillis < window }
        val counted = switched.copy(
            transitions = transitions,
            lastTransitionMillis = now,
            announced = status,
        )

        if (counted.unstable) {
            return Decision(counted, notice = Notice.Unstable(statusesOf(transitions), Volume.SILENT))
        }
        if (transitions.size >= FLAPPING_TRANSITIONS) {
            return Decision(
                counted.copy(unstable = true, lastLoudMillis = now),
                notice = Notice.Unstable(statusesOf(transitions), Volume.LOUD),
            )
        }

        val cooledDown = state.lastLoudMillis.let { it == null || now - it >= LOUD_COOLDOWN.inWholeMilliseconds }
        // A stricter status is news whatever brought it, a new network type
        // included: out of Wi-Fi with the VPN client off, the messengers in
        // the pocket go just as silent.
        val announced = state.announced
        val louder = announced != null && rank(status) > rank(announced)
        val volume = if (louder && cooledDown) Volume.LOUD else Volume.NORMAL
        return Decision(
            counted.copy(lastLoudMillis = if (volume == Volume.LOUD) now else counted.lastLoudMillis),
            notice = Notice.StatusChanged(status, network, volume),
        )
    }

    /** Distinct statuses of [transitions] in the order they appeared. */
    private fun statusesOf(transitions: List<Transition>): List<NetworkStatus> =
        transitions.flatMap { listOf(it.from, it.to) }.distinctBy(::group)
}
