package com.fantasyidler.simulator

import com.fantasyidler.data.model.Player
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.data.model.Skills
import com.fantasyidler.repository.BoostRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.coroutines.CoroutineContext

/**
 * Layers 2–3 of the three-layer reality system:
 *
 *   Base Reality (Very Hard)
 *       └── Simulator (Parallel Checkpointed Reality)
 *               └── Fast Time Simulation (Time Skip)
 *
 * Entering the Simulator deep-copy checkpoints the full player state
 * ([Player] is a flat row of immutable JSON columns, so `copy()` is a complete
 * deep copy), and while [isSimulationActive] every game write is redirected by
 * [com.fantasyidler.data.db.dao.SimulationPlayerDao] into the temporary
 * simulation state only — the real save is never touched. Every session
 * read/write is redirected the same way by
 * [com.fantasyidler.data.db.dao.SimulationSessionDao] into [simSessions], an
 * isolated copy of the session table seeded on entry and dropped on exit, so no
 * Base Reality session (player, worker, combat, or boss) is ever created,
 * completed, overwritten, or deleted by a run.
 *
 * A run is bound to the character/save slot that started it ([ownsRun]): if the
 * active slot no longer matches, every Simulator operation and reward claim is
 * rejected and the temporary state is discarded
 * ("Simulation is no longer valid. Returning to Base Reality.").
 *
 * Layer 2 has no manual exit. The run remains active until the simulated player
 * dies; death is the single controlled transition back to Layer 1 (Base Reality).
 * Crash recovery and invalid-save-slot paths discard the temporary state directly
 * without offering rewards, and those paths are internal only — normal gameplay
 * never reaches them.
 *
 * Fast Time Simulation (Time Skip) is only available inside the Simulator: it
 * instantly advances the current action's own pre-simulated session frames —
 * the exact same rate calculations a real session uses — and its results land
 * only in the temporary simulation state, while still counting toward the
 * pick-3 reward pool. The Simulator itself is upgradeable (longer skips,
 * better efficiency); those upgrades persist in Base Reality.
 */
object RealitySimulator {

    /** UI states of the three-layer flow. */
    enum class Phase {
        /** Enter Simulator / return to Base Reality. */
        BASE_REALITY,
        /** Inside the Simulator. */
        IN_SIMULATION,
        /** Fast Time Simulation duration confirmation. */
        TIME_SKIP_CONFIRM,
        /** Fast Time Simulation result summary. */
        TIME_SKIP_RESULT,
        /** Death inside the Simulator. */
        SIM_DEATH,
        /** Pick-up-to-three reward selection before returning. */
        REWARD_SELECTION,
    }

    enum class RewardKind { STATS, SKILL_XP, ITEM }

    /** One claimable reward from the gains made in the simulation run. */
    data class SimReward(
        val kind: RewardKind,
        /** Skill key, item key, or [COINS_ID] for the coin pouch. */
        val id: String,
        /** Levels gained, XP gained, or item quantity gained. */
        val amount: Long,
    )

    /** Aggregated result of one Fast Time Simulation skip. */
    data class TimeSkipResult(
        val minutes: Int = 0,
        val xpBySkill: Map<String, Long> = emptyMap(),
        val items: Map<String, Int> = emptyMap(),
        val coins: Long = 0L,
        val died: Boolean = false,
        /** Combat only — food/arrows/runes the skipped frames consumed, deducted like a normal collect. */
        val foodConsumed: Map<String, Int> = emptyMap(),
        val arrowsConsumed: Map<String, Int> = emptyMap(),
        val runesConsumed: Map<String, Int> = emptyMap(),
    )

    /** Reward id for the coins "stat" reward. */
    const val COINS_ID = "coins"

    /** Exactly three rewards may be selected from the run's gains. */
    const val MAX_REWARDS = 3

    // ------------------------------------------------------------------ upgrades

    /** Upgrade: longer Time Skip durations. Persists in Base Reality. */
    const val UPGRADE_SKIP_DURATION = "skip_duration"

    /** Upgrade: better Simulator efficiency on Time Skip results. Persists in Base Reality. */
    const val UPGRADE_EFFICIENCY = "efficiency"

    const val MAX_SKIP_DURATION_LEVEL = 4
    const val MAX_EFFICIENCY_LEVEL = 5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phase = MutableStateFlow(Phase.BASE_REALITY)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _simPlayer = MutableStateFlow<Player?>(null)
    /** The temporary simulation state all writes are redirected into while active. */
    val simPlayer: StateFlow<Player?> = _simPlayer.asStateFlow()

    private val _lastSkipResult = MutableStateFlow<TimeSkipResult?>(null)
    val lastSkipResult: StateFlow<TimeSkipResult?> = _lastSkipResult.asStateFlow()

    private val _crashedLastRun = MutableStateFlow(false)
    /** True when the previous simulation was force-closed or crashed. */
    val crashedLastRun: StateFlow<Boolean> = _crashedLastRun.asStateFlow()

    private val _runInvalidated = MutableStateFlow(false)
    /**
     * True when a run had to be rejected because its character/save slot no longer matches
     * the active one ("Simulation is no longer valid. Returning to Base Reality.").
     */
    val runInvalidated: StateFlow<Boolean> = _runInvalidated.asStateFlow()

    private val _simSessions = MutableStateFlow<List<SkillSession>>(emptyList())
    /**
     * Isolated copy of the session table every session read/write is redirected into while
     * [isSimulationActive] — seeded from the real table on Simulator entry, dropped on exit,
     * claim, or recovery. Never written back: no Base Reality session is ever touched.
     */
    val simSessions: StateFlow<List<SkillSession>> = _simSessions.asStateFlow()

    // ------------------------------------------------------------------ lifecycle synchronization
    //
    // Lock ordering: simulatorMutex → playerMutex.  Never acquire simulatorMutex while
    // already holding playerMutex.  playerMutex is already held by virtually every
    // repository write; wrapping those writes with withSimulatorBoundary (which takes
    // simulatorMutex briefly only at entry/exit) preserves that ordering.
    //
    // Any coroutine that reads isSimulationActive and then performs one or more DAO calls
    // (which may suspend — e.g. Room transactions, or nested reads/writes) MUST run inside
    // withSimulatorBoundary {} so that:
    //
    //   1. it pins isSimulationActive once at entry, and
    //   2. shutdown (death transition, crash recovery, invalidation) waits for it to finish
    //      before flipping isSimulationActive to false and dropping the temporary state.
    //
    // This guarantees the invariant: if an operation sees isSimulationActive == true at its
    // start, every DAO call it makes also sees true (writes land in sim state); conversely
    // if it starts in Base Reality, every DAO call stays in Base Reality.

    /**
     * Mutex serialising lifecycle transitions (enter / death-exit / crash recovery /
     * invalidation) AND the snapshots taken by [withSimulatorBoundary].  Because lifecycle
     * transitions need to wait for all in-flight operations to drain before flipping
     * [isSimulationActive], and because snapshots need to observe a consistent lifecycle
     * state, both share this mutex.
     */
    private val simulatorMutex = Mutex()

    /**
     * Mutex serialising concurrent enterSimulation() calls so two coroutines cannot
     * both pass the "isSimulationActive == false" check, release simulatorMutex to take a
     * snapshot, and then both publish Layer 2 state.
     */
    private val entryGate = Mutex()

    /**
     * Count of operations currently running inside [withSimulatorBoundary].
     * Only accessed under [simulatorMutex].
     */
    private var inFlightOperations: Int = 0

    /**
     * Monotonic run generation counter. Incremented each time a new Layer 2 session is
     * successfully published. Every lifecycle action (clearCrashMarker, discard, claim)
     * captures its generation at start and refuses to mutate state if the generation has
     * advanced when it is ready to apply — so a stale death/abort/recovery can never
     * clear a NEWER run's crash marker, checkpoint, or sim state. Starts at 0 (Base
     * Reality; no active run).
     */
    @Volatile
    private var generation: Long = 0L

    // ------------------------------------------------------------------ pinned-layer plumbing
    //
    // PIN THE LAYER, NOT THE DATA. When a withSimulatorBoundary block STARTS we snapshot
    // WHETHER the operation began in sim (Boolean), propagate it across coroutine
    // suspensions/dispatch via a ThreadContextElement (coroutine-safe — survives
    // withContext dispatcher hops, unlike a plain ThreadLocal), and pop it on exit.
    // All reads/writes from a pinned-to-sim op go against the LIVE _simPlayer /
    // _simSessions StateFlows so concurrent sim ops observe each other's latest
    // updates (no lost updates / stale snapshots).
    //
    // A per-thread ArrayDeque<Boolean> stack supports nested boundary calls: outermost
    // wins (nested calls no-op after seeing an active pinned layer) so inFlightOperations
    // is never double-counted. The ThreadContextElement pushes on entry and pops on
    // restoreThreadContext, which is invoked on EVERY thread the coroutine resumes on
    // (including after dispatcher hops).

    private val pinnedStack = ThreadLocal<ArrayDeque<Boolean>>()

    private fun pushPinned(active: Boolean) {
        var s = pinnedStack.get()
        if (s == null) { s = ArrayDeque(); pinnedStack.set(s) }
        s.addLast(active)
    }
    private fun popPinned() {
        val s = pinnedStack.get() ?: return
        s.removeLast()
        if (s.isEmpty()) pinnedStack.remove()
    }
    /** Returns the pinned flag for the CURRENT thread, or null if not inside a boundary. */
    private fun pinnedActive(): Boolean? {
        val s = pinnedStack.get() ?: return null
        return s.last()
    }

    private class PinnedSimElement(private val active: Boolean)
        : ThreadContextElement<Unit> {
        companion object Key : CoroutineContext.Key<PinnedSimElement>
        override val key: CoroutineContext.Key<PinnedSimElement> get() = Key
        override fun updateThreadContext(context: CoroutineContext) { pushPinned(active) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) { popPinned() }
    }

    @Volatile
    var isSimulationActive: Boolean = false
        private set

    /** Deep-copy checkpoint of the full player state taken on Simulator entry. */
    @Volatile
    private var checkpoint: Player? = null

    /** Active save slot when the run started; 0 when no run exists. Rewards only ever go back to it. */
    @Volatile
    private var originSlot: Int = 0

    /** The generation that owns the current checkpoint/_simPlayer/_simSessions state. */
    @Volatile
    private var activeGeneration: Long = 0L

    /** Serializes reward claims so a double tap can never apply the same reward twice. */
    private val claimMutex = Mutex()

    /** Session id the Time Skip cursor belongs to; resets when the current action changes. */
    @Volatile
    private var fastForwardSessionId: String? = null

    /** Frame index up to which the current action has been fast-forwarded. */
    @Volatile
    private var fastForwardCursor: Int = 0

    // ------------------------------------------------------------------ upgrades (Base Reality)

    /** Skip durations offered by Fast Time Simulation, gated by [UPGRADE_SKIP_DURATION]. */
    val skipDurations: List<Int> = listOf(5, 15, 30, 60, 120, 240)

    /** Longest skip available at the given [UPGRADE_SKIP_DURATION] level. */
    fun maxSkipMinutes(level: Int): Int = when (level.coerceIn(0, MAX_SKIP_DURATION_LEVEL)) {
        0    -> 15
        1    -> 30
        2    -> 60
        3    -> 120
        else -> 240
    }

    /** Time Skip efficiency multiplier at the given [UPGRADE_EFFICIENCY] level (+5%/level). */
    fun efficiencyMultiplier(level: Int): Float =
        1f + 0.05f * level.coerceIn(0, MAX_EFFICIENCY_LEVEL)

    fun maxLevel(upgradeKey: String): Int = when (upgradeKey) {
        UPGRADE_SKIP_DURATION -> MAX_SKIP_DURATION_LEVEL
        UPGRADE_EFFICIENCY    -> MAX_EFFICIENCY_LEVEL
        else                  -> 0
    }

    /** Coin cost of the next level of [upgradeKey]; -1 when already maxed. */
    fun upgradeCost(upgradeKey: String, level: Int): Long {
        val max = maxLevel(upgradeKey)
        if (level >= max) return -1L
        val base = when (upgradeKey) {
            UPGRADE_SKIP_DURATION -> 10_000L
            UPGRADE_EFFICIENCY    -> 7_500L
            else                  -> return -1L
        }
        return base * listOf(1L, 3L, 9L, 27L, 81L, 243L)[level.coerceIn(0, 5)]
    }

    fun upgradeLevel(flags: PlayerFlags, upgradeKey: String): Int =
        flags.simulatorUpgrades[upgradeKey] ?: 0

    // ------------------------------------------------------------------ write isolation hooks
    //
    // The DAO decorators MUST NOT route based on the LIVE isSimulationActive flag alone: an
    // operation that reads sim state, suspends, then has death flip the flag underneath it
    // would otherwise resume and write to the real DAO.  Instead, every hook first checks the
    // pinned coroutine context (set by withSimulatorBoundary at operation start) — a simple
    // boolean: "this op started in sim" or "this op started in base".  Reads/writes from a
    // pinned-to-sim op still go against the LIVE _simPlayer / _simSessions StateFlows so
    // that concurrent sim ops observe each other's latest changes.  When there is no pinned
    // context (long-lived Flows, alarm side-effects, callers that bypassed the boundary) we
    // fall back to the live flag.

    /** True when the CURRENT execution is pinned to Layer 2 (started inside a sim boundary). */
    internal fun isPinnedToSim(): Boolean = pinnedActive() == true

    /** Returns whether a DAO access from the current execution should hit sim state. */
    internal fun effectiveActive(): Boolean =
        pinnedActive() ?: isSimulationActive

    /** Player read: pinned-to-sim reads the live sim player, so concurrent ops see each other. */
    internal fun effectiveSimPlayer(): Player? {
        val p = pinnedActive()
        return if (p != null) {
            if (p) _simPlayer.value else null
        } else {
            if (isSimulationActive) _simPlayer.value else null
        }
    }

    /** Sessions read: pinned-to-sim reads the live sim sessions list. */
    internal fun effectiveSimSessions(): List<SkillSession>? {
        val p = pinnedActive()
        return if (p != null) {
            if (p) _simSessions.value else null
        } else {
            if (isSimulationActive) _simSessions.value else null
        }
    }

    /** Player write. */
    internal fun onSimWrite(player: Player): Boolean {
        if (!effectiveActive()) return false
        _simPlayer.value = player
        return true
    }

    /** Flags write — uses the LIVE current sim player (not a stale snapshot). */
    internal fun onSimFlagsWrite(flags: String): Boolean {
        if (!effectiveActive()) return false
        val current = _simPlayer.value ?: return true
        _simPlayer.value = current.copy(flags = flags)
        return true
    }

    /** Session read. */
    internal fun simSessionsOrNull(): List<SkillSession>? = effectiveSimSessions()

    /** Session write: applies [block] to the live sim sessions list when sim-active. */
    internal fun updateSimSessions(block: (List<SkillSession>) -> List<SkillSession>): Boolean {
        if (!effectiveActive()) return false
        _simSessions.update(block)
        return true
    }

    // ------------------------------------------------------------------ lifecycle boundary

    /**
     * Runs [block] under the simulator lifecycle boundary. Non-inline so coroutine
     * context propagation (PinnedSimElement) works across suspensions and dispatcher
     * hops. Callers use labeled returns (`return@withSimulatorBoundary`) for early
     * exits. Nested calls inherit the outer pinned layer (outermost-wins, no double
     * counting of inFlightOperations).
     *
     * @PublishedApi so inline repository callers in the same module can invoke it.
     */
    @PublishedApi
    internal suspend fun <T> withSimulatorBoundary(block: suspend () -> T): T {
        if (pinnedActive() != null) return block()
        val active = simulatorMutex.withLock {
            inFlightOperations++
            isSimulationActive
        }
        try {
            return withContext(PinnedSimElement(active)) { block() }
        } finally {
            simulatorMutex.withLock { inFlightOperations-- }
        }
    }

    /**
     * Wait under [simulatorMutex] until every in-flight [withSimulatorBoundary] block has
     * finished.  Used by lifecycle transitions to drain outstanding operations before
     * flipping the active flag — the flip itself is what keeps future DAO calls out of the
     * (now-destroyed) sim state, so we must be sure no in-flight op is mid-write.
     */
    private suspend fun awaitDrainInFlight() {
        // Spin-yield loop under the mutex — inFlightOperations always decreases under the
        // mutex so the moment it reaches 0 is a stable point.
        while (true) {
            val drained = simulatorMutex.withLock {
                if (inFlightOperations == 0) true else {
                    false
                }
            }
            if (drained) return
            yield()
        }
    }

    // ------------------------------------------------------------------ run ownership

    /**
     * True when [activeSlot] is the character/save slot this run belongs to. Every Simulator
     * operation and every reward claim is rejected while it is false, so a run's isolated
     * state can never be read from — or claimed onto — a different character.
     */
    fun ownsRun(activeSlot: Int): Boolean = originSlot > 0 && activeSlot == originSlot

    // ------------------------------------------------------------------ enter / exit

    /**
     * Activated from Base Reality: deep-copy checkpoints the full player state, snapshots the
     * session table into [simSessions], and starts an exact parallel copy of the game on that
     * temporary state. [activeSlot] is the character/save slot this run is bound to.
     *
     * The player + session snapshot is taken atomically under [playerMutex] (via
     * [PlayerRepository.withLock]) so a concurrent Base Reality update cannot race between
     * the two reads and produce a mixed old/new starting state.
     */
    suspend fun enterSimulation(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
    ) {
        // Serialize concurrent enterSimulation calls so two coroutines cannot both pass
        // the "not active" check and publish two overlapping Layer 2 states. This gate is
        // only ever acquired by enterSimulation and is never held while any other lock
        // (simulatorMutex, playerMutex) is held for more than a brief snapshot, so it
        // cannot create deadlocks. tryLock is used so a second concurrent ENTER is a
        // no-op (a run is already being set up).
        if (!entryGate.tryLock()) return
        try {
            enterSimulationGuarded(playerRepository, sessionRepository, activeSlot)
        } finally {
            entryGate.unlock()
        }
    }

    private suspend fun enterSimulationGuarded(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
    ) {
        // Phase 1: take simulatorMutex and check we can enter.
        simulatorMutex.withLock {
            if (isSimulationActive) return
            if (activeSlot <= 0) {
                _runInvalidated.value = true
                _phase.value = Phase.BASE_REALITY
                return
            }
        }

        // Phase 2: drain any in-flight operations. With entryGate held, no other
        // enterSimulation can sneak in. Draining ensures no prior boundary op is
        // mid-write when we snapshot.
        awaitDrainInFlight()

        lateinit var playerSnapshot: Player
        lateinit var sessionSnapshot: List<SkillSession>
        var flagsSnapshot: PlayerFlags? = null
        var snapshotOk = true
        try {
            // Atomic snapshot: hold playerRepository.withLock { ... } (which takes
            // playerMutex) and read both player (via getOrCreatePlayerUnlocked, which
            // bypasses the boundary to avoid re-acquiring simulatorMutex) and sessions
            // (via getAllSessionsDirect which also bypasses the boundary and hits the
            // real DAO directly since isSimulationActive is still false). Session writes
            // from other repository methods do not take playerMutex, but:
            //   * sim ops can't run (isSimulationActive is false + drained)
            //   * base-reality session writes do happen via withSimulatorBoundary (which
            //     routes to real DAO), but those ops are infrequent (alarm-triggered
            //     completions, watchdog ticks, queued starts) and their effect on the
            //     snapshot is acceptable as an edge case equivalent to a write just
            //     before the player tapped Enter Simulator.
            playerRepository.withLock {
                val p = playerRepository.getOrCreatePlayerUnlocked()
                val f: PlayerFlags? = try { json.decodeFromString<PlayerFlags>(p.flags) } catch (_: Exception) { null }
                if (f == null) { snapshotOk = false; return@withLock }
                playerSnapshot = p.copy()
                flagsSnapshot = f
                sessionSnapshot = try { sessionRepository.getAllSessionsDirect() } catch (_: Exception) { emptyList() }
            }
        } catch (_: Exception) {
            snapshotOk = false
        }

        if (!snapshotOk || flagsSnapshot == null) {
            _crashedLastRun.value = true
            return
        }

        val flags = flagsSnapshot!!
        val player = playerSnapshot

        // Crash marker — a Base Reality write (isSimulationActive still false).
        try {
            playerRepository.updateFlags(flags.copy(simRunActiveSince = System.currentTimeMillis()))
        } catch (_: Exception) { /* continue even if we couldn't stamp */ }

        // Phase 3: re-acquire simulatorMutex, re-verify, publish. With entryGate held
        // across this whole method, no other enterSimulation could have published.
        // Re-verify isSimulationActive as a defence against direct forced teardown
        // (recoverFromCrash / discardRun) that might have flipped state while we were
        // snapshotting — if a forced teardown happened, abort the entry cleanly.
        simulatorMutex.withLock {
            if (isSimulationActive) return
            checkpoint = player.copy()
            _simPlayer.value = player.copy()
            fastForwardSessionId = null
            fastForwardCursor = 0
            _lastSkipResult.value = null
            _runInvalidated.value = false
            originSlot = activeSlot
            generation += 1
            activeGeneration = generation
            isSimulationActive = true
            _simSessions.value = sessionSnapshot
            _phase.value = Phase.IN_SIMULATION
        }
    }

    /**
     * Internal transition from IN_SIMULATION to REWARD_SELECTION on player death.
     *
     * Ordering:
     *   1. Under simulatorMutex: flip isSimulationActive = false (stop admitting new sim
     *      ops), but DO NOT clear checkpoint / _simPlayer / _simSessions / originSlot yet.
     *      Reward selection needs that state to compute rewardPool() and apply rewards.
     *   2. Drain in-flight ops while state is alive.
     *   3. Clear the crash marker for THIS generation (owns the marker iff originSlot/
     *      activeGeneration still match). Move to REWARD_SELECTION; checkpoint / simPlayer
     *      / simSessions remain set until claimRewards/abort clears them.
     */
    private suspend fun exitSimulationInternal(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
    ) {
        data class ExitInfo(val runGeneration: Long, val owned: Boolean)
        var earlyReturn = false
        val info: ExitInfo? = simulatorMutex.withLock {
            if (!isSimulationActive) { earlyReturn = true; null }
            else {
                val o = ownsRun(activeSlot)
                isSimulationActive = false
                ExitInfo(activeGeneration, o)
            }
        }
        if (earlyReturn) return
        awaitDrainInFlight()
        if (!info!!.owned) {
            invalidateRun(playerRepository)
            return
        }
        clearCrashMarkerIfOwned(playerRepository, info.runGeneration)
        _phase.value = Phase.REWARD_SELECTION
    }

    /**
     * Clear the Base Reality crash marker only if the current activeGeneration matches
     * [ownedByGeneration]. This prevents an old death/abort/recovery from clearing a
     * newer run's crash marker (e.g. when a new run is started after a crash recovery).
     */
    private suspend fun clearCrashMarkerIfOwned(playerRepository: PlayerRepository, ownedByGeneration: Long) {
        try {
            val flags = playerRepository.getFlags()
            if (flags.simRunActiveSince <= 0L) return
            // Only clear if the run we're tearing down is still the one that set the marker
            // (activeGeneration hasn't advanced to a new run since we captured ownedByGeneration).
            if (activeGeneration != ownedByGeneration) return
            playerRepository.updateFlags(flags.copy(simRunActiveSince = 0L))
        } catch (_: Exception) { /* never block teardown for a marker clear */ }
    }

    /**
     * Voluntary/manual exit from Layer 2.  This is NO LONGER a valid gameplay transition:
     * Layer 2 ends only when the simulated player dies.  Public API retained for internal
     * callers (crash-recovery tests, legacy call sites that are now no-ops during normal
     * play); the call is ignored while a normal simulation is active.
     *
     * Death is the only controlled way out and is handled by [acknowledgeDeath].
     */
    suspend fun exitSimulation(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
        died: Boolean = false,
    ) {
        // Only the death path is permitted to end Layer 2.  Voluntary/manual calls (which
        // historically passed died = false, the default) are silently ignored so there is no
        // normal-gameplay exit path.  Internal forced teardown (crash, invalidation,
        // recovery) uses discardRun / invalidateRun / recoverFromCrash directly.
        if (!died) return
        exitSimulationInternal(playerRepository, sessionRepository, activeSlot)
    }

    /** Discards the run without rewards (used when the reward pool is empty or from crash paths). */
    suspend fun abortToBaseReality(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
    ) {
        discardRun(playerRepository)
    }

    /**
     * Drops the isolated session table: every session the run created, completed, collected,
     * abandoned, deleted, or Time Skipped disappears with it. Because session writes never
     * reach the real [com.fantasyidler.data.db.dao.SkillSessionDao], no Base Reality session
     * is completed, overwritten, or deleted here.
     */
    private fun discardSimulationSessions() {
        _simSessions.value = emptyList()
        fastForwardSessionId = null
        fastForwardCursor = 0
    }

    private fun discardSimulationSessionsLocked() {
        _simSessions.value = emptyList()
        fastForwardSessionId = null
        fastForwardCursor = 0
    }

    /**
     * Forced teardown (crash recovery, invalidation, abort, reward-skip). Flip inactive,
     * drain, then clear state. Crash marker is cleared only if it still belongs to THIS
     * generation.
     */
    private suspend fun discardRun(playerRepository: PlayerRepository) {
        val runGeneration = simulatorMutex.withLock {
            isSimulationActive = false
            _phase.value = Phase.BASE_REALITY
            activeGeneration
        }
        awaitDrainInFlight()
        simulatorMutex.withLock {
            if (activeGeneration == runGeneration) {
                checkpoint = null
                _simPlayer.value = null
                discardSimulationSessionsLocked()
                originSlot = 0
                activeGeneration = 0L
            }
        }
        clearCrashMarkerIfOwned(playerRepository, runGeneration)
    }

    /**
     * Forced teardown callable FROM INSIDE a pinned-to-sim op (e.g. timeSkip detects
     * corruption). Flips isSimulationActive=false under the mutex so no new boundary can
     * pin to sim, but does NOT clear state here — that has to wait for the caller to
     * unwind and for drain to finish. We schedule a discard via scope launch so the
     * final cleanup happens after the in-flight op finishes.
     */
    private suspend fun discardRunFromWithinBoundary(playerRepository: PlayerRepository) {
        val runGeneration = simulatorMutex.withLock {
            isSimulationActive = false
            _phase.value = Phase.BASE_REALITY
            activeGeneration
        }
        // Launch the actual cleanup to run AFTER this in-flight op returns (it waits for
        // in-flight ops to drain). Crash marker is cleared as part of that cleanup.
        scope.launch {
            awaitDrainInFlight()
            simulatorMutex.withLock {
                if (activeGeneration == runGeneration) {
                    checkpoint = null
                    _simPlayer.value = null
                    discardSimulationSessionsLocked()
                    originSlot = 0
                    activeGeneration = 0L
                }
            }
            clearCrashMarkerIfOwned(playerRepository, runGeneration)
        }
    }

    private suspend fun invalidateRun(playerRepository: PlayerRepository) {
        discardRun(playerRepository)
        _runInvalidated.value = true
    }

    private suspend fun invalidateRunFromWithinBoundary(playerRepository: PlayerRepository) {
        discardRunFromWithinBoundary(playerRepository)
        _runInvalidated.value = true
    }

    /** Skip rewards: same discard path used after reward selection is abandoned/aborted. */
    suspend fun skipRewards(playerRepository: PlayerRepository) {
        discardRun(playerRepository)
    }

    // ------------------------------------------------------------------ Fast Time Simulation

    /** Time Skip is only available inside the Simulator. */
    fun canTimeSkip(): Boolean = isSimulationActive

    /** True when [session] has been fully fast-forwarded by Time Skip. */
    fun fastForwardComplete(session: SkillSession): Boolean {
        if (fastForwardSessionId != session.sessionId) return false
        val frames: List<SessionFrame> = try {
            json.decodeFromString(session.frames)
        } catch (_: Exception) {
            emptyList()
        }
        return frames.isNotEmpty() && fastForwardCursor >= frames.size
    }

    /**
     * Fast Time Simulation: instantly advances the current action by [minutes], applying the
     * session's own pre-simulated frames — the exact same rate calculations a real session
     * uses — to the temporary simulation state only. Every field a normal collect processes is
     * processed here too: XP, loot, coins, food/arrow/rune consumption (with the level-based
     * reclaim), death penalties, and boss win/loss rewards. All gains remain in the pick-3
     * reward pool. A death frame ends the run.
     *
     * The skipped frames stay in place as reward-free frames, so the isolated session keeps its
     * minute indices and elapsed position and a later normal collect pays out only the minutes
     * that were not skipped — never twice, and never nothing. Reaching the last frame (or a
     * death) completes the isolated session exactly like the completion alarm does.
     */
    suspend fun timeSkip(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        boostRepository: BoostRepository,
        minutes: Int,
        activeSlot: Int,
    ): TimeSkipResult = withSimulatorBoundary {
        if (!isSimulationActive) return@withSimulatorBoundary TimeSkipResult()
        if (!ownsRun(activeSlot)) {
            invalidateRunFromWithinBoundary(playerRepository)
            return@withSimulatorBoundary TimeSkipResult()
        }
        val session = try {
            sessionRepository.getActiveSession()
        } catch (_: Exception) {
            null
        }
        // No action running inside the simulation: nothing to advance (not an error).
        if (session == null) return@withSimulatorBoundary TimeSkipResult()
        val frames: List<SessionFrame> = try {
            json.decodeFromString(session.frames)
        } catch (_: Exception) {
            // Corrupt frame data: discard only the temporary state and report the error.
            discardRunFromWithinBoundary(playerRepository)
            _crashedLastRun.value = true
            return@withSimulatorBoundary TimeSkipResult()
        }
        // A zero-duration session has no frames to advance; that is not an error.
        if (frames.isEmpty()) return@withSimulatorBoundary TimeSkipResult()
        if (fastForwardSessionId != session.sessionId) {
            fastForwardSessionId = session.sessionId
            fastForwardCursor = 0
        }
        val from = fastForwardCursor.coerceIn(0, frames.size)
        if (from >= frames.size) return@withSimulatorBoundary TimeSkipResult()
        val to = (from + minutes.coerceAtLeast(0)).coerceAtMost(frames.size)
        if (to <= from) return@withSimulatorBoundary TimeSkipResult()

        val slice = frames.subList(from, to)
        // Death inside the skip ends the run at the death minute.
        val deathIndex = slice.indexOfFirst { it.died }
        val consumed = if (deathIndex >= 0) slice.subList(0, deathIndex + 1) else slice
        val died = deathIndex >= 0

        val isBoss = session.skillName == "boss"
        // A boss pays loot and coins only on a win (HomeViewModel.collectBossSession's rule);
        // every other session type keeps whatever its frames rolled.
        val bossWon = !isBoss || ((consumed.lastOrNull()?.kills ?: 0) > 0 && !died)

        val xpBySkill = mutableMapOf<String, Long>()
        val items     = mutableMapOf<String, Int>()
        val food      = mutableMapOf<String, Int>()
        val arrows    = mutableMapOf<String, Int>()
        val runes     = mutableMapOf<String, Int>()
        for (frame in consumed) {
            if (frame.xpBySkill.isNotEmpty()) {
                for ((skill, xp) in frame.xpBySkill) xpBySkill[skill] = (xpBySkill[skill] ?: 0L) + xp
            } else if (frame.xpGain > 0) {
                xpBySkill[session.skillName] = (xpBySkill[session.skillName] ?: 0L) + frame.xpGain
            }
            for ((item, qty) in frame.items)           items[item]  = (items[item] ?: 0) + qty
            for ((key, qty) in frame.foodConsumed)     food[key]    = (food[key] ?: 0) + qty
            for ((key, qty) in frame.arrowsConsumed)   arrows[key]  = (arrows[key] ?: 0) + qty
            for ((key, qty) in frame.runesConsumed)    runes[key]   = (runes[key] ?: 0) + qty
        }

        val flags = playerRepository.getFlags()
        // A dungeon/combat/tower death keeps only deathKeepFraction of the run's XP and loot,
        // exactly like a normal collect of the same frames.
        if (died && !isBoss) {
            val keep = boostRepository.deathKeepFraction(flags)
            xpBySkill.replaceAll { _, xp -> maxOf(1L, (xp * keep).toLong()) }
            items.replaceAll { _, qty -> maxOf(0, (qty * keep).toInt()) }
            items.entries.removeIf { it.value == 0 }
        }
        val coins = if (bossWon) (items.remove(COINS_ID)?.toLong() ?: 0L) else 0L
        if (!bossWon) items.clear()

        // Same collect path a real session uses (boosts, blessings, sigils), so the
        // rate calculations are identical; the Simulator efficiency upgrade scales
        // the result on top of those identical base rates.
        val efficiency = efficiencyMultiplier(upgradeLevel(flags, UPGRADE_EFFICIENCY))
        if (xpBySkill.size > 1 || coins > 0L || session.skillName == "combat" || isBoss) {
            playerRepository.applyMultiSkillResults(
                xpPerSkill            = xpBySkill,
                itemsGained           = items,
                coinsGained           = coins,
                efficiencyMultiplier  = efficiency,
                sessionId             = session.sessionId,
            )
        } else {
            playerRepository.applySessionResults(
                skillName            = session.skillName,
                xpGained             = xpBySkill.values.sum(),
                itemsGained          = items,
                efficiencyMultiplier = efficiency,
                sessionId            = session.sessionId,
            )
        }

        // Consumables: every resource field the consumed frames carry is deducted — and arrows
        // and runes partly reclaimed — with the same semantics as a normal collect, so a Time
        // Skip costs exactly what playing those minutes would have cost.
        if (food.isNotEmpty()) playerRepository.consumeItems(food)
        if (arrows.isNotEmpty()) playerRepository.consumeItems(arrows)
        val levels = playerRepository.getSkillLevels()
        val arrowsReclaimed = arrows.mapValues { (_, qty) ->
            (qty * (reclaimChance(levels[Skills.RANGED] ?: 1) + boostRepository.arrowReclaimBonus(flags)).coerceAtMost(0.95)).toInt()
        }.filterValues { it > 0 }
        val runesReclaimed = runes.mapValues { (_, qty) ->
            (qty * (reclaimChance(levels[Skills.MAGIC] ?: 1) + boostRepository.runeReclaimBonus(flags)).coerceAtMost(0.95)).toInt()
        }.filterValues { it > 0 }
        if (arrowsReclaimed.isNotEmpty()) playerRepository.addItems(arrowsReclaimed)
        if (runesReclaimed.isNotEmpty()) playerRepository.addItems(runesReclaimed)

        // Advance the isolated session: consumed frames keep their minute index and combat
        // context but carry no rewards, so later normal progression continues from exactly the
        // right frame and can never pay the same minute out a second time.
        val rewritten = frames.mapIndexed { index, frame ->
            if (index in from until from + consumed.size) consumedFrame(frame) else frame
        }
        val reachedEnd = from + consumed.size >= frames.size
        // Back-date the session by the skipped wall-clock (the same trick a queued/offline start
        // uses) so the frames that were not skipped keep their one-minute delivery rate and the
        // session's elapsed position matches the cursor.
        val perFrameMs = ((session.endsAt - session.startedAt) / frames.size).coerceAtLeast(0L)
        val skippedMs = perFrameMs * consumed.size
        val advanced = if (skippedMs > 0L) session.copy(
            startedAt      = session.startedAt - skippedMs,
            endsAt         = session.endsAt - skippedMs,
            startElapsedMs = session.startElapsedMs?.minus(skippedMs),
        ) else session
        try {
            sessionRepository.insertSession(
                advanced.copy(frames = json.encodeToString(json.serializersModule.serializer<List<SessionFrame>>(), rewritten))
            )
            // Ending on the last frame — or on a death — completes the session the same way the
            // completion alarm does, so its elapsed/completion state matches a normal collect.
            if (reachedEnd || died) sessionRepository.markCompleted(session.sessionId)
        } catch (_: Exception) {
            // Missing or corrupt session state: discard only the temporary state and report it.
            discardRunFromWithinBoundary(playerRepository)
            _crashedLastRun.value = true
            return@withSimulatorBoundary TimeSkipResult()
        }

        val result = TimeSkipResult(
            minutes        = consumed.size,
            xpBySkill      = xpBySkill,
            items          = items,
            coins          = coins,
            died           = died,
            foodConsumed   = food,
            arrowsConsumed = arrows,
            runesConsumed  = runes,
        )
        fastForwardCursor = from + consumed.size
        _lastSkipResult.value = result
        _phase.value = if (died) Phase.SIM_DEATH else Phase.TIME_SKIP_RESULT
        result
    }

    /** Arrow/rune reclaim chance per level — the same curve the collect path uses. */
    private fun reclaimChance(level: Int): Double = 0.25 + (level - 1) / 98.0 * 0.50

    /**
     * A frame whose gains were already applied by Time Skip: it keeps its minute, level, and
     * combat context for the session banner, but yields nothing (no XP, loot, coins, kills, or
     * consumables) if the session is collected normally afterwards.
     */
    private fun consumedFrame(frame: SessionFrame) = frame.copy(
        xpGain         = 0,
        xpBySkill      = emptyMap(),
        items          = emptyMap(),
        kills          = 0,
        killsByEnemy   = emptyMap(),
        levelUps       = emptyList(),
        leveledUp      = false,
        foodConsumed   = emptyMap(),
        arrowsConsumed = emptyMap(),
        runesConsumed  = emptyMap(),
    )

    // ------------------------------------------------------------------ rewards

    /**
     * The pick-3 pool: every gain the temporary state made over the checkpoint,
     * grouped as Stats (levels, coins) / Skill Experience / Items.
     */
    fun rewardPool(): List<SimReward> {
        val base = checkpoint ?: return emptyList()
        val sim = _simPlayer.value ?: return emptyList()
        val pool = mutableListOf<SimReward>()

        val baseLevels: Map<String, Int> = json.decodeFromString(base.skillLevels)
        val simLevels: Map<String, Int> = json.decodeFromString(sim.skillLevels)
        val baseXp: Map<String, Long> = json.decodeFromString(base.skillXp)
        val simXp: Map<String, Long> = json.decodeFromString(sim.skillXp)
        val baseInv: Map<String, Int> = json.decodeFromString(base.inventory)
        val simInv: Map<String, Int> = json.decodeFromString(sim.inventory)

        for ((skill, level) in simLevels) {
            val delta = level - (baseLevels[skill] ?: 1)
            if (delta > 0) pool += SimReward(RewardKind.STATS, skill, delta.toLong())
        }
        val coinDelta = sim.coins - base.coins
        if (coinDelta > 0) pool += SimReward(RewardKind.STATS, COINS_ID, coinDelta)

        for ((skill, xp) in simXp) {
            val delta = xp - (baseXp[skill] ?: 0L)
            if (delta > 0) pool += SimReward(RewardKind.SKILL_XP, skill, delta)
        }
        for ((item, qty) in simInv) {
            val delta = qty - (baseInv[item] ?: 0)
            if (delta > 0) pool += SimReward(RewardKind.ITEM, item, delta.toLong())
        }
        return pool
    }

    /**
     * Applies up to [MAX_REWARDS] selected rewards to Base Reality and finishes the
     * return. Accepts any [Iterable] of [SimReward] so callers can pass either a List
     * (from rewardPool filtering) or a Set (from UI toggle state). Anything not selected
     * is discarded with the temporary state.
     */
    suspend fun claimRewards(
        playerRepository: PlayerRepository,
        selected: Iterable<SimReward>,
        activeSlot: Int,
    ): Boolean = claimMutex.withLock {
        data class ClaimSnapshot(val runGeneration: Long, val owned: Boolean, val base: Player, val sim: Player)
        var earlyReturn: Boolean? = null
        val snap: ClaimSnapshot? = simulatorMutex.withLock {
            if (isSimulationActive) { earlyReturn = false; null }
            else {
                val b = checkpoint
                val s = _simPlayer.value
                if (b == null || s == null) { earlyReturn = true; null }
                else ClaimSnapshot(
                    runGeneration = activeGeneration,
                    owned = ownsRun(activeSlot),
                    base = b,
                    sim = s,
                )
            }
        }
        if (earlyReturn != null) {
            if (earlyReturn == true) discardRun(playerRepository)
            return@withLock earlyReturn == true
        }
        if (!snap!!.owned) {
            invalidateRun(playerRepository)
            return@withLock false
        }
        val runGeneration = snap.runGeneration
        val base = snap.base
        val sim = snap.sim
        val checkpointXp: Map<String, Long> = try {
            json.decodeFromString(base.skillXp)
        } catch (_: Exception) {
            emptyMap()
        }
        try {
            for (reward in selected.take(MAX_REWARDS)) {
                when (reward.kind) {
                    RewardKind.STATS -> if (reward.id == COINS_ID) {
                        playerRepository.addCoins(reward.amount)
                    } else {
                        // Retain the levels gained: top the skill up to the level the run
                        // reached, relative to the checkpoint (order-independent with SKILL_XP).
                        val currentXp = playerRepository.getSkillXp()[reward.id] ?: 0L
                        val targetLevel = XpTable.levelForXp(checkpointXp[reward.id] ?: 0L) + reward.amount.toInt()
                        val targetXp = XpTable.xpForLevel(targetLevel)
                        if (targetXp > currentXp) {
                            playerRepository.applySessionResults(
                                skillName      = reward.id,
                                xpGained       = targetXp - currentXp,
                                itemsGained    = emptyMap(),
                                applyXpBoosts  = false,
                            )
                        }
                    }
                    RewardKind.SKILL_XP -> if (reward.amount > 0) {
                        val currentXp = playerRepository.getSkillXp()[reward.id] ?: 0L
                        val targetXp = (checkpointXp[reward.id] ?: 0L) + reward.amount
                        if (targetXp > currentXp) {
                            playerRepository.applySessionResults(
                                skillName      = reward.id,
                                xpGained       = targetXp - currentXp,
                                itemsGained    = emptyMap(),
                                applyXpBoosts  = false,
                            )
                        }
                    }
                    RewardKind.ITEM -> if (reward.amount > 0) {
                        playerRepository.grantItem(reward.id, reward.amount.toInt())
                    }
                }
            }
        } catch (_: Exception) {
            discardRun(playerRepository)
            _crashedLastRun.value = true
            return@withLock false
        }
        // Drain any late in-flight ops before clearing sim state.
        awaitDrainInFlight()
        simulatorMutex.withLock {
            if (activeGeneration == runGeneration) {
                checkpoint = null
                _simPlayer.value = null
                discardSimulationSessionsLocked()
                originSlot = 0
                activeGeneration = 0L
            }
            _phase.value = Phase.BASE_REALITY
        }
        clearCrashMarkerIfOwned(playerRepository, runGeneration)
        true
    }

    // ------------------------------------------------------------------ crash recovery

    /**
     * Force-close / crash during a simulation leaves the crash marker set in Base Reality. The
     * temporary state (player checkpoint and isolated session table) only ever lived in memory,
     * so it died with the process; recovery discards what is left of it and clears the marker
     * without touching a single Base Reality session or player value
     * ("An unexpected error occurred. Returning to Base Reality.").
     */
    fun scheduleCrashRecovery(playerRepository: PlayerRepository, sessionRepository: SessionRepository) {
        scope.launch {
            try {
                recoverFromCrash(playerRepository, sessionRepository)
            } catch (_: Exception) {
                // Recovery must never crash the app.
            }
        }
    }

    internal suspend fun recoverFromCrash(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
    ) {
        val runGeneration = simulatorMutex.withLock {
            isSimulationActive = false
            _phase.value = Phase.BASE_REALITY
            activeGeneration
        }
        awaitDrainInFlight()
        simulatorMutex.withLock {
            if (activeGeneration == runGeneration) {
                _simPlayer.value = null
                checkpoint = null
                discardSimulationSessionsLocked()
                originSlot = 0
                activeGeneration = 0L
            }
        }
        clearCrashMarkerIfOwned(playerRepository, runGeneration)
        _crashedLastRun.value = true
    }

    /** Consumes the crashed-last-run notice so it is shown once. */
    fun consumeCrashNotice() {
        _crashedLastRun.value = false
    }

    /** Consumes the invalid-run notice so it is shown once. */
    fun consumeInvalidRunNotice() {
        _runInvalidated.value = false
    }

    // ------------------------------------------------------------------ UI phases

    fun openTimeSkipConfirm() {
        if (isSimulationActive) _phase.value = Phase.TIME_SKIP_CONFIRM
    }

    fun cancelTimeSkip() {
        if (isSimulationActive) _phase.value = Phase.IN_SIMULATION
    }

    fun dismissTimeSkipResult() {
        _phase.value = if (isSimulationActive) Phase.IN_SIMULATION else Phase.REWARD_SELECTION
    }

    /**
     * Death in the Simulator: this is the sole normal-gameplay transition out of Layer 2.
     * Drains all in-flight session/player operations (they continue to write into sim state),
     * then deactivates isolation and moves to reward selection.
     */
    suspend fun acknowledgeDeath(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
    ) {
        exitSimulation(playerRepository, sessionRepository, activeSlot, died = true)
    }
}
