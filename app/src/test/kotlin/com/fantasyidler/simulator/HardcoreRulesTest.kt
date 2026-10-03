package com.fantasyidler.simulator

import com.fantasyidler.data.json.DropEntry
import com.fantasyidler.data.json.DungeonData
import com.fantasyidler.data.json.DungeonRareDrop
import com.fantasyidler.data.json.EnemyCombatStats
import com.fantasyidler.data.json.EnemyData
import com.fantasyidler.data.json.EnemyDefensiveStats
import com.fantasyidler.data.json.EnemySpawn
import com.fantasyidler.data.json.EquipmentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins the exact three-layer numbers: Base Reality (Very Hard) multipliers and the
 * Simulator / Fast Time Simulation gating rules.
 */
class HardcoreRulesTest {

    // ------------------------------------------------------------------ Base Reality multipliers

    @Test
    fun `enemy hp is multiplied by 3`() {
        assertEquals(30, HardcoreRules.enemyHp(10))
        assertEquals(3, HardcoreRules.enemyHp(1))
    }

    @Test
    fun `enemy combat and defense stats are multiplied by 2`() {
        assertEquals(22, HardcoreRules.enemyStat(10))
        assertEquals(2, HardcoreRules.enemyStat(1))
    }

    @Test
    fun `encounter rates clamp to the 0_35 to 0_50 band`() {
        assertEquals(0.35, HardcoreRules.encounterRate(0.20), 1e-9)
        assertEquals(0.50, HardcoreRules.encounterRate(0.75), 1e-9)
        assertEquals(0.42, HardcoreRules.encounterRate(0.42), 1e-9)
    }

    @Test
    fun `rare drop chances are cut by 65 percent`() {
        assertEquals(0.02 * 0.35, HardcoreRules.rareDropChance(0.02), 1e-9)
    }

    @Test
    fun `all xp rates are reduced by 50 percent`() {
        assertEquals(0.5, HardcoreRules.XP_RATE_MULT, 1e-9)
    }

    @Test
    fun `healing is weaker and more expensive`() {
        assertEquals(5, HardcoreRules.healAmount(10))
        assertEquals(1, HardcoreRules.healAmount(1))
        assertEquals(200, HardcoreRules.healCost(100))
    }

    @Test
    fun `gear skill requirements are raised`() {
        assertEquals(13, HardcoreRules.gearRequirement(10))
        assertEquals(2, HardcoreRules.gearRequirement(1))
    }

    @Test
    fun `death keeps nothing`() {
        assertEquals(0.0, HardcoreRules.DEATH_KEEP_FRACTION, 1e-9)
    }

    // ------------------------------------------------------------------ data hardening

    private fun enemy() = EnemyData(
        name = "rat",
        displayName = "Rat",
        hp = 10,
        combatStats = EnemyCombatStats(
            attackLevel = 10, strengthLevel = 10, defenseLevel = 10,
            attackBonus = 5, strengthBonus = 5,
        ),
        defensiveStats = EnemyDefensiveStats(
            attackDefense = 8, strengthDefense = 8, rangedDefense = 4, magicDefense = 4,
        ),
        xpDrops = mapOf("combat" to 20),
        dropTable = listOf(
            DropEntry(item = "bones", chance = 0.8, quantityMin = 1, quantityMax = 1),
            DropEntry(item = "rare_sword", chance = 0.02, quantityMin = 1, quantityMax = 1),
        ),
    )

    private fun dungeon() = DungeonData(
        name = "farm",
        displayName = "Farm",
        description = "",
        recommendedLevel = 1,
        encounterRate = 0.75,
        enemySpawns = listOf(EnemySpawn("rat", 1)),
        rareDrops = listOf(DungeonRareDrop(item = "rare_sword", chance = 0.10)),
    )

    @Test
    fun `hardened enemy triples hp and scales stats`() {
        val hardened = HardcoreRules.hardenEnemy(enemy())
        assertEquals(30, hardened.hp)
        assertEquals(22, hardened.combatStats.attackLevel)
        assertEquals(22, hardened.defensiveStats.attackDefense)
        // Common drops untouched, rare chances cut by 65%.
        assertEquals(0.8, hardened.dropTable[0].chance, 1e-9)
        assertEquals(0.02 * 0.35, hardened.dropTable[1].chance, 1e-9)
    }

    @Test
    fun `hardened dungeon clamps encounter rate and cuts rare drops`() {
        val hardened = HardcoreRules.hardenDungeon(dungeon())
        assertEquals(0.50, hardened.encounterRate, 1e-9)
        assertEquals(0.10 * 0.35, hardened.rareDrops[0].chance, 1e-9)
    }

    @Test
    fun `hardened gear raises every requirement`() {
        val gear = EquipmentData(
            name = "sword", displayName = "Sword", slot = "weapon",
            requirements = mapOf("attack" to 10, "defense" to 4),
        )
        val hardened = HardcoreRules.hardenGear(gear)
        assertEquals(13, hardened.requirements["attack"])
        assertEquals(5, hardened.requirements["defense"])
    }

    // ------------------------------------------------------------------ Simulator / Time Skip gating

    @Test
    fun `time skip is unavailable in base reality`() {
        assertFalse(RealitySimulator.isSimulationActive)
        assertFalse(RealitySimulator.canTimeSkip())
    }

    @Test
    fun `exactly three rewards may be selected`() {
        assertEquals(3, RealitySimulator.MAX_REWARDS)
    }

    @Test
    fun `skip durations grow with the persistent upgrade`() {
        assertEquals(15, RealitySimulator.maxSkipMinutes(0))
        assertEquals(30, RealitySimulator.maxSkipMinutes(1))
        assertEquals(60, RealitySimulator.maxSkipMinutes(2))
        assertEquals(120, RealitySimulator.maxSkipMinutes(3))
        assertEquals(240, RealitySimulator.maxSkipMinutes(4))
        // Past the max level the cap stays put.
        assertEquals(240, RealitySimulator.maxSkipMinutes(9))
    }

    @Test
    fun `efficiency upgrade scales time skip results and is capped`() {
        assertEquals(1.0f, RealitySimulator.efficiencyMultiplier(0))
        assertEquals(1.25f, RealitySimulator.efficiencyMultiplier(5))
        assertEquals(1.25f, RealitySimulator.efficiencyMultiplier(9))
    }

    @Test
    fun `upgrades cost coins until max level`() {
        assertTrue(RealitySimulator.upgradeCost(RealitySimulator.UPGRADE_SKIP_DURATION, 0) > 0)
        assertEquals(-1L, RealitySimulator.upgradeCost(RealitySimulator.UPGRADE_SKIP_DURATION, RealitySimulator.MAX_SKIP_DURATION_LEVEL))
        assertEquals(-1L, RealitySimulator.upgradeCost(RealitySimulator.UPGRADE_EFFICIENCY, RealitySimulator.MAX_EFFICIENCY_LEVEL))
    }

    // ------------------------------------------------------------------ encounter gate

    @Test
    fun `encounter gate keeps every minute frame but not every minute a fight`() {
        val hardened = HardcoreRules.hardenDungeon(dungeon())
        val result = CombatSimulator.simulateDungeon(
            dungeon = hardened,
            enemies = mapOf("rat" to HardcoreRules.hardenEnemy(enemy())),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 99,
            random = Random(42),
        )
        // Frame count is unchanged: quiet minutes are still recorded.
        assertEquals(60, result.frames.size)
        // With a 0.50 encounter rate, some minutes are quiet (no enemy chain).
        assertTrue(result.frames.any { it.kills == 0 && it.enemyKey.isEmpty() })
    }

    @Test
    fun `encounter rate of 1_0 fights every minute like before`() {
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon().copy(encounterRate = 1.0),
            enemies = mapOf("rat" to enemy()),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 99,
            random = Random(7),
        )
        assertEquals(60, result.frames.size)
        assertTrue(result.frames.all { it.enemyKey.isNotEmpty() })
    }
}
