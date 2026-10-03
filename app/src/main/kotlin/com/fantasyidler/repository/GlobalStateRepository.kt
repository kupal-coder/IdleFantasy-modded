package com.fantasyidler.repository

import com.fantasyidler.data.db.dao.GlobalStateDao
import com.fantasyidler.data.model.GlobalState
import com.fantasyidler.data.model.GlobalStateKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalStateRepository @Inject constructor(
    private val dao: GlobalStateDao,
) {
    suspend fun isOnboardingComplete(): Boolean =
        dao.getValue(GlobalStateKey.ONBOARDING_COMPLETE) == "true"

    suspend fun markOnboardingComplete() {
        dao.setValue(GlobalState(
            key       = GlobalStateKey.ONBOARDING_COMPLETE,
            value     = "true",
            updatedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun clearOnboardingComplete() {
        dao.delete(GlobalStateKey.ONBOARDING_COMPLETE)
    }

    suspend fun getActiveSaveSlot(): Int =
        dao.getValue(GlobalStateKey.ACTIVE_SAVE_SLOT)?.toIntOrNull() ?: 1

    suspend fun simulatorUpgradeLevel(): Int =
        dao.getValue(GlobalStateKey.SIMULATOR_UPGRADE_LEVEL)?.toIntOrNull()?.coerceIn(0, 10) ?: 0

    suspend fun setSimulatorUpgradeLevel(level: Int) {
        dao.setValue(GlobalState(GlobalStateKey.SIMULATOR_UPGRADE_LEVEL, level.coerceIn(0, 10).toString(), System.currentTimeMillis()))
    }

    suspend fun setActiveSaveSlot(slot: Int) {
        dao.setValue(GlobalState(
            key       = GlobalStateKey.ACTIVE_SAVE_SLOT,
            value     = slot.toString(),
            updatedAt = System.currentTimeMillis(),
        ))
    }
}
