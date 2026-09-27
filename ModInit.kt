package com.fantasyidler

// Mod init file for kupal-coder/IdleFantasy-modded
// Source (via GitHub MCP): https://github.com/kupal-coder/IdleFantasy-modded
// Entry points in repo:
//   - FantasyIdlerApp : Application (@HiltAndroidApp, creates notification channels)
//   - MainActivity (@AndroidEntryPoint, injects PlayerRepository, BackupScheduler,
//     SessionNotificationManager, GlobalStateRepository, SaveSlotRepository)
//   - simulator/: SkillSimulator, CombatSimulator, CarnivalSimulator,
//     MercantileSimulator, ThievingSimulator, SkillingDungeonSimulator,
//     XpTable, TowerScaling, PrestigePoints/Boosts, HeirloomStats
//
// Drop this file in app/src/main/kotlin/com/fantasyidler/ModInit.kt
// and call ModInit.init(this) from FantasyIdlerApp.onCreate() after
// notificationManager.createChannels().

import android.app.Application
import android.util.Log

object ModInit {
    private const val TAG = "IdleFantasyMod"
    const val MOD_VERSION = "1.0.0"
    const val TARGET_APP_VERSION = "1.15.4"
    const val TARGET_VERSION_CODE = 154000

    fun init(app: Application) {
        Log.i(TAG, "ModInit v$MOD_VERSION for IdleFantasy v$TARGET_APP_VERSION ($TARGET_VERSION_CODE)")
        // TODO: your mod boot logic here.
        // Examples:
        // - schedule/reschedule alarms: BackupScheduler.schedule(), BootReceiver re-register
        // - tweak simulators: XpTable rates, TowerScaling curve, CombatSimulator drops
        // - log injected repos to verify Hilt graph before MainActivity runs
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
