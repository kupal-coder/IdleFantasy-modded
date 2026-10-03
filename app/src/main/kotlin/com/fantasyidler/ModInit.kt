package com.fantasyidler

import com.fantasyidler.data.model.Player

/** Central rules for the modded, permanently hard Base Reality. */
object ModInit {
    const val TARGET_APP_VERSION = "1.15.4"
    const val ENEMY_HP_MULTIPLIER = 3.0
    const val COMBAT_STAT_MULTIPLIER = 2.2
    const val XP_MULTIPLIER = 0.5
    const val DROP_MULTIPLIER = 0.35
    const val MIN_ENCOUNTER_MULTIPLIER = 0.35
    const val MAX_ENCOUNTER_MULTIPLIER = 0.50
    const val BASE_REALITY = "base_reality"

    @Volatile var hardcoreEnabled: Boolean = true
    fun init(@Suppress("UNUSED_PARAMETER") app: android.app.Application) { hardcoreEnabled = true }
    fun hardcore(value: Double): Double = if (hardcoreEnabled) value else value
    fun enemyHp(value: Int) = if (hardcoreEnabled) (value * ENEMY_HP_MULTIPLIER).toInt().coerceAtLeast(1) else value
    fun combatStat(value: Int) = if (hardcoreEnabled) (value * COMBAT_STAT_MULTIPLIER).toInt() else value
    fun experience(value: Long) = if (hardcoreEnabled) (value * XP_MULTIPLIER).toLong() else value
    fun dropChance(value: Double) = if (hardcoreEnabled) value * DROP_MULTIPLIER else value
    fun encounterRate(value: Double): Double = if (!hardcoreEnabled) value else value *
        if (value <= 0.5) MIN_ENCOUNTER_MULTIPLIER else MAX_ENCOUNTER_MULTIPLIER
}

/** In-memory transaction boundary used by UI/repositories for simulator writes. */
class SimulatorCheckpoint private constructor(
    val base: Player,
    var temporary: Player = base,
    val gainedItems: MutableMap<String, Int> = mutableMapOf(),
    val gainedSkillXp: MutableMap<String, Long> = mutableMapOf(),
    var gainedStats: MutableMap<String, Long> = mutableMapOf(),
) {
    var active = true
    var upgradeLevel = 0
    fun skip(minutes: Int): Int = if (active) (minutes.coerceAtLeast(0) * (1.0 + upgradeLevel * 0.25)).toInt() else 0
    fun rewardPool(): List<RewardCategory> = if (active) finish() else emptyList()
    fun finish(): List<RewardCategory> = listOf(RewardCategory.STATS, RewardCategory.SKILL_EXPERIENCE, RewardCategory.ITEMS)
    fun discard() { active = false; temporary = base }
    companion object { fun enter(player: Player) = SimulatorCheckpoint(player, player.copy()) }
}
enum class RewardCategory { STATS, SKILL_EXPERIENCE, ITEMS }
