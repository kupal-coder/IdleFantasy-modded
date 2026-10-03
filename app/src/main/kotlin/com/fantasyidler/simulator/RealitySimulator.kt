package com.fantasyidler.simulator

import com.fantasyidler.data.model.Player
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

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
 * simulation state only — the real save is never touched.
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

    @Volatile
    var isSimulationActive: Boolean = false
        private set

    /** Deep-copy checkpoint of the full player state taken on Simulator entry. */
    @Volatile
    private var checkpoint: Player? = null

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

    // ------------------------------------------------------------------ enter / exit

    /**
     * Activated from Base Reality: deep-copy checkpoints the full player state and
     * starts an exact parallel copy of the game on the temporary simulation state.
     */
    suspend fun enterSimulation(playerRepository: PlayerRepository) {
        if (isSimulationActive) return
        val player = playerRepository.getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        // Player is a flat data class of immutable JSON columns: copy() is a full deep copy.
        checkpoint = player.copy()
        _simPlayer.value = player.copy()
        fastForwardSessionId = null
        fastForwardCursor = 0
        _lastSkipResult.value = null
        // Crash marker written to Base Reality before isolation activates, so a
        // force-close during the simulation is detected on the next launch.
        playerRepository.updateFlags(flags.copy(simRunActiveSince = System.currentTimeMillis()))
        isSimulationActive = true
        _phase.value = Phase.IN_SIMULATION
    }

    /**
     * On death or voluntary exit: discard the temporary simulation state and return
     * to Base Reality. The run's gains become the pick-3 reward pool.
     */
    suspend fun exitSimulation(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        died: Boolean = false,
    ) {
        if (!isSimulationActive) return
        isSimulationActive = false
        val flags = playerRepository.getFlags()
        discardSimulationSessions(sessionRepository, flags.simRunActiveSince)
        clearCrashMarker(playerRepository)
        _phase.value = Phase.REWARD_SELECTION
    }

    /** Discards the run without rewards (used when the reward pool is empty). */
    suspend fun abortToBaseReality(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
    ) {
        isSimulationActive = false
        val flags = playerRepository.getFlags()
        val since = flags.simRunActiveSince
        checkpoint = null
        _simPlayer.value = null
        discardSimulationSessions(sessionRepository, since)
        clearCrashMarker(playerRepository)
        _phase.value = Phase.BASE_REALITY
    }

    /** Drops any sessions the simulation started so their results can never reach the real save. */
    private suspend fun discardSimulationSessions(sessionRepository: SessionRepository, since: Long) {
        if (since <= 0L) return
        try {
            sessionRepository.getActiveSession()?.let { session ->
                if (session.startedAt >= since) sessionRepository.deleteSession(session.sessionId)
            }
            for (session in sessionRepository.getAllCompletedSessions()) {
                if (session.startedAt >= since) sessionRepository.deleteSession(session.sessionId)
            }
        } catch (_: Exception) {
            // Session cleanup must never block a return to Base Reality.
        }
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
     * Fast Time Simulation: instantly advances the current action by [minutes],
     * applying the session's own pre-simulated frames — the exact same rate
     * calculations a real session uses — to the temporary simulation state only.
     * All gains remain in the pick-3 reward pool. A death frame ends the run.
     */
    suspend fun timeSkip(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
        minutes: Int,
    ): TimeSkipResult {
        if (!isSimulationActive) return TimeSkipResult()
        val session = sessionRepository.getActiveSession() ?: return TimeSkipResult()
        val frames: List<SessionFrame> = json.decodeFromString(session.frames)
        if (frames.isEmpty()) return TimeSkipResult()
        if (fastForwardSessionId != session.sessionId) {
            fastForwardSessionId = session.sessionId
            fastForwardCursor = 0
        }
        val from = fastForwardCursor.coerceIn(0, frames.size)
        if (from >= frames.size) return TimeSkipResult()
        val to = minOf(from + minutes.coerceAtLeast(0), frames.size)
        if (to <= from) return TimeSkipResult()

        val slice = frames.subList(from, to)
        // Death inside the skip ends the run at the death minute.
        val deathIndex = slice.indexOfFirst { it.died }
        val effective = if (deathIndex >= 0) slice.subList(0, deathIndex + 1) else slice

        val xpBySkill = mutableMapOf<String, Long>()
        val items = mutableMapOf<String, Int>()
        for (frame in effective) {
            if (frame.xpBySkill.isNotEmpty()) {
                for ((skill, xp) in frame.xpBySkill) xpBySkill[skill] = (xpBySkill[skill] ?: 0L) + xp
            } else if (frame.xpGain > 0) {
                xpBySkill[session.skillName] = (xpBySkill[session.skillName] ?: 0L) + frame.xpGain
            }
            for ((item, qty) in frame.items) items[item] = (items[item] ?: 0) + qty
        }
        val coins = (items.remove(COINS_ID)?.toLong() ?: 0L)

        // Same collect path a real session uses (boosts, blessings, sigils), so the
        // rate calculations are identical; the Simulator efficiency upgrade scales
        // the result on top of those identical base rates.
        val flags = playerRepository.getFlags()
        val efficiency = efficiencyMultiplier(upgradeLevel(flags, UPGRADE_EFFICIENCY))
        if (xpBySkill.size > 1 || coins > 0L || session.skillName == "combat") {
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

        // Consume the fast-forwarded frames so a later real collect can never
        // double-count them; they stay in place as quiet minutes.
        if (effective.isNotEmpty()) {
            val rewritten = frames.mapIndexed { index, frame ->
                if (index in from until from + effective.size) quietFrame(frame.minute) else frame
            }
            sessionRepository.insertSession(
                session.copy(frames = json.encodeToString(json.serializersModule.serializer<List<SessionFrame>>(), rewritten))
            )
        }

        val result = TimeSkipResult(
            minutes  = effective.size,
            xpBySkill = xpBySkill,
            items    = items,
            coins    = coins,
            died     = deathIndex >= 0,
        )
        fastForwardCursor = from + effective.size
        _lastSkipResult.value = result
        _phase.value = if (deathIndex >= 0) Phase.SIM_DEATH else Phase.TIME_SKIP_RESULT
        return result
    }

    /** A minute whose gains were already fast-forwarded: nothing gained, nothing lost. */
    private fun quietFrame(minute: Int) = SessionFrame(
        minute      = minute,
        xpGain      = 0,
        xpBefore    = 0,
        xpAfter     = 0,
        levelBefore = 0,
        levelAfter  = 0,
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
     */
    suspend fun claimRewards(playerRepository: PlayerRepository, selected: List<SimReward>) {
        val base = checkpoint
        val checkpointXp: Map<String, Long> = base?.let { json.decodeFromString(it.skillXp) } ?: emptyMap()
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
        checkpoint = null
        _simPlayer.value = null
        _phase.value = Phase.BASE_REALITY
    }

    // ------------------------------------------------------------------ crash recovery

    /**
     * Force-close / crash during a simulation leaves the crash marker set. The
     * temporary state is gone, so it is discarded here and any simulation sessions
     * are pruned so their results can never leak into the real save
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
        // Discard the temporary simulation state first so flag reads below always
        // come from the real save (this may run mid-process or at next app start).
        isSimulationActive = false
        _simPlayer.value = null
        checkpoint = null
        val flags = playerRepository.getFlags()
        if (flags.simRunActiveSince <= 0L) return
        val since = flags.simRunActiveSince
        discardSimulationSessions(sessionRepository, since)
        playerRepository.updateFlags(flags.copy(simRunActiveSince = 0L))
        _crashedLastRun.value = true
    }

    private suspend fun clearCrashMarker(playerRepository: PlayerRepository) {
        val flags = playerRepository.getFlags()
        if (flags.simRunActiveSince > 0L) {
            playerRepository.updateFlags(flags.copy(simRunActiveSince = 0L))
        }
    }

    /** Consumes the crashed-last-run notice so it is shown once. */
    fun consumeCrashNotice() {
        _crashedLastRun.value = false
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
    suspend fun acknowledgeDeath(playerRepository: PlayerRepository, sessionRepository: SessionRepository) {
        exitSimulation(playerRepository, sessionRepository, died = true)
    }
}
