package com.techdevgroup.guidechain.data;

/**
 * A single condition that must be true for a step to be considered complete.
 * All conditions in a step's {@code completionConditions} list must be
 * simultaneously satisfied before auto-advance fires.
 *
 * <pre>
 * QUEST     → questName (exact Quest enum name, e.g. "COOKS_ASSISTANT"), state ("FINISHED" etc.)
 * VARBIT    → varbitId, op ("GTE"|"LTE"|"EQ"|"GT"|"LT"|"NEQ"), value
 * SKILL     → skill (Skill enum name, e.g. "COOKING"), level (minimum required)
 * ITEM_HELD → itemId, qty (minimum count in inventory)
 * REGION    → regionId
 * MANUAL    → no extra fields; user presses Skip/Done
 * RECURRING → cadenceMinutes (SYNTHESIS §S4); never itself auto-evaluated —
 *             a completion-semantics marker, see {@link ConditionType#RECURRING}
 * </pre>
 */
public class CompletionCondition
{
    public ConditionType type;

    // ── QUEST ──────────────────────────────────────────────────────────────────
    /** Exact name of the {@code net.runelite.api.Quest} enum constant. */
    public String questName;
    /** Required quest state: "NOT_STARTED", "IN_PROGRESS", or "FINISHED". */
    public String state;

    // ── VARBIT ─────────────────────────────────────────────────────────────────
    public int varbitId;
    /** Comparison operator: GTE / LTE / EQ / GT / LT / NEQ */
    public String op;
    public int value;

    // ── SKILL ──────────────────────────────────────────────────────────────────
    /** Exact name of the {@code net.runelite.api.Skill} enum constant. */
    public String skill;
    public int level;

    // ── ITEM_HELD ──────────────────────────────────────────────────────────────
    public int itemId;
    public int qty;

    // ── REGION ─────────────────────────────────────────────────────────────────
    public int regionId;

    // ── RECURRING ──────────────────────────────────────────────────────────────
    /**
     * Real-world minutes until the background step re-arms after "completing"
     * (SYNTHESIS §S4). Zero/unset falls back to the owning {@code GuideStep}'s
     * own {@code cadenceMinutes}; if that is also null the step is treated as
     * event-driven (no countdown — manual acknowledgement only).
     */
    public int cadenceMinutes;
}
