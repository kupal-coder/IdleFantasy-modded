package com.fantasyidler.simulator

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.db.dao.PlayerDao
import com.fantasyidler.data.db.dao.SimulationPlayerDao
import com.fantasyidler.data.db.dao.SimulationSessionDao
import com.fantasyidler.data.db.dao.SkillSessionDao
import com.fantasyidler.data.model.Player
import com.fantasyidler.data.model.PlayerExport
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var gameData: GameDataRepository
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
        gameData = GameDataRepository(context, json)
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
        saveSlotRepo = saveSlotRepositoryOver(playerRepo, sessionRepo)
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
                // Bounded: a failed test must never leave cleanup waiting on a lock a parked
                // coroutine still holds (cancelling that coroutine releases the lock).
                runBlocking { withTimeoutOrNull(5_000) { RealitySimulator.abortToBaseReality(playerRepo, sessionRepo) } }
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

    /**
     * Waits for [predicate] to hold, letting other coroutines run instead of sleeping on
     * wall-clock time: the flows this harness drives are dispatched on [Dispatchers.Unconfined],
     * so a yield is enough for a pending emission to land. Bounded so a missing emission fails
     * the test instead of hanging it — nothing here sleeps, so a slow machine cannot make it
     * flaky, and there is no window in which a correct run would look like a failure.
     */
    private suspend fun waitFor(timeoutMs: Long = 5_000L, predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!predicate()) yield()
            true
        } ?: false

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

        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        val restored = withTimeoutOrNull(5_000) {
            playerRepo.playerFlow.first { it != null && it.coins == baseCoins }
        }
        assertNotNull("playerFlow kept serving the simulated player after the run ended", restored)
        assertBaseRealityUnchanged(realBefore)
    }

    @Test
    fun `an observer subscribed before the run switches to the simulated player and back`() = runBlocking {
        seedBaseReality()
        // This delegate stands in for Base Reality's row: it changes only when this test says
        // so, which makes "did the observer switch?" a question about the simulator alone.
        // (The real Room DAO emits asynchronously, so a test built on it could only assert
        // after an arbitrary wait; here every emission is delivered on this thread.)
        val base = realPlayerDao.getPlayer()!!.copy(coins = 999_999L)
        val baseFlow = MutableStateFlow<Player?>(base)
        val observed = SimulationPlayerDao(object : PlayerDao by realPlayerDao {
            override fun observePlayer(): Flow<Player?> = baseFlow
            override suspend fun getPlayer(): Player? = baseFlow.value
        })
        val seen = mutableListOf<Player?>()
        val collector = launch(Dispatchers.Unconfined) { observed.observePlayer().collect { seen += it } }

        assertTrue("no Base Reality player was ever observed", waitFor { seen.isNotEmpty() })
        assertEquals("the observer should start on Base Reality", 999_999L, seen.last()!!.coins)

        // Entering writes nothing to Base Reality's row, so only the switch itself can move an
        // already-subscribed observer over: re-subscribing (a fresh collect) would hide this.
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertEquals(Phase.IN_SIMULATION, RealitySimulator.phase.value)
        assertTrue(
            "an observer subscribed before entry stayed pointed at Base Reality",
            waitFor { seen.last()?.coins == 1_000L },
        )

        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        assertTrue(
            "an observer kept serving the simulated player after the run ended",
            waitFor { seen.last()?.coins == 999_999L },
        )
        collector.cancel()
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
        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
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

        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
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

    // ------------------------------------------------------------------ lifecycle transition races
    //
    // Each of these parks an operation between resolving its routing target and performing its
    // write, starts the competing lifecycle transition, and only then releases the write. The
    // interleaving is forced, not raced: nothing sleeps, and the assertions hold on any scheduler.

    @Test
    fun `a gameplay write cannot reach base reality while the run is exiting`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val armed = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        val insideRead = CompletableDeferred<Unit>()
        val gatedPlayerRepo = gatedPlayerRepository(armed, insideRead, readGate = gate)

        RealitySimulator.enterSimulation(gatedPlayerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)

        // Normal gameplay inside the run: it reads the simulated player and is then parked
        // before its write, still holding the operation scope the routing switch must respect.
        armed.set(true)
        val gameplay = async { gatedPlayerRepo.addCoins(500) }
        insideRead.await()

        // Exit starts while that operation is open. UNDISPATCHED runs it up to the point where
        // it can no longer progress, so the routing switch either already happened (bug) or is
        // waiting for the gameplay operation to finish (fixed).
        val exit = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.acknowledgeDeath(gatedPlayerRepo, sessionRepo, 1)
        }
        gate.complete(Unit)
        gameplay.await()
        exit.await()

        // The 500 coins belonged to the simulation: Base Reality is exactly as it was.
        assertBaseRealityUnchanged(realBefore)
        assertEquals(1_000L, realPlayerDao.getPlayer()!!.coins)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
    }

    @Test
    fun `an import cannot overlap with a simulation starting`() = runBlocking {
        seedBaseReality()                              // character A
        val characterA = playerRepo.exportSave(emptyList())
        playerRepo.resetProgression()                  // character B: a fresh character
        val characterB = playerRepo.exportSave(emptyList())
        playerRepo.importSave(characterA)
        val aCoins = json.decodeFromString<PlayerExport>(characterA).coins
        val bCoins = json.decodeFromString<PlayerExport>(characterB).coins
        assertTrue("the two characters must differ for this test to mean anything", aCoins != bCoins)

        val armed = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        val insideWrite = CompletableDeferred<Unit>()
        val gatedPlayerRepo = gatedPlayerRepository(armed, insideWrite, writeGate = gate)
        val gatedSaveSlotRepo = saveSlotRepositoryOver(gatedPlayerRepo, sessionRepo)

        // Import B, parked inside the write that replaces the character.
        armed.set(true)
        val import = async { gatedSaveSlotRepo.importFullSave(characterB) }
        insideWrite.await()
        val enter = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.enterSimulation(gatedPlayerRepo, sessionRepo, 1)
        }
        gate.complete(Unit)
        import.await()
        enter.await()

        // The import landed in Base Reality — it was not redirected into simulation state ...
        assertEquals(bCoins, realPlayerDao.getPlayer()!!.coins)
        assertNull(realInventory()["iron_ore"])
        // ... and the run that started was checkpointed from B, not from the character B replaced.
        assertTrue(RealitySimulator.isSimulationActive)
        assertEquals(
            "the run was checkpointed from the character the import replaced",
            bCoins, RealitySimulator.simPlayer.value!!.coins,
        )
        RealitySimulator.abortToBaseReality(gatedPlayerRepo, sessionRepo)
    }

    @Test
    fun `a progression reset cannot overlap with a simulation starting`() = runBlocking {
        seedBaseReality()
        val armed = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        val insideWrite = CompletableDeferred<Unit>()
        val gatedPlayerRepo = gatedPlayerRepository(armed, insideWrite, writeGate = gate)
        val gatedSaveSlotRepo = saveSlotRepositoryOver(gatedPlayerRepo, sessionRepo)

        armed.set(true)
        val reset = async { gatedSaveSlotRepo.resetProgression() }
        insideWrite.await()
        val enter = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.enterSimulation(gatedPlayerRepo, sessionRepo, 1)
        }
        gate.complete(Unit)
        assertTrue(reset.await())
        enter.await()

        // The reset landed in Base Reality ...
        assertEquals(0L, realXp()["mining"])
        assertNull(realInventory()["iron_ore"])
        // ... and the run was checkpointed from the reset character, not the one it replaced.
        assertTrue(RealitySimulator.isSimulationActive)
        assertEquals(
            "the run was checkpointed from the pre-reset character",
            0L, RealitySimulator.simPlayer.value!!.coins,
        )
        RealitySimulator.abortToBaseReality(gatedPlayerRepo, sessionRepo)
    }

    @Test
    fun `a slot switch cannot overlap with a simulation starting`() = runBlocking {
        seedBaseReality()                              // active slot 1: character A
        val characterA = playerRepo.exportSave(emptyList())
        val aCoins = json.decodeFromString<PlayerExport>(characterA).coins
        // Store A in slot 1 and move to slot 2, so there is a character to switch back to. The
        // outgoing character is given a coin value A cannot have, so "which character did the
        // run start from?" is unambiguous whatever slot 2 held before this test.
        saveSlotRepo.switchTo(2)
        val outgoingCoins = 424_242L
        realPlayerDao.upsert(realPlayerDao.getPlayer()!!.copy(coins = outgoingCoins))
        assertTrue("the outgoing character must differ from A", outgoingCoins != aCoins)

        val armed = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        val insideWrite = CompletableDeferred<Unit>()
        val gatedPlayerRepo = gatedPlayerRepository(armed, insideWrite, writeGate = gate)
        val gatedSaveSlotRepo = saveSlotRepositoryOver(gatedPlayerRepo, sessionRepo)

        // Switch back to slot 1 (A), parked inside the write that loads it.
        armed.set(true)
        val switch = async { gatedSaveSlotRepo.switchTo(1) }
        insideWrite.await()
        val enter = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.enterSimulation(gatedPlayerRepo, sessionRepo, 1)
        }
        gate.complete(Unit)
        switch.await()
        enter.await()

        // A is loaded again ...
        assertEquals("the switch did not land in base reality", aCoins, realPlayerDao.getPlayer()!!.coins)
        // ... and the run was checkpointed from A, not from the outgoing slot-2 character.
        assertTrue(RealitySimulator.isSimulationActive)
        assertEquals(
            "the run was checkpointed from the outgoing character",
            aCoins, RealitySimulator.simPlayer.value!!.coins,
        )
        RealitySimulator.abortToBaseReality(gatedPlayerRepo, sessionRepo)
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
        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
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

    // ------------------------------------------------------------------ Time Skip concurrency
    //
    // These use a session DAO that parks the first Time Skip inside its critical section until
    // the test releases it, so the interleaving is forced rather than raced: no sleeps, and the
    // assertions hold on any scheduler.

    /** A [SessionRepository] over an arbitrary [SkillSessionDao], sharing the run's global state. */
    private fun sessionRepositoryOver(sessionDao: SkillSessionDao): SessionRepository =
        SessionRepository(sessionDao, context, json, gameData, SimulationPlayerDao(realPlayerDao), playerRepo)

    /** A [QueuedSessionStarter] over [sessionRepository], sharing this run's global state. */
    private fun starterOver(sessionRepository: SessionRepository): QueuedSessionStarter {
        val questRepo = QuestRepository(db.questProgressDao(), gameData)
        val townRepo = TownRepository(gameData, playerRepo, questRepo, boostRepo)
        val mercRepo = MercenaryRepository(playerRepo, gameData)
        return QueuedSessionStarter(boostRepo, context, playerRepo, sessionRepository, townRepo, gameData, mercRepo, json)
    }

    /** A [WorkerQueuedSessionStarter] over [sessionRepository], sharing this run's global state. */
    private fun workerStarterOver(sessionRepository: SessionRepository): WorkerQueuedSessionStarter =
        WorkerQueuedSessionStarter(boostRepo, playerRepo, sessionRepository, gameData, json)

    /** A [SaveSlotRepository] over an arbitrary player/session repository pair, sharing this run's DB. */
    private fun saveSlotRepositoryOver(
        playerRepository: PlayerRepository,
        sessionRepository: SessionRepository,
    ): SaveSlotRepository {
        val backupScheduler = BackupScheduler(context, sessionRepository, globalStateRepo)
        val questRepo = QuestRepository(db.questProgressDao(), gameData)
        val townRepo = TownRepository(gameData, playerRepository, questRepo, boostRepo)
        val mercRepo = MercenaryRepository(playerRepository, gameData)
        val queuedSessionStarter = QueuedSessionStarter(
            boostRepo, context, playerRepository, sessionRepository, townRepo, gameData, mercRepo, json,
        )
        val workerStarter = WorkerQueuedSessionStarter(boostRepo, playerRepository, sessionRepository, gameData, json)
        val seasonalEventRepo = SeasonalEventRepository(playerRepository, gameData, DailyQuestRepository(gameData), context)
        val farmingRepo = FarmingRepository(
            context, db, db.farmingPatchDao(), playerRepository, gameData,
            seasonalEventRepo, globalStateRepo, json, boostRepo,
        )
        val guildRepo = GuildRepository(playerRepository, db.questProgressDao(), gameData, Provider { townRepo })
        return SaveSlotRepository(
            context, playerRepository, sessionRepository, questRepo, farmingRepo, guildRepo,
            globalStateRepo, queuedSessionStarter, workerStarter, backupScheduler,
            BuffNotificationScheduler(context), json,
        )
    }

    /**
     * A [PlayerRepository] whose player reads and writes can be parked mid-operation.
     *
     * The gated DAO sits OUTSIDE the [SimulationPlayerDao], so it intercepts a call before the
     * routing decision is taken: parking there holds an operation exactly between "the target
     * was resolved" and "the write is routed", which is the window these lifecycle races live
     * in. [readGate] parks the first read and [writeGate] the first write, each once and only
     * after [armed] is set, so setup work is never parked.
     *
     * A test using this opens an operation, starts the competing transition, and only then
     * releases the gate: the interleaving is forced, not raced, and no test sleeps.
     */
    private fun gatedPlayerRepository(
        armed: AtomicBoolean,
        inside: CompletableDeferred<Unit>,
        readGate: CompletableDeferred<Unit>? = null,
        writeGate: CompletableDeferred<Unit>? = null,
    ): PlayerRepository {
        val simDao = SimulationPlayerDao(realPlayerDao)
        val parkedRead = AtomicBoolean(false)
        val parkedWrite = AtomicBoolean(false)
        val gatedDao = object : PlayerDao by simDao {
            override suspend fun getPlayer(): Player? {
                // Resolved first: the parked operation is holding the value it is about to
                // write back, which is what makes an untimely routing switch observable.
                val routed = simDao.getPlayer()
                if (armed.get() && readGate != null && !parkedRead.getAndSet(true)) {
                    inside.complete(Unit)
                    readGate.await()
                }
                return routed
            }

            override suspend fun upsert(player: Player) {
                if (armed.get() && writeGate != null && !parkedWrite.getAndSet(true)) {
                    inside.complete(Unit)
                    writeGate.await()
                }
                simDao.upsert(player)
            }
        }
        return PlayerRepository(
            gatedDao,
            db.questProgressDao(),
            db.farmingPatchDao(),
            json,
            DailyQuestRepository(gameData),
            WeeklyQuestRepository(gameData),
            BuffNotificationScheduler(context),
            gameData,
            boostRepo,
            db,
        )
    }

    /** A session DAO wrapper that parks the first [getActiveSession] call on [gate]. */
    private fun gatedSessionDao(
        simDao: SimulationSessionDao,
        reads: AtomicInteger,
        gate: CompletableDeferred<Unit>,
        insideFirst: CompletableDeferred<Unit>,
    ): SkillSessionDao = object : SkillSessionDao by simDao {
        override suspend fun getActiveSession(): SkillSession? {
            if (reads.incrementAndGet() == 1) {
                insideFirst.complete(Unit)
                gate.await()
            }
            return simDao.getActiveSession()
        }
    }

    @Test
    fun `two concurrent time skips cannot process the same frames twice`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        val reads = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val insideFirst = CompletableDeferred<Unit>()
        val gatedRepo = sessionRepositoryOver(gatedSessionDao(SimulationSessionDao(realSessionDao), reads, gate, insideFirst))

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)

        val first = async(Dispatchers.IO) {
            RealitySimulator.timeSkip(playerRepo, gatedRepo, boostRepo, 60, 1)
        }
        withTimeout(10_000) { insideFirst.await() }   // the first skip is inside its critical section

        // A second skip must wait for the lock: it cannot read the session, let alone the cursor.
        val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.timeSkip(playerRepo, gatedRepo, boostRepo, 60, 1)
        }
        assertFalse("a second time skip must not run while the first is in flight", second.isCompleted)
        assertEquals("the second skip read the session before the first finished", 1, reads.get())

        gate.complete(Unit)
        val a = withTimeout(10_000) { first.await() }
        val b = withTimeout(10_000) { second.await() }

        assertEquals(60, a.minutes)
        assertEquals("the second skip replayed frames the first one already paid", 0, b.minutes)

        // Exactly one session's worth of rewards landed, once.
        assertEquals(1_000L + 3_000L, simXp()["mining"])
        assertEquals(5 + 120, simInventory()["iron_ore"])
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
        assertEquals(1_000L, realXp()["mining"])
    }

    @Test
    fun `an exit cannot interleave with a running time skip`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        val reads = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val insideFirst = CompletableDeferred<Unit>()
        val gatedRepo = sessionRepositoryOver(gatedSessionDao(SimulationSessionDao(realSessionDao), reads, gate, insideFirst))

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val skip = async(Dispatchers.IO) {
            RealitySimulator.timeSkip(playerRepo, gatedRepo, boostRepo, 60, 1)
        }
        withTimeout(10_000) { insideFirst.await() }

        // Exit must wait for the skip that is still writing isolated state, not run beside it.
        val exit = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        }
        assertFalse("exit must wait for the running time skip", exit.isCompleted)
        assertTrue("the run must stay active until the exit actually runs", RealitySimulator.isSimulationActive)
        assertEquals(1, reads.get())

        gate.complete(Unit)
        val result = withTimeout(10_000) { skip.await() }
        withTimeout(10_000) { exit.await() }

        // The skip finished before the switch back, and nothing was written after it.
        assertEquals(60, result.minutes)
        assertFalse(RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.simSessions.value.isEmpty())
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
        val xpReward = RealitySimulator.rewardPool().first { it.kind == RewardKind.SKILL_XP && it.id == "mining" }
        assertEquals(3_000L, xpReward.amount)

        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
        assertFalse(realSessionDao.getActiveSession()!!.completed)
        assertEquals(1_000L, realXp()["mining"])
    }

    @Test
    fun `a time skip requested after the exit applies nothing`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        assertFalse(RealitySimulator.isSimulationActive)

        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        assertEquals(0, result.minutes)
        assertEquals(1_000L, simXp()["mining"])
        assertEquals(5, simInventory()["iron_ore"])
        assertBaseRealityUnchanged(realBefore)
    }

    // ------------------------------------------------------------------ character replacement

    @Test
    fun `importing another character invalidates a pending checkpoint`() = runBlocking {
        seedBaseReality()
        val characterA = playerRepo.exportSave(emptyList())
        // Character B: a different save loaded into the same slot.
        playerRepo.resetProgression()
        val characterB = playerRepo.exportSave(emptyList())
        playerRepo.importSave(characterA)

        // Run as A, then swap the current character for B with rewards still pending.
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        val pool = RealitySimulator.rewardPool()
        assertTrue("the run should have rewards pending", pool.isNotEmpty())
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)

        saveSlotRepo.importFullSave(characterB)
        val coinsAfterImport = realPlayerDao.getPlayer()!!.coins
        val xpAfterImport = realXp()
        val inventoryAfterImport = realInventory()

        // The checkpoint belonged to A: B must not be able to claim a single reward from it.
        assertTrue("A's checkpoint survived the import", RealitySimulator.rewardPool().isEmpty())
        assertTrue(RealitySimulator.runInvalidated.value)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertTrue(RealitySimulator.claimRewards(playerRepo, pool.take(3), 1))

        // B's save is exactly as the import left it — none of A's gains crossed over.
        assertEquals(coinsAfterImport, realPlayerDao.getPlayer()!!.coins)
        assertEquals(xpAfterImport, realXp())
        assertEquals(inventoryAfterImport, realInventory())
        assertEquals(0L, json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags).simRunActiveSince)
    }

    @Test
    fun `resetting progression invalidates a pending checkpoint`() = runBlocking {
        seedBaseReality()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        val pool = RealitySimulator.rewardPool()
        assertTrue(pool.isNotEmpty())

        playerRepo.resetProgression()

        assertTrue(RealitySimulator.rewardPool().isEmpty())
        assertTrue(RealitySimulator.runInvalidated.value)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertFalse("a reset character must not own the old run", RealitySimulator.ownsRun(1))
        assertTrue(RealitySimulator.claimRewards(playerRepo, pool.take(3), 1))

        // A fresh character: none of the simulated mining XP or loot crossed over.
        assertEquals(0L, realXp()["mining"])
        assertNull(realInventory()["iron_ore"])
        assertEquals(0L, json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags).simRunActiveSince)
    }

    @Test
    fun `an import is refused while a run is active and leaves the run untouched`() = runBlocking {
        seedBaseReality()
        val characterA = playerRepo.exportSave(emptyList())
        playerRepo.resetProgression()
        val characterB = playerRepo.exportSave(emptyList())
        playerRepo.importSave(characterA)

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        assertTrue(RealitySimulator.isSimulationActive)
        val simCoinsBefore = RealitySimulator.simPlayer.value!!.coins
        val characterACoins = json.decodeFromString<PlayerExport>(characterA).coins
        val baseCoinsBefore = realPlayerDao.getPlayer()!!.coins
        val xpBefore = realXp()

        // Refused outright: the run is bound to the character being replaced, so the import
        // must not tear it down or strand it on a save it was never started from.
        val refused = try {
            saveSlotRepo.importFullSave(characterB)
            false
        } catch (_: IllegalStateException) {
            true
        }
        assertTrue("an import must be refused while a simulation is active", refused)

        // The run is exactly as the refused import found it, and still claimable/exitable.
        assertTrue(RealitySimulator.isSimulationActive)
        RealitySimulator.dismissTimeSkipResult()
        assertEquals(Phase.IN_SIMULATION, RealitySimulator.phase.value)
        assertEquals(4, RealitySimulator.simSessions.value.size)
        assertEquals(simCoinsBefore, RealitySimulator.simPlayer.value!!.coins)
        assertEquals("base reality moved during the refused import", baseCoinsBefore, realPlayerDao.getPlayer()!!.coins)
        assertEquals(xpBefore, realXp())
        assertEquals(
            "the refused import must not have loaded another character",
            characterACoins, realPlayerDao.getPlayer()!!.coins,
        )
        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        assertTrue("a refused import must not have discarded the rewards", RealitySimulator.rewardPool().isNotEmpty())
    }

    @Test
    fun `a progression reset is refused while a run is active and leaves the run untouched`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        val simCoinsBefore = RealitySimulator.simPlayer.value!!.coins
        val baseCoinsBefore = realPlayerDao.getPlayer()!!.coins

        assertFalse("a reset must be refused while a simulation is active", saveSlotRepo.resetProgression())

        // Nothing was wiped: the run keeps its sessions and the character keeps its progress.
        assertTrue(RealitySimulator.isSimulationActive)
        assertEquals(simCoinsBefore, RealitySimulator.simPlayer.value!!.coins)
        assertEquals(baseCoinsBefore, realPlayerDao.getPlayer()!!.coins)
        assertNotNull(realInventory()["iron_ore"])
        assertNotNull("the refused reset must not have wiped base sessions", realSessionDao.getActiveSession())
        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.rewardPool().isNotEmpty())
    }

    @Test
    fun `a reset outside a simulation still wipes the character`() = runBlocking {
        seedBaseReality()
        assertTrue(saveSlotRepo.resetProgression())
        assertEquals(0L, realXp()["mining"])
        assertNull(realInventory()["iron_ore"])
    }

    // ------------------------------------------------------------------ atomic reward claim

    @Test
    fun `a reward that fails to apply rolls the whole payout back and keeps the checkpoint`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)
        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)

        val pool = RealitySimulator.rewardPool()
        val itemReward = pool.first { it.kind == RewardKind.ITEM && it.id == "iron_ore" }
        val xpReward = pool.first { it.kind == RewardKind.SKILL_XP && it.id == "mining" }
        // A negative coin grant fails exactly like a real write failure would.
        val failingReward = RealitySimulator.SimReward(RewardKind.STATS, RealitySimulator.COINS_ID, -1L)

        assertFalse(RealitySimulator.claimRewards(playerRepo, listOf(itemReward, failingReward), 1))

        // Nothing was kept: the item that applied first was rolled back with the failure.
        assertEquals("a failed claim left a partial payout in base reality", 5, realInventory()["iron_ore"])
        assertEquals(1_000L, realXp()["mining"])
        assertEquals(1_000L, realPlayerDao.getPlayer()!!.coins)
        assertBaseRealityUnchanged(realBefore)

        // The run is still recoverable: the same rewards can be claimed again.
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
        assertTrue(RealitySimulator.crashedLastRun.value)
        assertEquals(pool, RealitySimulator.rewardPool())
        assertTrue(RealitySimulator.claimRewards(playerRepo, listOf(itemReward, xpReward), 1))
        assertEquals(5 + 120, realInventory()["iron_ore"])
        assertEquals(1_000L + 3_000L, realXp()["mining"])
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
    }

    // ------------------------------------------------------------------ fail-closed entry

    @Test
    fun `a session snapshot failure does not start an empty simulation`() = runBlocking {
        seedBaseReality()
        val realBefore = realPlayerDao.getPlayer()!!
        val sessionsBefore = realSessions()

        val failingDao = object : SkillSessionDao by realSessionDao {
            override suspend fun getAllSessions(): List<SkillSession> = error("session read failed")
        }
        val failingRepo = sessionRepositoryOver(failingDao)

        RealitySimulator.enterSimulation(playerRepo, failingRepo, 1)

        assertFalse("a failed session snapshot must not start a run", RealitySimulator.isSimulationActive)
        assertTrue(RealitySimulator.crashedLastRun.value)
        assertEquals(Phase.BASE_REALITY, RealitySimulator.phase.value)
        assertNull(RealitySimulator.simPlayer.value)
        assertTrue(RealitySimulator.simSessions.value.isEmpty())
        assertFalse(RealitySimulator.ownsRun(1))
        assertTrue(RealitySimulator.rewardPool().isEmpty())
        // Fail closed, and fail loudly: no crash marker, no empty parallel run.
        assertEquals(0L, json.decodeFromString<PlayerFlags>(realPlayerDao.getPlayer()!!.flags).simRunActiveSince)
        assertEquals(sessionsBefore, realSessions())
        assertBaseRealityUnchanged(realBefore)
    }

    // ------------------------------------------------------------------ Time Skip / collect parity

    @Test
    fun `a time skip pays the same item yield multipliers a normal collect does`() = runBlocking {
        val base = seedBaseReality()
        realPlayerDao.upsert(
            realPlayerDao.getPlayer()!!.copy(
                flags = encodeFlags(PlayerFlags(prestigeNodes = mapOf("mining" to listOf("mining_yield_1")))),
            )
        )
        sessionRepo.deleteAllSessions()
        seedSession("yield_run", "mining", "iron_ore", miningFrames(), base)

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 60, 1)

        // +5% prestige yield on 120 ore, exactly like collectGenericSkillSession.
        assertEquals(126, result.items["iron_ore"])
        assertEquals(5 + 126, simInventory()["iron_ore"])
        assertEquals(1_000L, realXp()["mining"])
        assertEquals(5, realInventory()["iron_ore"])
    }

    @Test
    fun `a boss time skip pays the daily coin soft cap after the full-coin kills`() = runBlocking {
        val base = seedBaseReality()

        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        var coins = 0L
        repeat(4) { index ->
            sessionRepo.insertSession(
                SkillSession(
                    sessionId   = "boss_$index",
                    skillName   = "boss",
                    activityKey = "sea_serpent",
                    startedAt   = base + 60_000L * (index + 1),
                    endsAt      = base + 60_000L * (index + 1) + 1_800_000L,
                    frames      = encodeFrames(bossFrames(win = true)),
                )
            )
            val result = RealitySimulator.timeSkip(playerRepo, sessionRepo, boostRepo, 30, 1)
            assertFalse(result.died)
            coins += result.coins
        }

        // Three full-coin kills, then the soft cap (0.25) on the fourth.
        assertEquals(500L + 500L + 500L + 125L, coins)
        assertEquals(1_000L + 1_625L, playerRepo.getOrCreatePlayer().coins)
        assertEquals(1_000L, realPlayerDao.getPlayer()!!.coins)
    }

    // ------------------------------------------------------------------ Layer 2 death boundary
    //
    // Session operations, worker sessions, the watchdog/recovery paths, and Simulator
    // entry all share one lifecycle boundary (PlayerRepository.playerMutex) that simulated
    // death takes on the way out. An operation holds it for its whole read -> suspend ->
    // write body, so death waits for it, the operation finishes against Layer 2 state,
    // and only then is the layer torn down. These tests force the interleavings with
    // gates — no Thread.sleep anywhere.

    @Test
    fun `a session completion cannot be split by death`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)
        val sessionsBefore = realSessions()

        val gate = CompletableDeferred<Unit>()
        val insideWrite = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val realSim = SimulationSessionDao(realSessionDao)
        val gatedDao = object : SkillSessionDao by realSim {
            override suspend fun markCompleted(sessionId: String) {
                insideWrite.complete(Unit)
                gate.await()
                events += "write"
                realSim.markCompleted(sessionId)
            }
        }
        val opRepo = sessionRepositoryOver(gatedDao)

        val op = async { opRepo.markCompleted("real_active") }
        withTimeout(5_000) { insideWrite.await() }

        val death = async(start = CoroutineStart.UNDISPATCHED) {
            events += "death:start"
            RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
            events += "death:end"
        }
        assertFalse("death must wait for the in-flight session op", death.isCompleted)

        gate.complete(Unit)
        op.await()

        assertEquals(listOf("death:start", "write"), events.toList().take(2))
        assertFalse("the completion must not leak into Layer 1", realSessionDao.getSession("real_active")!!.completed)
        assertEquals(sessionsBefore, realSessions())

        death.await()
        assertEquals(listOf("death:start", "write", "death:end"), events.toList())
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
        assertFalse(RealitySimulator.isSimulationActive)
        assertFalse("nothing must leak into Layer 1", realSessionDao.getSession("real_active")!!.completed)
        assertEquals(sessionsBefore, realSessions())
    }

    @Test
    fun `a worker session completion cannot be split by death`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)
        val sessionsBefore = realSessions()

        val gate = CompletableDeferred<Unit>()
        val insideWrite = CompletableDeferred<Unit>()
        val realSim = SimulationSessionDao(realSessionDao)
        val gatedDao = object : SkillSessionDao by realSim {
            override suspend fun markCompleted(sessionId: String) {
                insideWrite.complete(Unit)
                gate.await()
                realSim.markCompleted(sessionId)
            }
        }
        val opRepo = sessionRepositoryOver(gatedDao)

        val op = async { opRepo.markCompleted("worker_active") }
        withTimeout(5_000) { insideWrite.await() }

        val death = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        }
        assertFalse("death must wait for the in-flight worker session op", death.isCompleted)

        gate.complete(Unit)
        op.await()
        death.await()

        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
        assertFalse(RealitySimulator.isSimulationActive)
        assertFalse("the worker completion must not leak into Layer 1", realSessionDao.getSession("worker_active")!!.completed)
        assertEquals(sessionsBefore, realSessions())
    }

    @Test
    fun `a watchdog completion cannot be split by death`() = runBlocking {
        seedPlayer()
        val base = System.currentTimeMillis()
        seedSession("overdue", "mining", "iron_ore", miningFrames(), base - 120_000L, durationMs = 60_000L)
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)
        val sessionsBefore = realSessions()

        val gate = CompletableDeferred<Unit>()
        val insideRead = CompletableDeferred<Unit>()
        val realSim = SimulationSessionDao(realSessionDao)
        val gatedDao = object : SkillSessionDao by realSim {
            override suspend fun getActiveSession(): SkillSession? {
                insideRead.complete(Unit)
                gate.await()
                return realSim.getActiveSession()
            }
        }
        val watchdogRepo = sessionRepositoryOver(gatedDao)
        val watchdog = async { watchdogRepo.completeOverdueSessions(starterOver(sessionRepo), workerStarterOver(sessionRepo)) }
        withTimeout(5_000) { insideRead.await() }

        val death = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        }
        assertFalse("death must wait for the in-flight watchdog op", death.isCompleted)

        gate.complete(Unit)
        watchdog.await()
        death.await()

        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
        assertFalse(RealitySimulator.isSimulationActive)
        assertFalse("the watchdog must not complete a Layer 1 session", realSessionDao.getSession("overdue")!!.completed)
        assertEquals(sessionsBefore, realSessions())
    }

    @Test
    fun `a recovery completion cannot be split by death`() = runBlocking {
        seedPlayer()
        val base = System.currentTimeMillis()
        seedSession("overdue", "mining", "iron_ore", miningFrames(), base - 120_000L, durationMs = 60_000L)
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)
        val sessionsBefore = realSessions()

        val gate = CompletableDeferred<Unit>()
        val insideRead = CompletableDeferred<Unit>()
        val realSim = SimulationSessionDao(realSessionDao)
        val gatedDao = object : SkillSessionDao by realSim {
            override suspend fun getActiveSession(): SkillSession? {
                insideRead.complete(Unit)
                gate.await()
                return realSim.getActiveSession()
            }
        }
        val recoveryRepo = sessionRepositoryOver(gatedDao)
        val recovery = async { recoveryRepo.recoverActiveSession(starterOver(sessionRepo)) }
        withTimeout(5_000) { insideRead.await() }

        val death = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
        }
        assertFalse("death must wait for the in-flight recovery op", death.isCompleted)

        gate.complete(Unit)
        recovery.await()
        death.await()

        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
        assertFalse(RealitySimulator.isSimulationActive)
        assertFalse("recovery must not complete a Layer 1 session", realSessionDao.getSession("overdue")!!.completed)
        assertEquals(sessionsBefore, realSessions())
    }

    @Test
    fun `there is no manual exit path out of layer 2`() = runBlocking {
        seedBaseReality()
        RealitySimulator.enterSimulation(playerRepo, sessionRepo, 1)
        assertTrue(RealitySimulator.isSimulationActive)
        assertEquals(Phase.IN_SIMULATION, RealitySimulator.phase.value)

        RealitySimulator.exitSimulation(playerRepo, sessionRepo, 1)

        assertTrue("a non-death exit must leave the run active", RealitySimulator.isSimulationActive)
        assertEquals(Phase.IN_SIMULATION, RealitySimulator.phase.value)

        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)

        assertFalse(RealitySimulator.isSimulationActive)
        assertEquals(Phase.REWARD_SELECTION, RealitySimulator.phase.value)
    }

    @Test
    fun `simulator entry takes one consistent player snapshot`() = runBlocking {
        seedBaseReality()
        val armed = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        val insideRead = CompletableDeferred<Unit>()
        val gatedPlayerRepo = gatedPlayerRepository(armed, insideRead, readGate = gate)

        armed.set(true)
        val enter = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.enterSimulation(gatedPlayerRepo, sessionRepo, 1)
        }
        insideRead.await()

        // A normal Layer 1 write is blocked until the entry snapshot — and the Layer
        // flip that follows it — complete, so it cannot split the two.
        val write = async(start = CoroutineStart.UNDISPATCHED) { gatedPlayerRepo.addCoins(500) }
        assertFalse("a gameplay write must wait for the simulator entry snapshot", write.isCompleted)

        gate.complete(Unit)
        enter.await()
        write.await()

        // The run started from one pre-write Base Reality state, and the concurrent
        // write landed entirely in Layer 2: nothing touched the real save.
        assertTrue(RealitySimulator.isSimulationActive)
        assertEquals(1_000L, realPlayerDao.getPlayer()!!.coins)
        assertEquals("the write must apply to the run, not to Base Reality", 1_500L, RealitySimulator.simPlayer.value!!.coins)

        RealitySimulator.acknowledgeDeath(gatedPlayerRepo, sessionRepo, 1)
    }

    @Test
    fun `simulator entry takes one consistent player and session snapshot`() = runBlocking {
        seedBaseReality()
        val gate = CompletableDeferred<Unit>()
        val insideRead = CompletableDeferred<Unit>()
        val realSim = SimulationSessionDao(realSessionDao)
        val gatedDao = object : SkillSessionDao by realSim {
            override suspend fun getAllSessions(): List<SkillSession> {
                insideRead.complete(Unit)
                gate.await()
                return realSim.getAllSessions()
            }
        }
        val entryRepo = sessionRepositoryOver(gatedDao)

        val enter = async(start = CoroutineStart.UNDISPATCHED) {
            RealitySimulator.enterSimulation(playerRepo, entryRepo, 1)
        }
        insideRead.await()

        // The concurrent Layer 1 write touches sessions and player flags together:
        // it cannot commit while the entry snapshot is open.
        val write = async(start = CoroutineStart.UNDISPATCHED) {
            sessionRepo.startSession("mining", "iron_ore", encodeFrames(miningFrames(10)), durationMs = 3_600_000L, skillDisplayName = "Mining")
        }
        assertFalse("a gameplay write must wait for the simulator entry snapshot", write.isCompleted)

        gate.complete(Unit)
        enter.await()
        val started = write.await()

        // At entry time neither half of the concurrent write was visible in the run...
        assertTrue(RealitySimulator.isSimulationActive)
        // ...the whole write then landed in Layer 2 — not in Base Reality, and not split.
        assertNull("the write must not create a Layer 1 session", realSessionDao.getSession(started.sessionId))
        assertTrue("the run and its flags must move to Layer 2 together",
            RealitySimulator.simSessions.value.any { it.sessionId == started.sessionId } ==
                stampFor(started.sessionId))

        RealitySimulator.acknowledgeDeath(playerRepo, sessionRepo, 1)
    }

    /** True when the isolated player flags carry the heirloom mirror stamp for [sessionId]. */
    private fun stampFor(sessionId: String): Boolean = runCatching {
        json.decodeFromString<PlayerFlags>(RealitySimulator.simPlayer.value!!.flags)
            .heirloomMirrorTargets.containsKey(sessionId)
    }.getOrDefault(false)
}
