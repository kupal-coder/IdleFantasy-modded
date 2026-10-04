package com.fantasyidler.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.repository.SaveSlotRepository
import com.fantasyidler.repository.SlotInfo
import com.fantasyidler.simulator.RealitySimulator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SaveSlotsViewModel @Inject constructor(
    private val saveSlotRepo: SaveSlotRepository,
) : ViewModel() {

    data class UiState(
        val slots: List<SlotInfo> = emptyList(),
        val isLoading: Boolean = true,
        val isSwitching: Boolean = false,
        /** One-shot: set after a successful switch so the screen can navigate home. */
        val switchCompleted: Boolean = false,
        /** One-shot: set when a switch threw so the screen can show an error. */
        val switchFailed: Boolean = false,
        /** One-shot alongside switchCompleted: the loaded slot was an edited ironman save. */
        val ironmanDemoted: Boolean = false,
        /** One-shot: a Simulator run is active, so the character cannot be switched yet. */
        val switchBlockedBySimulation: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(slots = saveSlotRepo.slotInfos(), isLoading = false) }
        }
    }

    fun switchTo(slot: Int) {
        if (_state.value.isSwitching) return
        // A Simulator run belongs to the character that started it: switching mid-run would
        // strand its isolated state and let its rewards land on a different character.
        if (RealitySimulator.isSimulationActive) {
            _state.update { it.copy(switchBlockedBySimulation = true) }
            return
        }
        _state.update { it.copy(isSwitching = true) }
        viewModelScope.launch {
            try {
                val ironmanDemoted = saveSlotRepo.switchTo(slot)
                _state.update { it.copy(isSwitching = false, switchCompleted = true, ironmanDemoted = ironmanDemoted) }
            } catch (_: Exception) {
                _state.update { it.copy(isSwitching = false, switchFailed = true) }
                refresh()
            }
        }
    }

    fun deleteSlot(slot: Int) {
        viewModelScope.launch {
            saveSlotRepo.deleteSlot(slot)
            refresh()
        }
    }

    fun switchCompletedConsumed() = _state.update { it.copy(switchCompleted = false, ironmanDemoted = false) }
    fun switchFailedConsumed()    = _state.update { it.copy(switchFailed = false) }
    fun switchBlockedConsumed()   = _state.update { it.copy(switchBlockedBySimulation = false) }
}
