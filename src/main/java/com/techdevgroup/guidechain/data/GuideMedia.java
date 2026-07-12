package com.techdevgroup.guidechain.data;

import java.util.Collections;
import java.util.Map;

/**
 * One captured frame/gif attached to a {@link GuideStep} (FRAMES_GALLERY §1).
 *
 * <p>Frames are tier-3 capture-hierarchy artifacts (GRANULARITY §7b: wiki page
 * → cache data → simulated-viewport capture) and inherit its honesty bar —
 * recorded with the state that produced them, never a happenstance screencap.
 * {@code state} is therefore mandatory content on every entry (lint enforces
 * upstream); this class only carries it through as an opaque map so the web
 * view can render whatever keys the harness produced without a schema churn
 * each time a new capture dimension is added.
 *
 * <p>All fields nullable — Gson leaves them null when absent from JSON, same
 * additive-nullable contract as {@link GuideRef}/{@link GuideHint}.
 */
public class GuideMedia
{
    /** {@code "png" | "gif"} — selects the served Content-Type. */
    public String kind;

    /** Guide-source-relative, content-addressed path: {@code media/<hash6>/<name>}. Exclusive with {@link #url}. */
    public String path;

    /** Absolute URL (rare; loopback/pages only). Exclusive with {@link #path}. */
    public String url;

    /** Display caption shown on thumb hover/title and in the lightbox. */
    public String caption;

    /** The capture state that produced this frame — harness, rev, scenario, tile, varbits, captured. */
    public Map<String, Object> state;

    public Map<String, Object> state()
    {
        return state != null ? state : Collections.emptyMap();
    }
}
