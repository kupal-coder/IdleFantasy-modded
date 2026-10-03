package com.fantasyidler.simulator

import com.fantasyidler.data.json.DungeonData
import com.fantasyidler.data.json.EnemyData
import com.fantasyidler.data.json.EquipmentData
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Layer 1 of the three-layer reality system — Base Reality (Very Hard).
 *
 * Global hardcore difficulty multipliers, applied at static-data load time
 * ([com.fantasyidler.repository.GameDataRepository]) and at the player-facing
 * multiplier choke points ([com.fantasyidler.repository.BoostRepository]), so
 * every consumer — combat simulation, UI, bestiary, and the Simulator's exact
 * parallel copy of the game — sees the same hardened numbers.
 */
object HardcoreRules {

    /** Enemy HP × 3.0 */
    const val ENEMY_HP_MULT = 3.0

    /** Enemy combat & defense stats × 2.2 */
    const val ENEMY_STAT_MULT = 2.2

    /** Encounter rates reduced to the 0.35–0.50 band. */
    const val ENCOUNTER_RATE_MIN = 0.35
    const val ENCOUNTER_RATE_MAX = 0.50

    /** Rare drop chances cut by 65% (midpoint of the 60–70% band). */
    const val RARE_DROP_MULT = 0.35

    /** Drop-table entries at or below this chance count as "rare" drops. */
    const val RARE_DROP_THRESHOLD = 0.10

    /** All XP rates reduced by 50%. */
    const val XP_RATE_MULT = 0.5

    /** Healing weaker: food heals at 50% value. */
    const val HEAL_MULT = 0.5

    /** Healing more expensive: heal-related prices × 2. */
    const val HEAL_COST_MULT = 2.0

    /** Gear skill requirements raised: +25% levels (rounded up). */
    const val GEAR_REQ_MULT = 1.25

    /** Death is permanent / heavily punishing: nothing is kept from a death run. */
    const val DEATH_KEEP_FRACTION = 0.0

    fun enemyHp(hp: Int): Int = (hp * ENEMY_HP_MULT).roundToInt().coerceAtLeast(1)

    fun enemyStat(value: Int): Int = (value * ENEMY_STAT_MULT).roundToInt()

    fun encounterRate(rate: Double): Double = rate.coerceIn(ENCOUNTER_RATE_MIN, ENCOUNTER_RATE_MAX)

    fun rareDropChance(chance: Double): Double = (chance * RARE_DROP_MULT).coerceIn(0.0, 1.0)

    fun healAmount(heal: Int): Int = (heal * HEAL_MULT).roundToInt().coerceAtLeast(if (heal > 0) 1 else 0)

    fun healCost(coins: Int): Int = (coins * HEAL_COST_MULT).roundToInt()

    fun gearRequirement(level: Int): Int = ceil(level * GEAR_REQ_MULT).toInt()

    /** Hardened enemy: HP × 3.0, combat & defense stats × 2.2, rare drop chances −65%. */
    fun hardenEnemy(enemy: EnemyData): EnemyData = enemy.copy(
        hp = enemyHp(enemy.hp),
        combatStats = enemy.combatStats.copy(
            attackLevel    = enemyStat(enemy.combatStats.attackLevel),
            strengthLevel  = enemyStat(enemy.combatStats.strengthLevel),
            defenseLevel   = enemyStat(enemy.combatStats.defenseLevel),
            attackBonus    = enemyStat(enemy.combatStats.attackBonus),
            strengthBonus  = enemyStat(enemy.combatStats.strengthBonus),
        ),
        defensiveStats = enemy.defensiveStats.copy(
            attackDefense   = enemyStat(enemy.defensiveStats.attackDefense),
            strengthDefense = enemyStat(enemy.defensiveStats.strengthDefense),
            rangedDefense   = enemyStat(enemy.defensiveStats.rangedDefense),
            magicDefense    = enemyStat(enemy.defensiveStats.magicDefense),
        ),
        dropTable = enemy.dropTable.map { drop ->
            if (drop.chance <= RARE_DROP_THRESHOLD) drop.copy(chance = rareDropChance(drop.chance)) else drop
        },
    )

    /** Hardened dungeon: encounter rate clamped to 0.35–0.50, rare drop chances −65%. */
    fun hardenDungeon(dungeon: DungeonData): DungeonData = dungeon.copy(
        encounterRate = encounterRate(dungeon.encounterRate),
        rareDrops     = dungeon.rareDrops.map { it.copy(chance = rareDropChance(it.chance)) },
    )

    /** Hardened gear: every skill requirement raised by [GEAR_REQ_MULT]. */
    fun hardenGear(equipment: EquipmentData): EquipmentData = equipment.copy(
        requirements = equipment.requirements.mapValues { (_, level) -> gearRequirement(level) },
    )
}
