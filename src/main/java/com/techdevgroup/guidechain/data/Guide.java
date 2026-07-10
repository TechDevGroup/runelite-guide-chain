package com.techdevgroup.guidechain.data;

import java.util.Collections;
import java.util.List;

/**
 * An ordered list of {@link GuideStep}s that together describe one
 * discrete chunk of progression (e.g. "complete Cook's Assistant").
 *
 * Guides are the files stored in {@code ~.runelite/guide-chain/guides/}
 * and on the remote {@code guides} branch.
 */
public class Guide
{
    /** Stable identifier matching the filename (without extension). */
    public String id;

    /** Human-readable name shown in chain listings. */
    public String name;

    /** Optional description of what this guide covers. */
    public String description;

    public List<GuideStep> steps;

    public List<GuideStep> steps()
    {
        return steps != null ? steps : Collections.emptyList();
    }
}
