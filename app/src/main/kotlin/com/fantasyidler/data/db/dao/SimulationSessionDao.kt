package com.fantasyidler.data.db.dao

import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.simulator.RealitySimulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * [SkillSessionDao] decorator that routes every session read/write to the isolated simulation
 * session table ([RealitySimulator.simSessions]) while [RealitySimulator.isSimulationActive] is
 * true, so a simulation can never create, complete, overwrite, collect, abandon, or delete a
 * Base Reality session. Outside a simulation every call is forwarded to the real DAO unchanged.
 *
 * Isolation covers every session variant — the player session (`worker_slot = 0`), combat and
 * boss sessions, and both worker slots — because the isolated table is a full copy of the real
 * one, seeded when the Simulator is entered and dropped when the run ends.
 *
 * The in-memory queries below mirror the SQL of [SkillSessionDao] one for one (same filters,
 * same ordering, same limits) so the game behaves identically inside a simulation.
 */
class SimulationSessionDao(
    private val delegate: SkillSessionDao,
) : SkillSessionDao by delegate {

    // ── Isolation plumbing ──────────────────────────────────────────────────

    /**
     * Runs [sim] against the isolated table when a simulation is active, otherwise [real].
     * Never falls through on an empty result: an isolated table with no matching session means
     * the simulation genuinely has none, not that the real table should be consulted.
     */
    private suspend fun <T> isolated(sim: (List<SkillSession>) -> T, real: suspend () -> T): T {
        val sessions = RealitySimulator.simSessionsOrNull()
        return if (sessions != null) sim(sessions) else real()
    }

    /**
     * Same switch for observers. The real DAO's flow is merged with the isolated table so a
     * simulation starting or ending mid-collection immediately flips which state observers
     * receive. While a run is active the value always comes from the isolated table; otherwise
     * the real DAO's own emission is passed straight through (no extra query), and the real
     * table is only re-read when the isolated store changed while no run was active.
     */
    private fun <T> isolatedFlow(
        source: Flow<T>,
        sim: (List<SkillSession>) -> T,
        real: suspend () -> T,
    ): Flow<T> = merge(
        source.map { value ->
            val sessions = RealitySimulator.simSessionsOrNull()
            if (sessions != null) sim(sessions) else value
        },
        RealitySimulator.simSessions.map {
            val sessions = RealitySimulator.simSessionsOrNull()
            if (sessions != null) sim(sessions) else real()
        },
    )

    /** Mirrors `WHERE user_id = 1 AND worker_slot = 0 ORDER BY started_at DESC LIMIT 1`. */
    private fun List<SkillSession>.activePlayerSession(): SkillSession? =
        filter { it.playerId == 1L && it.workerSlot == 0 }.maxByOrNull { it.startedAt }

    /** Mirrors `WHERE user_id = 1 AND worker_slot = :slot ORDER BY started_at DESC LIMIT 1`. */
    private fun List<SkillSession>.activeWorkerSession(slot: Int): SkillSession? =
        filter { it.playerId == 1L && it.workerSlot == slot }.maxByOrNull { it.startedAt }

    // ── Player sessions (worker_slot = 0) ───────────────────────────────────

    override suspend fun getActiveSession(): SkillSession? =
        isolated({ it.activePlayerSession() }, { delegate.getActiveSession() })

    override fun observeActiveSession(): Flow<SkillSession?> =
        isolatedFlow(delegate.observeActiveSession(), { it.activePlayerSession() }, { delegate.getActiveSession() })

    override suspend fun getRecentCompleted(limit: Int): List<SkillSession> =
        isolated(
            { sessions ->
                sessions.filter { it.completed && it.playerId == 1L && it.workerSlot == 0 }
                    .sortedByDescending { it.startedAt }.take(limit)
            },
            { delegate.getRecentCompleted(limit) },
        )

    override suspend fun getAllCompletedSessions(): List<SkillSession> =
        isolated(
            { sessions ->
                sessions.filter { it.completed && it.playerId == 1L && it.workerSlot == 0 }
                    .sortedBy { it.startedAt }
            },
            { delegate.getAllCompletedSessions() },
        )

    override suspend fun getOldestCompletedSession(): SkillSession? =
        isolated(
            { sessions ->
                sessions.filter { it.completed && it.playerId == 1L && it.workerSlot == 0 }
                    .minByOrNull { it.startedAt }
            },
            { delegate.getOldestCompletedSession() },
        )

    override fun observeCompletedCount(): Flow<Int> =
        isolatedFlow(
            delegate.observeCompletedCount(),
            { sessions -> sessions.count { it.completed && it.playerId == 1L && it.workerSlot == 0 } },
            { delegate.getAllCompletedSessions().size },
        )

    // ── Worker sessions — slot-parameterized ────────────────────────────────

    override suspend fun getActiveWorkerSession(slot: Int): SkillSession? =
        isolated({ it.activeWorkerSession(slot) }, { delegate.getActiveWorkerSession(slot) })

    override fun observeActiveWorkerSession(slot: Int): Flow<SkillSession?> =
        isolatedFlow(
            delegate.observeActiveWorkerSession(slot),
            { it.activeWorkerSession(slot) },
            { delegate.getActiveWorkerSession(slot) },
        )

    override suspend fun getAllCompletedWorkerSessions(slot: Int): List<SkillSession> =
        isolated(
            { sessions ->
                sessions.filter { it.completed && it.playerId == 1L && it.workerSlot == slot }
                    .sortedBy { it.startedAt }
            },
            { delegate.getAllCompletedWorkerSessions(slot) },
        )

    override fun observeWorkerCompletedCount(): Flow<Int> =
        isolatedFlow(
            delegate.observeWorkerCompletedCount(),
            { sessions -> sessions.count { it.completed && it.playerId == 1L && it.workerSlot > 0 } },
            { delegate.getAllSessions().count { it.completed && it.playerId == 1L && it.workerSlot > 0 } },
        )

    override fun observeWorkerCompletedCount(slot: Int): Flow<Int> =
        isolatedFlow(
            delegate.observeWorkerCompletedCount(slot),
            { sessions -> sessions.count { it.completed && it.playerId == 1L && it.workerSlot == slot } },
            { delegate.getAllCompletedWorkerSessions(slot).size },
        )

    override suspend fun deleteAllWorkerSessions(slot: Int) {
        if (!RealitySimulator.updateSimSessions { sessions ->
                sessions.filterNot { it.playerId == 1L && it.workerSlot == slot }
            }) delegate.deleteAllWorkerSessions(slot)
    }

    override suspend fun deleteAllWorkerSessions() {
        if (!RealitySimulator.updateSimSessions { sessions ->
                sessions.filterNot { it.playerId == 1L && it.workerSlot > 0 }
            }) delegate.deleteAllWorkerSessions()
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    override suspend fun getAllSessions(): List<SkillSession> =
        isolated({ it.filter { session -> session.playerId == 1L } }, { delegate.getAllSessions() })

    override suspend fun getSession(sessionId: String): SkillSession? =
        isolated(
            { sessions -> sessions.firstOrNull { it.sessionId == sessionId } },
            { delegate.getSession(sessionId) },
        )

    /** Mirrors `@Insert(onConflict = REPLACE)`: a row with the same primary key is replaced. */
    override suspend fun insert(session: SkillSession) {
        if (!RealitySimulator.updateSimSessions { sessions ->
                sessions.filterNot { it.sessionId == session.sessionId } + session
            }) delegate.insert(session)
    }

    /** Mirrors `@Update`: only an existing row (matched by primary key) is written. */
    override suspend fun update(session: SkillSession) {
        if (!RealitySimulator.updateSimSessions { sessions ->
                sessions.map { if (it.sessionId == session.sessionId) session else it }
            }) delegate.update(session)
    }

    override suspend fun markCompleted(sessionId: String) {
        if (!RealitySimulator.updateSimSessions { sessions ->
                sessions.map { if (it.sessionId == sessionId) it.copy(completed = true) else it }
            }) delegate.markCompleted(sessionId)
    }

    /** Mirrors the expiry query's clock-trust condition, including its fail-open branches. */
    override suspend fun markAllExpiredWorkerSessions(
        now: Long,
        nowElapsed: Long,
        toleranceMs: Long,
        enforceClock: Boolean,
        bootCount: Int,
    ) {
        if (!RealitySimulator.updateSimSessions { sessions ->
                sessions.map { session ->
                    val anchor = session.startElapsedMs
                    val boot   = session.startBootCount
                    val expired = session.playerId == 1L && session.workerSlot > 0 &&
                        !session.completed && session.endsAt <= now
                    // Same fail-open branches as the query: a missing anchor, a missing or
                    // changed boot count, or a negative elapsed delta all trust the wall clock.
                    val clockTrusted = !enforceClock || anchor == null || boot == null ||
                        boot != bootCount || anchor > nowElapsed ||
                        (now - session.startedAt) <= (nowElapsed - anchor) + toleranceMs
                    if (expired && clockTrusted) session.copy(completed = true) else session
                }
            }) delegate.markAllExpiredWorkerSessions(now, nowElapsed, toleranceMs, enforceClock, bootCount)
    }

    override suspend fun delete(sessionId: String) {
        if (!RealitySimulator.updateSimSessions { sessions ->
                sessions.filterNot { it.sessionId == sessionId }
            }) delegate.delete(sessionId)
    }

    override suspend fun deleteAll() {
        if (!RealitySimulator.updateSimSessions { emptyList() }) delegate.deleteAll()
    }
}
