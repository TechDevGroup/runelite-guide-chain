# Guide Chain — RuneLite Plugin

Plays back declarative, chained account-progression guides as quest-helper-style
**zero-ambiguity step-by-step guidance**.

**Hard rule: overlay and highlight only — this plugin never automates player input.**

---

## What it does

Guide Chain is a *renderer* of guide data. You point it at a JSON guide file (or a
chain of them) and it shows you exactly what to do, one step at a time:

- **Sidebar panel** (RuneLite toolbar icon) — chain picker, step instruction + detail,
  progress counter (n / total), and a Mark Done button for MANUAL-condition steps
- Clickbox outline on the target object, NPC, or tile in the game scene
- **Bobbing world arrow** floating above the current step's on-screen world target,
  persistent until the step's completion condition is met (see below)
- Item highlight in your inventory, bank, or equipment
- World-map pin at the destination point
- Directional arrow at the edge of the viewport when the destination is off-screen
- Auto-advances when the configured completion condition fires on a game tick;
  MANUAL-condition steps wait for the Mark Done button in the sidebar panel

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
| `MANUAL`    | *(no extra fields — user clicks Mark Done in the sidebar panel)*   |

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

## Architecture: one store, three surfaces

All mutable state — active chain, current position, per-step done/skip marks,
manual-condition acks, last-known character snapshot, session metrics — lives in a
single shared **`GuideStore`** (`com.techdevgroup.guidechain.store`). The store is
**plain Java with zero RuneLite imports** and persists atomically to:

```
~/.runelite/guide-chain/state.json      (write-to-temp + atomic move)
```

Three surfaces read and mutate through that one instance — never parallel copies:

1. **Sidebar panel** (`GuidePanel`) — a RuneLite `PluginPanel` opened via the toolbar
   `NavigationButton`. Contains the chain picker, step instruction + detail, progress
   counter, and the Mark Done button for MANUAL steps. This is the interactive guide
   surface; all clicks land on real Swing components.
2. **Canvas overlays** (display-only) — `SceneOverlay`, `ItemOverlay`,
   `WidgetHighlightOverlay`, `WorldMapOverlay` render in-world highlights and
   world-map markers for the current step. Nothing on the canvas requires a click;
   mouse events pass through to the game as normal.
3. **Embedded web view** — an htmx UI served by the JDK built-in
   `com.sun.net.httpserver` (no extra dependencies). Back/skip/done actions go through
   the same store, so they are reflected immediately in the sidebar panel and overlays.

The store fires change listeners, so a click in the browser or sidebar panel reflects
in-game immediately. The plugin pushes a character snapshot (skills + tracked quest
states) and live condition values into the store each game tick — cheap, and persisted
only when the snapshot actually changes. Guide content and local overrides are *pushed
into* the store from their own sources of truth (guides source / overrides dir);
they are not duplicated inside `state.json`.

**Hard rule unchanged:** every surface is overlay/annotate-only. Nothing automates
game input.

### Sidebar panel (v0.3.0+)

Click the **Guide Chain** toolbar icon (cyan icon, priority 7) to open the panel.

| Control | What it does |
|---------|--------------|
| Chain picker | Switch chains; immediately moves to the first pending step of the new chain |
| Progress counter | Shows guide name and current/total step number across the whole chain |
| Instruction text | Current step instruction |
| Detail text | Optional longer context (hidden when absent) |
| Mark Done button | Marks the current step complete and advances; visible only for MANUAL-condition steps or steps with no auto-conditions |

The on-canvas Back / Skip arrow controls that existed in v0.2.0 have been removed.
Navigation back or explicit skip is still available through the web view
(`/actions/step/{guideId}/{stepId}/back` and `.../skip`).

---

### Bobbing world arrow (v0.3.1+)

A downward-pointing arrow bobs above the current step's on-screen world target so
you always know exactly which object / NPC / tile the step refers to — and, just as
importantly, so you never mistake an unfinished step for a finished one. The arrow
is **persistent**: it keeps bobbing every frame for as long as the step is active,
and disappears only when the step's completion condition (`QUEST` / `VARBIT` /
`SKILL` / `ITEM_HELD` / `REGION`) auto-satisfies on a game tick, or when you press
**Mark Done**. Both paths advance the current step in the shared `GuideStore`, and
the arrow follows the store's current step — there is no separate timer or dismiss.

It resolves the target to a scene `LocalPoint`:

- `OBJECT` — the scene game object matching `id` (and `worldX/worldY/plane` if set)
- `NPC` — the live location of the first scene NPC matching `id`
- `TILE` — `LocalPoint.fromWorld` on `worldX/worldY/plane`

and projects a point a fixed height above it with
`net.runelite.api.Perspective.localToCanvas(client, localPoint, plane, zHeight)`.
The vertical bob is `Math.sin(client.getGameCycle() / period) * amplitude`. When the
target tile is off-screen the arrow simply does not project; the world-map pin and
edge directional hint cover that case.

Toggle with **Show World Arrow** (default on) and recolor with **World Arrow Color**.

> **Technique credit:** the world-arrow approach — an `ABOVE_SCENE` overlay that
> resolves the target to a `LocalPoint`, projects it above the tile via `Perspective`,
> applies a game-cycle sinusoidal bob, and draws a black-outlined filled arrowhead — is
> modelled on quest-helper's `DirectionArrow.drawWorldArrow` /
> `QuestHelperWorldArrowOverlay`
> ([Zoinkwiz/quest-helper](https://github.com/Zoinkwiz/quest-helper), BSD-2-Clause).
> Guide Chain's `WorldArrowOverlay` is an original re-implementation of that technique;
> no code was copied.

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
java -jar build/libs/runelite-guide-chain-0.3.1-standalone.jar --port 7780 --dir ~/.runelite/guide-chain-standalone
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
| Show Panel      | ✓                                         | Legacy toggle (unused since v0.3.0 — panel is always accessible via the toolbar icon) |
| Highlight Color | cyan                                      | Color for all highlight types                       |
| Show World Arrow | ✓                                        | Bobbing arrow above the current step's on-screen world target |
| World Arrow Color | gold                                    | Fill color of the bobbing world arrow               |
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
# jar → build/libs/runelite-guide-chain-0.3.1.jar
# standalone web view jar → ./gradlew standaloneJar
```

---

## License

BSD 2-Clause — see [LICENSE](LICENSE).
