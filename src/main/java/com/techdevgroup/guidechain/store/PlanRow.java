package com.techdevgroup.guidechain.store;

import com.techdevgroup.guidechain.data.GuideStep;

/**
 * One flattened row of the active chain's plan — a step with its position,
 * stable key, and derived status. Computed on demand by
 * {@link GuideStore#plan()}; never persisted.
 */
public class PlanRow
{
    /** Row status derived from position + marks. */
    public enum Status { CURRENT, DONE, SKIPPED, PENDING }

    /** 1-based position across the whole chain. */
    public int globalIndex;

    /** Guide this step belongs to. */
    public String guideId;
    public String guideName;

    /** Stable mark key: {@code guideId + "/" + stepId}. */
    public String key;

    /** The step, with any local override already applied. */
    public GuideStep step;

    public Status status;
}
