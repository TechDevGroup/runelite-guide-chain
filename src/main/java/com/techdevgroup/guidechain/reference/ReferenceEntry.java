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

    public List<GuideRef> refs()
    {
        return refs != null ? refs : Collections.emptyList();
    }
}
