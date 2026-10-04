package com.fantasyidler.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.repository.BoostRepository
import com.fantasyidler.repository.GlobalStateRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SessionRepository
import com.fantasyidler.simulator.RealitySimulator
import com.fantasyidler.simulator.RealitySimulator.Phase
import com.fantasyidler.simulator.RealitySimulator.SimReward
import com.fantasyidler.simulator.RealitySimulator.TimeSkipResult
import com.fantasyidler.util.withAppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class SimulatorUiState(
    val isLoading: Boolean = true,
    val phase: Phase = Phase.BASE_REALITY,
    val coins: Long = 0L,
    val simulatorUpgrades: Map<String, Int> = emptyMap(),
    val activeSession: SkillSession? = null,
    val lastSkipResult: TimeSkipResult? = null,
    val rewardPool: List<SimReward> = emptyList(),
    val selectedRewards: Set<SimReward> = emptySet(),
    val crashedNotice: Boolean = false,
    val runInvalidated: Boolean = false,
    val offline: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SimulatorViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val sessionRepo: SessionRepository,
    private val boostRepo: BoostRepository,
    private val globalStateRepo: GlobalStateRepository,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : ViewModel() {

    private val _extra = MutableStateFlow(SimulatorUiState())

    val uiState: StateFlow<SimulatorUiState> = combine(
        playerRepo.playerFlow,
        sessionRepo.activeSessionFlow,
        RealitySimulator.phase,
        RealitySimulator.lastSkipResult,
        _extra,
    ) { player, session, phase, skipResult, extra ->
        if (player == null) extra.copy(isLoading = true)
        else {
            val flags: PlayerFlags = json.decodeFromString(player.flags)
            extra.copy(
                isLoading          = false,
                phase              = phase,
                coins              = player.coins,
                simulatorUpgrades  = flags.simulatorUpgrades,
                activeSession      = if (RealitySimulator.isSimulationActive) session else null,
                lastSkipResult     = skipResult,
                crashedNotice      = RealitySimulator.crashedLastRun.value,
                runInvalidated     = RealitySimulator.runInvalidated.value,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SimulatorUiState())

    init {
        refreshOffline()
    }

    // ------------------------------------------------------------------ enter / exit

    fun enterSimulation() {
        viewModelScope.launch {
            try {
                RealitySimulator.enterSimulation(playerRepo, sessionRepo, activeSlot())
                refreshRewardPool()
                refreshOffline()
            } catch (e: Exception) {
                abortWithCrashMessage()
            }
        }
    }

    fun exitSimulation() {
        viewModelScope.launch {
            try {
                RealitySimulator.exitSimulation(playerRepo, sessionRepo, activeSlot())
                refreshRewardPool()
            } catch (e: Exception) {
                abortWithCrashMessage()
            }
        }
    }

    fun acknowledgeDeath() {
        viewModelScope.launch {
            try {
                RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, activeSlot())
                refreshRewardPool()
            } catch (e: Exception) {
                abortWithCrashMessage()
            }
        }
    }

    // ------------------------------------------------------------------ Fast Time Simulation

    /** Time Skip is only available inside the Simulator; unavailable in Base Reality. */
    fun canTimeSkip(): Boolean = RealitySimulator.canTimeSkip()

    fun openTimeSkip() {
        if (!RealitySimulator.canTimeSkip()) {
            _extra.update { it.copy(message = context.withAppLocale().getString(R.string.simulator_time_skip_unavailable)) }
            return
        }
        if (uiState.value.activeSession == null) {
            _extra.update { it.copy(message = context.withAppLocale().getString(R.string.simulator_time_skip_no_action)) }
            return
        }
        RealitySimulator.openTimeSkipConfirm()
    }

    fun cancelTimeSkip() = RealitySimulator.cancelTimeSkip()

    /** True while a Time Skip is in flight: a second tap must not queue a second skip. */
    @Volatile
    private var skipInProgress = false

    fun confirmTimeSkip(minutes: Int) {
        if (skipInProgress) return
        viewModelScope.launch {
            skipInProgress = true
            try {
                if (!RealitySimulator.canTimeSkip()) {
                    _extra.update { it.copy(message = context.withAppLocale().getString(R.string.simulator_time_skip_unavailable)) }
                    return@launch
                }
                refreshOffline()
                val session = uiState.value.activeSession
                if (session == null) {
                    _extra.update { it.copy(message = context.withAppLocale().getString(R.string.simulator_time_skip_no_action)) }
                    return@launch
                }
                val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, minutes, activeSlot())
                if (result.minutes == 0) {
                    RealitySimulator.cancelTimeSkip()
                    _extra.update {
                        it.copy(message = context.withAppLocale().getString(
                            if (RealitySimulator.fastForwardComplete(session)) R.string.simulator_time_skip_action_complete
                            else R.string.simulator_time_skip_no_action
                        ))
                    }
                } else {
                    refreshRewardPool()
                }
            } catch (e: Exception) {
                abortWithCrashMessage()
            } finally {
                skipInProgress = false
            }
        }
    }

    fun dismissTimeSkipResult() {
        RealitySimulator.dismissTimeSkipResult()
    }

    // ------------------------------------------------------------------ rewards

    fun toggleReward(reward: SimReward) {
        _extra.update { state ->
            val selected = state.selectedRewards.toMutableSet()
            if (!selected.remove(reward) && selected.size < RealitySimulator.MAX_REWARDS) selected.add(reward)
            state.copy(selectedRewards = selected)
        }
    }

    fun claimRewards() {
        viewModelScope.launch {
            try {
                val claimed = RealitySimulator.claimRewards(playerRepo, uiState.value.selectedRewards, activeSlot())
                _extra.update {
                    it.copy(
                        selectedRewards = emptySet(),
                        rewardPool      = emptyList(),
                        // A rejected claim raises its own notice; don't also announce a clean return.
                        message         = if (claimed) context.withAppLocale().getString(R.string.simulator_return_message) else null,
                    )
                }
            } catch (e: Exception) {
                abortWithCrashMessage()
            }
        }
    }

    fun skipRewards() {
        viewModelScope.launch {
            try {
                val claimed = RealitySimulator.claimRewards(playerRepo, emptyList(), activeSlot())
                _extra.update {
                    it.copy(selectedRewards = emptySet(), rewardPool = emptyList(),
                        message = if (claimed) context.withAppLocale().getString(R.string.simulator_return_message) else null)
                }
            } catch (e: Exception) {
                abortWithCrashMessage()
            }
        }
    }

    /**
     * The character/save slot every Simulator operation is bound to. A run whose slot no longer
     * matches is rejected by [RealitySimulator.ownsRun] rather than applied to another character.
     */
    private suspend fun activeSlot(): Int = try {
        globalStateRepo.getActiveSaveSlot()
    } catch (_: Exception) {
        0
    }

    private fun refreshRewardPool() {
        _extra.update { it.copy(rewardPool = RealitySimulator.rewardPool(), selectedRewards = emptySet()) }
    }

    // ------------------------------------------------------------------ upgrades (Base Reality)

    fun buyUpgrade(upgradeKey: String) {
        viewModelScope.launch {
            try {
                if (RealitySimulator.isSimulationActive) return@launch
                val flags = playerRepo.getFlags()
                val level = RealitySimulator.upgradeLevel(flags, upgradeKey)
                val cost = RealitySimulator.upgradeCost(upgradeKey, level)
                if (cost < 0L) return@launch
                if (!playerRepo.spendCoins(cost)) {
                    _extra.update { it.copy(message = context.withAppLocale().getString(R.string.simulator_upgrade_not_enough_coins)) }
                    return@launch
                }
                playerRepo.updateFlags(flags.copy(simulatorUpgrades = flags.simulatorUpgrades + (upgradeKey to level + 1)))
                _extra.update { it.copy(message = context.withAppLocale().getString(R.string.simulator_upgrade_purchased)) }
            } catch (e: Exception) {
                abortWithCrashMessage()
            }
        }
    }

    // ------------------------------------------------------------------ misc

    fun consumeMessage() = _extra.update { it.copy(message = null) }

    fun consumeCrashNotice() {
        RealitySimulator.consumeCrashNotice()
        _extra.update { it.copy(crashedNotice = false) }
    }

    fun consumeInvalidRunNotice() {
        RealitySimulator.consumeInvalidRunNotice()
        _extra.update { it.copy(runInvalidated = false) }
    }

    fun refreshOffline() {
        _extra.update { it.copy(offline = isOffline()) }
    }

    private fun isOffline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Any crash during a simulation discards the temporary state and returns to Base Reality. */
    private fun abortWithCrashMessage() {
        viewModelScope.launch {
            try {
                RealitySimulator.abortToBaseReality(playerRepo, sessionRepo)
            } catch (_: Exception) {
            }
            _extra.update {
                it.copy(
                    message     = context.withAppLocale().getString(R.string.simulator_error),
                    rewardPool  = emptyList(),
                    selectedRewards = emptySet(),
                )
            }
        }
    }
}
