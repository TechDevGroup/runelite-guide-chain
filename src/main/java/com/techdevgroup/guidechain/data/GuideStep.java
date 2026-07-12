package com.techdevgroup.guidechain.data;

import java.util.Collections;
import java.util.List;
import com.techdevgroup.guidechain.data.GuideRef;

/**
 * One atomic step within a {@link Guide}.
 *
 * <p>Steps auto-advance when ALL {@code completionConditions} evaluate to true
 * on a game tick. If any condition is {@link ConditionType#MANUAL} and no
 * automatic condition fires first, the step waits for the player to click
 * "Done" or "Skip" in the panel.
 */
public class GuideStep
{
    /** Unique identifier used by the override system. */
    public String id;

    /**
     * Optional episode/phase this step belongs to (e.g. "Toward Monkey Madness I").
     * Steps sharing a phase render under one heading — the guide reads as ordered
     * milestone episodes rather than a flat list of skill bands. Null = ungrouped.
     */
    public String phase;

    /**
     * Short, imperative instruction shown in the panel.
     * Must be written in the author's own words — never copied from the wiki
     * or game dialogue.
     */
    public String instruction;

    /** Optional longer detail shown below the instruction. */
    public String detail;

    /** Entities to highlight while this step is active. */
    public List<HighlightTarget> highlights;

    /** Map pins placed while this step is active. */
    public List<MapMarker> mapMarkers;

    /**
     * Conditions that trigger auto-advance.  An empty list means the step
     * only advances via Skip/Done (equivalent to a single MANUAL condition).
     */
    public List<CompletionCondition> completionConditions;

    /**
     * Optional execution hints (GRANULARITY ��4). Rendered as small chips
     * under the step card. Removing all hints must leave a still-completable
     * step — hints are advisory only. Null/empty = no chips rendered.
     */
    public List<GuideHint> hints;

    /**
     * Optional checkpoint group label (GRANULARITY §3a). Non-null on the
     * header record and all member steps of a checkpoint group. The web view
     * renders a {@code .checkpoint-divider} before the first step whose
     * checkpoint value differs from the previous step's. Null = ungrouped.
     */
    public String checkpoint;

    /**
     * Optional wiki citations shown as chip links in the web view.
     * Null/empty = no chips rendered. Additive-nullable: plugin-safe.
     */
    public List<GuideRef> refs;

    /**
     * Optional captured frames/gifs (FRAMES_GALLERY §1). Null/empty = no
     * gallery pane rendered for this step. Additive-nullable: plugin-safe,
     * same pattern as {@link #refs}. Order is display order; entry 0 is the
     * step's thumbnail wherever a single image is wanted.
     */
    public List<GuideMedia> media;

    // ── SYNTHESIS §1e — sequencer + background + steer (Lane 4 owns the Java side) ──

    /**
     * {@code "background" | "passive" | "alternation" | null} (normal step).
     * Drives panel lane: {@code "background"} steps render in the loops-lane
     * sub-list (never as the main-index current step); {@code "passive"}
     * steps never render as their own card at all (§1a) — their content
     * surfaces only via {@link #passiveOverlays} badges on a host step.
     */
    public String slotType;

    /**
     * Real-world minutes between recurrences. Null = event-driven / one-shot
     * (stays MANUAL — see {@link ConditionType#RECURRING}). This is the
     * step-level default; a {@link CompletionCondition#cadenceMinutes} on a
     * RECURRING condition, if positive, takes precedence — see {@link
     * #recurringCadenceMinutes()}.
     */
    public Integer cadenceMinutes;

    /**
     * Author-declared lifecycle template as an arrow-separated cycle, e.g.
     * {@code "idle->active"}. The *live*, per-account current state is
     * tracked separately by {@code GuideStore} (persisted; see SYNTHESIS
     * §S4) — this field is only the static shape of the cycle.
     */
    public String lifecycleState;

    /**
     * Pre-resolved LABELS of passive embeds active on this host step (badge
     * render only — pattern 6). Null/empty = no badges.
     */
    public List<String> passiveOverlays;

    /** Named supply chain this step belongs to; plugin groups as a collapsible section. */
    public String supplyChain;

    /** Steer-point kind for badge decoration; null on regular steps. */
    public String steerKind;

    /**
     * Optional 1:many training methods for a skill band (skill-methods
     * normalizer). When present the step renders a method PICKER — several
     * activity options with their own location / xp-hr / reqs / wiki
     * breadcrumbs — instead of a single cited {@link #detail} line (the
     * "no sloppy detail-text" contract). Null/empty = plain step.
     * Additive-nullable, plugin-safe (same pattern as {@link #refs}).
     */
    public List<TrainMethod> methods;

    /** One activity option for a training band; xp_hr/members are "??" when the wiki never stated them. */
    public static final class TrainMethod
    {
        public String method;
        public String location;
        public String level_band;
        /** Boolean, or the string "??" when unknown. */
        public Object members;
        /** Number, or the string "??" when the page never stated a rate. */
        public Object xp_hr;
        /** Raw {skills:{}, quests:[], items:[]} gate, summarized for display like reqs elsewhere. */
        public Object reqs;
        public List<GuideRef> refs;

        public List<GuideRef> refs()
        {
            return refs != null ? refs : Collections.emptyList();
        }
    }

    /**
     * Opportunistic-granularity sub-checklist (GRANULARITY W3 Faux-grain
     * atoms). ATTACH model, not flat-injection: this step (a quest- or
     * train- coarse step) stays the routing/grant anchor; its atoms hang underneath
     * it as an equal-grade granular breakdown. Null/empty = plain step, same
     * additive-nullable contract as {@link #methods}. Attached only when the
     * route opts in via {@code goal.granular:true} (enrich.py).
     */
    public SubChecklist subChecklist;

    /** A step's granular action list + checkpoint dividers (subChecklist{} on the enrich.py side). */
    public static final class SubChecklist
    {
        public List<SubStep> atoms;
        public List<Checkpoint> checkpoints;

        public List<SubStep> atoms()
        {
            return atoms != null ? atoms : Collections.emptyList();
        }

        public List<Checkpoint> checkpoints()
        {
            return checkpoints != null ? checkpoints : Collections.emptyList();
        }
    }

    /**
     * One granular action within a {@link SubChecklist} (steps_oppgran.jsonl
     * atom row, projected to its render-relevant fields by enrich.py's
     * {@code _project_subchecklist_atom}).
     */
    public static final class SubStep
    {
        public String id;
        /** Instruction text, in the atom's own words (mirrors {@link GuideStep#instruction}). */
        public String label;
        public String detail;
        public Atom atom;
        public List<GuideHint> hints;
        public List<GuideRef> refs;
        /** Raw {item: qty, ...} maps — kept as Object, shape varies per atom (see reqs elsewhere). */
        public Object produces;
        public Object consumes;
        /** Raw {region, zone, quest_gate, quest_phase} location descriptor. */
        public Object location;
        /** access | gather | produce | quest | train | unlock. */
        public String kind;

        public List<GuideHint> hints()
        {
            return hints != null ? hints : Collections.emptyList();
        }

        public List<GuideRef> refs()
        {
            return refs != null ? refs : Collections.emptyList();
        }
    }

    /** verb/target/count/cmp/until action descriptor (GRANULARITY atom{}). */
    public static final class Atom
    {
        public String verb;
        public String target;
        /** Null for state-only atoms (talk-to, walk-to, equip, ...). */
        public Integer count;
        /** eq | gte | null. */
        public String cmp;
        /**
         * Raw completion-state gate — polymorphic ({@code {item:...}} |
         * {@code {skill:...}} | {@code {state:...}} | {@code {drop:...}}),
         * kept as Object like {@link TrainMethod#reqs}.
         */
        public Object until;
    }

    /** One checkpoint divider within a {@link SubChecklist}; {@code start} is its first member atom's id. */
    public static final class Checkpoint
    {
        public String label;
        public String start;
    }

    // ── Convenience helpers ────────────────────────────────────────────────────

    public List<HighlightTarget> highlights()
    {
        return highlights != null ? highlights : Collections.emptyList();
    }

    public List<MapMarker> mapMarkers()
    {
        return mapMarkers != null ? mapMarkers : Collections.emptyList();
    }

    public List<CompletionCondition> completionConditions()
    {
        return completionConditions != null ? completionConditions : Collections.emptyList();
    }

    public List<GuideHint> hints()
    {
        return hints != null ? hints : Collections.emptyList();
    }

    public List<GuideRef> refs()
    {
        return refs != null ? refs : Collections.emptyList();
    }

    public List<GuideMedia> media()
    {
        return media != null ? media : Collections.emptyList();
    }

    public List<String> passiveOverlays()
    {
        return passiveOverlays != null ? passiveOverlays : Collections.emptyList();
    }

    /**
     * True for {@code slotType == "background"} or {@code "passive"} — steps
     * that are overlay content, never a member of the main sequential path
     * (§1a: "passive steps never render as their own card"; background steps
     * render only in the loops lane). {@code GuideStore} uses this so such a
     * step can never become the "current" pointer, whether or not it is
     * additionally RECURRING-conditioned — an event-driven background step
     * (cadence null, plain MANUAL condition) is still overlay content, just
     * without a timer to arm.
     */
    public boolean isOverlaySlot()
    {
        return "background".equals(slotType) || "passive".equals(slotType);
    }

    /**
     * True iff this step's conditions include {@link ConditionType#RECURRING}
     * (SYNTHESIS §S4) — such a step never occupies the main guide pointer;
     * {@code GuideStore} arms/re-arms it in the loops lane instead.
     */
    public boolean isRecurring()
    {
        for (CompletionCondition c : completionConditions())
        {
            if (c.type == ConditionType.RECURRING) return true;
        }
        return false;
    }

    /**
     * Resolved cadence in minutes: the first positive {@code cadenceMinutes}
     * found on a RECURRING condition, else this step's own {@link
     * #cadenceMinutes}, else 0 (event-driven — no countdown, manual only).
     */
    public int recurringCadenceMinutes()
    {
        for (CompletionCondition c : completionConditions())
        {
            if (c.type == ConditionType.RECURRING && c.cadenceMinutes > 0) return c.cadenceMinutes;
        }
        return cadenceMinutes != null ? cadenceMinutes : 0;
    }
}
