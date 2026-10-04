package com.fantasyidler.data.db.dao

import com.fantasyidler.data.model.Player
import com.fantasyidler.simulator.RealitySimulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

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

    /**
     * Observers must never receive the Base Reality player while a simulation is active —
     * [com.fantasyidler.repository.PlayerRepository.playerFlow] feeds every screen in the app.
     * The real flow is merged with the simulated state so entering or leaving a simulation
     * mid-collection immediately switches which one observers see: while a run is active every
     * emission resolves to the simulated player, and outside a run the real row is passed
     * through untouched (the simulated state is only re-resolved against the real save).
     */
    override fun observePlayer(): Flow<Player?> = merge(
        delegate.observePlayer().map { real ->
            if (RealitySimulator.isSimulationActive) RealitySimulator.simPlayer.value else real
        },
        RealitySimulator.simPlayer.map { simulated ->
            if (RealitySimulator.isSimulationActive) simulated else delegate.getPlayer()
        },
    )

    override suspend fun upsert(player: Player) {
        if (!RealitySimulator.onSimWrite(player)) delegate.upsert(player)
    }

    override suspend fun updateFlags(flags: String): Int =
        if (RealitySimulator.onSimFlagsWrite(flags)) 1 else delegate.updateFlags(flags)
}
