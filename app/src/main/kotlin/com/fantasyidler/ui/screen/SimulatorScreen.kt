package com.fantasyidler.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fantasyidler.R
import com.fantasyidler.simulator.RealitySimulator
import com.fantasyidler.simulator.RealitySimulator.Phase
import com.fantasyidler.simulator.RealitySimulator.RewardKind
import com.fantasyidler.simulator.RealitySimulator.SimReward
import com.fantasyidler.ui.viewmodel.SimulatorUiState
import com.fantasyidler.ui.viewmodel.SimulatorViewModel
import com.fantasyidler.util.GameStrings
import com.fantasyidler.util.formatCoins
import androidx.compose.ui.platform.LocalContext

/**
 * All UI states of the three-layer reality system: enter Simulator, inside
 * Simulator, Time Skip confirm, Time Skip result, death in Simulator, reward
 * selection, and return to Base Reality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen(
    viewModel: SimulatorViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    AppBannerEffect(state.message, viewModel::consumeMessage)
    LaunchedEffect(state.crashedNotice) {
        if (state.crashedNotice) {
            AppBannerCenter.enqueue(context.getString(R.string.simulator_error)) { viewModel.consumeCrashNotice() }
        }
    }
    LaunchedEffect(state.runInvalidated) {
        if (state.runInvalidated) {
            AppBannerCenter.enqueue(context.getString(R.string.simulator_invalid_run)) { viewModel.consumeInvalidRunNotice() }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.simulator_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.offline && state.phase != Phase.BASE_REALITY) {
                NoticeCard(stringResource(R.string.simulator_offline))
            }

            when (state.phase) {
                Phase.BASE_REALITY -> BaseRealityContent(state, viewModel)
                Phase.IN_SIMULATION,
                Phase.TIME_SKIP_CONFIRM,
                Phase.TIME_SKIP_RESULT,
                Phase.SIM_DEATH -> InsideSimulationContent(state, viewModel)
                Phase.REWARD_SELECTION -> RewardSelectionContent(state, viewModel)
            }
        }
    }

    if (state.phase == Phase.TIME_SKIP_CONFIRM) {
        TimeSkipConfirmDialog(state, viewModel)
    }
    if (state.phase == Phase.TIME_SKIP_RESULT) {
        TimeSkipResultDialog(state, viewModel)
    }
    if (state.phase == Phase.SIM_DEATH) {
        AlertDialog(
            onDismissRequest = { viewModel.acknowledgeDeath() },
            title = { Text(stringResource(R.string.simulator_death_title)) },
            text  = { Text(stringResource(R.string.simulator_death_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.acknowledgeDeath() }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
        )
    }
}

// ------------------------------------------------------------------ Base Reality

@Composable
private fun BaseRealityContent(
    state: SimulatorUiState,
    viewModel: SimulatorViewModel,
) {
    PhaseBadge(stringResource(R.string.simulator_base_badge))
    Text(stringResource(R.string.simulator_base_description), style = MaterialTheme.typography.bodyMedium)

    Button(
        onClick = { viewModel.enterSimulation() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Science, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.simulator_enter))
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(stringResource(R.string.simulator_upgrades_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.simulator_upgrades_description), style = MaterialTheme.typography.bodySmall)

    UpgradeCard(
        title       = stringResource(R.string.simulator_upgrade_skip_duration),
        description = stringResource(
            R.string.simulator_upgrade_skip_duration_desc,
            RealitySimulator.maxSkipMinutes(state.simulatorUpgrades[RealitySimulator.UPGRADE_SKIP_DURATION] ?: 0),
        ),
        level       = state.simulatorUpgrades[RealitySimulator.UPGRADE_SKIP_DURATION] ?: 0,
        maxLevel    = RealitySimulator.MAX_SKIP_DURATION_LEVEL,
        cost        = RealitySimulator.upgradeCost(
            RealitySimulator.UPGRADE_SKIP_DURATION,
            state.simulatorUpgrades[RealitySimulator.UPGRADE_SKIP_DURATION] ?: 0,
        ),
        onBuy       = { viewModel.buyUpgrade(RealitySimulator.UPGRADE_SKIP_DURATION) },
    )
    UpgradeCard(
        title       = stringResource(R.string.simulator_upgrade_efficiency),
        description = stringResource(R.string.simulator_upgrade_efficiency_desc, 5),
        level       = state.simulatorUpgrades[RealitySimulator.UPGRADE_EFFICIENCY] ?: 0,
        maxLevel    = RealitySimulator.MAX_EFFICIENCY_LEVEL,
        cost        = RealitySimulator.upgradeCost(
            RealitySimulator.UPGRADE_EFFICIENCY,
            state.simulatorUpgrades[RealitySimulator.UPGRADE_EFFICIENCY] ?: 0,
        ),
        onBuy       = { viewModel.buyUpgrade(RealitySimulator.UPGRADE_EFFICIENCY) },
    )
}

@Composable
private fun UpgradeCard(
    title: String,
    description: String,
    level: Int,
    maxLevel: Int,
    cost: Long,
    onBuy: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.simulator_upgrade_level, level, maxLevel),
                style = MaterialTheme.typography.bodySmall,
            )
            if (cost < 0L) {
                Text(stringResource(R.string.simulator_upgrade_maxed), style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = onBuy) {
                    Text(stringResource(R.string.simulator_upgrade_buy, cost.formatCoins()))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Inside the Simulator

@Composable
private fun InsideSimulationContent(
    state: SimulatorUiState,
    viewModel: SimulatorViewModel,
) {
    PhaseBadge(stringResource(R.string.simulator_inside_badge))
    Text(stringResource(R.string.simulator_inside_description), style = MaterialTheme.typography.bodyMedium)

    val session = state.activeSession
    if (session == null) {
        NoticeCard(stringResource(R.string.simulator_no_action))
    } else {
        NoticeCard(stringResource(R.string.simulator_current_action, session.activityKey.ifEmpty { session.skillName }))
    }

    Button(
        onClick = { viewModel.openTimeSkip() },
        enabled = session != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.simulator_time_skip))
    }
    OutlinedButton(
        onClick = { viewModel.exitSimulation() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.simulator_exit))
    }
}

// ------------------------------------------------------------------ Time Skip confirm / result

@Composable
private fun TimeSkipConfirmDialog(
    state: SimulatorUiState,
    viewModel: SimulatorViewModel,
) {
    val upgradeLevel = state.simulatorUpgrades[RealitySimulator.UPGRADE_SKIP_DURATION] ?: 0
    val maxMinutes = RealitySimulator.maxSkipMinutes(upgradeLevel)
    AlertDialog(
        onDismissRequest = { viewModel.cancelTimeSkip() },
        title = { Text(stringResource(R.string.simulator_time_skip_confirm_title)) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.simulator_time_skip_confirm_message))
                for (minutes in RealitySimulator.skipDurations) {
                    val unlocked = minutes <= maxMinutes
                    OutlinedButton(
                        onClick = { viewModel.confirmTimeSkip(minutes) },
                        enabled = unlocked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.simulator_time_skip_option, minutes))
                        if (!unlocked) {
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.simulator_time_skip_locked))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.cancelTimeSkip() }) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

@Composable
private fun TimeSkipResultDialog(
    state: SimulatorUiState,
    viewModel: SimulatorViewModel,
) {
    val context = LocalContext.current
    val result = state.lastSkipResult
    AlertDialog(
        onDismissRequest = { viewModel.dismissTimeSkipResult() },
        title = { Text(stringResource(R.string.simulator_time_skip_result_title)) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (result == null || (result.xpBySkill.isEmpty() && result.items.isEmpty() && result.coins == 0L)) {
                    Text(stringResource(R.string.simulator_time_skip_result_empty))
                } else {
                    for ((skill, xp) in result.xpBySkill) {
                        Text(stringResource(R.string.simulator_time_skip_result_xp, GameStrings.skillName(context, skill), xp))
                    }
                    if (result.coins > 0) {
                        Text(stringResource(R.string.simulator_time_skip_result_coins, result.coins))
                    }
                    for ((item, qty) in result.items) {
                        Text(stringResource(R.string.simulator_time_skip_result_item, GameStrings.itemName(context, item), qty))
                    }
                }
                if (result?.died == true) {
                    Text(stringResource(R.string.simulator_time_skip_result_died), fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.dismissTimeSkipResult() }) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
    )
}

// ------------------------------------------------------------------ Reward selection

@Composable
private fun RewardSelectionContent(
    state: SimulatorUiState,
    viewModel: SimulatorViewModel,
) {
    val context = LocalContext.current
    Text(stringResource(R.string.simulator_rewards_title), style = MaterialTheme.typography.titleMedium)

    if (state.rewardPool.isEmpty()) {
        NoticeCard(stringResource(R.string.simulator_rewards_empty))
        Button(
            onClick = { viewModel.skipRewards() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.simulator_claim_return))
        }
        return
    }

    Text(
        stringResource(R.string.simulator_rewards_message, RealitySimulator.MAX_REWARDS),
        style = MaterialTheme.typography.bodyMedium,
    )

    for (kind in listOf(RewardKind.STATS, RewardKind.SKILL_XP, RewardKind.ITEM)) {
        val rewards = state.rewardPool.filter { it.kind == kind }
        if (rewards.isEmpty()) continue
        Text(
            stringResource(
                when (kind) {
                    RewardKind.STATS    -> R.string.simulator_reward_group_stats
                    RewardKind.SKILL_XP -> R.string.simulator_reward_group_skill_xp
                    RewardKind.ITEM     -> R.string.simulator_reward_group_items
                }
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        for (reward in rewards) {
            RewardRow(
                reward   = reward,
                selected = reward in state.selectedRewards,
                onToggle = { viewModel.toggleReward(reward) },
            )
        }
    }

    Text(
        stringResource(
            R.string.simulator_reward_count,
            state.selectedRewards.size,
            RealitySimulator.MAX_REWARDS,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = { viewModel.claimRewards() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.simulator_claim_return))
    }
}

@Composable
private fun RewardRow(
    reward: SimReward,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Text(
            when (reward.kind) {
                RewardKind.STATS -> if (reward.id == RealitySimulator.COINS_ID) {
                    stringResource(R.string.simulator_reward_coins, reward.amount)
                } else {
                    stringResource(
                        R.string.simulator_reward_level,
                        reward.amount.toInt(),
                        GameStrings.skillName(context, reward.id),
                    )
                }
                RewardKind.SKILL_XP -> stringResource(
                    R.string.simulator_reward_xp,
                    reward.amount,
                    GameStrings.skillName(context, reward.id),
                )
                RewardKind.ITEM -> stringResource(
                    R.string.simulator_reward_item,
                    GameStrings.itemName(context, reward.id),
                    reward.amount.toInt(),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ------------------------------------------------------------------ shared

@Composable
private fun PhaseBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
