package com.techdevgroup.guidechain.data;

import java.util.Collections;
import java.util.List;

/**
 * One entry in the {@link Manifest}: a named chain that references an
 * ordered list of guide files to play in sequence.
 */
public class ChainEntry
{
    /** Stable identifier used in config and the chain-selection dropdown. */
    public String id;

    /** Display name shown in the dropdown. */
    public String name;

    /** Short description of the chain's scope. */
    public String description;

    /** Optional grouping for the library directory (e.g. "Progression", "Reference"). */
    public String category;

    /**
     * Ordered guide filenames (without path; resolved from the guides source).
     * Example: {@code ["f2p-early-game.json"]}.
     */
    public List<String> guides;

    public List<String> guides()
    {
        return guides != null ? guides : Collections.emptyList();
    }
}
