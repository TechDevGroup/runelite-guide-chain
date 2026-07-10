package com.techdevgroup.guidechain.store;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Last-known character state, pushed into the {@link GuideStore} by the
 * RuneLite plugin on each game tick (cheap, change-detected before persisting).
 *
 * <p>Plain Java — no RuneLite imports. In standalone mode this is whatever
 * was last persisted (or a fixture), clearly marked stale by
 * {@code updatedAtMs} and the store's {@code clientConnected} flag.
 */
public class CharacterSnapshot
{
    /** Display name of the logged-in player, or {@code null}. */
    public String playerName;

    /** Whether the snapshot was taken while logged in. */
    public boolean loggedIn;

    /** Skill enum name → real (unboosted) level. Sorted for stable JSON. */
    public Map<String, Integer> skills = new TreeMap<>();

    /**
     * Quest enum name → state name (NOT_STARTED / IN_PROGRESS / FINISHED).
     * Only quests referenced by the active chain's conditions are tracked.
     */
    public Map<String, String> quests = new TreeMap<>();

    /** Epoch millis when this snapshot was taken. */
    public long updatedAtMs;

    /** Content equality ignoring the timestamp — used for change detection. */
    public boolean sameContentAs(CharacterSnapshot o)
    {
        if (o == null) return false;
        return loggedIn == o.loggedIn
            && Objects.equals(playerName, o.playerName)
            && Objects.equals(skills, o.skills)
            && Objects.equals(quests, o.quests);
    }
}
