package com.techdevgroup.guidechain.store;

/**
 * Live evaluation of one completion condition of the current step, pushed
 * into the {@link GuideStore} by the plugin each game tick. Transient —
 * never persisted. When no client is connected, fragments render
 * {@code "client offline"} instead.
 */
public class ConditionStatus
{
    /** Condition type name (QUEST / VARBIT / SKILL / ITEM_HELD / REGION / MANUAL). */
    public String type;

    /** Human-readable target value, e.g. {@code ">= 15"}. */
    public String expected;

    /** Human-readable live value read from the client. */
    public String live;

    /** Whether the condition currently evaluates true. */
    public boolean met;

    public ConditionStatus() {}

    public ConditionStatus(String type, String expected, String live, boolean met)
    {
        this.type = type;
        this.expected = expected;
        this.live = live;
        this.met = met;
    }
}
