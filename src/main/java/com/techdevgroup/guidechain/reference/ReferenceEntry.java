package com.techdevgroup.guidechain.reference;

import com.techdevgroup.guidechain.data.GuideRef;

import java.util.Collections;
import java.util.List;

/**
 * One row of the reference catalog: a quest, achievement diary, minigame, or
 * one-off unlock copied build-time from the osrs-wiki research corpus (see
 * {@code tools/gen_reference_catalog.py} and {@link ReferenceStore}).
 *
 * <p>{@code reqs} / {@code rewards} are heterogeneous free-form JSON across
 * source rows (a dict of skill levels, a list of item strings, or a plain
 * summary string) — kept as raw Gson-decoded {@code Object}
 * (Map/List/String/Double/null) rather than typed, and summarized for
 * display by {@code WebFragments}, mirroring how it already renders
 * heterogeneous decoded JSON for media {@code state{}} values.
 */
public final class ReferenceEntry
{
    /** Stable id: quest/diary ids match assets/data/tools/quest_db.jsonl; minigame/unlock ids are the contrib.jsonl key with its db prefix stripped. */
    public String id;

    /** One of quest | diary | minigame | unlock. */
    public String kind;

    /** Display name (diary names carry their tier, e.g. "Ardougne Diary (Elite)"). */
    public String name;

    public Object reqs;
    public Object rewards;
    public List<GuideRef> refs;
    public String notes;

    /**
     * Structured, prose-free blocks minted by the card-facts normalizer
     * (see {@code gen_reference_catalog.py::attach_card_facts}). When present
     * these render as typed info blocks and the prose {@link #notes} blob is
     * suppressed — the "nothing displays as prose" contract. Absent only on
     * rows the normalizer never reached (a handful of gear unlocks), which
     * fall back to {@link #notes}.
     */
    public String summary;
    public List<Fact> facts;
    public List<ReqItem> req_items;

    /** One objective, checkable statement decomposed from the source prose. */
    public static final class Fact
    {
        /** Closed enum: overview|boss|kills|combat|start|length|difficulty|unlock|required-for|hazard|mechanics|xp-note|items-note|removed|caveat. */
        public String label;
        public String value;
    }

    /** One required/consumed item parsed out of the reqs prose. */
    public static final class ReqItem
    {
        public String name;
        /** Integer count, or the string "??" when the source never stated one. */
        public Object qty;
        public String note;
        public boolean optional;
    }

    public List<GuideRef> refs()
    {
        return refs != null ? refs : Collections.emptyList();
    }

    public List<Fact> facts()
    {
        return facts != null ? facts : Collections.emptyList();
    }

    public List<ReqItem> reqItems()
    {
        return req_items != null ? req_items : Collections.emptyList();
    }
}
