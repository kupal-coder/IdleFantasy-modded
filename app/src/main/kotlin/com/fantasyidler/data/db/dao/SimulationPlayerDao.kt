package com.fantasyidler.data.db.dao

import com.fantasyidler.data.model.Player
import com.fantasyidler.simulator.RealitySimulator

/**
 * [PlayerDao] decorator that routes every player-save read/write to the temporary
 * simulation state while [RealitySimulator.isSimulationActive] is true, so an active
 * simulation can never touch the real save. Outside a simulation every call is
 * forwarded to the real DAO unchanged.
 */
class SimulationPlayerDao(
    private val delegate: PlayerDao,
) : PlayerDao by delegate {

    override suspend fun getPlayer(): Player? =
        if (RealitySimulator.isSimulationActive) RealitySimulator.simPlayer.value else delegate.getPlayer()

    override suspend fun upsert(player: Player) {
        if (!RealitySimulator.onSimWrite(player)) delegate.upsert(player)
    }

    override suspend fun updateFlags(flags: String): Int =
        if (RealitySimulator.onSimFlagsWrite(flags)) 1 else delegate.updateFlags(flags)
}
