package com.fantasyidler.simulator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.db.dao.SimulationPlayerDao
import com.fantasyidler.data.db.dao.SimulationSessionDao
import com.fantasyidler.data.model.Player
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.repository.BackupScheduler
import com.fantasyidler.repository.BoostRepository
import com.fantasyidler.repository.BuffNotificationScheduler
import com.fantasyidler.repository.DailyQuestRepository
import com.fantasyidler.repository.FarmingRepository
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.GlobalStateRepository
import com.fantasyidler.repository.GuildRepository
import com.fantasyidler.repository.MercenaryRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.QueuedSessionStarter
import com.fantasyidler.repository.QuestRepository
import com.fantasyidler.repository.SaveSlotRepository
import com.fantasyidler.repository.SeasonalEventRepository
import com.fantasyidler.repository.SessionRepository
import com.fantasyidler.repository.TownRepository
import com.fantasyidler.repository.WeeklyQuestRepository
import com.fantasyidler.repository.WorkerQueuedSessionStarter
import com.fantasyidler.simulator.RealitySimulator.Phase
import com.fantasyidler.simulator.RealitySimulator.RewardKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * Simulator isolation and Time Skip correctness.
 *
 * Every assertion here is a regression for a leak between the temporary simulation state and
 * Base Reality: the player flow, the session table (player, combat, boss, and both worker
 * slots), Time Skip frame semantics (completion, partial progress, consumables, death, boss
 * rewards), duplicate reward claims, crash recovery, and save-slot/character ownership.
 *
 * The repositories are wired exactly like [com.fantasyidler.di.DatabaseModule]: both DAOs are
 * wrapped by their Simulation decorators, and the raw Room DAOs are kept for assertions about
 * what Base Reality really holds.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class SimulatorIsolationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var json: Json
    private lateinit var boostRepo: BoostRepository
    private lateinit var playerRepo: PlayerRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var saveSlotRepo: SaveSlotRepository
    private lateinit var globalStateRepo: GlobalStateRepository

    /** The unwrapped DAOs: what Base Reality actually holds, whatever the simulation believes. */
    private val realPlayerDao get() = db.playerDao()
    private val realSessionDao get() = db.skillSessionDao()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        json = Json { ignoreUnknownKeys = true }
        val gameData = GameDataRepository(context, json)
        val dailyQuestRepo = DailyQuestRepository(gameData)
        val weeklyQuestRepo = WeeklyQuestRepository(gameData)
        boostRepo = BoostRepository(gameData)
        val buffNotifScheduler = BuffNotificationScheduler(context)
        val playerDao = SimulationPlayerDao(db.playerDao())
        val sessionDao = SimulationSessionDao(db.skillSessionDao())
        playerRepo = PlayerRepository(
            playerDao,
            db.questProgressDao(),
            db.farmingPatchDao(),
            json,
            dailyQuestRepo,
            weeklyQuestRepo,
            buffNotifScheduler,
            gameData,
            boostRepo,
            db,
        )
        sessionRepo = SessionRepository(sessionDao, context, json, gameData, playerDao, playerRepo)
        globalStateRepo = GlobalStateRepository(db.globalStateDao())
        val backupScheduler = BackupScheduler(context, sessionRepo, globalStateRepo)
        val questRepo = QuestRepository(db.questProgressDao(), gameData)
        val townRepo = TownRepository(gameData, playerRepo, questRepo, boostRepo)
        val mercRepo = MercenaryRepository(playerRepo, gameData)
        val queuedSessionStarter = QueuedSessionStarter(
            boostRepo, context, playerRepo, sessionRepo, townRepo, gameData, mercRepo, json,
        )
        val workerStarter = WorkerQueuedSessionStarter(boostRepo, playerRepo, sessionRepo, gameData, json)
        val seasonalEventRepo = SeasonalEventRepository(playerRepo, gameData, dailyQuestRepo, context)
        val farmingRepo = FarmingRepository(
            context, db, db.farmingPatchDao(), playerRepo, gameData,
            seasonalEventRepo, globalStateRepo, json, boostRepo,
        )
        val guildRepo = GuildRepository(playerRepo, db.questProgressDao(), gameData, Provider { townRepo })
        saveSlotRepo = SaveSlotRepository(
            context, playerRepo, sessionRepo, questRepo, farmingRepo, guildRepo,
            globalStateRepo, queuedSessionStarter, workerStarter, backupScheduler,
            buffNotifScheduler, json,
        )
        runBlocking {
            globalStateRepo.setActiveSaveSlot(1)
            playerRepo.getOrCreatePlayer()
            // RealitySimulator is a process-wide singleton: start every test in Base Reality.
            RealitySimulator.abortToBaseReality(playerRepo, sessionRepo)
        }
        RealitySimulator.consumeCrashNotice()
        RealitySimulator.consumeInvalidRunNotice()
    }

    @After
    fun tearDown() {
        try {
            if (::playerRepo.isInitialized) {
                runBlocking { RealitySimulator.abortToBaseReality(playerRepo, sessionRepo) }
            }
        } catch (_: Exception) {
            // Never let cleanup mask the real failure.
        }
        RealitySimulator.consumeCrashNotice()
        RealitySimulator.consumeInvalidRunNotice()
        db.close()
    }

    // ------------------------------------------------------------------ harness

    private fun encodeFrames(frames: List<SessionFrame>): String =
        json.encodeToString(json.serializersModule.serializer<List<SessionFrame>>(), frames)

    private fun decodeFrames(session: SkillSession): List<SessionFrame> =
        json.decodeFromString(session.frames)

    /** Explicit two-arg encode, matching PlayerRepository's helper (no reified-form ambiguity). */
    private fun encodeFlags(flags: PlayerFlags): String =
        json.encodeToString(json.serializersModule.serializer<PlayerFlags>(), flags)

    /** A Base Reality character: level 10 mining, some combat levels, and a stocked bag. */
    private suspend fun seedPlayer(flags: PlayerFlags = PlayerFlags()) {
        realPlayerDao.upsert(
            playerRepo.getOrCreatePlayer().copy(
                skillLevels = """{"mining":10,"attack":10,"strength":10,"ranged":1,"magic":1}""",
                skillXp     = """{"mining":1000}""",
                inventory   = """{"iron_ore":5,"bread":60,"iron_arrow":120,"fire_rune":180}""",
                equipped    = """{}""",
                pets        = """[]""",
                coins       = 1_000L,
                flags       = encodeFlags(flags),
            )
        )
    }

    /** [count] gathering frames: 100 XP and 2 iron ore per minute. */
    private fun miningFrames(count: Int = 60): List<SessionFrame> {
        var xp = 0L
        return (1..count).map { minute ->
            val before = xp
            xp += 100
            SessionFrame(
                minute      = minute,
                xpGain      = 100,
                xpBefore    = before,
                xpAfter     = xp,
                levelBefore = 1,
                levelAfter  = 1,
                items       = mapOf("iron_ore" to 2),
            )
        }
    }

    /**
     * [count] combat frames: 25 attack + 25 strength XP, 1 bones and 10 coins of loot, and
     * 1 bread / 2 arrows / 3 runes consumed per minute. [deathAt] (1-based minute) kills the run.
     */
    private fun combatFrames(count: Int = 60, deathAt: Int? = null): List<SessionFrame> =
        (1..count).map { minute ->
            val dead = deathAt != null && minute >= deathAt
            SessionFrame(
                minute         = minute,
                xpGain         = 0,
                xpBefore       = 0,
                xpAfter        = 0,
                levelBefore    = 10,
                levelAfter     = 10,
                xpBySkill      = mapOf("attack" to 25L, "strength" to 25L),
                items          = mapOf("bones" to 1, "coins" to 10),
                kills          = 1,
                killsByEnemy   = mapOf("goblin" to 1),
                died           = minute == deathAt,
                foodConsumed   = mapOf("bread" to 1),
                arrowsConsumed = mapOf("iron_arrow" to 2),
                runesConsumed  = mapOf("fire_rune" to 3),
                hpAfter        = if (dead) 0 else 40,
            )
        }

    /** [count] boss frames; the boss dies on the last one when [win], otherwise the player does. */
    private fun bossFrames(count: Int = 30, win: Boolean): List<SessionFrame> =
        (1..count).map { minute ->
            val last = minute == count
            SessionFrame(
                minute       = minute,
                xpGain       = 0,
                xpBefore     = 0,
                xpAfter      = 0,
                levelBefore  = 10,
                levelAfter   = 10,
                xpBySkill    = mapOf("attack" to 40L),
                items        = if (win && last) mapOf("coins" to 500, "dragon_scale" to 3) else emptyMap(),
                kills        = if (win && last) 1 else 0,
                died         = !win && last,
                foodConsumed = mapOf("bread" to 1),
                enemyKey     = "sea_serpent",
            )
        }

    private suspend fun seedSession(
        sessionId: String,
        skillName: String,
        activityKey: String,
        frames: List<SessionFrame>,
        startedAt: Long,
        completed: Boolean = false,
        workerSlot: Int = 0,
        durationMs: Long = 3_600_000L,
    ) {
        sessionRepo.insertSession(
            SkillSession(
                sessionId       = sessionId,
                skillName       = skillName,
                activityKey     = activityKey,
                startedAt       = startedAt,
                endsAt          = startedAt + durationMs,
                frames          = encodeFrames(frames),
                completed       = completed,
                isWorkerSession = workerSlot > 0,
                workerSlot      = workerSlot,
            )
        )
    }

    /** A typical Base Reality state: one active + one completed player session, one per worker slot. */
    private suspend fun seedBaseReality(base: Long = System.currentTimeMillis()): Long {
        seedPlayer()
        seedSession("real_active", "mining", "iron_ore", miningFrames(), base)
        seedSession("real_completed", "woodcutting", "oak_tree", miningFrames(), base - 7_200_000L, completed = true)
        seedSession("worker_active", "fishing", "shrimp", miningFrames(10), base, workerSlot = 1)
        seedSession("worker_completed", "farming", "potato", miningFrames(10), base - 7_200_000L, completed = true, workerSlot = 2)
        return base
    }

    private suspend fun realSessions(): List<SkillSession> =
        realSessionDao.getAllSessions().sortedBy { it.sessionId }

    private suspend fun realInventory(): Map<String, Int> =
        json.decodeFromString(realPlayerDao.getPlayer()!!.inventory)

    private suspend fun realXp(): Map<String, Long> =
        json.decodeFromString(realPlayerDao.getPlayer()!!.skillXp)

    private suspend fun simInventory(): Map<String, Int> =
        json.decodeFromString(playerRepo.getOrCreatePlayer().inventory)

    private suspend fun simXp(): Map<String, Long> =
        json.decodeFromString(playerRepo.getOrCreatePlayer().skillXp)

    /**
     * Asserts Base Reality is byte-identical to [expected], ignoring only the crash marker the
     * Simulator deliberately writes to the real save while a run is in progress.
     */
    private suspend fun assertBaseRealityUnchanged(expected: Player) {
        val actual = realPlayerDao.getPlayer()!!
        assertEquals("real coins changed", expected.coins, actual.coins)
        assertEquals("real skill xp changed", expected.skillXp, actual.skillXp)
        assertEquals("real skill levels changed", expected.skillLevels, actual.skillLevels)
        assertEquals("real inventory changed", expected.inventory, actual.inventory)
        assertEquals("real equipment changed", expected.equipped, actual.equipped)
        assertEquals("real pets changed", expected.pets, actual.pets)
        val expectedFlags: PlayerFlags = json.decodeFromString(expected.flags)
        val actualFlags: PlayerFlags = json.decodeFromString(actual.flags)
        assertEquals(
            "real flags changed",
            expectedFlags.copy(simRunActiveSince = 0L),
            actualFlags.copy(simRunActiveSince = 0L),
        )
    }

    // ------------------------------------------------------------------ player isolation

    @Test
    fun `player flow serves the simulated player while a run is active and the real one after`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val baseCoins = realBefore.coins

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        playerRepo.addCoins(500)

        val simulated = withTimeoutOrNull(5_000) {
            playerRepo.playerFlow.first { it != null && it.coins == baseCoins + 500 }
        }
        assertNotNull("playerFlow never emitted the simulated player", simulated)
        assertEquals("the real save moved during the simulation", baseCoins, realPlayerDao.getPlayer()!!.coins)

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1)
        val restored = withTimeoutOrNull(5_000) {
            playerRepo.playerFlow.first { it != null && it.coins == baseCoins }
        }
        assertNotNull("playerFlow kept serving the simulated player after the run ended", restored)
        assertBaseRealityUnchanged(realBefore)
    }

    @Test
    fun `every repository read inside a run resolves to the simulated state`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        playerRepo.addCoins(250)
        playerRepo.grantItem("iron_ore", 7)
        playerRepo.applySessionResults("mining", 400, mapOf("coal" to 3))
        playerRepo.updateFlags(playerRepo.getFlags().copy(currentHp = 12))

        assertEquals(1_250L, playerRepo.getOrCreatePlayer().coins)
        assertEquals(12, simInventory()["iron_ore"])
        assertEquals(3, simInventory()["coal"])
        assertEquals(1_000L + 200L, simXp()["mining"])
        assertEquals(12, playerRepo.getFlags().currentHp)

        // Base Reality: none of it landed.
        assertBaseRealityUnchanged(realBefore)
        assertEquals(5, realInventory()["iron_ore"])
        assertNull(realInventory()["coal"])
        assertEquals(1_000L, realXp()["mining"])
    }

    // ------------------------------------------------------------------ session isolation

    @Test
    fun `entering the simulator copies every base reality session without touching them`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()
        assertEquals(4, sessionsBefore.size)

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)

        // The parallel run starts from exactly the Base Reality session state...
        assertEquals(sessionsBefore.map { it.sessionId }.sorted(), RealitySimulator.simSessions.value.map { it.sessionId }.sorted())
        assertEquals("real_active", sessionRepo.getActiveSession()!!.sessionId)
        assertEquals("worker_active", sessionRepo.getActiveWorkerSession(1)!!.sessionId)
        // ...and Base Reality is untouched.
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
    }

    @Test
    fun `sessions created completed collected abandoned and deleted inside a run never reach base reality`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)

        // Create through the real start path (alarm + heirloom stamp included).
        val started = sessionRepo.startSession(
            skillName        = "mining",
            activityKey      = "iron_ore",
            frames           = encodeFrames(miningFrames()),
            skillDisplayName = "Mining",
        )
        assertNotNull(sessionRepo.getSession(started.sessionId))
        assertNull("a simulated session reached the real table", realSessionDao.getSession(started.sessionId))

        // Collect: complete the running session and delete the finished one, like HomeViewModel.
        sessionRepo.markCompleted("real_active")
        assertTrue(sessionRepo.getSession("real_active")!!.completed)
        sessionRepo.deleteSession("real_completed")
        assertNull(sessionRepo.getSession("real_completed"))

        // Abandon the session the run started.
        sessionRepo.abandonSession(started.sessionId)
        assertNull(sessionRepo.getSession(started.sessionId))
        assertEquals("real_active", sessionRepo.getActiveSession()!!.sessionId)

        // Base Reality: the table is exactly as it was, and the run's own session never existed.
        assertEquals(sessionsBefore, realSessions())
        assertFalse(realSessionDao.getSession("real_active")!!.completed)
        assertNotNull(realSessionDao.getSession("real_completed"))
        assertBaseRealityUnchanged(realBefore)

        // Discarding the run drops the isolated table and leaves Base Reality as it was.
        RealitySimulator.abortToBaseReality(playerRepo, sessionRepo)
        assertTrue(RealitySimulator.simSessions.value.isEmpty())
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
    }

    @Test
    fun `worker sessions in every slot are isolated`() = runBlocking {
        seedBaseReality()
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)

        sessionRepo.markCompleted("worker_active")
        sessionRepo.deleteAllWorkerSessions(2)
        sessionRepo.insertSession(
            SkillSession(
                sessionId       = "sim_worker",
                skillName       = "fishing",
                activityKey     = "shrimp",
                startedAt       = System.currentTimeMillis() + 60_000L,
                endsAt          = System.currentTimeMillis() + 3_660_000L,
                frames          = encodeFrames(miningFrames(5)),
                isWorkerSession = true,
                workerSlot      = 1,
            )
        )

        // Inside the run: slot 1 completed and replaced, slot 2 wiped.
        assertEquals(listOf("worker_active"), sessionRepo.getAllCompletedWorkerSessions(1).map { it.sessionId })
        assertEquals("sim_worker", sessionRepo.getActiveWorkerSession(1)!!.sessionId)
        assertTrue(sessionRepo.getAllCompletedWorkerSessions(2).isEmpty())

        // Base Reality: both worker slots exactly as seeded.
        assertEquals(sessionsBefore, realSessions())
        assertFalse(realSessionDao.getActiveWorkerSession(1)!!.completed)
        assertEquals(listOf("worker_completed"), realSessionDao.getAllCompletedWorkerSessions(2).map { it.sessionId })
        assertNull(realSessionDao.getSession("sim_worker"))
    }

    // ------------------------------------------------------------------ Time Skip

    @Test
    fun `a full time skip completes the isolated session and pays it once, inside the simulation only`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)

        assertEquals(60, result.minutes)
        assertEquals(6_000L, result.xpBySkill["mining"])
        assertEquals(120, result.items["iron_ore"])
        assertFalse(result.died)
        assertEquals(Phase.TIME_SKIP_RESULT, RealitySimulator.phase.value)

        // The isolated session is completed and spent, exactly like a normally collected one.
        val simSession = sessionRepo.getActiveSession()!!
        assertTrue("Time Skip did not complete the session it consumed", simSession.completed)
        assertTrue(RealitySimulator.fastForwardComplete(simSession))
        assertEquals(60, decodeFrames(simSession).size)
        assertEquals(0, decodeFrames(simSession).sumOf { it.xpGain })

        // Rewards landed in the simulation only, at Base Reality's halved XP rate.
        assertEquals(1_000L + 3_000L, simXp()["mining"])
        assertEquals(5 + 120, simInventory()["iron_ore"])

        // Collecting it normally afterwards must not pay a second time.
        val framesLeft = decodeFrames(sessionRepo.getSession("real_active")!!)
        assertEquals(0, framesLeft.sumOf { it.xpGain })
        assertTrue(framesLeft.all { it.items.isEmpty() })

        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
        assertFalse(realSessionDao.getActiveSession()!!.completed)
        assertEquals(1_000L, realXp()["mining"])
        assertEquals(5, realInventory()["iron_ore"])
    }

    @Test
    fun `a partial time skip keeps the remaining frames at the right elapsed position`() = runBlocking {
        val base = seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val first = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 20, 1)
        assertEquals(20, first.minutes)
        assertEquals(2_000L, first.xpBySkill["mining"])

        var session = sessionRepo.getSession("real_active")!!
        assertFalse("a partial skip must not complete the session", session.completed)
        var frames = decodeFrames(session)
        assertEquals(60, frames.size)
        // The skipped minutes are spent...
        assertEquals(0, frames.take(20).sumOf { it.xpGain })
        assertTrue(frames.take(20).all { it.items.isEmpty() })
        // ...the remaining ones are untouched and still sit at their original minute indices.
        assertEquals(4_000, frames.drop(20).sumOf { it.xpGain })
        assertEquals((21..60).toList(), frames.drop(20).map { it.minute })
        assertEquals(80, frames.drop(20).sumOf { it.items["iron_ore"] ?: 0 })
        // The session was back-dated by the skipped wall-clock, so normal progression resumes at
        // frame 20 one minute per frame instead of replaying the minutes that were skipped.
        assertEquals(base - 1_200_000L, session.startedAt)
        assertEquals(base + 2_400_000L, session.endsAt)
        assertFalse(RealitySimulator.fastForwardComplete(session))

        // A second skip continues from exactly that point.
        val second = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 20, 1)
        assertEquals(20, second.minutes)
        assertEquals(2_000L, second.xpBySkill["mining"])
        session = sessionRepo.getSession("real_active")!!
        frames = decodeFrames(session)
        assertEquals(2_000, frames.sumOf { it.xpGain })
        assertEquals(40, frames.sumOf { it.items["iron_ore"] ?: 0 })
        assertEquals(base - 2_400_000L, session.startedAt)
        assertFalse(session.completed)

        // Collecting the session normally now pays only the 20 minutes never skipped.
        val remainingItems = mutableMapOf<String, Int>()
        for (frame in frames) for ((key, qty) in frame.items) remainingItems[key] = (remainingItems[key] ?: 0) + qty
        assertEquals(mapOf("iron_ore" to 40), remainingItems)
        playerRepo.applySessionResults(
            skillName   = "mining",
            xpGained    = frames.sumOf { it.xpGain.toLong() },
            itemsGained = remainingItems,
            sessionId   = "real_active",
        )
        sessionRepo.deleteSession("real_active")
        assertNull(sessionRepo.getSession("real_active"))

        // Two skips plus the collect equal exactly one full session: nothing duplicated, nothing lost.
        assertEquals(1_000L + 3_000L, simXp()["mining"])
        assertEquals(5 + 120, simInventory()["iron_ore"])

        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
    }

    @Test
    fun `time skip deducts food arrows and runes like a normal collect`() = runBlocking {
        val base = seedBaseReality()
        seedSession("combat_run", "combat", "dark_cave", combatFrames(), base + 60_000L)
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)

        assertEquals(mapOf("bread" to 60), result.foodConsumed)
        assertEquals(mapOf("iron_arrow" to 120), result.arrowsConsumed)
        assertEquals(mapOf("fire_rune" to 180), result.runesConsumed)
        assertEquals(600L, result.coins)
        assertEquals(60, result.items["bones"])

        val inventory = simInventory()
        // Food and arrows are consumed; a quarter of the arrows come back at ranged level 1.
        assertNull(inventory["bread"])
        assertEquals(30, inventory["iron_arrow"])
        // Runes follow the collect path exactly: reclaimed, never deducted.
        assertEquals(180 + 45, inventory["fire_rune"])
        assertEquals(60, inventory["bones"])
        assertEquals(1_000L + 600L, playerRepo.getOrCreatePlayer().coins)
        // Combat XP splits per skill and is halved by Base Reality.
        assertEquals(750L, simXp()["attack"])
        assertEquals(750L, simXp()["strength"])

        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
        assertEquals(60, realInventory()["bread"])
        assertEquals(120, realInventory()["iron_arrow"])
        assertEquals(180, realInventory()["fire_rune"])
        assertEquals(1_000L, realPlayerDao.getPlayer()!!.coins)
    }

    @Test
    fun `a death inside a time skip applies the death penalty and ends the run`() = runBlocking {
        val base = seedBaseReality()
        seedSession("deadly_run", "combat", "dark_cave", combatFrames(deathAt = 10), base + 60_000L)
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)

        assertTrue(result.died)
        assertEquals("the run must stop at the death minute", 10, result.minutes)
        assertEquals(Phase.SIM_DEATH, RealitySimulator.phase.value)
        // Base Reality death keeps nothing: XP floors at 1 per skill, loot and coins are dropped.
        assertEquals(1L, result.xpBySkill["attack"])
        assertEquals(1L, result.xpBySkill["strength"])
        assertTrue(result.items.isEmpty())
        assertEquals(0L, result.coins)
        // The minutes fought before the death still cost their consumables.
        assertEquals(mapOf("bread" to 10), result.foodConsumed)
        assertEquals(60 - 10, simInventory()["bread"])

        val session = sessionRepo.getSession("deadly_run")!!
        assertTrue("the fight is over at the death frame", session.completed)
        val frames = decodeFrames(session)
        assertTrue(frames.take(10).all { it.xpBySkill.isEmpty() && it.items.isEmpty() && it.foodConsumed.isEmpty() })
        assertFalse("frames after the death must stay intact", frames.drop(10).all { it.xpBySkill.isEmpty() })

        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
    }

    @Test
    fun `a boss time skip pays loot and coins only on a win`() = runBlocking {
        val base = seedBaseReality()
        seedSession("boss_win", "boss", "sea_serpent", bossFrames(win = true), base + 60_000L, durationMs = 1_800_000L)
        val realBefore = realPlayerDao.getPlayer()!!

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val win = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 30, 1)
        assertFalse(win.died)
        assertEquals(500L, win.coins)
        assertEquals(3, win.items["dragon_scale"])
        assertEquals(1_000L + 500L, playerRepo.getOrCreatePlayer().coins)
        assertTrue(sessionRepo.getSession("boss_win")!!.completed)
        RealitySimulator.abortToBaseReality(playerRepo, sessionRepo)

        // A lost fight: the player dies before the boss, so nothing is paid but the run still ends.
        sessionRepo.deleteSession("boss_win")
        seedSession("boss_loss", "boss", "sea_serpent", bossFrames(win = false), System.currentTimeMillis(), durationMs = 1_800_000L)
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val loss = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 30, 1)
        assertTrue(loss.died)
        assertEquals(0L, loss.coins)
        assertTrue(loss.items.isEmpty())
        assertEquals(Phase.SIM_DEATH, RealitySimulator.phase.value)

        // Neither fight touched Base Reality.
        assertBaseRealityUnchanged(realBefore)
        assertEquals(1_000L, realPlayerDao.getPlayer()!!.coins)
        assertNull(realInventory()["dragon_scale"])
    }

    @Test
    fun `a zero duration session is a safe no-op and an empty frame list cannot tear down the run`() = runBlocking {
        val now = System.currentTimeMillis()
        seedPlayer()
        seedSession("instant", "mining", "iron_ore", miningFrames(10), now, durationMs = 0L)

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        // No wall-clock duration: the frames are still processed, but nothing is back-dated.
        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 10, 1)
        assertEquals(10, result.minutes)
        val session = sessionRepo.getSession("instant")!!
        assertTrue(session.completed)
        assertEquals(now, session.startedAt)
        assertEquals(now, session.endsAt)

        // An empty frame list has nothing to advance and must not be treated as corruption.
        sessionRepo.insertSession(
            SkillSession(
                sessionId = "empty",
                skillName = "mining",
                activityKey = "iron_ore",
                startedAt = now + 60_000L,
                endsAt = now + 60_000L,
                frames = "[]",
            )
        )
        val empty = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        assertEquals(0, empty.minutes)
        assertTrue("an empty session must not discard the run", RealitySimulator.isSimulationActive)
        assertEquals(Phase.TIME_SKIP_RESULT, RealitySimulator.phase.value)
        assertFalse(RealitySimulator.crashedLastRun.value)
    }

    @Test
    fun `corrupt session frames discard only the simulation and report the error`() = runBlocking {
        val base = seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        sessionRepo.insertSession(
            SkillSession(
                sessionId = "corrupt",
                skillName = "mining",
                activityKey = "iron_ore",
                startedAt = base + 60_000L,
                endsAt = base + 3_660_000L,
                frames = "{oops",
            )
        )

        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 30, 1)
        assertEquals(0, result.minutes)
        assertFalse(RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.crashedLastRun.value)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertTrue(RealitySimulator.simSessions.value.isEmpty())

        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
    }

    // ------------------------------------------------------------------ rewards

    @Test
    fun `claimed rewards are applied exactly once`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)

        val pool = RealitySimulator.rewardPool()
        val xpReward = pool.first { it.kind == RewardKind.SKILL_XP && it.id == "mining" }
        val itemReward = pool.first { it.kind == RewardKind.ITEM && it.id == "iron_ore" }
        assertEquals(3_000L, xpReward.amount)
        assertEquals(120L, itemReward.amount)

        // Nothing leaks into Base Reality before the rewards are explicitly claimed.
        assertBaseRealityUnchanged(realBefore)

        assertTrue(RealitySimulator.claimRewards(playerRepo, listOf(xpReward, itemReward), 1))
        val afterFirstClaim = realPlayerDao.getPlayer()!!
        assertEquals(1_000L + 3_000L, realXp()["mining"])
        assertEquals(5 + 120, realInventory()["iron_ore"])
        assertEquals(0L, json.decodeFromString<PlayerFlags>(afterFirstClaim.flags).simRunActiveSince)

        // A second claim of the same rewards (double tap, stale UI) must apply nothing again.
        RealitySimulator.claimRewards(playerRepo, listOf(xpReward, itemReward), 1)
        assertEquals("a repeated claim applied rewards twice", afterFirstClaim, realPlayerDao.getPlayer())
        assertEquals(1_000L + 3_000L, realXp()["mining"])
        assertEquals(5 + 120, realInventory()["iron_ore"])
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)

        // The temporary state is gone with the claim: nothing simulated is left to re-apply.
        assertNull(RealitySimulator.simPlayer.value)
        assertTrue(RealitySimulator.rewardPool().isEmpty())
    }

    @Test
    fun `discarding a run keeps base reality exactly as it was`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        sessionRepo.markCompleted("real_active")
        sessionRepo.deleteSession("real_completed")
        RealitySimulator.abortToBaseReality(playerRepo, sessionRepo)

        assertFalse(RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.simSessions.value.isEmpty())
        assertTrue(RealitySimulator.rewardPool().isEmpty())
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        // No stale simulated value was restored over the real save.
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
        assertEquals(1_000L, realXp()["mining"])
        assertEquals(5, realInventory()["iron_ore"])
    }

    @Test
    fun `simulator upgrades persist in base reality across a run and a claim`() = runBlocking {
        seedBaseReality()
        realPlayerDao.upsert(
            realPlayerDao.getPlayer()!!.copy(
                flags = encodeFlags(
                    PlayerFlags(
                        simulatorUpgrades = mapOf(
                            RealitySimulator.UPGRADE_SKIP_DURATION to 2,
                            RealitySimulator.UPGRADE_EFFICIENCY to 1,
                        ),
                    )
                ),
            )
        )
        val upgradesBefore = json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags).simulatorUpgrades

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        // The checkpoint carries the upgrades, and the efficiency upgrade scales the skip.
        assertEquals(upgradesBefore, playerRepo.getFlags().simulatorUpgrades)
        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        assertEquals(6_000L, result.xpBySkill["mining"])
        assertEquals(1_000L + 3_150L, simXp()["mining"])
        assertEquals(5 + 126, simInventory()["iron_ore"])

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1)
        val xpReward = RealitySimulator.rewardPool().first { it.kind == RewardKind.SKILL_XP && it.id == "mining" }
        assertTrue(RealitySimulator.claimRewards(playerRepo, listOf(xpReward), 1))

        val realFlags = json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags)
        assertEquals("Simulator upgrades must survive a run", upgradesBefore, realFlags.simulatorUpgrades)
        assertEquals(2, realFlags.simulatorUpgrades[RealitySimulator.UPGRADE_SKIP_DURATION])
        assertEquals(1, realFlags.simulatorUpgrades[RealitySimulator.UPGRADE_EFFICIENCY])
        assertEquals(0L, realFlags.simRunActiveSince)
        assertEquals(1_000L + 3_150L, realXp()["mining"])
        assertEquals("upgrades are Base Reality state, never a claimable reward", 5, realInventory()["iron_ore"])
    }

    // ------------------------------------------------------------------ crash recovery

    @Test
    fun `crash recovery discards the run without touching base reality`() = runBlocking {
        val base = seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        sessionRepo.markCompleted("real_active")
        sessionRepo.deleteSession("real_completed")
        seedSession("sim_only", "mining", "iron_ore", miningFrames(5), base + 120_000L)

        // The crash marker is the one thing a run writes to Base Reality, so a force-close is
        // detectable on the next launch.
        assertTrue(json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags).simRunActiveSince > 0L)

        // Force-close: the process dies, the temporary state dies with it, recovery runs at launch.
        RealitySimulator.recoverFromCrash(playerRepo, sessionRepo)

        assertFalse(RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.crashedLastRun.value)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertTrue(RealitySimulator.simSessions.value.isEmpty())
        assertTrue(RealitySimulator.rewardPool().isEmpty())
        assertEquals(0L, json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags).simRunActiveSince)

        // Nothing the run did survived, and nothing Base Reality had was lost.
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
        assertFalse(realSessionDao.getActiveSession()!!.completed)
        assertNotNull(realSessionDao.getSession("real_completed"))
        assertNull(realSessionDao.getSession("sim_only"))
        assertEquals(1_000L, realXp()["mining"])

        RealitySimulator.consumeCrashNotice()
        assertFalse(RealitySimulator.crashedLastRun.value)
    }

    @Test
    fun `recovery without a crash marker changes nothing`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.recoverFromCrash(playerRepo, sessionRepo)

        assertFalse(RealitySimulator.crashedLastRun.value)
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
    }

    // ------------------------------------------------------------------ character / save-slot protection

    @Test
    fun `switching characters is refused while a simulation is active`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)

        assertFalse("a character switch must be refused during a simulation", saveSlotRepo.switchTo(2))
        assertEquals(1, saveSlotRepo.activeSlot())
        assertTrue("the refused switch must not end the run", RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.ownsRun(1))
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)

        // Once the run is discarded the very same switch goes through.
        RealitySimulator.abortToBaseReality(playerRepo, sessionRepo)
        saveSlotRepo.switchTo(2)
        assertEquals(2, saveSlotRepo.activeSlot())
    }

    @Test
    fun `a time skip for another character is rejected and discards the run`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 2)

        assertEquals(0, result.minutes)
        assertFalse(RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.runInvalidated.value)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertTrue(RealitySimulator.rewardPool().isEmpty())
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
        assertEquals(1_000L, realXp()["mining"])

        RealitySimulator.consumeInvalidRunNotice()
        assertFalse(RealitySimulator.runInvalidated.value)
    }

    @Test
    fun `rewards claimed from another character are rejected and apply nothing`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1)
        val pool = RealitySimulator.rewardPool()
        assertTrue(pool.isNotEmpty())

        // Claiming on a character that did not start the run is refused...
        assertFalse(RealitySimulator.claimRewards(playerRepo, pool.take(3), 2))
        assertTrue(RealitySimulator.runInvalidated.value)
        // ...and the run's rewards die with it instead of crossing characters.
        assertTrue(RealitySimulator.rewardPool().isEmpty())
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
        assertEquals(1_000L, realXp()["mining"])
        assertEquals(5, realInventory()["iron_ore"])
        assertEquals(1_000L, realPlayerDao.getPlayer()!!.coins)
    }

    @Test
    fun `a run without a valid originating slot never starts`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 0)

        assertFalse(RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.runInvalidated.value)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertTrue(RealitySimulator.simSessions.value.isEmpty())
        assertFalse("an invalid run must not be claimable", RealitySimulator.ownsRun(1))
        assertEquals(0L, json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags).simRunActiveSince)
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
    }

    @Test
    fun `time skip is unavailable outside the simulator`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!

        assertFalse(RealitySimulator.canTimeSkip())
        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        assertEquals(0, result.minutes)
        assertBaseRealityUnchanged(realBefore)
        assertEquals(1_000L, realXp()["mining"])
    }
}
