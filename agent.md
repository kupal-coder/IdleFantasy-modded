# agent.md — How to Add Custom Things to IdleFantasy-modded

> Repo: `https://github.com/kupal-coder/IdleFantasy-modded`
> Fork of: `https://github.com/tristinbaker/IdleFantasy`
> Version at time of writing: `1.15.4 (154000)` — `applicationId com.tristinbaker.idlefantasy`, `namespace com.fantasyidler`
> Stack: Kotlin, Jetpack Compose + Material3, Room + Hilt + KSP, AlarmManager, kotlinx.serialization, MVVM + Repository. No GMS. F-Droid compatible.
> Build: JDK 17+, Android SDK 35, `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

This file is for an AI/human agent adding **custom content** (items, enemies, dungeons, skills, recipes, spells, pets, quests, bosses, etc.) or **custom code** (simulators, UI, DI, DB).

## 0. Golden rules

1. **JSON-first.** 95% of "custom things" are JSON in `app/src/main/assets/data/`. No Kotlin needed unless you add new *behavior*.
2. **IDs are `snake_case`, globally unique.** e.g. `mythrite_ore`, `my_dungeon`, `fire_wave`. Renaming an ID = breaking saves.
3. **Schema is enforced by `app/src/main/kotlin/com/fantasyidler/data/json/*.kt`.** If you add a field not in the `@Serializable` data class, it is ignored / crashes on strict parse. Always read the corresponding `*Data.kt` first.
4. **Cross-reference.** New equipment/enemy/item must also be registered where it is *used*: `items.json` category list, `enemies.json` entry + `dungeons/<x>.json` spawn, `equipment.json` stats, drop tables, `quests.json` targets, `marketplace.json`, sprites, strings.
5. **Validate + build:** `python -m json.tool <file>` (or `./gradlew :app:assembleDebug`), `./gradlew :app:testDebugUnitTest`, `./gradlew lintDebug`.
6. **Room caution:** Changing `app/src/main/kotlin/com/fantasyidler/data/db/` entities requires a new Room migration + schema in `app/schemas/`. Prefer JSON-only mods to avoid migrations.

## 1. Project map (where things live)

```
app/src/main/assets/data/          # <-- ALL game content. Start here.
  items.json                       # master category lists (ores/bars/weapons/armor/fish/...)
  equipment.json                   # every equippable stat block (see EquipmentData.kt)
  enemies.json                     # every enemy stat + drops (see EnemyData.kt)
  dungeons/*.json                  # 33+ files, one per dungeon (see DungeonData.kt), e.g. farm.json
  skilling_dungeons/ trade_routes/ skills/ recipes/
  skills/*.json                    # mining.json, fishing.json, ... xp_ranges + drop_tables
  ores.json fish.json trees.json logs.json crops.json gems.json runes.json bones.json
  recipes/smithing.json cooking.json crafting.json fletching.json herblore.json construction.json
  spells.json slayer_tasks.json thieving_npcs.json agility_courses.json
  pets.json blessings.json raid_bosses.json mercenaries.json
  quests.json daily_quests.json weekly_quests.json guild_quests.json guild_daily_quests.json
  prestige_paths.json buildings.json marketplace.json seasonal_events.json
  carnival_prizes.json official_themes.json house_tiles.json xp_table.json

app/src/main/kotlin/com/fantasyidler/
  FantasyIdlerApp.kt               # @HiltAndroidApp entry. Mod boot hook goes here.
  MainActivity.kt                  # @AndroidEntryPoint, injects PlayerRepository etc.
  data/json/*.kt                   # JSON schemas — source of truth (EquipmentData, EnemyData, DungeonData, QuestData, RecipeData, ...)
  data/db/ data/model/ repository/ di/  # Room, repos, Hilt modules
  simulator/                       # SkillSimulator, CombatSimulator, CarnivalSimulator, MercantileSimulator,
                                   # ThievingSimulator, SkillingDungeonSimulator, XpTable, TowerScaling, PrestigePoints/Boosts, HeirloomStats
  ui/ util/ notification/ receiver/
app/src/main/res/                  # strings.xml (20 langs, Weblate-compatible), drawables, themes
app/src/main/assets/sprites/       # item/enemy sprites
wiki/rules/ADDING_*.md docs/ scripts/  # wiki gen, locale sync, house atlas
```

Local stub in this workspace (`/public/IdleFantasy-modded/`):
- `ModInit.kt` → drop into `app/src/main/kotlin/com/fantasyidler/ModInit.kt`, call `ModInit.init(this)` from `FantasyIdlerApp.onCreate()` after `createChannels()`. Use for logging, rescheduling `BackupScheduler`, tweaking `XpTable`/`TowerScaling` without forking everything.
- `init.sh` → clone + `assembleDebug` helper.

## 2. Data-driven recipes (copy-paste patterns)

### 2.1 Custom equipment / weapon / tool / armor

1. Read schema: `data/json/EquipmentData.kt` — required: `name, display_name, slot, description`. Optional: `combat_style, attack_bonus, strength_bonus, defense_bonus, ranged_*, magic_*, requirements{skill:level}, infinite_runes, attack_speed, *_efficiency, cape_skill, cape_bonus, two_handed, heirloom_skill, heirloom_base`.
   Slots seen in data: `weapon, helmet, platebody, platelegs, shield, pickaxe, axe, fishing_rod`, etc. (infer from `equipment.json` keys).
2. Append to `app/src/main/assets/data/equipment.json`:
```json
"my_rune_greatsword": {
  "name": "my_rune_greatsword",
  "display_name": "My Rune Greatsword",
  "slot": "weapon",
  "combat_style": "melee",
  "description": "A custom greatsword.",
  "attack_bonus": 65,
  "strength_bonus": 70,
  "defense_bonus": 0,
  "requirements": { "attack": 60 },
  "two_handed": true,
  "attack_speed": 3.0
}
```
Tool example (gathering boost):
```json
"my_mithril_pickaxe": {
  "name": "my_mithril_pickaxe",
  "display_name": "My Mithril Pickaxe",
  "slot": "pickaxe",
  "description": "Mines 25% faster.",
  "mining_efficiency": 1.25,
  "requirements": { "mining": 55 }
}
```
3. Register ID in `app/src/main/assets/data/items.json` under correct array (`weapons`, `armor`, `tools`, ...). If you skip this, shop/loot/inventory filters won't see it.
4. Make it obtainable: add a `smithing.json`/`crafting.json` recipe (2.5) OR enemy `drop_table` entry (2.2) OR `marketplace.json` entry + sprite in `assets/sprites/` + `res/values/strings.xml` name if localized.
5. Test: `./gradlew :app:assembleDebug`, equip in-game, check `CombatSimulator.kt` damage calc.

### 2.2 Custom enemy

Schema: `data/json/EnemyData.kt` → `name, display_name, hp, combat_stats{attack_level,strength_level,defense_level,attack_bonus,strength_bonus}, defensive_stats{attack_defense,strength_defense,ranged_defense,magic_defense}, xp_drops{skill:xp}, drop_table[{item,chance,quantity_min,quantity_max}], always_drops[{item,quantity}], tags[]`.

1. Ensure all `item` IDs exist in `items.json`/`equipment.json`.
2. Append to `enemies.json` (object keyed by ID). Example pattern from existing goblins/rats:
```json
"my_shadow_wolf": {
  "name": "my_shadow_wolf",
  "display_name": "Shadow Wolf",
  "hp": 120,
  "combat_stats": {"attack_level": 40, "strength_level": 40, "defense_level": 30, "attack_bonus": 25, "strength_bonus": 28},
  "defensive_stats": {"attack_defense": 30, "strength_defense": 30, "ranged_defense": 15, "magic_defense": 20},
  "xp_drops": {"attack": 45, "hitpoints": 30},
  "drop_table": [{"item": "bones", "chance": 0.8, "quantity_min": 1, "quantity_max": 1}, {"item": "my_rune_greatsword", "chance": 0.02, "quantity_min": 1, "quantity_max": 1}],
  "always_drops": [{"item": "coins", "quantity": 25}],
  "tags": ["beast"]
}
```
3. Spawn it: add to a `dungeons/<dungeon>.json` `enemy_spawns[]` (weights are relative).

### 2.3 Custom combat dungeon (easiest new zone)

Schema: `data/json/DungeonData.kt` → `name, display_name, description, recommended_level, encounter_rate, enemy_spawns[{enemy,weight}], lore_unlock_only=false, rare_drops[{item,chance}], safe_zone=false, lore_hint?, event_key?`.

Real example `dungeons/farm.json`:
```json
{
  "name": "farm",
  "display_name": "Farm",
  "description": "A peaceful farm...",
  "recommended_level": 1,
  "encounter_rate": 0.75,
  "safe_zone": true,
  "enemy_spawns": [{"enemy": "chicken","weight": 4},{"enemy": "sheep","weight": 4},{"enemy": "cow","weight": 2}]
}
```
Steps:
1. Create `app/src/main/assets/data/dungeons/my_hollow.json` with unique `name` == filename.
2. Use only existing `enemy` IDs. `weight` = relative spawn chance. `encounter_rate` ~0.2–0.75. `safe_zone:true` = can't die (good for level 1–5). `event_key` only for seasonal (see `seasonal_events.json`), `lore_unlock_only:true` + `lore_hint` only if gated by expeditions.
3. No registry file — loader scans the folder. Just rebuild. Dungeon auto-appears sorted by `recommended_level`.
4. Optional `rare_drops` = rolled once per *run* (not per kill).

Skilling dungeon: same but in `skilling_dungeons/` + schema `SkillingDungeonData.kt`.

### 2.4 Custom gathering node (ore / fish / tree / crop ...)

Each resource file is a flat map: `{ "<id>": {display_name, level_required, xp_per_*, time_per_*}}`.
E.g. `ores.json`:
```json
"my_star_ore": {"display_name": "Star Ore", "level_required": 85, "xp_per_ore": 150, "time_per_ore": 1}
```
`fish.json` uses `xp_per_catch/time_per_catch`. `trees.json` / `logs.json` similar. Elder-tier example already exists: `mythrite_ore/abyssal_ore/voidsteel_ore/starforged_ore`, `raw_tidepool_crab/raw_lavafin`, etc.

Then wire into `skills/mining.json` (or `fishing.json` etc.):
- `skills/<skill>.json` shape: `{name, display_name, description, max_level:99, xp_ranges{"1":{min,max}...}, drop_tables{"<level>":[{item,chance}]}}`
- Add your item to the appropriate level bucket, keep `chance` sum = 1.0 per bucket. See `mining.json` in repo for full pattern.
- Register ID in `items.json` (`ores`, `fish`, `logs`...).

### 2.5 Custom recipe (smithing / cooking / crafting / fletching / herblore / construction)

Schema: `data/json/RecipeData.kt`. Each file has its own type:
- `SmithingRecipe{type, display_name, level_required, materials{item:qty}, output_quantity, xp_per_item, time_per_item}`
- `CookingRecipe{raw_item, cooked_item, display_name, level_required, xp_per_item, healing_value, time_per_item}`
- `FletchingRecipe{item_name, display_name, type, level_required, xp_per_item, materials, output_quantity, time_per_batch, damage?, attack_bonus? ...}`
- `CraftingRecipe/ConstructionRecipe` like smithing.

Example: add to `recipes/cooking.json` array (check if file is list or map — follow existing header):
```json
{"raw_item": "raw_manta_ray", "cooked_item": "manta_ray", "display_name": "Cook Manta Ray", "level_required": 85, "xp_per_item": 220.0, "healing_value": 45, "time_per_item": 2}
```

### 2.6 Custom spell / rune

`runes.json`: `{ "<id>": {display_name, ...}}`. `spells.json` shape (real):
```json
"my_void_bolt": {"display_name": "Void Bolt", "rune_type": "blood_rune", "magic_level_required": 75, "max_hit": 19, "rune_cost": 2, "description": "..."}
```
Schema: `SpellData.kt`. `rune_type` must exist in `runes.json`. Add staff with `infiniteRunes` in `equipment.json` if you want free casting.

### 2.7 Custom pet

`pets.json` is `{ "<id>": {id, display_name, emoji, description, source, effect_type, boosted_skill, boost_percent}}`.
`effect_type`: `xp_boost` or `coin_boost`. `boosted_skill`: any skill key, `combat`, `all`, or `coins`.
```json
"my Ember Fox": {"id":"my_ember_fox","display_name":"Ember Fox","emoji":"🦊","description":"...","source":"My dungeon rare drop","effect_type":"xp_boost","boosted_skill":"firemaking","boost_percent":10}
```
Then add drop hook: raid `rare_drops`, dungeon `rare_drops`, or `SkillSimulator.kt` pet-roll table — grep `rock_golem|tower_pet` to find roll logic.

### 2.8 Custom quest / daily / weekly / guild / slayer / thieving / blessing

- Quest schema `QuestData.kt`: `{id, name, skill, tier, requires_previous?, type, target, amount, description, rewards{coins,xp,items{}}, requires_dungeon_unlock?}`. Append to `quests.json`. `type/target` must match tracker keys (grep `QuestData` usage in `repository/`). Keep chain via `requires_previous`.
- Dailies: `daily_quests.json` (`DailyQuestData.kt`), weeklies (`WeeklyQuestData.kt`), guild (`guild_quests.json` + `guild_daily_quests.json`). Same pattern, smaller rewards.
- Slayer: `slayer_tasks.json` (`SlayerTaskData.kt`) — enemy ID + kill count.
- Thieving NPC: `thieving_npcs.json` is a *list*: `{key, display_name, level_required, base_xp, coins_min/max, loot_table[{item,chance,min_qty?,max_qty?}]}`. Copy `peasant` entry above.
- Blessing/bone: `blessings.json` is a list `{key, prayer_level_required, type: XP|DEFENSE|COINS, magnitude}`. `bones.json` (`BoneData.kt`) similar. Church UI reads these directly.

### 2.9 Custom raid boss / mercenary / prestige / building / shop / carnival / event / theme / house / agility

- `raid_bosses.json` (`BossData.kt`): includes HP, party-wide mechanics, `BossRareDrop`. Hire test mercs from `mercenaries.json` (`MercenaryData.kt`). Combat view = `CombatSimulator.kt` + raid UI in `ui/`.
- `prestige_paths.json` (`PrestigeData.kt` + `PrestigeBoosts.kt`/`PrestigePoints.kt`): per-skill tree. Keep point math in sync.
- `buildings.json` (`TownBuildingData.kt`), `marketplace.json` (`MarketplaceData.kt`), `trade_routes/` (`TradeRouteData.kt` + `MercantileSimulator.kt`/`MercantilePerks.kt`).
- `carnival_prizes.json` (`CarnivalPrize.kt` + `CarnivalSimulator.kt`), `seasonal_events.json` (`SeasonalEventData.kt` — use `event_key` to gate dungeons), `official_themes.json` (`ThemeData.kt`), `house_tiles.json` (`HouseTilesData.kt` + `scripts/build_house_atlas.py`), `agility_courses.json` + `skills/agility.json`.
- `xp_table.json` + `simulator/XpTable.kt` — changing XP curve affects *all* skills; prefer `ModInit` tweak + test.

## 3. Custom code (when JSON isn't enough)

### A. Quick tweak without fork (ModInit hook)
1. Copy workspace `ModInit.kt` → `app/src/main/kotlin/com/fantasyidler/ModInit.kt`.
2. In `FantasyIdlerApp.kt` (`@HiltAndroidApp`) call `ModInit.init(this)` in `onCreate()` after channels.
3. Put XP/loot/rate tweaks, alarm rescheduling (`BackupScheduler`, `BootReceiver`), logging of Hilt repos there. Bump `MOD_VERSION`.

### B. New simulator behavior
Edit `simulator/SkillSimulator.kt` (18k) / `CombatSimulator.kt` (42k) / `ThievingSimulator.kt` / `SkillingDungeonSimulator.kt` / `CarnivalSimulator.kt` / `MercantileSimulator.kt`. Pure Kotlin, unit-testable (`app/src/test/`). Keep functions deterministic where possible for tests.

### C. New UI screen
`ui/` is Compose + Navigation + Hilt ViewModels. Steps: ViewModel in `ui/<feature>/` injecting `PlayerRepository/GlobalStateRepository/SaveSlotRepository` → `@Composable` screen → add route in NavGraph → add strings to `res/values/strings.xml` (+ translations via `TRANSLATING.md` + `scripts/sync_locale_strings.py`) → add entry button (Town/Home).

### D. New persistent data (Room)
`data/db/` + `data/model/` + `repository/` + `di/` (Hilt modules). After entity change: bump DB version, add `Migration`, export schema to `app/schemas/`, add Robolectric migration test (`unitTests.isIncludeAndroidResources=true`). See `app/build.gradle.kts` `ksp { room.schemaLocation }`.

### E. Sprites / localization / wiki
- Sprites: `app/src/main/assets/sprites/` + `scripts/build_house_atlas.py` for housing.
- Strings: `app/src/main/res/values/strings.xml`; sync with `scripts/sync_locale_strings.py`, normalize with `normalize_locale_strings.py`. Run `lintDebug` — `MissingTranslation` is warning-only by config but keep clean.
- Wiki: `wiki/src/ templates/ rules/` → see `wiki/rules/ADDING_STATIC_WIKI_PAGES.md` + `ADDING_DYNAMIC_WIKI_PAGES.md`.

## 4. Verify checklist

```bash
git clone https://github.com/kupal-coder/IdleFantasy-modded.git
cd IdleFantasy-modded
./gradlew :app:assembleDebug          # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM tests, no emulator
./gradlew lintDebug                   # baseline in app/lint-baseline.xml — only NEW issues fail CI
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JSON checklist before PR:
- [ ] `python -m json.tool` passes on every edited file (or `jq empty`)
- [ ] IDs snake_case, unique across `enemies.json/equipment.json/dungeons/`, referenced IDs exist
- [ ] `items.json` category updated, drop chances sum to 1.0 per bucket, `requirements` skills exist (23 skills)
- [ ] `strings.xml` + sprite added if player-visible, translation docs checked
- [ ] Tested offline session (AlarmManager 1h cap), raid with mercs, tower floor 10-multiple if touched
- [ ] No Room version bump unless intended; if bumped, migration + schema + test included
- [ ] `CONTRIBUTING.md` followed — open issue before large change

## 5. Common pitfalls

- Forgetting `items.json` → item exists but invisible to shop/filters.
- Typo'd `enemy`/`item` in `enemy_spawns`/`drop_table` → silent miss or crash on load. Grep IDs repo-wide.
- `encounter_rate` >1 or negative weights → unbalanced / divide-by-zero.
- New dungeon `recommended_level` colliding with 33 existing → sort overlap; pick a gap.
- `lore_unlock_only` without expedition note → unreachable content. Set `lore_hint`.
- `event_key` without `seasonal_events.json` entry → dungeon never shows.
- kotlinx.serialization is case-sensitive: `display_name` not `displayName` in JSON.
- Hardcoding XP in `XpTable.kt` + `xp_table.json` mismatch → prestige math breaks. Change in one place, mirror in other.
- Saving: player save is Room, not JSON — old saves won't get new items until dropped/quested. Provide migration path or quest/shop grant.

---
*Generated for kupal-coder/IdleFantasy-modded. Verify `*Data.kt` schemas at HEAD before editing — they are authoritative over this doc.*
