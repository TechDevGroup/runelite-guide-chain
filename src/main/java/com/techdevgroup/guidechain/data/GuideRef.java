package com.techdevgroup.guidechain.data;

/**
 * A wiki citation attached to a {@link GuideStep}.
 *
 * <p>All fields are nullable — Gson will leave them null when absent from JSON.
 */
public class GuideRef
{
    /** Display title, e.g. "Chicken". */
    public String title;

    /** Canonical wiki URL, e.g. "https://oldschool.runescape.wiki/w/Chicken". */
    public String url;

    /**
     * Entity kind hint: one of item | npc | quest | location | other.
     * Nullable; affects icon choice only.
     */
    public String kind;
}
