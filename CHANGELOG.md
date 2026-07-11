# Changelog

## v0.3.1

### Added — persistent bobbing world arrow

A Quest-Helper-style downward arrow now bobs above the current step's on-screen
world target, so the player never mistakes an unfinished step for a finished one.

- New `overlay/WorldArrowOverlay.java` on `OverlayLayer.ABOVE_SCENE`.
- Resolves the current step's first world-locatable highlight target to a
  scene `LocalPoint`:
  - `OBJECT` — scene game object matching `id` (+ `worldX/worldY/plane` if set),
    via `GameObject.getLocalLocation()`
  - `NPC` — the live location of the first scene NPC matching `id`, via
    `NPC.getLocalLocation()`
  - `TILE` — `LocalPoint.fromWorld(client, worldPoint)`
- Projects a point a fixed height above the tile with
  `Perspective.localToCanvas(client, localPoint, plane, zHeight)`.
- Vertical bob: `Math.sin(client.getGameCycle() / period) * amplitude` per frame.
- Draws a black-outlined, color-filled downward arrowhead + stem (original drawing).
- **Persistence:** the arrow renders every frame while the current step is active
  and disappears only when that step changes — which happens when its completion
  condition (`QUEST` / `VARBIT` / `SKILL` / `ITEM_HELD` / `REGION`) auto-satisfies
  on a game tick, or the user presses Mark Done. It is driven entirely by
  `GuideStore.currentStep()`; there is no separate timer or dismiss.
- Off-screen targets don't project — the existing world-map pin + edge directional
  hint (`WorldMapOverlay`) still cover that case.
- New config: `showWorldArrow` (default true) and `worldArrowColor` (default gold).

**Technique credit:** modelled on quest-helper's `DirectionArrow.drawWorldArrow` /
`QuestHelperWorldArrowOverlay`
([Zoinkwiz/quest-helper](https://github.com/Zoinkwiz/quest-helper), BSD-2-Clause) —
original re-implementation, no code copied.

**Unchanged:** sidebar panel, scene/item/widget highlights, world-map markers, and
the web view all remain driven by the one shared `GuideStore`. Hard rule intact:
overlay/highlight-only, never automates input.

## v0.3.0

### Changed — interactive guide surface moved to sidebar panel

The guide's interactive UI now lives in a RuneLite **sidebar panel**
(`GuidePanel`) opened via a toolbar `NavigationButton`, following the standard
RuneLite hub-plugin pattern (same as quest-helper).

**What moved to the sidebar panel:**
- Chain picker (JComboBox — selects the active guide chain)
- Current step instruction text + optional detail
- Step progress counter (guide name · n / total)
- Mark Done button (visible for MANUAL-condition steps or steps with no auto-conditions)

**What was removed from the canvas:**
- The on-canvas `PanelOverlay` (instruction text + Back / Skip arrows drawn as
  canvas overlay elements) has been deleted. Those elements were click-through
  (mouse events passed to the game), so the Back and Skip arrows could never
  actually be clicked. The panel overlay is gone entirely.

**What stays on the canvas (display-only, non-interactive):**
- `SceneOverlay` — clickbox outlines on game objects, NPCs, and ground tiles
- `ItemOverlay` — item highlights inside inventory / bank / equipment
- `WidgetHighlightOverlay` — widget border highlights
- `WorldMapOverlay` — world-map pins and off-screen directional arrows

Nothing on the canvas requires a click; mouse events pass through normally.

**One store — three surfaces (unchanged contract):**
- The sidebar panel, the canvas overlays, and the localhost web view all read
  and mutate the same `GuideStore` instance. A Mark Done in the panel or the
  web view immediately reflects in the overlays, and auto-advance from the
  game tick still fires correctly.

**Back / skip controls:**
- The on-canvas Back / Skip arrows are removed and are not reproduced in the
  sidebar panel (per design). Back and explicit skip remain available through the
  web view (`/actions/step/{guideId}/{stepId}/back` and `.../skip`).

### Internal
- Deleted `overlay/PanelOverlay.java`
- Added `panel/GuidePanel.java` (extends `PluginPanel`, `@Singleton`)
- Added `NavigationButton` wired to `GuidePanel` in `GuideChainPlugin.startUp`
- Plugin icon copied to classpath resources for `ImageUtil.loadImageResource`
- `storeListener` now dispatches `guidePanel.refresh()` via `SwingUtilities.invokeLater`
  on plan/chain/overrides/marks/position changes (not on character/liveConditions
  which fire every tick)
- Removed `plugin.skipStep()` and `plugin.backStep()` (dead code after
  PanelOverlay removal; web-view actions go directly to the store)
- Bumped version to 0.3.0 in `build.gradle` and `runelite-plugin.properties`

---

## v0.2.0

- Introduced shared `GuideStore` — single state instance behind overlays,
  config actions, and the embedded web view
- Atomic JSON persistence (`state.json`)
- Localhost htmx web view (`GuideWebServer`, `WebFragments`, `Html`)
- Auto-advance from game-tick condition evaluation
- Session metrics and character snapshot
- Per-step override system (`~/.runelite/guide-chain/overrides/`)

## v0.1.0

- Initial release: `SceneOverlay`, `ItemOverlay`, `WidgetHighlightOverlay`,
  `WorldMapOverlay`, `DebugOverlay`, `PanelOverlay` (canvas)
- JSON guide + manifest format with schema files
- Remote fetch + local cache + override chain
