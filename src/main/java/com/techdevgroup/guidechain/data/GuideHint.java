package com.techdevgroup.guidechain.data;

/**
 * An execution hint attached to a {@link GuideStep}.
 *
 * <p>Hints are advisory only — removing all hints must leave a still-completable
 * step. Anything completion-relevant belongs in {@code atom}, {@code reqs}, or
 * {@code completionConditions}, never here.
 *
 * <p>Rendered as small chips under the step card in both the web view and the
 * plugin panel. The {@code toggle-state} hint is stateful: its chip remains
 * visible on subsequent steps until a later step reverts the toggle.
 *
 * <p>Closed {@code type} enum (GRANULARITY §4):
 * <ul>
 *   <li>{@code do-while} — multitask during a host activity</li>
 *   <li>{@code dialogue} — spacebar-skip / exact option routing</li>
 *   <li>{@code toggle-state} — persistent state that gates behavior</li>
 *   <li>{@code batch-size} — act N-at-a-time for price/xp multiplier</li>
 *   <li>{@code teleport-choice} — ranked transport options</li>
 *   <li>{@code rng-variance} — completion count is a distribution</li>
 *   <li>{@code keep-drop} — loot policy</li>
 *   <li>{@code safespot} — positioning/prayer recipe</li>
 *   <li>{@code contested-fallback} — hotspot may be occupied; alternate</li>
 * </ul>
 */
public class GuideHint
{
    /** Hint type — one of the closed enum in GRANULARITY §4. */
    public String type;

    /** Optional slug for the target item/npc/setting this hint applies to. */
    public String target;

    /** Optional structured value (e.g. csv of teleport options, batch count). */
    public String value;

    /** Human-readable note explaining the hint. */
    public String note;
}
