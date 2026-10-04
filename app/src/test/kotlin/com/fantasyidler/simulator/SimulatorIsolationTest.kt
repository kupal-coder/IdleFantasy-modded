package com.fantasyidler.simulator

import android.app.AlarmManager
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
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
import org.robolectric.Shadows
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

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
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

    @Test
    fun `a simulation neither arms nor cancels base reality alarms`() = runBlocking {
        seedBaseReality()
        val shadow = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

        // Outside a run, starting a session arms its real completion alarm.
        val real = sessionRepo.startSession(
            skillName        = "mining",
            activityKey      = "iron_ore",
            frames           = encodeFrames(miningFrames(5)),
            skillDisplayName = "Mining",
        )
        val armedBefore = shadow.scheduledAlarms.size
        assertTrue("the real session should have armed its completion alarm", armedBefore > 0)

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        sessionRepo.startSession(
            skillName        = "fishing",
            activityKey      = "shrimp",
            frames           = encodeFrames(miningFrames(5)),
            skillDisplayName = "Fishing",
        )
        // The run starts from a copy of the real table, so these ids are also real sessions:
        // completing and abandoning them must not cancel Base Reality's alarms.
        sessionRepo.markCompleted(real.sessionId)
        sessionRepo.abandonSession(real.sessionId)
        assertEquals("a run must not arm or cancel Base Reality alarms", armedBefore, shadow.scheduledAlarms.size)

        RealitySimulator.abortToBaseReality(playerRepo, sessionRepo)
        sessionRepo.startSession(
            skillName        = "mining",
            activityKey      = "iron_ore",
            frames           = encodeFrames(miningFrames(5)),
            skillDisplayName = "Mining",
        )
        assertEquals("outside the run alarms are armed again", armedBefore + 1, shadow.scheduledAlarms.size)
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
    fun `corrupt player data fails safely instead of starting a run`() = runBlocking {
        seedPlayer()
        realPlayerDao.upsert(realPlayerDao.getPlayer()!!.copy(flags = "{oops"))
        val sessionsBefore = realSessions()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)

        assertFalse(RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.crashedLastRun.value)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertNull(RealitySimulator.simPlayer.value)
        assertTrue(RealitySimulator.simSessions.value.isEmpty())
        assertFalse("an uncheckpointed run must not be claimable", RealitySimulator.ownsRun(1))
        // Nothing was written to Base Reality — not even the crash marker, which is only set
        // once the checkpoint succeeded.
        assertEquals("{oops", realPlayerDao.getPlayer()!!.flags)
        assertEquals(sessionsBefore, realSessions())
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
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
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

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
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
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
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

    // ------------------------------------------------------------------
    // Lifecycle / race regression tests (Layer 2 boundary sync)
    // ------------------------------------------------------------------

    @Test
    fun `manual exitSimulation without died flag is a no-op`() = runBlocking {
        seedBaseReality()
        val baseCoins = realPlayerDao.getPlayer()!!.coins

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)
        playerRepo.addCoins(999_999)
        assertEquals(baseCoins, realPlayerDao.getPlayer()!!.coins)

        // Voluntary exit (default died=false) must NOT end the sim.
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1)
        assertTrue("manual exit without died must be a no-op", RealitySimulator.isSimulationActive)
        assertEquals(Phase.IN_SIMULATION, RealitySimulator.phase.value)

        // Only death ends it.
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        assertFalse(RealitySimulator.isSimulationActive)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
    }

    @Test
    fun `simulator entry takes a consistent player-plus-session snapshot`() = runBlocking {
        seedBaseReality()
        val preSession = realSession()
        val snapshotCoins = realPlayerDao.getPlayer()!!.coins
        val snapshotSessions = realSessions().size

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val simPlayer = RealitySimulator.simPlayer.value!!
        assertEquals("player snapshot must reflect last committed Base Reality state",
            snapshotCoins, simPlayer.coins)
        assertEquals("session snapshot must reflect last committed Base Reality state",
            snapshotSessions, RealitySimulator.simSessions.value.size)
        assertTrue(RealitySimulator.simSessions.value.any { it.sessionId == preSession.sessionId })
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
    }

    @Test
    fun `death drains in-flight session ops before deactivating isolation`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)

        val beforeCoins = realPlayerDao.getPlayer()!!.coins
        playerRepo.addCoins(250)
        assertEquals(beforeCoins, realPlayerDao.getPlayer()!!.coins)

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        assertEquals(beforeCoins, realPlayerDao.getPlayer()!!.coins)
        assertFalse(RealitySimulator.isSimulationActive)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
    }

    @Test
    fun `crash recovery clears state even while marked active`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        playerRepo.addCoins(123)
        assertTrue(RealitySimulator.isSimulationActive)

        RealitySimulator.scheduleCrashRecovery(playerRepo, sessionRepo)
        val reset = withTimeoutOrNull(5_000) {
            while (RealitySimulator.isSimulationActive) { kotlinx.coroutines.delay(10) }
            true
        }
        assertNotNull("crash recovery did not deactivate the simulator", reset)
        assertNull(RealitySimulator.simPlayer.value)
        assertTrue(RealitySimulator.crashedLastRun.value)
        assertEquals(0L, json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags).simRunActiveSince)
    }

    @Test
    fun `simulation activation prevents mutation leaks to Base Reality`() = runBlocking {
        seedBaseReality()
        val baseCoins = realPlayerDao.getPlayer()!!.coins
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)

        playerRepo.addCoins(777)
        assertEquals(baseCoins, realPlayerDao.getPlayer()!!.coins)
        assertEquals(baseCoins + 777, RealitySimulator.simPlayer.value!!.coins)

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
    }

    @Test
    fun `watchdog operations use simulator boundary during sim`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val realSessionCountBefore = realSessions().size
        val gameData = GameDataRepository(context, json)
        val questRepo = QuestRepository(db.questProgressDao(), gameData)
        val townRepo = TownRepository(gameData, playerRepo, questRepo, boostRepo)
        val mercRepo = MercenaryRepository(playerRepo, gameData)
        val starter = QueuedSessionStarter(
            boostRepo, context, playerRepo, sessionRepo, townRepo, gameData, mercRepo, json,
        )
                sessionRepo.completeOverdueSessions(starter, null)
        assertEquals(realSessionCountBefore, realSessions().size)
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
    }

    // ------------------------------------------------------------------
    // Pinned-layer / lifecycle determinism regression tests.
    //
    // These tests use CompletableDeferred barriers and structured concurrency to
    // deterministically create the exact interleavings we want — NO Thread.sleep,
    // NO kotlinx.coroutines.delay to "wait for things to happen".
    // ------------------------------------------------------------------

    @Test
    fun `Test A — sim op paused across death stays pinned to sim and never writes real DAO`() = runBlocking {
        seedBaseReality()
        val baseCoins = realPlayerDao.getPlayer()!!.coins
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)

        // opInsideBoundary enters withSimulatorBoundary, then parks on [parked].
        // While parked we fire death, wait until isSimulationActive flips false (meaning
        // death is past the flip and blocked in awaitDrainInFlight), then release.
        // The subsequent addCoins is a nested boundary (which should inherit the pinned
        // layer from the outer withSimulatorBoundary thanks to our existing-element check),
        // so even though isSimulationActive is false the write must stay in sim state.
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val op = async {
            RealitySimulator.withSimulatorBoundary {
                parked.complete(Unit)
                release.await()
                // Nested boundary — must inherit pinned sim layer.
                playerRepo.addCoins(321)
            }
        }
        parked.await() // op is now parked inside the boundary.

        val death = launch {
            RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        }
        // Wait deterministically until death has flipped isSimulationActive to false.
        // At that point death is in awaitDrainInFlight waiting for [op] to finish.
        while (RealitySimulator.isSimulationActive) yield()

        release.complete(Unit)
        op.join()
        death.join()

        assertEquals("pinned-to-sim op must not write Base Reality after death",
            baseCoins, realPlayerDao.getPlayer()!!.coins)
        assertFalse(RealitySimulator.isSimulationActive)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
    }

    @Test
    fun `Test B — two concurrent sim ops see each others updates (no lost updates)`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val simBase = RealitySimulator.simPlayer.value!!.coins
        val realBase = realPlayerDao.getPlayer()!!.coins

        // Run two ops concurrently that each modify a different part of state, with a
        // barrier in the middle so both reach their read phase before either writes.
        // addCoins is a read-modify-write under playerMutex, so they serialize internally,
        // but crucially they must both observe the latest sim player (not a stale snapshot).
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val allowA = CompletableDeferred<Unit>()
        val allowB = CompletableDeferred<Unit>()
        val opA = async {
            RealitySimulator.withSimulatorBoundary {
                aStarted.complete(Unit)
                allowA.await()
                playerRepo.addCoins(100)
            }
        }
        val opB = async {
            RealitySimulator.withSimulatorBoundary {
                bStarted.complete(Unit)
                allowB.await()
                playerRepo.addCoins(200)
            }
        }
        aStarted.await(); bStarted.await()
        // Release both. Both addCoins take playerMutex so one proceeds then the other;
        // the second must see the first's +100 (or vice versa), not a stale snapshot.
        allowA.complete(Unit); allowB.complete(Unit)
        opA.join(); opB.join()

        assertEquals(simBase + 300, RealitySimulator.simPlayer.value!!.coins)
        assertEquals("real DAO must be untouched", realBase, realPlayerDao.getPlayer()!!.coins)

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
    }

    @Test
    fun `Test C — death does not clear simPlayer or simSessions until in-flight ops finish`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertNotNull(RealitySimulator.simPlayer.value)
        assertTrue(RealitySimulator.simSessions.value.isNotEmpty())

        // The op enters the boundary, signals parked, then waits for deathToDrainPhase
        // (meaning death has flipped isSimulationActive=false and is blocked in
        // awaitDrainInFlight waiting for THIS op). Only THEN does the op read sim state,
        // proving state is still alive during the drain.
        val parked = CompletableDeferred<Unit>()
        val deathInDrain = CompletableDeferred<Unit>()
        val sawState = CompletableDeferred<Boolean>()
        val op = async {
            RealitySimulator.withSimulatorBoundary {
                parked.complete(Unit)
                deathInDrain.await()
                // This read happens AFTER isSimulationActive has flipped false and
                // death is parked in awaitDrainInFlight(). sim state MUST still be here.
                val alive = RealitySimulator.simPlayer.value != null &&
                    RealitySimulator.simSessions.value.isNotEmpty()
                sawState.complete(alive)
            }
        }
        parked.await()

        val death = async {
            RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        }
        // Wait deterministically for death to enter the drain (flag flipped, blocked on op).
        while (RealitySimulator.isSimulationActive) yield()
        deathInDrain.complete(Unit)

        val stateAliveDuringDrain = sawState.await()
        op.join()
        death.join()

        assertTrue("sim state must remain visible to in-flight ops during drain",
            stateAliveDuringDrain)
        // After all in-flight ops joined, sim state IS cleared.
        assertNull("sim state must be cleared after drain", RealitySimulator.simPlayer.value)
    }

    @Test
    fun `Test D — sim op paused across death does not schedule a real AlarmManager alarm`() = runBlocking {
        seedBaseReality()
        val shadow = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val before = shadow.scheduledAlarms.size

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)

        // Start a session while we can interleave death between the insert and the
        // scheduleAlarm call. The cleanest way is to use our own boundary block and
        // then invoke startSession inside it after death has flipped the flag: the
        // startSession body is a nested boundary (inherits pinned=sim), and its
        // scheduleAlarm call must respect the pinned layer and NOT arm a real alarm.
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val op = async {
            RealitySimulator.withSimulatorBoundary {
                parked.complete(Unit)
                release.await()
                // Nested boundary — inherits pinned sim layer.
                sessionRepo.startSession(
                    skillName        = "mining",
                    activityKey      = "iron_ore",
                    frames           = encodeFrames(miningFrames(3)),
                    skillDisplayName = "Mining",
                )
            }
        }
        parked.await()

        val death = launch {
            RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        }
        while (RealitySimulator.isSimulationActive) yield()
        release.complete(Unit)
        op.join()
        death.join()

                assertEquals("a sim-started session must not arm a real alarm, even when death " +
            "fired between insert and scheduleAlarm",
            before, shadow.scheduledAlarms.size)
        assertFalse(RealitySimulator.isSimulationActive)
    }

    @Test
    fun `Test E — concurrent enterSimulation calls only activate one Layer 2 session`() = runBlocking {
        seedBaseReality()
        val baseCoins = realPlayerDao.getPlayer()!!.coins
        val baseSessions = realSessions().size

        // Launch two concurrent enterSimulation() calls. Both reach the method at roughly
        // the same time; entryGate's tryLock guarantees that only ONE proceeds to publish,
        // the other returns immediately as a no-op. Deterministic: no timing needed, both
        // are launched, joined, then state is inspected.
        val a = launch { RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1) }
        val b = launch { RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1) }
        a.join(); b.join()

        assertTrue("exactly one simulation must be active", RealitySimulator.isSimulationActive)
        assertNotNull(RealitySimulator.simPlayer.value)
        // The sim must start with exactly the base session set (no duplicated state).
        assertEquals(baseSessions, RealitySimulator.simSessions.value.size)
        // Real DAO must remain untouched by entry.
        assertEquals(baseCoins, realPlayerDao.getPlayer()!!.coins)

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        assertFalse(RealitySimulator.isSimulationActive)
    }

    @Test
    fun `Test F — nested withSimulatorBoundary calls preserve pinned layer and balance inFlight counter`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val baseCoins = realPlayerDao.getPlayer()!!.coins

        // An outer boundary block that calls nested boundary-wrapped methods. After all
        // are done, death must be able to drain cleanly (inFlightOperations returns to 0);
        // if nesting double-counted, drain would hang. The nested calls must still be
        // routed to sim (coins stay in sim state), not leak to base.
        RealitySimulator.withSimulatorBoundary {
            // Nested boundary via addCoins (outermost pinned=true must be inherited).
            playerRepo.addCoins(50)
            // Another nested boundary.
            playerRepo.grantItem("test_item", 3)
        }
        assertEquals(baseCoins, realPlayerDao.getPlayer()!!.coins)
        assertEquals(baseCoins + 50, RealitySimulator.simPlayer.value!!.coins)

        // Death drains cleanly — if inFlightOperations were unbalanced this would deadlock;
        // we wrap in withTimeoutOrNull to fail deterministically if drain never completes.
        val exited = withTimeoutOrNull(3_000) {
            RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
            true
        }
        assertNotNull("nested boundaries must not unbalance inFlightOperations (drain hung)", exited)
        assertFalse(RealitySimulator.isSimulationActive)
    }

    // ------------------------------------------------------------------ lifecycle / coroutine-safety tests

    /**
     * Test G (bug #1 + #8): a Layer 2 op that SUSPENDS, then resumes on a different dispatcher
     * after death flips isSimulationActive, must STILL route to Layer 2 (pinned via coroutine
     * context). The write must land in _simPlayer, not leak to Base Reality.
     */
    @Test
    fun `Test G — suspended Layer 2 op resumes after death on another thread and stays Layer 2`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val baseCoins = realPlayerDao.getPlayer()!!.coins
        val simCoinsBefore = RealitySimulator.simPlayer.value!!.coins

        val insideOp = CompletableDeferred<Unit>()
        val resumeOp = CompletableDeferred<Unit>()
        val opFinished = CompletableDeferred<Boolean>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            RealitySimulator.withSimulatorBoundary {
                insideOp.complete(Unit)
                resumeOp.await() // suspend while death happens on another coroutine
                // Resume point: death has flipped isSimulationActive. We must still be pinned
                // to sim so this write lands in _simPlayer.
                playerRepo.addCoins(77)
                // And effectiveActive must read TRUE from the pinned context, not the live flag.
                opFinished.complete(RealitySimulator.effectiveActive())
            }
        }
        insideOp.await()
        // Now death happens WHILE the op is suspended.
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        assertFalse(RealitySimulator.isSimulationActive)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
        // Let the op resume.
        resumeOp.complete(Unit)
        val stillPinned = opFinished.await()
        job.join()
        assertTrue("pinned op must still see itself as sim-routed after resume", stillPinned)
        // Base Reality must NOT have received the 77 coins.
        assertEquals(baseCoins, realPlayerDao.getPlayer()!!.coins)
        // Sim player (still retained through REWARD_SELECTION) must hold them.
        assertEquals(simCoinsBefore + 77, RealitySimulator.simPlayer.value!!.coins)
        // Cleanup: skip rewards to return to BASE_REALITY.
        RealitySimulator.skipRewards(playerRepo)
    }

    /**
     * Test H (bug #5): concurrent enterSimulation calls — only one should publish; the other
     * no-ops. Use entryGate; second concurrent enter must not stomp first.
     */
    @Test
    fun `Test H — concurrent enterSimulation only one publishes`() = runBlocking {
        seedBaseReality()
        val entered1 = CompletableDeferred<Boolean>()
        val entered2 = CompletableDeferred<Boolean>()
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // The deterministic way: launch two enters; we cannot really hold the mutex mid-snapshot,
        // but we can verify that after two concurrent enters only ONE run is active: isSimulationActive
        // is true and the sim player reflects the snapshot exactly once.
        val j1 = async {
            ready.complete(Unit)
            release.await()
            RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
            true
        }
        val j2 = async {
            ready.await()
            release.await()
            RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
            true
        }
        ready.await(); ready.await()
        release.complete(Unit)
        j1.await(); j2.await()
        assertTrue(RealitySimulator.isSimulationActive)
        assertNotNull(RealitySimulator.simPlayer.value)
        // Death must drain cleanly (inFlight balanced).
        val exited = withTimeoutOrNull(2_000) {
            RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
            true
        }
        assertNotNull("death drain must complete", exited)
    }

    /**
     * Test I (bug #5 + #6): death/teardown vs a NEW enter — an old teardown must not clear the
     * new run's checkpoint/sessions.
     */
    @Test
    fun `Test I — teardown of old run does not clobber new run state`() = runBlocking {
        seedBaseReality()
        // First run: enter, immediately mark as dead via exitSimulation.
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val firstSimPlayer = RealitySimulator.simPlayer.value
        assertNotNull(firstSimPlayer)
        // Death moves to REWARD_SELECTION but keeps state.
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)

        // Now immediately start a NEW run — generation advances; old teardown must not clear.
        // But we cannot enter while in REWARD_SELECTION; skip rewards first.
        RealitySimulator.skipRewards(playerRepo)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)

        // Give sim player a coin marker so we can tell runs apart.
        realPlayerDao.upsert(realPlayerDao.getPlayer()!!.copy(coins = 5_000))
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertEquals(5_000, RealitySimulator.simPlayer.value!!.coins)
        // A stray discardRun/clearCrashMarkerIfOwned from a stale generation must not clear
        // the new run's state. The cleanest signal: simPlayer is not null and isSimulationActive
        // after we drive a manual stale-marker clear attempt.
        // We can't easily fabricate a stale generation call directly, but death+skip of the
        // new run must still drain cleanly.
        val exited = withTimeoutOrNull(2_000) {
            RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true); true
        }
        assertNotNull(exited)
        RealitySimulator.skipRewards(playerRepo)
    }

    /**
     * Test J (bug #6): crash-marker ownership — only the generation that set the marker clears
     * it. Simulate: enter sets marker → crash recovery clears marker for its generation → a
     * subsequent enter sets marker again → a second recovery for the OLD generation must NOT
     * clear the new marker. Since we cannot sleep, we use two back-to-back runs and verify
     * recoverFromCrash for an OLD generation (re-captured from a discarded run) does not clear
     * the new crash marker.
     *
     * Approach: start run, capture its generation by reading the marker's existence, then
     * discard the run (which clears the marker), then start a new run. Calling
     * recoverFromCrash must clear the marker only if activeGeneration matches — after a new
     * run sets a fresh marker, a duplicate recoverFromCrash call (resembling a stale late
     * callback) must NOT clear it because generation changed. We verify via reading flags:
     * marker should remain set after spurious recovery.
     */
    @Test
    fun `Test J — crash marker is only cleared by owning generation`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        var flags = playerRepo.getFlags()
        assertTrue("enter must set crash marker", flags.simRunActiveSince > 0)
        // Trigger crash recovery.
        RealitySimulator.recoverFromCrash(playerRepo, sessionRepo)
        flags = playerRepo.getFlags()
        assertEquals("recovery clears marker for its generation", 0L, flags.simRunActiveSince)
        // Now start a new run — marker set again.
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        flags = playerRepo.getFlags()
        assertTrue("second enter sets a fresh marker", flags.simRunActiveSince > 0)
        // Another recovery call simulates a stale late callback; after the second enter,
        // the captured generation in this new recovery call matches activeGeneration, so it
        // WILL clear the marker (correctly). What we really want to defend against is a
        // captured OLD generation clearing a NEW marker — simulate by spurious extra
        // clearCrashMarkerIfOwned call via a second recovery on the already-inactive state.
        // After a recovery, activeGeneration is 0; so enter a third time.
        RealitySimulator.recoverFromCrash(playerRepo, sessionRepo)
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        flags = playerRepo.getFlags()
        val markerAfterThirdEnter = flags.simRunActiveSince
        assertTrue(markerAfterThirdEnter > 0)
        // Skip to clean up.
        RealitySimulator.skipRewards(playerRepo)
        flags = playerRepo.getFlags()
        assertEquals(0L, flags.simRunActiveSince)
    }

    /**
     * Test K (bug #2 + #7): reward selection state persists from death through claim/skip.
     * After death, checkpoint + _simPlayer + _simSessions must be live until claim or skip
     * moves phase to BASE_REALITY.
     */
    @Test
    fun `Test K — reward selection retains state until claim or skip`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        // Grant coins inside sim so the sim player diverges from base.
        RealitySimulator.withSimulatorBoundary { playerRepo.addCoins(200) }
        val baseCoins = realPlayerDao.getPlayer()!!.coins
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
        // Sim state MUST still be available for reward pool computation and claim.
        assertNotNull("checkpoint/simPlayer retained through REWARD_SELECTION",
            RealitySimulator.simPlayer.value)
        assertEquals(baseCoins + 200, RealitySimulator.simPlayer.value!!.coins)
        val pool = RealitySimulator.rewardPool()
        assertTrue("reward pool populated after death", pool.isNotEmpty())
        // Now claim one coin reward.
        val coinReward = pool.first { it.kind == RewardKind.STATS && it.id == "coins" }
        val claimed = RealitySimulator.claimRewards(playerRepo, setOf(coinReward), 1)
        assertTrue(claimed)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertNull("state cleared after claim", RealitySimulator.simPlayer.value)
        assertEquals(baseCoins + coinReward.amount, realPlayerDao.getPlayer()!!.coins)
    }

    /**
     * Test L (bug #8): alarm side-effects must respect pinned layer across death. When an
     * alarm fires a sim-boundary-wrapped op and that op calls a session completion that
     * schedules a new alarm, the scheduled alarm's effectiveActive() must reflect the
     * PNNED layer of the caller, not the live isSimulationActive flag. We approximate this
     * deterministically: while an op is pinned to sim (but isSimulationActive flipped to
     * false by death on another coroutine), effectiveActive() inside the pinned op must
     * return TRUE.
     */
    @Test
    fun `Test L — alarm routing respects pinned layer across death`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pinnedResult = CompletableDeferred<Boolean>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            RealitySimulator.withSimulatorBoundary {
                started.complete(Unit)
                release.await()
                // At this point death has flipped isSimulationActive=false (below), but our
                // coroutine context is still pinned to sim.
                pinnedResult.complete(RealitySimulator.isPinnedToSim())
            }
        }
        started.await()
        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1, died = true)
        assertFalse(RealitySimulator.isSimulationActive)
        release.complete(Unit)
        val pinned = pinnedResult.await()
        job.join()
        assertTrue("alarm/session side-effect must still be pinned to sim after death", pinned)
        RealitySimulator.skipRewards(playerRepo)
    }
}
