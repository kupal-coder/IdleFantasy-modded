package com.fantasyidler.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.fantasyidler.data.db.dao.PlayerDao
import com.fantasyidler.data.db.dao.SkillSessionDao
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.receiver.SessionAlarmReceiver
import com.fantasyidler.simulator.CombatSimulator
import com.fantasyidler.simulator.RealitySimulator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SkillSessionDao,
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val gameData: GameDataRepository,
    private val playerDao: PlayerDao,
    private val playerRepo: PlayerRepository,
) {
    val activeSessionFlow: Flow<SkillSession?> = sessionDao.observeActiveSession()
    val completedCountFlow: Flow<Int> = sessionDao.observeCompletedCount()
    val workerCompletedCountFlow: Flow<Int> = sessionDao.observeWorkerCompletedCount()

    fun workerCompletedCountFlow(slot: Int): Flow<Int> =
        sessionDao.observeWorkerCompletedCount(slot)

    fun activeWorkerSessionFlow(slot: Int): Flow<SkillSession?> =
        sessionDao.observeActiveWorkerSession(slot)

    /**
     * Every session operation lives inside [playerRepo.playerMutex] (unless the caller
     * already holds it), the same boundary
     * [com.fantasyidler.simulator.RealitySimulator] takes for every Layer 1/Layer 2
     * transition. An operation that spans suspended routing reads therefore cannot be
     * split by an enter/exit/death, and a death or enter waits until it finishes.
     */
    private suspend fun <T> sessionOp(playerMutexHeld: Boolean, block: suspend () -> T): T =
        if (playerMutexHeld) block() else playerRepo.playerMutex.withLock { block() }

    /** Runs [block] while holding the same lifecycle boundary [sessionOp] uses. */
    suspend fun <T> withSessionLock(block: suspend () -> T): T =
        playerRepo.withLock(block)

    suspend fun getActiveSession(playerMutexHeld: Boolean = false): SkillSession? =
        sessionOp(playerMutexHeld) { sessionDao.getActiveSession() }

    suspend fun getActiveWorkerSession(slot: Int, playerMutexHeld: Boolean = false): SkillSession? =
        sessionOp(playerMutexHeld) { sessionDao.getActiveWorkerSession(slot) }

    suspend fun getAllCompletedWorkerSessions(slot: Int, playerMutexHeld: Boolean = false): List<SkillSession> =
        sessionOp(playerMutexHeld) { sessionDao.getAllCompletedWorkerSessions(slot) }

    suspend fun deleteAllWorkerSessions(slot: Int, playerMutexHeld: Boolean = false) =
        sessionOp(playerMutexHeld) { sessionDao.deleteAllWorkerSessions(slot) }
    suspend fun deleteAllWorkerSessions(playerMutexHeld: Boolean = false) =
        sessionOp(playerMutexHeld) { sessionDao.deleteAllWorkerSessions() }

    /**
     * Persist a new session and schedule an AlarmManager alarm for completion.
     *
     * @param skillName        canonical skill key, e.g. "mining"
     * @param activityKey      sub-activity key, e.g. "iron_ore" or "dark_cave"
     * @param frames           pre-serialised JSON of List<SessionFrame>
     * @param durationMs       wall-clock duration (already reduced by agility bonus)
     * @param skillDisplayName localised skill name forwarded to the notification
     */
    suspend fun startSession(
        skillName: String,
        activityKey: String,
        frames: String,
        durationMs: Long = SESSION_DURATION_MS,
        skillDisplayName: String,
        alarmOffsetMs: Long? = null,
        insertAsCompleted: Boolean = false,
        backdateMs: Long = 0L,
        catalystKey: String? = null,
        catalystQty: Int = 0,
        levelAtStart: Int = 0,
        weaponSlot: String? = null,
        playerMutexHeld: Boolean = false,
        isElderSession: Boolean = false,
    ): SkillSession {
        val now = System.currentTimeMillis()
        val startedAt = now - backdateMs
        val session = SkillSession(
            sessionId    = UUID.randomUUID().toString(),
            skillName    = skillName,
            startedAt    = startedAt,
            endsAt       = startedAt + durationMs,
            frames       = frames,
            activityKey  = activityKey,
            completed    = insertAsCompleted,
            catalystKey  = catalystKey,
            catalystQty  = catalystQty,
            levelAtStart = levelAtStart,
            startElapsedMs = if (insertAsCompleted) null else SystemClock.elapsedRealtime() - backdateMs,
            startBootCount = if (insertAsCompleted) null else currentBootCount(),
            isElderSession = isElderSession,
        )
        return sessionOp(playerMutexHeld) {
            sessionDao.insert(session)
            playerRepo.stampHeirloomMirrorTargetsUnlocked(session.sessionId, weaponSlot)
            if (!insertAsCompleted) {
                val alarmAt = if (alarmOffsetMs != null) startedAt + alarmOffsetMs else session.endsAt
                scheduleAlarm(session.sessionId, alarmAt, skillDisplayName)
            }
            session
        }
    }

    suspend fun startWorkerSession(
        workerSlot: Int,
        skillName: String,
        activityKey: String,
        frames: String,
        durationMs: Long,
        skillDisplayName: String,
        efficiencyMultiplier: Float,
        levelAtStart: Int = 0,
        weaponSlot: String? = null,
        playerMutexHeld: Boolean = false,
    ): SkillSession {
        val now = System.currentTimeMillis()
        val session = SkillSession(
            sessionId            = UUID.randomUUID().toString(),
            skillName            = skillName,
            startedAt            = now,
            endsAt               = now + durationMs,
            frames               = frames,
            activityKey          = activityKey,
            isWorkerSession      = true,
            efficiencyMultiplier = efficiencyMultiplier,
            workerSlot           = workerSlot,
            levelAtStart         = levelAtStart,
            startElapsedMs       = SystemClock.elapsedRealtime(),
            startBootCount       = currentBootCount(),
        )
        return sessionOp(playerMutexHeld) {
            sessionDao.insert(session)
            playerRepo.stampHeirloomMirrorTargetsUnlocked(session.sessionId, weaponSlot)
            scheduleAlarm(session.sessionId, session.endsAt, skillDisplayName)
            session
        }
    }

    suspend fun markCompleted(sessionId: String, playerMutexHeld: Boolean = false) {
        sessionOp(playerMutexHeld) {
            cancelAlarm(sessionId)
            sessionDao.markCompleted(sessionId)
        }
    }

    /**
     * Wall-clock moment a boss fight is actually over (boss or player dead), derived
     * from the pre-simulated frames. endsAt is only the cosmetic full-duration end.
     */
    fun bossFightEndMs(session: SkillSession): Long = try {
        val frames: List<SessionFrame> = json.decodeFromString(session.frames)
        val durMin     = (gameData.bosses[session.activityKey]?.durationMinutes ?: 60).coerceAtLeast(1)
        val perFrameMs = ((session.endsAt - session.startedAt) / durMin).coerceAtLeast(1L)
        val offset     = CombatSimulator.bossEndAlarmOffsetMs(frames, durMin, perFrameMs)
        if (offset != null) minOf(session.endsAt, session.startedAt + offset) else session.endsAt
    } catch (_: Exception) { session.endsAt }

    /**
     * Heirloom item keys already rolled inside any stored session's frames (active or
     * completed-but-uncollected, player or worker). Boss simulations must block these
     * alongside owned heirlooms, otherwise two queued sessions can each roll the same
     * unique before the first is collected (issue #1618).
     */
    suspend fun pendingHeirloomKeys(playerMutexHeld: Boolean = false): Set<String> =
        sessionOp(playerMutexHeld) {
            val heirloomKeys = gameData.equipment.filterValues { it.heirloomSkill != null }.keys
            if (heirloomKeys.isEmpty()) return@sessionOp emptySet()
            sessionDao.getAllSessions().flatMapTo(mutableSetOf()) { session ->
                try {
                    val frames: List<SessionFrame> = json.decodeFromString(session.frames)
                    frames.flatMap { frame -> frame.items.keys.filter { it in heirloomKeys } }
                } catch (_: Exception) { emptyList() }
            }
        }

    /**
     * True when [session]'s completion time is consistent with its monotonic anchor.
     * Enforced for ironman characters only — normal characters always pass; anchors are
     * still stamped for everyone so enforcement decisions stay possible later. Fails open
     * when the anchor or boot count is missing, or when the device rebooted since the
     * session started (elapsedRealtime restarts at boot, making the anchor meaningless).
     */
    suspend fun hasTrustedClock(session: SkillSession, playerMutexHeld: Boolean = false): Boolean =
        sessionOp(playerMutexHeld) {
            val anchor = session.startElapsedMs ?: return@sessionOp true
            if (!isIronman()) return@sessionOp true
            val bootCount = currentBootCount()
            if (session.startBootCount == null || bootCount == null || bootCount != session.startBootCount) return@sessionOp true
            val elapsedSinceStart = SystemClock.elapsedRealtime() - anchor
            if (elapsedSinceStart < 0L) return@sessionOp true
            System.currentTimeMillis() - session.startedAt <= elapsedSinceStart + CLOCK_SKEW_TOLERANCE_MS
        }

    private suspend fun isIronman(): Boolean = try {
        playerDao.getPlayer()?.let { json.decodeFromString<PlayerFlags>(it.flags).ironman } ?: false
    } catch (_: Exception) { false }

    internal fun currentBootCount(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) { null }

    private val watchdogMutex = Mutex()

    /**
     * In-app watchdog: completes any overdue session (main and workers) without
     * depending on AlarmManager delivery, which Doze can defer for hours. Boss
     * sessions end at their simulated death moment; everything else at endsAt.
     * Overdue time is fed to the queue as offline catch-up, same as recovery.
     * Safe to call repeatedly from any ViewModel ticker.
     */
    suspend fun completeOverdueSessions(
        starter: QueuedSessionStarter,
        workerStarter: WorkerQueuedSessionStarter? = null,
        playerMutexHeld: Boolean = false,
    ): Unit = watchdogMutex.withLock {
        sessionOp(playerMutexHeld) {
            val now = System.currentTimeMillis()
            val session = getActiveSession(playerMutexHeld = true)
            if (session != null && !session.completed) {
                val endMs = if (session.skillName == "boss") bossFightEndMs(session) else session.endsAt
                if (now >= endMs && hasTrustedClock(session, playerMutexHeld = true)) {
                    markCompleted(session.sessionId, playerMutexHeld = true)
                    var catchUpMs = now - endMs
                    while (catchUpMs > 0) {
                        val used = try { starter.insertNextQueuedAsOffline(catchUpMs, playerMutexHeld = true) } catch (_: Exception) { 0L }
                        if (used == 0L) break
                        catchUpMs -= used
                    }
                    try { starter.startNextQueued(backdateMs = catchUpMs.coerceAtLeast(0L), playerMutexHeld = true) } catch (_: Exception) {}
                }
            } else if (session != null && session.completed) {
                // The session already finished but the next queued item never started (e.g. the
                // process died between the alarm's markCompleted and its startNextQueued, which
                // aggressive battery savers do). Keep retrying every tick, back-dating by the
                // time lost since the session ended — an unbackdated start here permanently
                // pushed the queue's schedule late (issue #1739).
                if (hasTrustedClock(session, playerMutexHeld = true)) {
                    val endMs = if (session.skillName == "boss") bossFightEndMs(session) else session.endsAt
                    var catchUpMs = maxOf(0L, now - endMs)
                    while (catchUpMs > 0) {
                        val used = try { starter.insertNextQueuedAsOffline(catchUpMs, playerMutexHeld = true) } catch (_: Exception) { 0L }
                        if (used == 0L) break
                        catchUpMs -= used
                    }
                    try { starter.startNextQueued(backdateMs = catchUpMs, playerMutexHeld = true) } catch (_: Exception) {}
                } else {
                    try { starter.startNextQueued(playerMutexHeld = true) } catch (_: Exception) {}
                }
            }
            if (workerStarter != null) {
                for (slot in 1..2) {
                    val ws = getActiveWorkerSession(slot, playerMutexHeld = true)
                    if (ws != null && !ws.completed && now >= ws.endsAt && hasTrustedClock(ws, playerMutexHeld = true)) {
                        markCompleted(ws.sessionId, playerMutexHeld = true)
                        try { workerStarter.startNextQueued(slot, playerMutexHeld = true) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    suspend fun markAllExpiredWorkerSessions(playerMutexHeld: Boolean = false) {
        sessionOp(playerMutexHeld) {
            sessionDao.markAllExpiredWorkerSessions(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                CLOCK_SKEW_TOLERANCE_MS,
                enforceClock = isIronman(),
                // -1 never matches a stored boot count, so an unreadable setting fails open.
                bootCount = currentBootCount() ?: -1,
            )
        }
    }

    /**
     * Called on boot or app open to recover from a lost alarm.
     * - If the active session has already passed its end time, marks it complete and
     *   advances the queue via [starter].
     * - If it's still running, reschedules the alarm so it fires at the correct time.
     */
    suspend fun recoverActiveSession(starter: QueuedSessionStarter, playerMutexHeld: Boolean = false) {
        sessionOp(playerMutexHeld) {
            val session = try { getActiveSession(playerMutexHeld = true) } catch (_: Exception) { null } ?: run {
                starter.startNextQueued(playerMutexHeld = true)
                return@sessionOp
            }
            if (session.completed) {
                if (!hasTrustedClock(session, playerMutexHeld = true)) return@sessionOp
                val endMs = if (session.skillName == "boss") bossFightEndMs(session) else session.endsAt
                var catchUpMs = maxOf(0L, System.currentTimeMillis() - endMs)
                while (catchUpMs > 0) {
                    val used = try { starter.insertNextQueuedAsOffline(catchUpMs, playerMutexHeld = true) } catch (_: Exception) { 0L }
                    if (used == 0L) break
                    catchUpMs -= used
                }
                try { starter.startNextQueued(backdateMs = catchUpMs, playerMutexHeld = true) } catch (_: Exception) { markCompleted(session.sessionId, playerMutexHeld = true) }
                return@sessionOp
            }
            // Boss sessions: endsAt is cosmetic (full duration). The session really ends
            // at bossFightEndMs — complete or re-arm the alarm based on that moment,
            // never on endsAt.
            if (session.skillName == "boss") {
                val fightEndMs = bossFightEndMs(session)
                if (System.currentTimeMillis() >= fightEndMs && hasTrustedClock(session, playerMutexHeld = true)) {
                    markCompleted(session.sessionId, playerMutexHeld = true)
                    // Fast-forward the offline window like the generic path below, or a repeat
                    // chain (x100 boss runs) advances only one fight per app launch when the OS
                    // suppresses alarms for a killed app (Discord report, Aug 2026).
                    var catchUpMs = System.currentTimeMillis() - fightEndMs
                    while (catchUpMs > 0) {
                        val used = try { starter.insertNextQueuedAsOffline(catchUpMs, playerMutexHeld = true) } catch (_: Exception) { 0L }
                        if (used == 0L) break
                        catchUpMs -= used
                    }
                    try { starter.startNextQueued(backdateMs = catchUpMs, playerMutexHeld = true) } catch (_: Exception) { }
                } else {
                    scheduleAlarm(session.sessionId, fightEndMs, session.skillName)
                }
                return@sessionOp
            }
            val now = System.currentTimeMillis()
            try {
                if (now >= session.endsAt && hasTrustedClock(session, playerMutexHeld = true)) {
                    markCompleted(session.sessionId, playerMutexHeld = true)
                    var catchUpMs = now - session.endsAt
                    while (catchUpMs > 0) {
                        val used = starter.insertNextQueuedAsOffline(catchUpMs, playerMutexHeld = true)
                        if (used == 0L) break
                        catchUpMs -= used
                    }
                    starter.startNextQueued(backdateMs = catchUpMs, playerMutexHeld = true)
                } else {
                    scheduleAlarm(session.sessionId, session.endsAt, session.skillName)
                }
            } catch (_: Exception) {
                if (hasTrustedClock(session, playerMutexHeld = true)) markCompleted(session.sessionId, playerMutexHeld = true)
            }
        }
    }

    suspend fun recoverActiveWorkerSession(slot: Int, workerStarter: WorkerQueuedSessionStarter, playerMutexHeld: Boolean = false) {
        sessionOp(playerMutexHeld) {
            val session = try { getActiveWorkerSession(slot, playerMutexHeld = true) } catch (_: Exception) { null } ?: run {
                workerStarter.startNextQueued(slot, playerMutexHeld = true)
                return@sessionOp
            }
            if (session.completed) {
                workerStarter.startNextQueued(slot, playerMutexHeld = true)
                return@sessionOp
            }
            val now = System.currentTimeMillis()
            try {
                if (now >= session.endsAt && hasTrustedClock(session, playerMutexHeld = true)) {
                    markCompleted(session.sessionId, playerMutexHeld = true)
                    workerStarter.startNextQueued(slot, playerMutexHeld = true)
                } else {
                    scheduleAlarm(session.sessionId, session.endsAt, session.skillName)
                }
            } catch (_: Exception) {
                if (hasTrustedClock(session, playerMutexHeld = true)) markCompleted(session.sessionId, playerMutexHeld = true)
            }
        }
    }

    suspend fun getSession(sessionId: String, playerMutexHeld: Boolean = false): SkillSession? =
        sessionOp(playerMutexHeld) { sessionDao.getSession(sessionId) }

    /** Every stored session: active and completed, player and both worker slots. */
    suspend fun getAllSessions(playerMutexHeld: Boolean = false): List<SkillSession> =
        sessionOp(playerMutexHeld) { sessionDao.getAllSessions() }

    suspend fun abandonSession(sessionId: String, playerMutexHeld: Boolean = false) =
        sessionOp(playerMutexHeld) {
            cancelAlarm(sessionId)
            sessionDao.delete(sessionId)
            pruneMirrorStamps()
        }

    /** Delete a completed session after rewards have been applied. */
    suspend fun deleteSession(sessionId: String, playerMutexHeld: Boolean = false) =
        sessionOp(playerMutexHeld) {
            cancelAlarm(sessionId)
            sessionDao.delete(sessionId)
            pruneMirrorStamps()
        }

    suspend fun deleteAllSessions(playerMutexHeld: Boolean = false) =
        sessionOp(playerMutexHeld) {
            sessionDao.deleteAll()
            pruneMirrorStamps()
        }

    private suspend fun pruneMirrorStamps() =
        playerRepo.pruneHeirloomMirrorTargetsUnlocked(sessionDao.getAllSessions().mapTo(mutableSetOf()) { it.sessionId })

    suspend fun insertSession(session: SkillSession, playerMutexHeld: Boolean = false) =
        sessionOp(playerMutexHeld) { sessionDao.insert(session) }

    suspend fun getRecentCompleted(limit: Int = 20, playerMutexHeld: Boolean = false): List<SkillSession> =
        sessionOp(playerMutexHeld) { sessionDao.getRecentCompleted(limit) }

    suspend fun getAllCompletedSessions(playerMutexHeld: Boolean = false): List<SkillSession> =
        sessionOp(playerMutexHeld) { sessionDao.getAllCompletedSessions() }

    suspend fun getOldestCompletedSession(playerMutexHeld: Boolean = false): SkillSession? =
        sessionOp(playerMutexHeld) { sessionDao.getOldestCompletedSession() }

    // ------------------------------------------------------------------

    private fun alarmIntent(sessionId: String, skillDisplayName: String): PendingIntent {
        val intent = Intent(context, SessionAlarmReceiver::class.java).apply {
            putExtra(SessionAlarmReceiver.KEY_SESSION_ID, sessionId)
            putExtra(SessionAlarmReceiver.KEY_SKILL_DISPLAY_NAME, skillDisplayName)
        }
        return PendingIntent.getBroadcast(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, SessionAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleAlarm(sessionId: String, endsAt: Long, skillDisplayName: String) {
        // Sessions started inside a Simulator run live only in the isolated session table, so
        // arming a real alarm for one would fire against a session Base Reality never had. The
        // in-app watchdog and Time Skip complete isolated sessions instead.
        if (RealitySimulator.isSimulationActive) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(sessionId, skillDisplayName)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pi)
        }
    }

    internal fun cancelAlarm(sessionId: String) {
        // An isolated session can share a real session's id (the run starts from a copy of the
        // table), so completing/abandoning/deleting it inside a simulation must not cancel Base
        // Reality's alarm: that real session still needs it, and cancelling it would defer its
        // completion to the watchdog.
        if (RealitySimulator.isSimulationActive) return
        try {
            val am      = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = cancelIntent(sessionId)
            am.cancel(pending)
            pending.cancel()
        } catch (_: Exception) {}
    }

    companion object {
        const val SESSION_DURATION_MS = 60L * 60L * 1_000L  // 1 hour
        const val CLOCK_SKEW_TOLERANCE_MS = 120_000L
    }
}
