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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.math.roundToInt

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
 * On death or voluntary exit the temporary state is discarded and the player
 * may bring back up to [MAX_REWARDS] rewards (Stats / Skill Experience /
 * Items) from the gains made in that run.
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

    @Volatile
    var isSimulationActive: Boolean = false
        private set

    /** Deep-copy checkpoint of the full player state taken on Simulator entry. */
    @Volatile
    private var checkpoint: Player? = null

    /** Active save slot when the run started; 0 when no run exists. Rewards only ever go back to it. */
    @Volatile
    private var originSlot: Int = 0

    /** Serializes reward claims so a double tap can never apply the same reward twice. */
    private val claimMutex = Mutex()

    /**
     * Serializes every Simulator state transition, and the whole Fast Time Simulation
     * operation with them: entering, exiting, discarding, crash recovery, claiming, and Time
     * Skipping are mutually exclusive.
     *
     * [com.fantasyidler.repository.PlayerRepository.playerMutex] is not enough on its own — it
     * guards single writes, not the read-cursor → pay → advance-cursor sequence, so two Time
     * Skips could still read the same cursor and pay the same frames twice, and an exit could
     * still land between those steps. Every entry point into the simulator therefore takes
     * this lock for the whole operation, and the state transition ([isSimulationActive] plus
     * the temporary state) is atomic from the simulator's point of view.
     */
    private val simulatorMutex = Mutex()

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

    /** Called by [com.fantasyidler.data.db.dao.SimulationPlayerDao] on every player write. */
    internal fun onSimWrite(player: Player): Boolean {
        if (!isSimulationActive) return false
        _simPlayer.value = player
        return true
    }

    /** Called by [com.fantasyidler.data.db.dao.SimulationPlayerDao] on every flags write. */
    internal fun onSimFlagsWrite(flags: String): Boolean {
        if (!isSimulationActive) return false
        val current = _simPlayer.value ?: return true
        _simPlayer.value = current.copy(flags = flags)
        return true
    }

    /**
     * Called by [com.fantasyidler.data.db.dao.SimulationSessionDao] on every session read:
     * returns the isolated session table while a simulation is active, or null to read the
     * real DAO instead.
     */
    internal fun simSessionsOrNull(): List<SkillSession>? =
        if (isSimulationActive) _simSessions.value else null

    /**
     * Called by [com.fantasyidler.data.db.dao.SimulationSessionDao] on every session write.
     * Returns false when no simulation is active so the caller forwards to the real DAO.
     */
    internal fun updateSimSessions(block: (List<SkillSession>) -> List<SkillSession>): Boolean {
        if (!isSimulationActive) return false
        _simSessions.update(block)
        return true
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
     * Runs under [simulatorMutex], so entering can never race a Time Skip that is still
     * writing, or an exit that is still tearing a previous run down.
     */
    suspend fun enterSimulation(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
    ) = simulatorMutex.withLock {
        enterSimulationUnlocked(playerRepository, sessionRepository, activeSlot)
    }

    private suspend fun enterSimulationUnlocked(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
    ) {
        if (isSimulationActive) return
        // No valid originating character/save slot: nothing may be bound to, so the run is
        // rejected before any temporary state is created.
        if (activeSlot <= 0) {
            invalidateRun(playerRepository)
            return
        }
        val player = try { playerRepository.getOrCreatePlayer() } catch (_: Exception) { null }
        val flags: PlayerFlags? = try {
            player?.let { json.decodeFromString<PlayerFlags>(it.flags) }
        } catch (_: Exception) { null }
        if (player == null || flags == null) {
            // Missing or corrupt player data: fail safely rather than start a run that could
            // not be checkpointed ("An unexpected error occurred. Returning to Base Reality.").
            discardRun(playerRepository)
            _crashedLastRun.value = true
            return
        }
        // Player is a flat data class of immutable JSON columns: copy() is a full deep copy.
        checkpoint = player.copy()
        _simPlayer.value = player.copy()
        fastForwardSessionId = null
        fastForwardCursor = 0
        _lastSkipResult.value = null
        _runInvalidated.value = false
        originSlot = activeSlot
        // Read the real session table while isolation is still off, so the parallel run starts
        // from exactly the Base Reality session state (player, combat, boss, and every worker
        // slot — active and completed) and can never write back to it. A read failure must not
        // be mistaken for "this character has no sessions": entering with an empty snapshot
        // would run the parallel copy against state the player never had, so fail closed
        // ("An unexpected error occurred. Returning to Base Reality.") instead.
        val sessionSnapshot: List<SkillSession> = try {
            sessionRepository.getAllSessions()
        } catch (_: Exception) {
            discardRun(playerRepository)
            _crashedLastRun.value = true
            return
        }
        // Crash marker written to Base Reality before isolation activates, so a
        // force-close during the simulation is detected on the next launch.
        playerRepository.updateFlags(flags.copy(simRunActiveSince = System.currentTimeMillis()))
        isSimulationActive = true
        // Published after activation so session observers switch to the isolated table with the
        // snapshot already in place.
        _simSessions.value = sessionSnapshot
        _phase.value = Phase.IN_SIMULATION
    }

    /**
     * On death or voluntary exit: discard the temporary session state and return to Base
     * Reality. The run's gains become the pick-3 reward pool. [activeSlot] must still be the
     * character/save slot the run started from, otherwise no rewards may be offered.
     *
     * Runs under [simulatorMutex]: if a Time Skip is still in flight this waits for it — never
     * blocks a thread — so the simulator can never switch back to Base Reality while isolated
     * writes from that skip are still running.
     */
    suspend fun exitSimulation(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
        died: Boolean = false,
    ) = simulatorMutex.withLock {
        exitSimulationUnlocked(playerRepository, sessionRepository, activeSlot, died)
    }

    private suspend fun exitSimulationUnlocked(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
        died: Boolean = false,
    ) {
        if (!isSimulationActive) return
        isSimulationActive = false
        discardSimulationSessions()
        // A run whose character/save slot no longer matches must not offer rewards: claiming
        // them would apply one character's gains to another character's save.
        if (!ownsRun(activeSlot)) {
            invalidateRun(playerRepository)
            return
        }
        clearCrashMarker(playerRepository)
        _phase.value = Phase.REWARD_SELECTION
    }

    /** Discards the run without rewards (used when the reward pool is empty). */
    suspend fun abortToBaseReality(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
    ) = simulatorMutex.withLock {
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

    /**
     * Drops every piece of temporary simulation state and clears the Base Reality crash marker.
     * The real save keeps exactly the state it had before the run started — no stale simulated
     * value is ever restored over it.
     */
    private suspend fun discardRun(playerRepository: PlayerRepository) {
        isSimulationActive = false
        checkpoint = null
        _simPlayer.value = null
        discardSimulationSessions()
        originSlot = 0
        clearCrashMarker(playerRepository)
        _phase.value = Phase.BASE_REALITY
    }

    /**
     * Rejects a Simulator operation whose character/save slot no longer matches the run's
     * originating one: the temporary state is discarded and the run is reported invalid
     * ("Simulation is no longer valid. Returning to Base Reality.").
     */
    private suspend fun invalidateRun(playerRepository: PlayerRepository) {
        discardRun(playerRepository)
        _runInvalidated.value = true
    }

    /**
     * A run belongs to the character it was checkpointed from: importing, resetting, or
     * otherwise replacing that character invalidates the run, so its checkpoint — and every
     * reward in it — can never be claimed onto a different character's save
     * ("Simulation is no longer valid. Returning to Base Reality.").
     *
     * Called by [com.fantasyidler.repository.PlayerRepository] (from [PlayerRepository.importSave]
     * and [PlayerRepository.resetProgression], the two places every import / reset / slot switch
     * funnels through) *before* it overwrites the player row: afterwards the replacement's own
     * writes would land in Base Reality instead of the temporary state. Taking [simulatorMutex]
     * makes the caller wait for an in-flight Time Skip, so no simulated write can outlive the
     * character it belonged to.
     */
    internal suspend fun invalidateRunForReplacement() = simulatorMutex.withLock {
        if (!isSimulationActive && checkpoint == null && originSlot == 0) return@withLock
        isSimulationActive = false
        checkpoint = null
        _simPlayer.value = null
        discardSimulationSessions()
        originSlot = 0
        _lastSkipResult.value = null
        _phase.value = Phase.BASE_REALITY
        _runInvalidated.value = true
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
     *
     * The whole operation runs under [simulatorMutex], so only one Time Skip can execute for a
     * simulation at a time: a second call waits (it never blocks a thread) instead of reading
     * the same cursor and paying the same frames a second time, and an exit can only run once
     * this one has finished.
     */
    suspend fun timeSkip(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        boostRepository: BoostRepository,
        minutes: Int,
        activeSlot: Int,
    ): TimeSkipResult = simulatorMutex.withLock {
        timeSkipUnlocked(playerRepository, sessionRepository, boostRepository, minutes, activeSlot)
    }

    private suspend fun timeSkipUnlocked(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        boostRepository: BoostRepository,
        minutes: Int,
        activeSlot: Int,
    ): TimeSkipResult {
        if (!isSimulationActive) return TimeSkipResult()
        if (!ownsRun(activeSlot)) {
            invalidateRun(playerRepository)
            return TimeSkipResult()
        }
        val session = try {
            sessionRepository.getActiveSession()
        } catch (_: Exception) {
            null
        }
        // No action running inside the simulation: nothing to advance (not an error).
        if (session == null) return TimeSkipResult()
        val frames: List<SessionFrame> = try {
            json.decodeFromString(session.frames)
        } catch (_: Exception) {
            // Corrupt frame data: discard only the temporary state and report the error.
            discardRun(playerRepository)
            _crashedLastRun.value = true
            return TimeSkipResult()
        }
        // A zero-duration session has no frames to advance; that is not an error.
        if (frames.isEmpty()) return TimeSkipResult()
        if (fastForwardSessionId != session.sessionId) {
            fastForwardSessionId = session.sessionId
            fastForwardCursor = 0
        }
        val from = fastForwardCursor.coerceIn(0, frames.size)
        if (from >= frames.size) return TimeSkipResult()
        val to = (from + minutes.coerceAtLeast(0)).coerceAtMost(frames.size)
        if (to <= from) return TimeSkipResult()

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
        // A winning boss pays the same daily soft-capped coin drop a normal collect pays
        // (HomeViewModel.collectBossSession): full coins for the first kills of the day,
        // BOSS_COIN_SOFT_CAP_MULT for every one after that. Non-boss sessions keep their coins.
        val bossCoinMult = if (isBoss && bossWon) playerRepository.rollBossCoinSoftCap(session.activityKey) else 1.0
        val coins = if (bossWon) ((items.remove(COINS_ID)?.toLong() ?: 0L) * bossCoinMult).toLong() else 0L
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
            // Item yield follows the normal collect of these frames too: prestige yield nodes
            // plus the flow-state ramp (HomeViewModel.collectGenericSkillSession). Dungeon loot
            // above is deliberately not scaled, and the cape multiplier lives in HomeViewModel.
            val sessionDurMs = (session.endsAt - session.startedAt).coerceAtLeast(0L)
            val yieldMult = boostRepository.yieldMultiplier(session.skillName, flags) *
                boostRepository.flowMultiplier(
                    session.skillName, flags, boostRepository.flowElapsedMs(flags, session.skillName, sessionDurMs),
                )
            if (yieldMult > 1.0) items.replaceAll { _, qty -> (qty * yieldMult).roundToInt().coerceAtLeast(qty) }
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
            discardRun(playerRepository)
            _crashedLastRun.value = true
            return TimeSkipResult()
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
        return result
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
     * return. Anything not selected is discarded with the temporary state.
     *
     * Rewards go only to the character/save slot that started the run ([activeSlot] must still
     * match [ownsRun]), and only once: the claim is serialized on [claimMutex] and clears the
     * checkpoint it pays out from, so a second or concurrent claim finds nothing left to apply.
     *
     * The payout itself is one all-or-nothing transaction: either every selected reward lands
     * in Base Reality or none does, and the checkpoint is only cleared after that transaction
     * commits, so a failure leaves the rewards recoverable instead of half paid.
     *
     * Returns true when this call completed the return to Base Reality.
     */
    suspend fun claimRewards(
        playerRepository: PlayerRepository,
        selected: List<SimReward>,
        activeSlot: Int,
    ): Boolean = claimMutex.withLock {
        // A claim and any simulator state transition (a new run starting, a run being
        // discarded) must never overlap: these writes only belong to Base Reality while no
        // simulation is active.
        simulatorMutex.withLock {
            claimRewardsUnlocked(playerRepository, selected, activeSlot)
        }
    }

    private suspend fun claimRewardsUnlocked(
        playerRepository: PlayerRepository,
        selected: List<SimReward>,
        activeSlot: Int,
    ): Boolean {
        // Claiming while a run is still active would apply rewards to the temporary state.
        if (isSimulationActive) return false
        val base = checkpoint
        val sim = _simPlayer.value
        if (base == null || sim == null) {
            // Nothing left to claim (already claimed, or the run was discarded): finish the
            // return without applying anything a second time.
            discardRun(playerRepository)
            return true
        }
        // Not the character/save slot this run belongs to: no reward may cross characters.
        if (!ownsRun(activeSlot)) {
            invalidateRun(playerRepository)
            return false
        }
        val checkpointXp: Map<String, Long> = try {
            json.decodeFromString(base.skillXp)
        } catch (_: Exception) {
            emptyMap()
        }
        try {
            // One transaction for the whole payout, so a reward that fails halfway through
            // leaves nothing behind: every write below is rolled back together.
            playerRepository.inPlayerTransaction {
                for (reward in selected.take(MAX_REWARDS)) {
                    when (reward.kind) {
                        RewardKind.STATS -> if (reward.id == COINS_ID) {
                            playerRepository.addCoinsUnlocked(reward.amount)
                        } else {
                            // Retain the levels gained: top the skill up to the level the run
                            // reached, relative to the checkpoint (order-independent with SKILL_XP).
                            val currentXp = playerRepository.getSkillXp()[reward.id] ?: 0L
                            val targetLevel = XpTable.levelForXp(checkpointXp[reward.id] ?: 0L) + reward.amount.toInt()
                            val targetXp = XpTable.xpForLevel(targetLevel)
                            if (targetXp > currentXp) {
                                playerRepository.applySessionResultsUnlocked(
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
                                playerRepository.applySessionResultsUnlocked(
                                    skillName      = reward.id,
                                    xpGained       = targetXp - currentXp,
                                    itemsGained    = emptyMap(),
                                    applyXpBoosts  = false,
                                )
                            }
                        }
                        RewardKind.ITEM -> if (reward.amount > 0) {
                            playerRepository.grantItemUnlocked(reward.id, reward.amount.toInt())
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // The payout was rolled back, so the run is exactly as claimable as it was before:
            // report the error and keep the checkpoint instead of discarding a half-paid run.
            _crashedLastRun.value = true
            return false
        }
        checkpoint = null
        _simPlayer.value = null
        discardSimulationSessions()
        originSlot = 0
        _phase.value = Phase.BASE_REALITY
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
    ) = simulatorMutex.withLock {
        recoverFromCrashUnlocked(playerRepository, sessionRepository)
    }

    private suspend fun recoverFromCrashUnlocked(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
    ) {
        // Discard the temporary simulation state first so flag reads below always
        // come from the real save (this may run mid-process or at next app start).
        isSimulationActive = false
        _simPlayer.value = null
        checkpoint = null
        discardSimulationSessions()
        originSlot = 0
        _phase.value = Phase.BASE_REALITY
        val flags = try { playerRepository.getFlags() } catch (_: Exception) { null } ?: return
        if (flags.simRunActiveSince <= 0L) return
        try {
            playerRepository.updateFlags(flags.copy(simRunActiveSince = 0L))
        } catch (_: Exception) {
            // Recovery must never crash the app.
        }
        _crashedLastRun.value = true
    }

    private suspend fun clearCrashMarker(playerRepository: PlayerRepository) {
        try {
            val flags = playerRepository.getFlags()
            if (flags.simRunActiveSince > 0L) {
                playerRepository.updateFlags(flags.copy(simRunActiveSince = 0L))
            }
        } catch (_: Exception) {
            // Clearing the marker must never block a return to Base Reality.
        }
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

    /** Death in the Simulator: discard temp state and move on to reward selection. */
    suspend fun acknowledgeDeath(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        activeSlot: Int,
    ) {
        exitSimulation(playerRepository, sessionRepository, activeSlot, died = true)
    }
}
