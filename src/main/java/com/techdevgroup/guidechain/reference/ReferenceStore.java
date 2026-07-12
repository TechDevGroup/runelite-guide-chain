package com.techdevgroup.guidechain.reference;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The local analogue of the wiki's Supplemental-guides index: quests,
 * achievement diaries, minigames, and one-off unlocks, loaded once from the
 * bundled classpath resource {@code /reference/catalog.jsonl} — a copy of
 * osrs-wiki's {@code assets/data/tools/quest_db.jsonl} and the
 * {@code minigamedb:*}/{@code unlockdb:*} rows of {@code tools/wiki-kb/contrib.jsonl},
 * consolidated by {@code tools/gen_reference_catalog.py}. This class never
 * reads the osrs-wiki repo at runtime — only its own bundled copy — so it
 * works identically inside the plugin and the standalone jar.
 *
 * <p>Same "small + immutable + classpath, loaded eagerly at construction"
 * shape as {@code GuideWebMain}'s fixture loading, but always present (the
 * catalog isn't fixture-only / RuneLite-specific).
 */
public final class ReferenceStore
{
    private static final Logger LOG = Logger.getLogger(ReferenceStore.class.getName());
    private static final String RESOURCE = "/reference/catalog.jsonl";

    private final List<ReferenceEntry> entries;

    public ReferenceStore(Gson gson)
    {
        this.entries = Collections.unmodifiableList(load(gson));
    }

    /** Every entry, in catalog file order. */
    public List<ReferenceEntry> all()
    {
        return entries;
    }

    /** Entries of one kind (quest | diary | minigame | unlock); all entries when {@code kind} is null/blank. */
    public List<ReferenceEntry> byKind(String kind)
    {
        if (kind == null || kind.isEmpty()) return entries;
        List<ReferenceEntry> out = new ArrayList<>();
        for (ReferenceEntry e : entries)
        {
            if (kind.equals(e.kind)) out.add(e);
        }
        return out;
    }

    private static List<ReferenceEntry> load(Gson gson)
    {
        List<ReferenceEntry> out = new ArrayList<>();
        try (InputStream in = ReferenceStore.class.getResourceAsStream(RESOURCE))
        {
            if (in == null)
            {
                LOG.warning("reference catalog missing from classpath: " + RESOURCE);
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null)
            {
                if (line.trim().isEmpty()) continue;
                ReferenceEntry e = gson.fromJson(line, ReferenceEntry.class);
                if (e != null) out.add(e);
            }
        }
        catch (IOException e)
        {
            LOG.log(Level.WARNING, "failed to load reference catalog", e);
        }
        return out;
    }
}
