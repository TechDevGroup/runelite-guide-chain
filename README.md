# Guide Chain — RuneLite Plugin

Plays back declarative, chained account-progression guides as quest-helper-style
**zero-ambiguity step-by-step guidance**.

**Hard rule: overlay and highlight only — this plugin never automates player input.**

---

## What it does

Guide Chain is a *renderer* of guide data. You point it at a JSON guide file (or a
chain of them) and it shows you exactly what to do, one step at a time:

- Step instruction + optional detail in a sidebar panel
- Clickbox outline on the target object, NPC, or tile in the game scene
- Item highlight in your inventory, bank, or equipment
- World-map pin at the destination point
- Directional arrow at the edge of the viewport when the destination is off-screen
- Auto-advances when the configured completion condition fires on a game tick;
  fallback to manual Skip/Back buttons

---

## Guide format

Guides are JSON files validated against [`schema/guide.schema.json`](schema/guide.schema.json).
The full structure is:

```
guide.json
  id          — stable string id matching the filename (no extension)
  name        — human display name
  description — optional
  steps[]
    id                    — unique within this guide (used by override system)
    instruction           — short imperative text; AUTHOR'S OWN WORDS ONLY
    detail                — optional longer context
    highlights[]          — what to draw in-world (see below)
    mapMarkers[]          — world-map pins for this step
    completionConditions[] — when ALL fire, step auto-advances (see below)
```

### Highlight target types

| type     | required fields                          | optional                      |
|----------|------------------------------------------|-------------------------------|
| `OBJECT` | `id` (game-object id)                    | `worldX`, `worldY`, `plane`   |
| `NPC`    | `id` (npc id)                            |                               |
| `ITEM`   | `id` (item id), `container`              | container: INVENTORY/BANK/EQUIPMENT |
| `WIDGET` | `group`, `child`                         |                               |
| `TILE`   | `worldX`, `worldY`                       | `plane` (default 0)           |

### Completion condition types

| type        | fields                                        |
|-------------|-----------------------------------------------|
| `QUEST`     | `questName` (Quest enum name), `state`        |
| `VARBIT`    | `varbitId`, `op` (EQ/NEQ/GTE/LTE/GT/LT), `value` |
| `SKILL`     | `skill` (Skill enum name), `level`            |
| `ITEM_HELD` | `itemId`, `qty`                               |
| `REGION`    | `regionId`                                    |
| `MANUAL`    | *(no extra fields — user clicks Skip/Done)*   |

All conditions in a step's list must be simultaneously true for auto-advance.
An empty conditions list means the step never auto-advances.

---

## Data sources (override chain — highest wins)

1. **Local step overrides** — `~/.runelite/guide-chain/overrides/<guideId>/<stepId>.json`
   Partial patch: only the fields you set replace the upstream step.
   Schema: [`schema/override.schema.json`](schema/override.schema.json)

2. **Local guides directory** — `~/.runelite/guide-chain/guides/<filename>.json`
   Drop a complete guide file here to shadow the remote version.

3. **Remote git source** — fetched via `raw.githubusercontent.com` from the configured
   repo + branch (`guides` orphan branch by default).
   Cache stored at `~/.runelite/guide-chain/cache/`.

File paths summary:
```
~/.runelite/guide-chain/
  guides/        ← local guide files (override remote)
  overrides/
    <guideId>/
      <stepId>.json  ← per-step patches
  cache/         ← remote fetch cache
```

---

## Chain manifest

`manifest.json` at the root of the guides data source lists all available chains:

```json
{
  "chains": [
    {
      "id": "f2p-early-game",
      "name": "F2P Early Game",
      "description": "From Lumbridge start through early F2P quests and skill milestones.",
      "guides": ["f2p-early-game.json"]
    }
  ]
}
```

---

## Debug panel

Enable **Show Debug Panel** in config. It shows:

- Current guide id + step id
- Each completion condition with its **live value** vs **expected value** (green = met, red = unmet)
- List of active highlight targets

If a step misbehaves (wrong highlight, condition never fires), diagnose here and fix
by dropping an override JSON into `~/.runelite/guide-chain/overrides/`.

---

## Config

| Setting         | Default                                   | Description                                          |
|-----------------|-------------------------------------------|------------------------------------------------------|
| Guides Repo     | `TechDevGroup/runelite-guide-chain`       | GitHub `owner/repo` for guide data                  |
| Guides Branch   | `guides`                                  | Branch on that repo with manifest.json + guide files |
| Selected Chain  | `0`                                       | Index into manifest chains list                     |
| Show Panel      | ✓                                         | Sidebar instruction panel                           |
| Highlight Color | cyan                                      | Color for all highlight types                       |
| Show Debug      | ✗                                         | Debug condition/target overlay                      |

---

## Seed content (demo)

The `guides` orphan branch contains one starter chain **`f2p-early-game`** (~18 steps)
covering Lumbridge start → first cook attempt → Cook's Assistant → early Woodcutting/
Fishing/Firemaking/Mining milestones → Doric's Quest → Imp Catcher — enough to
demonstrate chaining, auto-advance conditions, all highlight types, and map markers.

> **Note:** This is a demonstration seed only. Full optimised F2P + early P2P chains
> will be generated from the progression-router planner (see `PROGRESSION_ROUTER_BRIEF.md`).

---

## Sideload install

```powershell
irm https://raw.githubusercontent.com/TechDevGroup/runelite-rect-overlay/dist/get.ps1 | iex
```

---

## Build

Requires Temurin JDK 11 and uses Gradle 8.10 (via wrapper).

```bash
export JAVA_HOME=/path/to/jdk-11
./gradlew build
# jar → build/libs/runelite-guide-chain-0.1.0.jar
```

---

## License

BSD 2-Clause — see [LICENSE](LICENSE).
