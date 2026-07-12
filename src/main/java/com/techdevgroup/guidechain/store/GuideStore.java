package com.techdevgroup.guidechain.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.techdevgroup.guidechain.data.ChainEntry;
import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.ConditionType;
import com.techdevgroup.guidechain.data.Guide;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.Manifest;
import com.techdevgroup.guidechain.data.StepOverride;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single shared state instance behind all three surfaces of Guide Chain:
 * the in-game overlays, the plugin config actions, and the embedded web view.
 *
 * <p><strong>Portability rule: this class (and everything in
 * {@code com.techdevgroup.guidechain.store} / {@code ...data} /
 * {@code ...web}) has ZERO RuneLite imports.</strong> It can be booted
 * standalone (see {@code GuideWebMain}) against a fixture state.
 *
 * <h3>What it owns</h3>
 * <ul>
 *   <li>active chain id + current position (guide index / step index)</li>
 *   <li>per-step done / skip marks and manual-condition acks</li>
 *   <li>local step-override data (pushed in from the overrides directory)</li>
 *   <li>last-known character snapshot (pushed in by the plugin each tick)</li>
 *   <li>simple session metrics</li>
 *   <li>transient live condition values for the current step</li>
 * </ul>
 *
 * <p>Persisted atomically as JSON at
 * {@code ~/.runelite/guide-chain/state.json} (write-to-temp + atomic move).
 * Guide/manifest content and overrides are <em>not</em> persisted here —
 * their sources of truth are the guides source and the overrides directory;
 * they are pushed in via {@link #setPlan} / {@link #setOverrides}.
 *
 * <p>All mutations notify registered {@link Listener}s, so a web-view
 * mutation reflects in-game immediately (overlays read through this store).
 */
public final class GuideStore
{
    /** Fired after any observable change; {@code what} is a short tag. */
    public interface Listener
    {
        void storeChanged(GuideStore store, String what);
    }

    private static final Logger LOG = Logger.getLogger(GuideStore.class.getName());
    private static final int SCHEMA_VERSION = 1;

    private final File stateFile;
    private final Gson gson;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    // ── Persisted state ───────────────────────────────────────────────────────

    /** JSON shape of {@code state.json}. */
    static class State
    {
        int schemaVersion = SCHEMA_VERSION;
        String activeChainId;
        /** CHAIN_CONSOLIDATION.md §2 — filters {@code plan()} rendering only; null = {@link Lens#FULL}. */
        String activeLensId;
        int guideIndex;
        int stepIndex;
        Set<String> doneSteps    = new LinkedHashSet<>();
        Set<String> skippedSteps = new LinkedHashSet<>();
        Set<String> manualAcks   = new LinkedHashSet<>();
        /** Manually checked steps that are not pointer-done (persist across restarts). */
        Set<String> manualDone   = new LinkedHashSet<>();
        CharacterSnapshot character;
        SessionMetrics metrics = new SessionMetrics();

        /**
         * SYNTHESIS §S4 — RECURRING background steps. Keyed by the usual
         * {@code guideId/stepId}. Mirrors (standalone/store path) what the
         * RuneLite plugin additionally persists per-account as
         * {@code guidechain.bg.<stepId>.nextFireEpochMs} / {@code .lifecycleState}
         * — this map is what makes GuideWebMain work identically without
         * RuneLite's ConfigManager.
         */
        Map<String, Long> bgNextFireEpochMs = new LinkedHashMap<>();
        /** Live current lifecycle state per recurring step key (§S4). */
        Map<String, String> bgLifecycleState = new LinkedHashMap<>();
    }

    private State state = new State();

    // ── In-memory plan content (source of truth: guides source / overrides dir) ──

    private Manifest manifest = new Manifest();
    private final Map<String, Guide> guides = new LinkedHashMap<>();
    /** key {@code guideId + "/" + stepId} → override patch. */
    private final Map<String, StepOverride> overrides = new LinkedHashMap<>();

    // ── Transient live data (never persisted) ────────────────────────────────

    private boolean clientConnected;
    private String liveStepKey;
    private List<ConditionStatus> liveConditions = Collections.emptyList();
    private long liveUpdatedAtMs;

    // ── Construction / persistence ───────────────────────────────────────────

    public GuideStore(File baseDir, Gson gson)
    {
        this.gson = gson;
        this.stateFile = new File(baseDir, "state.json");
        load();
        state.metrics.sessionStartMs = System.currentTimeMillis();
    }

    public File stateFile()
    {
        return stateFile;
    }

    private void load()
    {
        if (!stateFile.exists()) return;
        try
        {
            String json = Files.readString(stateFile.toPath(), StandardCharsets.UTF_8);
            State loaded = gson.fromJson(json, State.class);
            if (loaded != null)
            {
                // null-safety for hand-edited or older files
                if (loaded.doneSteps == null)    loaded.doneSteps    = new LinkedHashSet<>();
                if (loaded.skippedSteps == null) loaded.skippedSteps = new LinkedHashSet<>();
                if (loaded.manualAcks == null)   loaded.manualAcks   = new LinkedHashSet<>();
                if (loaded.manualDone == null)   loaded.manualDone   = new LinkedHashSet<>();
                if (loaded.metrics == null)      loaded.metrics      = new SessionMetrics();
                if (loaded.bgNextFireEpochMs == null) loaded.bgNextFireEpochMs = new LinkedHashMap<>();
                if (loaded.bgLifecycleState == null)  loaded.bgLifecycleState  = new LinkedHashMap<>();
                state = loaded;
            }
        }
        catch (IOException | RuntimeException e)
        {
            LOG.log(Level.WARNING, "Could not read " + stateFile + " — starting fresh", e);
        }
    }

    /** Atomic persist: write a temp file next to state.json, then move over it. */
    private synchronized void save()
    {
        try
        {
            File dir = stateFile.getParentFile();
            if (dir != null) dir.mkdirs();
            Path tmp = new File(dir, "state.json.tmp").toPath();
            Files.writeString(tmp, gson.toJson(state), StandardCharsets.UTF_8);
            try
            {
                Files.move(tmp, stateFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException atomicUnsupported)
            {
                Files.move(tmp, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e)
        {
            LOG.log(Level.WARNING, "Could not persist " + stateFile, e);
        }
    }

    private void notify(String what)
    {
        for (Listener l : listeners)
        {
            try
            {
                l.storeChanged(this, what);
            }
            catch (RuntimeException e)
            {
                LOG.log(Level.WARNING, "Store listener failed for '" + what + "'", e);
            }
        }
    }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // ── Plan content ──────────────────────────────────────────────────────────

    /**
     * Push freshly loaded guide content in. Reconciles the persisted position:
     * resolves {@code activeChainId} (falling back to the first chain) and
     * clamps guide/step indices to the new bounds.
     */
    public synchronized void setPlan(Manifest manifest, Map<String, Guide> loadedGuides)
    {
        this.manifest = manifest != null ? manifest : new Manifest();
        this.guides.clear();
        if (loadedGuides != null) this.guides.putAll(loadedGuides);
        armAllRecurring();

        List<ChainEntry> chains = this.manifest.chains();
        if (!chains.isEmpty())
        {
            if (chainIndexOf(state.activeChainId) < 0)
            {
                state.activeChainId = chains.get(0).id;
                state.guideIndex = 0;
                state.stepIndex = 0;
            }
            clampPosition();
            skipOverlaySlotsAtCurrentPosition();
        }
        save();
        notify("plan");
    }

    /** Replace the override map (key {@code guideId/stepId}). */
    public synchronized void setOverrides(Map<String, StepOverride> map)
    {
        overrides.clear();
        if (map != null) overrides.putAll(map);
        notify("overrides");
    }

    // ── Chain selection ───────────────────────────────────────────────────────

    public synchronized List<ChainEntry> chains()
    {
        return manifest.chains();
    }

    public synchronized int chainIndex()
    {
        int i = chainIndexOf(state.activeChainId);
        return Math.max(i, 0);
    }

    public synchronized String activeChainId()
    {
        return state.activeChainId;
    }

    public synchronized ChainEntry activeChain()
    {
        List<ChainEntry> chains = manifest.chains();
        if (chains.isEmpty()) return null;
        int i = chainIndex();
        return i < chains.size() ? chains.get(i) : null;
    }

    public synchronized void selectChainByIndex(int index)
    {
        List<ChainEntry> chains = manifest.chains();
        if (chains.isEmpty()) return;
        int i = Math.max(0, Math.min(index, chains.size() - 1));
        selectChainById(chains.get(i).id);
    }

    /** Switch chains; position jumps to the first step not already done/skipped. */
    public synchronized void selectChainById(String chainId)
    {
        if (chainId == null || chainIndexOf(chainId) < 0) return;
        if (chainId.equals(state.activeChainId)) return; // no-op, avoids listener loops
        state.activeChainId = chainId;
        moveToFirstPending();
        save();
        notify("chain");
    }

    // ── Lens selection (CHAIN_CONSOLIDATION.md §2/§3, additive) ──────────────

    /**
     * Active lens id, defaulting to {@link Lens#FULL} — never null, so callers
     * never need their own fallback. Mirrors {@link #activeChainId()}, except
     * a lens always resolves (no manifest lookup can fail).
     */
    public synchronized String activeLensId()
    {
        return state.activeLensId != null ? state.activeLensId : Lens.FULL.id;
    }

    public synchronized Lens activeLens()
    {
        return Lens.byId(activeLensId());
    }

    /**
     * Switch the active lens. Filters what {@link #plan()}'s rendering shows
     * (via {@code WebFragments#planFragment()}) — never touches position,
     * marks, or any other progression state, so toggling lenses can neither
     * lose nor fork progress. Unknown ids resolve to {@link Lens#FULL}
     * (same defensive fallback {@link Lens#byId} always applies).
     */
    public synchronized void selectLensById(String lensId)
    {
        String resolved = Lens.byId(lensId).id;
        if (resolved.equals(activeLensId())) return; // no-op, avoids listener loops
        state.activeLensId = resolved;
        save();
        notify("lens");
    }

    // ── Position / navigation ─────────────────────────────────────────────────

    public synchronized int guideIndex() { return state.guideIndex; }
    public synchronized int stepIndex()  { return state.stepIndex; }

    /** Current guide, or {@code null} when nothing is loaded / chain done. */
    public synchronized Guide currentGuide()
    {
        ChainEntry chain = activeChain();
        if (chain == null) return null;
        List<String> files = chain.guides();
        if (state.guideIndex >= files.size()) return null;
        return guides.get(filenameToId(files.get(state.guideIndex)));
    }

    /** Current step with any override applied, or {@code null}. */
    public synchronized GuideStep currentStep()
    {
        Guide g = currentGuide();
        if (g == null) return null;
        List<GuideStep> steps = g.steps();
        if (state.stepIndex >= steps.size()) return null;
        return applyOverride(g.id, steps.get(state.stepIndex));
    }

    /** Mark key of the current step ({@code guideId/stepId}), or {@code null}. */
    public synchronized String currentStepKey()
    {
        Guide g = currentGuide();
        if (g == null) return null;
        List<GuideStep> steps = g.steps();
        if (state.stepIndex >= steps.size()) return null;
        return key(g.id, steps.get(state.stepIndex).id);
    }

    /** Move forward one step, crossing guide boundaries. */
    public synchronized boolean advance()
    {
        boolean moved = advanceInternal();
        save();
        notify("position");
        return moved;
    }

    /**
     * Move forward one *visitable* step. Raw-advances repeatedly past any
     * overlay-slot step (§1a/§S4: {@code slotType == "background"} or
     * {@code "passive"} — never a member of the main sequential path,
     * whether or not it is additionally RECURRING-conditioned), arming any
     * RECURRING one on first encounter so its loops-lane countdown starts
     * immediately even if {@link #armAllRecurring()} hasn't reached it yet.
     */
    private boolean advanceInternal()
    {
        boolean moved = stepForwardRaw();
        while (moved && atOverlaySlotStep())
        {
            armFirstTimeIfNeeded(currentGuide(), currentRawStep());
            moved = stepForwardRaw();
        }
        return moved;
    }

    /** Advance {@code state.stepIndex} by exactly one slot, crossing guide boundaries. */
    private boolean stepForwardRaw()
    {
        Guide g = currentGuide();
        if (g == null) return false;
        state.stepIndex++;
        if (state.stepIndex >= g.steps().size())
        {
            ChainEntry chain = activeChain();
            if (chain != null && state.guideIndex + 1 < chain.guides().size())
            {
                state.guideIndex++;
                state.stepIndex = 0;
                return true;
            }
            state.stepIndex = g.steps().size(); // cap at end (chain complete)
            return false;
        }
        return true;
    }

    /** True if the raw pointer currently sits on an overlay-slot (background/passive) step. */
    private boolean atOverlaySlotStep()
    {
        GuideStep s = currentRawStep();
        return s != null && s.isOverlaySlot();
    }

    /** The step at the raw pointer position, un-overridden, or null past the end. */
    private GuideStep currentRawStep()
    {
        Guide g = currentGuide();
        if (g == null) return null;
        List<GuideStep> steps = g.steps();
        return state.stepIndex < steps.size() ? steps.get(state.stepIndex) : null;
    }

    /**
     * Defensive sweep for positions set directly (not via {@link #advanceInternal()}
     * / {@link #back()}) — e.g. {@link #setPlan} resetting to {@code (0, 0)} on
     * first load. Walks the pointer forward off any overlay-slot step it lands on.
     */
    private void skipOverlaySlotsAtCurrentPosition()
    {
        while (atOverlaySlotStep())
        {
            armFirstTimeIfNeeded(currentGuide(), currentRawStep());
            if (!stepForwardRaw()) break;
        }
    }

    /**
     * Move back one step, crossing guide boundaries. Skips past overlay-slot
     * (background/passive) steps in reverse too (§1a/§S4 — symmetric with
     * {@link #advanceInternal()}; they never occupy the main pointer).
     */
    public synchronized void back()
    {
        boolean moved = stepBackRaw();
        while (moved && atOverlaySlotStep())
        {
            moved = stepBackRaw();
        }
        if (!moved) return;
        save();
        notify("position");
    }

    /** Move {@code state.stepIndex} back by exactly one slot, crossing guide boundaries. */
    private boolean stepBackRaw()
    {
        if (state.stepIndex > 0)
        {
            state.stepIndex--;
            return true;
        }
        if (state.guideIndex > 0)
        {
            ChainEntry chain = activeChain();
            if (chain != null)
            {
                state.guideIndex--;
                Guide g = guides.get(filenameToId(chain.guides().get(state.guideIndex)));
                state.stepIndex = g != null && !g.steps().isEmpty() ? g.steps().size() - 1 : 0;
                return true;
            }
        }
        return false; // already at the very first step
    }

    // ── Marks ─────────────────────────────────────────────────────────────────

    /**
     * Mark a step done. If it is the current step the position advances;
     * a MANUAL condition on it is recorded as acknowledged.
     *
     * <p>§S4: if the step is RECURRING, "done" never touches the main index
     * or the done/skip sets at all — it re-arms via {@link #completeRecurring}
     * instead (defensive: {@link #advanceInternal()} already keeps such steps
     * from ever becoming the current pointer, but this guard covers direct
     * calls with an explicit key too).
     */
    public synchronized void markDone(String stepKey)
    {
        if (stepKey == null) return;
        GuideStep step = rawStepByKey(stepKey);
        if (step != null && step.isRecurring())
        {
            completeRecurring(stepKey, step);
            return;
        }
        state.doneSteps.add(stepKey);
        state.skippedSteps.remove(stepKey);
        if (hasManualCondition(stepKey)) state.manualAcks.add(stepKey);
        state.metrics.stepsCompleted++;
        state.metrics.lastActionMs = System.currentTimeMillis();
        if (stepKey.equals(currentStepKey())) advanceInternal();
        save();
        notify("marks");
    }

    /** Mark a step skipped. If it is the current step the position advances. */
    public synchronized void markSkipped(String stepKey)
    {
        if (stepKey == null) return;
        state.skippedSteps.add(stepKey);
        state.doneSteps.remove(stepKey);
        state.metrics.stepsSkipped++;
        state.metrics.lastActionMs = System.currentTimeMillis();
        if (stepKey.equals(currentStepKey())) advanceInternal();
        save();
        notify("marks");
    }

    /**
     * Two-way checklist toggle.
     * <ul>
     *   <li>If the key is in {@code manualDone}: remove (uncheck).</li>
     *   <li>If the step is pointer-done (before the current position): rewind
     *       the position pointer to that step (accidental-check undo).</li>
     *   <li>Otherwise: add to {@code manualDone} (check).</li>
     * </ul>
     */
    public synchronized void toggle(String stepKey)
    {
        if (stepKey == null) return;
        GuideStep recurring = rawStepByKey(stepKey);
        if (recurring != null && recurring.isRecurring())
        {
            // Loops-lane "log now" reuses the same toggle entry point (task
            // requirement: one action calls GuideStore.toggle() either way).
            completeRecurring(stepKey, recurring);
            return;
        }
        if (state.manualDone.contains(stepKey))
        {
            state.manualDone.remove(stepKey);
            save();
            notify("marks");
            return;
        }
        int[] pos = findStepPosition(stepKey);
        if (pos != null && isBeforeCurrent(pos))
        {
            // Rewind pointer — the checkbox was accidentally ticked for an already-done step
            state.guideIndex = pos[0];
            state.stepIndex  = pos[1];
            save();
            notify("position");
            return;
        }
        state.manualDone.add(stepKey);
        save();
        notify("marks");
    }

    /** Find [guideIndex, stepIndex] of a step by key, or null if not found. */
    private int[] findStepPosition(String stepKey)
    {
        ChainEntry chain = activeChain();
        if (chain == null) return null;
        List<String> files = chain.guides();
        for (int gi = 0; gi < files.size(); gi++)
        {
            Guide g = guides.get(filenameToId(files.get(gi)));
            if (g == null) continue;
            List<GuideStep> steps = g.steps();
            for (int si = 0; si < steps.size(); si++)
            {
                if (stepKey.equals(key(g.id, steps.get(si).id))) return new int[]{gi, si};
            }
        }
        return null;
    }

    private boolean isBeforeCurrent(int[] pos)
    {
        if (pos[0] < state.guideIndex) return true;
        return pos[0] == state.guideIndex && pos[1] < state.stepIndex;
    }

    /** Raw (un-overridden) step for a mark key, or null if not found in the active chain. */
    private GuideStep rawStepByKey(String stepKey)
    {
        int[] pos = findStepPosition(stepKey);
        if (pos == null) return null;
        ChainEntry chain = activeChain();
        if (chain == null) return null;
        List<String> files = chain.guides();
        if (pos[0] >= files.size()) return null;
        Guide g = guides.get(filenameToId(files.get(pos[0])));
        if (g == null) return null;
        List<GuideStep> steps = g.steps();
        return pos[1] < steps.size() ? steps.get(pos[1]) : null;
    }

    // ── Background / RECURRING loops (§S4) ───────────────────────────────────

    /**
     * "Completes" a RECURRING background step: NEVER advances the main index
     * (callers must not call {@link #advanceInternal()} around this). Re-arms
     * at {@code now + cadenceMinutes} and cycles {@link GuideStep#lifecycleState}
     * through its authored {@code "a->b->c"} template. No-op (still notifies)
     * if the step has no numeric cadence and/or no lifecycle template — the
     * loops-lane UI then just shows "no fixed cadence" / no lifecycle badge.
     */
    public synchronized void completeRecurring(String stepKey, GuideStep step)
    {
        if (stepKey == null || step == null) return;
        int cadence = step.recurringCadenceMinutes();
        if (cadence > 0)
        {
            state.bgNextFireEpochMs.put(stepKey, System.currentTimeMillis() + cadence * 60_000L);
        }
        String next = cycleLifecycle(step.lifecycleState, state.bgLifecycleState.get(stepKey));
        if (next != null) state.bgLifecycleState.put(stepKey, next);
        state.metrics.stepsCompleted++;
        state.metrics.lastActionMs = System.currentTimeMillis();
        save();
        notify("bg");
    }

    /** Epoch ms of this recurring step's next fire, or null if never armed / not recurring. */
    public synchronized Long nextFireEpochMs(String stepKey)
    {
        return state.bgNextFireEpochMs.get(stepKey);
    }

    /** Current live lifecycle state of a recurring step, or null if unarmed / no template. */
    public synchronized String liveLifecycleState(String stepKey)
    {
        return state.bgLifecycleState.get(stepKey);
    }

    /**
     * Hydrate recurring timer/lifecycle state from an external source — the
     * RuneLite plugin calls this at login with values read from its
     * per-account {@code guidechain.bg.<stepId>.*} config keys (§S4). Config
     * is authoritative once the player is logged in, so this overwrites;
     * null components leave the corresponding field untouched (config had no
     * value yet — e.g. first-ever login for this account).
     */
    public synchronized void seedBackgroundState(String stepKey, Long nextFireEpochMs, String lifecycleState)
    {
        if (stepKey == null) return;
        if (nextFireEpochMs != null) state.bgNextFireEpochMs.put(stepKey, nextFireEpochMs);
        if (lifecycleState != null) state.bgLifecycleState.put(stepKey, lifecycleState);
    }

    /**
     * Arms every RECURRING step across every loaded guide so the loops-lane
     * UI can show a countdown for all of them regardless of where the main
     * pointer sits. Idempotent — a step already carrying a timer or a live
     * lifecycle value is left untouched (real progress, or a login hydration
     * that already ran, must never be clobbered by a re-load).
     */
    private void armAllRecurring()
    {
        for (Guide g : guides.values())
        {
            for (GuideStep s : g.steps())
            {
                armFirstTimeIfNeeded(g, s);
            }
        }
    }

    /** Seeds a fresh timer/lifecycle baseline for one recurring step, unless it already has one. */
    private void armFirstTimeIfNeeded(Guide g, GuideStep step)
    {
        if (g == null || step == null || !step.isRecurring()) return;
        String k = key(g.id, step.id);
        boolean hasTimer = state.bgNextFireEpochMs.containsKey(k);
        boolean hasState = state.bgLifecycleState.containsKey(k);
        if (hasTimer && hasState) return;
        if (!hasTimer)
        {
            int cadence = step.recurringCadenceMinutes();
            if (cadence > 0) state.bgNextFireEpochMs.put(k, System.currentTimeMillis() + cadence * 60_000L);
        }
        if (!hasState)
        {
            String initial = initialLifecycleState(step.lifecycleState);
            if (initial != null) state.bgLifecycleState.put(k, initial);
        }
    }

    /** First state of an author-declared {@code "a->b->c"} lifecycle template, or null. */
    private static String initialLifecycleState(String template)
    {
        if (template == null || template.isEmpty()) return null;
        String[] states = template.split("->");
        return states.length > 0 ? states[0].trim() : null;
    }

    /** Next state after {@code current} in an author-declared {@code "a->b->c"} template (wraps). */
    private static String cycleLifecycle(String template, String current)
    {
        if (template == null || template.isEmpty()) return null;
        String[] states = template.split("->");
        if (states.length == 0) return null;
        for (int i = 0; i < states.length; i++) states[i] = states[i].trim();
        int idx = -1;
        for (int i = 0; i < states.length; i++)
        {
            if (states[i].equals(current)) { idx = i; break; }
        }
        if (idx < 0) idx = 0; // first-ever completion: current == null, cycle from the start
        return states[(idx + 1) % states.length];
    }

    public synchronized boolean isDone(String stepKey)    { return state.doneSteps.contains(stepKey); }
    public synchronized boolean isSkipped(String stepKey) { return state.skippedSteps.contains(stepKey); }

    /** Count a mutation that arrived through the web surface. */
    public synchronized void recordWebAction()
    {
        state.metrics.webActions++;
        state.metrics.lastActionMs = System.currentTimeMillis();
        // saved by the accompanying mutation; avoid double write here
    }

    // ── Flattened plan ────────────────────────────────────────────────────────

    /** Ordered rows of the active chain with derived per-row status. */
    public synchronized List<PlanRow> plan()
    {
        List<PlanRow> rows = new ArrayList<>();
        ChainEntry chain = activeChain();
        if (chain == null) return rows;
        String curKey = currentStepKey();
        int n = 0;
        boolean seenCurrent = false;
        for (String file : chain.guides())
        {
            Guide g = guides.get(filenameToId(file));
            if (g == null) continue;
            for (GuideStep s : g.steps())
            {
                PlanRow row = new PlanRow();
                row.globalIndex = ++n;
                row.guideId = g.id;
                row.guideName = g.name != null ? g.name : g.id;
                row.key = key(g.id, s.id);
                row.step = applyOverride(g.id, s);
                if (row.key.equals(curKey) && !seenCurrent)
                {
                    row.status = PlanRow.Status.CURRENT;
                    seenCurrent = true;
                }
                else if (state.skippedSteps.contains(row.key))
                {
                    row.status = PlanRow.Status.SKIPPED;
                }
                else if (state.doneSteps.contains(row.key) || state.manualDone.contains(row.key)
                        || !seenCurrent && curKey != null)
                {
                    // anything before the current position counts as done;
                    // manualDone covers checkbox-only marks
                    row.status = PlanRow.Status.DONE;
                }
                else
                {
                    row.status = PlanRow.Status.PENDING;
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Row for one step key, or {@code null}. */
    public synchronized PlanRow planRow(String stepKey)
    {
        for (PlanRow r : plan())
        {
            if (r.key.equals(stepKey)) return r;
        }
        return null;
    }

    public synchronized int totalSteps()
    {
        int total = 0;
        ChainEntry chain = activeChain();
        if (chain == null) return 0;
        for (String f : chain.guides())
        {
            Guide g = guides.get(filenameToId(f));
            if (g != null) total += g.steps().size();
        }
        return total;
    }

    /** 1-based number of the current step across the whole chain. */
    public synchronized int globalStepNumber()
    {
        ChainEntry chain = activeChain();
        if (chain == null) return 0;
        int base = 0;
        for (int i = 0; i < state.guideIndex && i < chain.guides().size(); i++)
        {
            Guide g = guides.get(filenameToId(chain.guides().get(i)));
            if (g != null) base += g.steps().size();
        }
        return base + state.stepIndex + 1;
    }

    public synchronized boolean isChainComplete()
    {
        ChainEntry chain = activeChain();
        if (chain == null) return true;
        Guide g = currentGuide();
        if (g == null) return true;
        boolean lastGuide = state.guideIndex == chain.guides().size() - 1;
        return lastGuide && state.stepIndex >= g.steps().size();
    }

    // ── Character snapshot / live values (pushed in by the plugin) ───────────

    /** Persists only when the snapshot content actually changed. */
    public synchronized void updateCharacter(CharacterSnapshot snap)
    {
        if (snap == null) return;
        boolean changed = state.character == null || !snap.sameContentAs(state.character);
        state.character = snap;
        if (changed)
        {
            save();
            notify("character");
        }
    }

    public synchronized CharacterSnapshot character()
    {
        return state.character;
    }

    /** Push live condition evaluations for the current step (transient). */
    public synchronized void updateLiveConditions(String stepKey, List<ConditionStatus> conditions)
    {
        this.liveStepKey = stepKey;
        this.liveConditions = conditions != null ? conditions : Collections.emptyList();
        this.liveUpdatedAtMs = System.currentTimeMillis();
        this.clientConnected = true;
    }

    public synchronized void setClientConnected(boolean connected)
    {
        this.clientConnected = connected;
    }

    public synchronized boolean isClientConnected()
    {
        return clientConnected;
    }

    /** Live conditions for a step key, or {@code null} if unavailable/stale. */
    public synchronized List<ConditionStatus> liveConditionsFor(String stepKey)
    {
        if (!clientConnected || stepKey == null || !stepKey.equals(liveStepKey)) return null;
        return liveConditions;
    }

    public synchronized SessionMetrics metrics()
    {
        return state.metrics;
    }

    public synchronized Set<String> doneSteps()    { return new LinkedHashSet<>(state.doneSteps); }
    public synchronized Set<String> skippedSteps() { return new LinkedHashSet<>(state.skippedSteps); }
    public synchronized Set<String> manualAcks()   { return new LinkedHashSet<>(state.manualAcks); }
    public synchronized Set<String> manualDone()   { return new LinkedHashSet<>(state.manualDone); }

    // ── Machine-readable full state (GET /api/state.json) ────────────────────

    public synchronized JsonObject stateJson()
    {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("generatedAtMs", System.currentTimeMillis());
        root.addProperty("clientConnected", clientConnected);
        root.addProperty("activeChainId", state.activeChainId);
        ChainEntry chain = activeChain();
        root.addProperty("activeChainName", chain != null ? chain.name : null);
        root.add("chains", gson.toJsonTree(manifest.chains()));

        JsonObject pos = new JsonObject();
        pos.addProperty("guideIndex", state.guideIndex);
        pos.addProperty("stepIndex", state.stepIndex);
        pos.addProperty("globalStep", globalStepNumber());
        pos.addProperty("totalSteps", totalSteps());
        pos.addProperty("chainComplete", isChainComplete());
        root.add("position", pos);

        root.addProperty("currentStepKey", currentStepKey());
        root.add("currentStep", gson.toJsonTree(currentStep()));

        List<JsonObject> planRows = new ArrayList<>();
        for (PlanRow r : plan())
        {
            JsonObject o = new JsonObject();
            o.addProperty("index", r.globalIndex);
            o.addProperty("key", r.key);
            o.addProperty("guideId", r.guideId);
            o.addProperty("instruction", r.step.instruction);
            o.addProperty("status", r.status.name().toLowerCase());
            planRows.add(o);
        }
        root.add("plan", gson.toJsonTree(planRows));

        JsonObject marks = new JsonObject();
        marks.add("done", gson.toJsonTree(state.doneSteps));
        marks.add("skipped", gson.toJsonTree(state.skippedSteps));
        marks.add("manualAcks", gson.toJsonTree(state.manualAcks));
        root.add("marks", marks);

        root.add("character", gson.toJsonTree(state.character));
        root.add("metrics", gson.toJsonTree(state.metrics));
        root.addProperty("liveUpdatedAtMs", liveUpdatedAtMs);
        root.add("liveConditions",
            gson.toJsonTree(liveConditionsFor(currentStepKey())));

        // §S4 — RECURRING background loops (loops-lane countdown source).
        JsonObject bg = new JsonObject();
        bg.add("nextFireEpochMs", gson.toJsonTree(state.bgNextFireEpochMs));
        bg.add("lifecycleState", gson.toJsonTree(state.bgLifecycleState));
        root.add("bg", bg);
        return root;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static String key(String guideId, String stepId)
    {
        return guideId + "/" + stepId;
    }

    private int chainIndexOf(String chainId)
    {
        if (chainId == null) return -1;
        List<ChainEntry> chains = manifest.chains();
        for (int i = 0; i < chains.size(); i++)
        {
            if (chainId.equals(chains.get(i).id)) return i;
        }
        return -1;
    }

    private void clampPosition()
    {
        ChainEntry chain = activeChain();
        if (chain == null) return;
        List<String> files = chain.guides();
        if (state.guideIndex > files.size()) state.guideIndex = files.size() > 0 ? files.size() - 1 : 0;
        if (state.guideIndex < 0) state.guideIndex = 0;
        if (state.guideIndex < files.size())
        {
            Guide g = guides.get(filenameToId(files.get(state.guideIndex)));
            int max = g != null ? g.steps().size() : 0;
            if (state.stepIndex > max) state.stepIndex = max;
            if (state.stepIndex < 0) state.stepIndex = 0;
        }
    }

    private void moveToFirstPending()
    {
        state.guideIndex = 0;
        state.stepIndex = 0;
        ChainEntry chain = activeChain();
        if (chain == null) return;
        List<String> files = chain.guides();
        for (int gi = 0; gi < files.size(); gi++)
        {
            Guide g = guides.get(filenameToId(files.get(gi)));
            if (g == null) continue;
            List<GuideStep> steps = g.steps();
            for (int si = 0; si < steps.size(); si++)
            {
                GuideStep s = steps.get(si);
                // §1a/§S4: overlay-slot (background/passive) steps never occupy the main pointer.
                if (s.isOverlaySlot()) continue;
                String k = key(g.id, s.id);
                if (!state.doneSteps.contains(k) && !state.skippedSteps.contains(k))
                {
                    state.guideIndex = gi;
                    state.stepIndex = si;
                    return;
                }
            }
        }
        // everything done — park at the end
        state.guideIndex = Math.max(0, files.size() - 1);
        Guide last = files.isEmpty() ? null : guides.get(filenameToId(files.get(state.guideIndex)));
        state.stepIndex = last != null ? last.steps().size() : 0;
    }

    private boolean hasManualCondition(String stepKey)
    {
        PlanRow row = planRow(stepKey);
        if (row == null) return false;
        for (CompletionCondition c : row.step.completionConditions())
        {
            if (c.type == ConditionType.MANUAL) return true;
        }
        return false;
    }

    private GuideStep applyOverride(String guideId, GuideStep step)
    {
        if (step == null) return null;
        StepOverride ov = overrides.get(key(guideId, step.id));
        if (ov == null) return step;
        GuideStep patched = new GuideStep();
        patched.id                   = step.id;
        patched.instruction          = ov.instruction          != null ? ov.instruction          : step.instruction;
        patched.detail               = ov.detail               != null ? ov.detail               : step.detail;
        patched.highlights           = ov.highlights           != null ? ov.highlights           : step.highlights;
        patched.completionConditions = ov.completionConditions != null ? ov.completionConditions : step.completionConditions;
        patched.mapMarkers           = ov.mapMarkers           != null ? ov.mapMarkers           : step.mapMarkers;
        patched.hints                = step.hints;    // additive pass-through; overrides cannot clear hints
        patched.checkpoint           = step.checkpoint;
        patched.refs                 = step.refs;     // additive pass-through; overrides cannot clear refs
        patched.media                = step.media;    // additive pass-through; overrides cannot clear media (FRAMES_GALLERY §1)
        // §S4/§1e — sequencer+background+steer fields: additive pass-through, same as
        // hints/checkpoint/refs above. A StepOverride patch can't clear these; if it
        // could, an override on a background step could silently escape the loops
        // lane / RECURRING re-arm semantics it's still subject to.
        patched.slotType             = step.slotType;
        patched.cadenceMinutes       = step.cadenceMinutes;
        patched.lifecycleState       = step.lifecycleState;
        patched.passiveOverlays      = step.passiveOverlays;
        patched.supplyChain          = step.supplyChain;
        patched.steerKind            = step.steerKind;
        return patched;
    }

    private static String filenameToId(String filename)
    {
        return filename.endsWith(".json")
            ? filename.substring(0, filename.length() - 5)
            : filename;
    }
}
