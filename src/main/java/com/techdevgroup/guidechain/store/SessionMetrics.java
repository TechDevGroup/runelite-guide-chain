package com.techdevgroup.guidechain.store;

/**
 * Simple counters kept by the {@link GuideStore}. Counters are cumulative
 * across sessions; {@code sessionStartMs} is reset each time the store boots.
 */
public class SessionMetrics
{
    /** Epoch millis when the current store instance was created. */
    public long sessionStartMs;

    /** Steps marked done (auto-advance or Done button, any surface). */
    public int stepsCompleted;

    /** Steps explicitly skipped. */
    public int stepsSkipped;

    /** Mutations that arrived through the web surface. */
    public int webActions;

    /** Epoch millis of the most recent mutation. */
    public long lastActionMs;
}
