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
  state.json     ← shared GuideStore state (position, marks, snapshot, metrics)
  guides/        ← local guide files (override remote)
  overrides/
    <guideId>/
      <stepId>.json  ← per-step patches
  cache/         ← remote fetch cache
```

---

## Architecture: one store, three surfaces (v0.2.0)

All mutable state — active chain, current position, per-step done/skip marks,
manual-condition acks, last-known character snapshot, session metrics — lives in a
single shared **`GuideStore`** (`com.techdevgroup.guidechain.store`). The store is
**plain Java with zero RuneLite imports** and persists atomically to:

```
~/.runelite/guide-chain/state.json      (write-to-temp + atomic move)
```

Three surfaces read and mutate through that one instance — never parallel copies:

1. **In-game overlays** — render `GuideStore.currentStep()`; auto-advance marks steps done in the store.
2. **Plugin config actions** — chain selection etc. delegate to the store (and stay in sync with web picks).
3. **Embedded web view** — an htmx UI served by the JDK built-in `com.sun.net.httpserver` (no extra dependencies).

The store fires change listeners, so a click in the browser reflects in-game
immediately. The plugin pushes a character snapshot (skills + tracked quest states)
and live condition values into the store each game tick — cheap, and persisted only
when the snapshot actually changes. Guide content and local overrides are *pushed
into* the store from their own sources of truth (guides source / overrides dir);
they are not duplicated inside `state.json`.

**Hard rule unchanged:** every surface is overlay/annotate-only. Nothing automates
game input.

---

## Web view

Enable **Web View → Enable Web View Server** in the plugin config, then open
`http://127.0.0.1:7780/` (port configurable; binds 127.0.0.1 only).

| Route | Method | Purpose |
|---|---|---|
| `/` | GET | app shell |
| `/fragments/chains` | GET | chain picker partial |
| `/fragments/plan` | GET | ordered task list (current step highlighted, done/pending states) |
| `/fragments/step/current` | GET | detail partial following the current step |
| `/fragments/step/{guideId}/{stepId}` | GET | detail partial for one step (live condition values when the client is connected) |
| `/actions/select-chain` | POST | form `chain=<chainId>` |
| `/actions/step/{guideId}/{stepId}/done` | POST | mark done (advances if current) |
| `/actions/step/{guideId}/{stepId}/skip` | POST | mark skipped (advances if current) |
| `/actions/step/{guideId}/{stepId}/back` | POST | move back one step |
| `/actions/refresh-guides` | POST | re-fetch guide content |
| `/api/state.json` | GET | full machine-readable state for external tools/agents |

Fragments are small HTML partials (htmx swap targets); one step-row partial is
reused for both the plan list and the detail card. Action responses set
`HX-Trigger: guide-store-changed` so every fragment refreshes together.

### Responsive notes

The UI is mobile-first and stays readable from **380px through 1600px**:
fluid containers (`max-width` + padding, no fixed pixel widths), CSS grid that
collapses to one column under 900px, wrapping flex rows, and `min-width: 0` on
flex/grid children so long instructions wrap instead of clipping at container
edges. Verified headless at 380×800 and 1400×900 with
`document.scrollingElement.scrollWidth <= window.innerWidth`.

### Standalone mode (portability proof)

The store + web server boot outside RuneLite against the bundled
`f2p-early-game` fixture. With no client attached, live condition values show
as *client offline* — everything else (plan, navigation, marks, persistence,
state API) works identically:

```bash
./gradlew standaloneJar
java -jar build/libs/runelite-guide-chain-0.2.0-standalone.jar --port 7780 --dir ~/.runelite/guide-chain-standalone
# or, without building a jar:
./gradlew runWeb -Pport=7780
```

Standalone state defaults to `~/.runelite/guide-chain-standalone/state.json`
so it never fights the plugin over one file (point `--dir` at
`~/.runelite/guide-chain` deliberately if you want to drive the plugin's state
while the client is closed).

### htmx attribution

The web UI vendors [htmx](https://htmx.org) 1.9.12 (`src/main/resources/web/htmx.min.js`),
licensed under the **Zero-Clause BSD** license — see
[`src/main/resources/web/HTMX-LICENSE`](src/main/resources/web/HTMX-LICENSE),
also served at `/static/HTMX-LICENSE`.

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
| Enable Web View Server | ✗                                  | Localhost web view of the shared guide state        |
| Web View Port   | `7780`                                    | Port for the web view (127.0.0.1 only)              |

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
# jar → build/libs/runelite-guide-chain-0.2.0.jar
# standalone web view jar → ./gradlew standaloneJar
```

---

## License

BSD 2-Clause — see [LICENSE](LICENSE).
