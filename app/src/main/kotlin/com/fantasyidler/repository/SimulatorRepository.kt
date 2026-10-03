package com.fantasyidler.repository

import com.fantasyidler.SimulatorCheckpoint
import com.fantasyidler.RewardCategory
import com.fantasyidler.data.model.Player
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transaction boundary for the parallel Simulator. Base Reality is never replaced by temporary state. */
@Singleton
class SimulatorRepository @Inject constructor(
    private val globalStateRepository: GlobalStateRepository,
) {
    private val mutex = Mutex()
    private var checkpoint: SimulatorCheckpoint? = null
    private var state: SimulatorState = SimulatorState.BASE_REALITY
    private var action: (suspend (Int, SimulatorCheckpoint) -> Unit)? = null

    enum class SimulatorState { BASE_REALITY, ENTERING, ACTIVE, TIME_SKIP_CONFIRM, TIME_SKIP_RESULT, DEATH, REWARD_SELECTION, RETURNING }

    suspend fun enter(player: Player): Boolean = mutex.withLock {
        if (checkpoint?.active == true) return@withLock false
        state = SimulatorState.ENTERING
        checkpoint = SimulatorCheckpoint.enter(player)
        state = SimulatorState.ACTIVE
        true
    }

    suspend fun isSimulationActive(): Boolean = mutex.withLock { checkpoint?.active == true }

    suspend fun temporaryPlayer(): Player? = mutex.withLock { checkpoint?.temporary }

    suspend fun setAction(rateCalculator: suspend (Int, SimulatorCheckpoint) -> Unit) = mutex.withLock {
        action = rateCalculator
    }

    suspend fun timeSkip(minutes: Int): Int = mutex.withLock {
        val current = checkpoint ?: return@withLock 0
        if (!current.active || state != SimulatorState.ACTIVE) return@withLock 0
        state = SimulatorState.TIME_SKIP_CONFIRM
        val effectiveMinutes = current.skip(minutes)
        action?.invoke(effectiveMinutes, current)
        state = SimulatorState.TIME_SKIP_RESULT
        effectiveMinutes
    }

    suspend fun recordItem(key: String, quantity: Int) = mutex.withLock {
        checkpoint?.takeIf { it.active }?.gainedItems?.let { it[key] = (it[key] ?: 0) + quantity }
    }

    suspend fun recordSkillXp(skill: String, xp: Long) = mutex.withLock {
        checkpoint?.takeIf { it.active }?.gainedSkillXp?.let { it[skill] = (it[skill] ?: 0) + xp }
    }

    suspend fun finish(): List<RewardCategory> = mutex.withLock {
        if (checkpoint == null) return@withLock emptyList()
        state = SimulatorState.REWARD_SELECTION
        checkpoint?.finish() ?: emptyList()
    }

    suspend fun state(): SimulatorState = mutex.withLock { state }

    suspend fun selectedRewards(categories: Set<RewardCategory>): Map<RewardCategory, Any> = mutex.withLock {
        if (categories.size > 3) return@withLock emptyMap()
        val run = checkpoint ?: return@withLock emptyMap()
        buildMap {
            if (RewardCategory.STATS in categories) put(RewardCategory.STATS, run.gainedStats.toMap())
            if (RewardCategory.SKILL_EXPERIENCE in categories) put(RewardCategory.SKILL_EXPERIENCE, run.gainedSkillXp.toMap())
            if (RewardCategory.ITEMS in categories) put(RewardCategory.ITEMS, run.gainedItems.toMap())
        }
    }

    suspend fun leave() = mutex.withLock {
        checkpoint?.discard()
        checkpoint = null
        action = null
        state = SimulatorState.BASE_REALITY
    }

    suspend fun upgrade(): Int = mutex.withLock {
        val current = globalStateRepository.simulatorUpgradeLevel()
        if (current >= 10) return@withLock current
        globalStateRepository.setSimulatorUpgradeLevel(current + 1)
        current + 1
    }

    /** Force-close recovery: no temporary write is ever committed. */
    suspend fun recoverAfterProcessDeath() = leave()
}
