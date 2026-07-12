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
    MANUAL,
    /**
     * Marks a background/loop step (SYNTHESIS §S4). Like {@link #MANUAL} this
     * is never itself auto-evaluated — it is a completion-semantics marker:
     * a step whose conditions include RECURRING never advances the main
     * guide index. "Completing" it (its VARBIT/ITEM_HELD sibling conditions
     * firing, or a manual click in the loops-lane) re-arms it at
     * {@code now + cadenceMinutes} and cycles {@code GuideStep.lifecycleState}
     * instead. See {@link com.techdevgroup.guidechain.store.GuideStore}.
     */
    RECURRING
}
