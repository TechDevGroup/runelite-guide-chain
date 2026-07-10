package com.techdevgroup.guidechain.data;

import java.util.List;

/**
 * A partial patch for one step loaded from
 * {@code ~/.runelite/guide-chain/overrides/<guideId>/<stepId>.json}.
 *
 * Only non-null fields replace the corresponding field on the live step.
 * This lets a local Claude agent fix a broken step without editing the
 * upstream guide file.
 */
public class StepOverride
{
    /** Guide this override belongs to. */
    public String guideId;
    /** Step this override targets. */
    public String stepId;

    /** If non-null, replaces the step's instruction. */
    public String instruction;
    /** If non-null, replaces the step's detail. */
    public String detail;
    /** If non-null, replaces the full highlights list. */
    public List<HighlightTarget> highlights;
    /** If non-null, replaces the full completionConditions list. */
    public List<CompletionCondition> completionConditions;
    /** If non-null, replaces the full mapMarkers list. */
    public List<MapMarker> mapMarkers;
}
