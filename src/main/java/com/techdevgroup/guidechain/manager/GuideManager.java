package com.techdevgroup.guidechain.manager;

import com.google.gson.Gson;
import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.data.ChainEntry;
import com.techdevgroup.guidechain.data.Guide;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.Manifest;
import com.techdevgroup.guidechain.data.StepOverride;
import com.techdevgroup.guidechain.store.GuideStore;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads guide content (manifest, guides, local overrides) and pushes it into
 * the shared {@link GuideStore}. Since v0.2.0 all position/mark state lives
 * in the store — this class is the RuneLite-side <em>loader + facade</em>;
 * the overlays, config actions, and the embedded web view all read and
 * mutate through the same store instance.
 *
 * <h3>Data source override chain (highest wins)</h3>
 * <ol>
 *   <li>Local override dir: {@code ~/.runelite/guide-chain/overrides/&lt;guideId&gt;/&lt;stepId&gt;.json}</li>
 *   <li>Local guides dir:   {@code ~/.runelite/guide-chain/guides/&lt;filename&gt;}</li>
 *   <li>Remote git branch:  raw GitHub URLs built from the configured repo + manifest.json</li>
 * </ol>
 */
@Slf4j
@Singleton
public class GuideManager
{
    /** Base directory under the RuneLite user home. */
    private static final File GC_DIR = new File(RuneLite.RUNELITE_DIR, "guide-chain");
    private static final File GUIDES_DIR    = new File(GC_DIR, "guides");
    private static final File OVERRIDES_DIR = new File(GC_DIR, "overrides");
    private static final File CACHE_DIR     = new File(GC_DIR, "cache");

    private static final int FETCH_TIMEOUT_MS = 8_000;

    @Inject private GuideChainConfig config;
    @Inject private Gson gson;
    @Inject private GuideStore store;

    // ── Public API (delegates state to the shared store) ─────────────────────

    /** (Re-)fetch the manifest, pre-load all chains' guides, push to store. */
    public void refresh()
    {
        Manifest manifest = loadManifest();
        if (manifest == null) manifest = new Manifest();

        Map<String, Guide> loaded = new LinkedHashMap<>();
        for (ChainEntry chain : manifest.chains())
        {
            for (String filename : chain.guides())
            {
                String id = filenameToId(filename);
                if (!loaded.containsKey(id))
                {
                    Guide g = loadGuide(filename);
                    if (g != null) loaded.put(id, g);
                }
            }
        }

        store.setOverrides(loadOverrides());
        store.setPlan(manifest, loaded);
    }

    /** Call after the user selects a chain in the config panel. */
    public void selectChain(int index)
    {
        store.selectChainByIndex(index);
    }

    /** Names of all available chains for display. */
    public List<String> chainNames()
    {
        List<String> names = new ArrayList<>();
        for (ChainEntry e : store.chains())
        {
            names.add(e.name != null ? e.name : e.id);
        }
        return names;
    }

    public int getChainIndex()         { return store.chainIndex(); }
    public int getGuideIndex()         { return store.guideIndex(); }
    public int getStepIndex()          { return store.stepIndex(); }
    public GuideStep currentStep()     { return store.currentStep(); }
    public Guide currentGuide()        { return store.currentGuide(); }
    public int totalSteps()            { return store.totalSteps(); }
    public int globalStepNumber()      { return store.globalStepNumber(); }
    public boolean advance()           { return store.advance(); }
    public void back()                 { store.back(); }
    public boolean isChainComplete()   { return store.isChainComplete(); }

    // ── Override loading (pushed into the store) ─────────────────────────────

    /** Scan {@code overrides/&lt;guideId&gt;/&lt;stepId&gt;.json} into a keyed map. */
    private Map<String, StepOverride> loadOverrides()
    {
        Map<String, StepOverride> map = new HashMap<>();
        File[] guideDirs = OVERRIDES_DIR.listFiles(File::isDirectory);
        if (guideDirs == null) return map;
        for (File dir : guideDirs)
        {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null) continue;
            for (File f : files)
            {
                try (Reader r = new FileReader(f, StandardCharsets.UTF_8))
                {
                    StepOverride ov = gson.fromJson(r, StepOverride.class);
                    if (ov != null)
                    {
                        String stepId = f.getName().substring(0, f.getName().length() - 5);
                        map.put(GuideStore.key(dir.getName(), stepId), ov);
                    }
                }
                catch (IOException | RuntimeException e)
                {
                    log.warn("Failed to read step override {}", f, e);
                }
            }
        }
        return map;
    }

    // ── Guide loading ─────────────────────────────────────────────────────────

    private Guide loadGuide(String filename)
    {
        // 1. Local guides dir
        File localFile = new File(GUIDES_DIR, filename);
        if (localFile.exists())
        {
            try (Reader r = new FileReader(localFile, StandardCharsets.UTF_8))
            {
                return gson.fromJson(r, Guide.class);
            }
            catch (IOException e)
            {
                log.warn("Failed to read local guide {}", localFile, e);
            }
        }

        // 2. Local cache
        File cacheFile = new File(CACHE_DIR, filename);
        if (cacheFile.exists())
        {
            try (Reader r = new FileReader(cacheFile, StandardCharsets.UTF_8))
            {
                Guide g = gson.fromJson(r, Guide.class);
                if (g != null) return g;
            }
            catch (IOException e)
            {
                log.warn("Failed to read cache guide {}", cacheFile, e);
            }
        }

        // 3. Remote
        String rawBase = buildRawBase();
        if (rawBase != null)
        {
            try
            {
                String json = fetchString(rawBase + "/" + filename);
                Guide g = gson.fromJson(json, Guide.class);
                if (g != null)
                {
                    cacheToFile(cacheFile, json);
                    return g;
                }
            }
            catch (IOException e)
            {
                log.warn("Failed to fetch remote guide {}", filename, e);
            }
        }

        log.warn("Guide {} not found in any source", filename);
        return null;
    }

    private Manifest loadManifest()
    {
        // 1. Local guides dir
        File localManifest = new File(GUIDES_DIR, "manifest.json");
        if (localManifest.exists())
        {
            try (Reader r = new FileReader(localManifest, StandardCharsets.UTF_8))
            {
                return gson.fromJson(r, Manifest.class);
            }
            catch (IOException e)
            {
                log.warn("Failed to read local manifest", e);
            }
        }

        // 2. Local cache
        File cacheManifest = new File(CACHE_DIR, "manifest.json");

        // 3. Remote first, then fall back to stale cache
        String rawBase = buildRawBase();
        if (rawBase != null)
        {
            try
            {
                String json = fetchString(rawBase + "/manifest.json");
                Manifest m = gson.fromJson(json, Manifest.class);
                if (m != null)
                {
                    cacheToFile(cacheManifest, json);
                    return m;
                }
            }
            catch (IOException e)
            {
                log.warn("Failed to fetch remote manifest — falling back to cache", e);
            }
        }

        if (cacheManifest.exists())
        {
            try (Reader r = new FileReader(cacheManifest, StandardCharsets.UTF_8))
            {
                return gson.fromJson(r, Manifest.class);
            }
            catch (IOException e)
            {
                log.warn("Failed to read cache manifest", e);
            }
        }

        return null;
    }

    // ── Network / cache helpers ───────────────────────────────────────────────

    private String buildRawBase()
    {
        String repo = config.guidesRepo();
        if (repo == null || repo.isBlank()) return null;
        String branch = config.guidesBranch();
        if (branch == null || branch.isBlank()) branch = "guides";
        // raw.githubusercontent.com/<owner>/<repo>/<branch>
        return "https://raw.githubusercontent.com/" + repo.trim() + "/" + branch.trim();
    }

    private String fetchString(String urlStr) throws IOException
    {
        URL url = new URL(urlStr);
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(FETCH_TIMEOUT_MS);
        conn.setReadTimeout(FETCH_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "runelite-guide-chain/0.3.0");
        try (InputStream is = conn.getInputStream();
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8))
        {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = isr.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        }
    }

    private void cacheToFile(File file, String content)
    {
        try
        {
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            log.warn("Failed to cache to {}", file, e);
        }
    }

    private static String filenameToId(String filename)
    {
        return filename.endsWith(".json")
            ? filename.substring(0, filename.length() - 5)
            : filename;
    }
}
