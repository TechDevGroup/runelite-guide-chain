package com.techdevgroup.guidechain.store;

import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.ConditionType;
import com.techdevgroup.guidechain.data.GuideStep;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A pure predicate over one spine step (CHAIN_CONSOLIDATION.md §2). Filters
 * what {@code WebFragments#planFragment()} renders; never touches
 * progression — {@link GuideStore#plan()} always walks the FULL spine keyed
 * by step id, and a lens toggle can neither lose nor fork that state.
 *
 * <p>Predicates ported verbatim from the proven spike
 * ({@code tools/guide-export/spikes/lens-spike.mjs}) — id-prefix conventions
 * the spine's steps already carry, no new hand-labeling.
 *
 * <p>This enum IS the lookup table (CLAUDE.md: lookup tables over if-ladders).
 */
public enum Lens
{
    FULL("full", "All", s -> true),
    QUESTS("quests", "Quests", Lens::isQuestStep),
    ORIGIN("origin", "Origin", Lens::isOriginStep),
    MILESTONES("milestones", "Milestones", Lens::isMilestoneStep),
    TRAINING("training", "Training", Lens::isTrainingStep);

    private static final Pattern QUEST_ID_PREFIX = Pattern.compile("^(quest|rfd)-");
    private static final Pattern ORIGIN_ID_PREFIX = Pattern.compile("^(ori|chkpt-origin)-");
    private static final String MILESTONE_ID_PREFIX = "milestone-";

    /** Stable id used in {@code hx-vals}, persisted state, and the route param. */
    public final String id;

    /** Segmented-control button label. */
    public final String label;

    private final Predicate<GuideStep> predicate;

    Lens(String id, String label, Predicate<GuideStep> predicate)
    {
        this.id = id;
        this.label = label;
        this.predicate = predicate;
    }

    public boolean matches(GuideStep step)
    {
        return step != null && predicate.test(step);
    }

    /** Resolves an id from a request/persisted value; unknown or null falls back to {@link #FULL}. */
    public static Lens byId(String id)
    {
        for (Lens l : values())
        {
            if (l.id.equals(id)) return l;
        }
        return FULL;
    }

    // ── Predicates (verbatim port of lens-spike.mjs LENSES) ─────────────────

    private static boolean isQuestStep(GuideStep s)
    {
        return s.id != null && QUEST_ID_PREFIX.matcher(s.id).find();
    }

    private static boolean isOriginStep(GuideStep s)
    {
        return s.id != null && ORIGIN_ID_PREFIX.matcher(s.id).find();
    }

    private static boolean isMilestoneStep(GuideStep s)
    {
        if (s.id != null && s.id.startsWith(MILESTONE_ID_PREFIX)) return true;
        return s.checkpoint != null;
    }

    private static boolean isTrainingStep(GuideStep s)
    {
        for (CompletionCondition c : s.completionConditions())
        {
            if (c.type == ConditionType.SKILL) return true;
        }
        return false;
    }
}
