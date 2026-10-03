Repo: kupal-coder/IdleFantasy-modded
Base branch: main (current HEAD aa4dec3)

You will implement the entire feature in ONE sitting. Do not stop for confirmation. Explore, implement, test, and open a single Pull Request.

GOAL
Implement the full three-layer system with minimal diff, no refactors, and no new dependencies:

Base Reality (Very Hard)
    └── Simulator (Parallel Checkpointed Reality)
            └── Fast Time Simulation (Time Skip)

EXACT BEHAVIOR

1. Base Reality (Very Hard)
   - Enemy HP × 3.0
   - Enemy combat & defense stats × 2.2
   - Almost all safe_zone flags set to false (including Farm)
   - Encounter rates reduced to 0.35–0.50
   - Rare drop chances cut by 60–70%
   - All XP rates reduced by 50%
   - Healing weaker / more expensive
   - Gear skill requirements raised
   - Death is permanent / heavily punishing

2. Simulator
   - Activated from Base Reality
   - Creates a deep-copy checkpoint of the full player state
   - Runs an exact parallel copy of the game
   - All writes go only to the temporary simulation state
   - On death or voluntary exit → discard temporary state and return to Base Reality
   - Player may select up to exactly 3 rewards from gains made in that run:
     • Stats
     • Skill Experience
     • Items obtained

3. Fast Time Simulation (Time Skip)
   - Only available while inside the Simulator
   - Instantly advances the current action by a selectable duration
   - Uses the exact same rate calculations as a real session
   - Results applied only to the temporary simulation state
   - All gains still count toward the final pick-3 reward pool
   - Simulator itself is upgradeable (longer skips, better efficiency, etc.) with upgrades that persist in Base Reality

PROCESS (do all of this without stopping)

STEP 1 – EXPLORE (document answers inside the PR body)
Answer these 5 questions by reading the code:
1. How is enemy combat power currently calculated and applied at runtime?
2. Where is the safe_zone flag read and enforced, and which early dungeons currently set it to true?
3. How are global XP multipliers or rate adjustments currently applied?
4. Is there any existing difficulty / hardcore flag that can be reused?
5. What is the exact call site where ModInit.init() should run?

STEP 2 – BUILD
Implement the following requirements with the smallest possible diff:

1. Add a global hardcore difficulty multiplier system for Base Reality.
2. Apply the exact numerical multipliers listed above.
3. Remove safe_zone protection from early dungeons including Farm.
4. Implement deep-copy checkpoint of the full player state on Simulator entry.
5. Isolate all game writes so isSimulationActive == true never touches the real save.
6. On death or exit from Simulator, present a reward selection screen limited to exactly three choices (Stats / Skill Experience / Items).
7. Implement Fast Time Simulation (Time Skip) that only works inside the Simulator.
8. Track all gains from Time Skip so they remain available in the pick-3 pool.
9. Make the Simulator upgradeable with persistent upgrades stored in Base Reality.
10. Cover every UI state: enter Simulator, inside Simulator, Time Skip confirm, Time Skip result, death in Simulator, reward selection, return to Base Reality.
11. Handle edge cases: force-close during simulation, offline progress while in simulation, empty reward pool, max upgrade levels.

Error handling:
- Any crash → “An unexpected error occurred. Returning to Base Reality.”
- Offline → “You are offline. Simulation continues with local data only.”

RULES
- No secrets, no PII
- All user-facing strings through strings.xml
- Match existing code style exactly
- Must not crash
- Prefer ModInit.kt and existing simulator classes
- Minimal lines changed, no refactors, no new dependencies

FINAL ACTIONS
1. Create a new branch.
2. Commit the changes with a clear message.
3. Open ONE Pull Request against main.
4. In the PR body include:
   - Answers to the 5 exploration questions
   - List of files changed
   - How the three-layer integration was done
   - This exact test list:
     1. Start in Base Reality and confirm early enemies are much harder and Farm is no longer safe.
     2. Enter Simulator, use Time Skip, die or exit, and verify you can select exactly three rewards that correctly apply to Base Reality.
     3. Negative case: attempt Time Skip while still in Base Reality — it must be unavailable.
     4. Offline case: enter Simulator, enable airplane mode, use Time Skip, exit, and confirm rewards still apply.

Do everything in one continuous run. Do not ask for confirmation. Deliver the finished Pull Request.