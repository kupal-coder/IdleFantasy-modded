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

    suspend fun enter(player: Player): Boolean = mutex.withLock {
        if (checkpoint?.active == true) return@withLock false
        checkpoint = SimulatorCheckpoint.enter(player)
        true
    }

    suspend fun isSimulationActive(): Boolean = mutex.withLock { checkpoint?.active == true }

    suspend fun temporaryPlayer(): Player? = mutex.withLock { checkpoint?.temporary }

    suspend fun timeSkip(minutes: Int): Int = mutex.withLock {
        checkpoint?.skip(minutes) ?: 0
    }

    suspend fun recordItem(key: String, quantity: Int) = mutex.withLock {
        checkpoint?.takeIf { it.active }?.gainedItems?.let { it[key] = (it[key] ?: 0) + quantity }
    }

    suspend fun recordSkillXp(skill: String, xp: Long) = mutex.withLock {
        checkpoint?.takeIf { it.active }?.gainedSkillXp?.let { it[skill] = (it[skill] ?: 0) + xp }
    }

    suspend fun finish(): List<RewardCategory> = mutex.withLock {
        checkpoint?.finish() ?: emptyList()
    }

    suspend fun leave() = mutex.withLock {
        checkpoint?.discard()
        checkpoint = null
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
