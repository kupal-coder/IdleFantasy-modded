package com.fantasyidler

import android.app.Application
import android.util.Log
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SessionRepository
import com.fantasyidler.simulator.RealitySimulator

// Mod init file for kupal-coder/IdleFantasy-modded
// Source (via GitHub MCP): https://github.com/kupal-coder/IdleFantasy-modded
// Entry points in repo:
//   - FantasyIdlerApp : Application (@HiltAndroidApp, creates notification channels)
//   - MainActivity (@AndroidEntryPoint, injects PlayerRepository, BackupScheduler,
//     SessionNotificationManager, GlobalStateRepository, SaveSlotRepository)
//   - simulator/: SkillSimulator, CombatSimulator, CarnivalSimulator,
//     MercantileSimulator, ThievingSimulator, SkillingDungeonSimulator,
//     XpTable, TowerScaling, PrestigePoints/Boosts, HeirloomStats

object ModInit {
    private const val TAG = "IdleFantasyMod"
    const val MOD_VERSION = "1.1.0"
    const val TARGET_APP_VERSION = "1.15.4"
    const val TARGET_VERSION_CODE = 154000

    /**
     * Runs from [FantasyIdlerApp.onCreate] after notificationManager.createChannels().
     *
     * Installs the three-layer reality system:
     *   Base Reality (Very Hard) — [com.fantasyidler.simulator.HardcoreRules] multipliers
     *     are applied at game-data load and at the XP/death choke points;
     *   Simulator (Parallel Checkpointed Reality) — [RealitySimulator] checkpoints the
     *     full player state and isolates all writes from the real save;
     *   Fast Time Simulation (Time Skip) — [RealitySimulator.timeSkip], in-sim only.
     */
    fun init(
        app: Application,
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
    ) {
        Log.i(TAG, "ModInit v$MOD_VERSION for IdleFantasy v$TARGET_APP_VERSION ($TARGET_VERSION_CODE)")
        // Discard a simulation that was force-closed mid-run before any collect can
        // see its sessions ("An unexpected error occurred. Returning to Base Reality.").
        RealitySimulator.scheduleCrashRecovery(playerRepository, sessionRepository)
    }

    fun defaultSimConfig(): Map<String, String> = mapOf(
        "entryApp" to "com.fantasyidler.FantasyIdlerApp",
        "entryActivity" to "com.fantasyidler.MainActivity",
        "applicationId" to "com.tristinbaker.idlefantasy",
        "namespace" to "com.fantasyidler",
        "skills" to "23 (Mining/Fishing/Woodcutting/Farming/Thieving + 8 crafting + 3 support + 7 combat)",
        "dungeons" to "33 + Infinite Tower + Raids + Expeditions",
        "quests" to "189+ + daily + guilds(20) + housing + church + carnival"
    )
}
