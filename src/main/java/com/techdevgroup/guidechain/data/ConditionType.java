package com.techdevgroup.guidechain.data;

/** Discriminator for a {@link CompletionCondition}. */
public enum ConditionType
{
    /** Quest is in a given state (NOT_STARTED / IN_PROGRESS / FINISHED). */
    QUEST,
    /** A varbit satisfies a numeric comparison. */
    VARBIT,
    /** The player's real level in a skill meets a threshold. */
    SKILL,
    /** The player holds a minimum quantity of an item in their inventory. */
    ITEM_HELD,
    /** The player is inside a given map region. */
    REGION,
    /**
     * No automatic check — the step advances only when the player presses
     * the "Done" / "Skip" button in the panel.
     */
    MANUAL
}
