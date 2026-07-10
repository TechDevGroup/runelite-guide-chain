package com.techdevgroup.guidechain.data;

import java.util.Collections;
import java.util.List;

/**
 * Top-level descriptor fetched from the guides source.
 *
 * Stored as {@code manifest.json} at the root of the guides data source
 * (remote branch or local guides directory).
 */
public class Manifest
{
    /**
     * All chains available from this source, in suggested display order.
     */
    public List<ChainEntry> chains;

    public List<ChainEntry> chains()
    {
        return chains != null ? chains : Collections.emptyList();
    }
}
