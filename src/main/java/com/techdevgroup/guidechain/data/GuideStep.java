package com.techdevgroup.guidechain.data;

import java.util.Collections;
import java.util.List;

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
}
