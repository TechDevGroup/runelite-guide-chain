package com.techdevgroup.guidechain.manager;

import com.google.gson.Gson;
import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.data.ChainEntry;
import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.Guide;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.data.MapMarker;
import com.techdevgroup.guidechain.data.Manifest;
import com.techdevgroup.guidechain.data.StepOverride;
import lombok.Getter;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads guides and manages the current chain/step position.
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

    // ── Loaded data ──────────────────────────────────────────────────────────

    @Getter private Manifest manifest;
    /** guideId → loaded Guide */
    private final Map<String, Guide> loadedGuides = new HashMap<>();

    // ── Current position ─────────────────────────────────────────────────────

    /** Index into manifest.chains for the active chain. */
    @Getter private int chainIndex = 0;
    /** Index into active chain's guide list. */
    @Getter private int guideIndex = 0;
    /** Index into the active guide's step list. */
    @Getter private int stepIndex  = 0;

    // ── Public API ────────────────────────────────────────────────────────────

    /** (Re-)fetch the manifest and pre-load the first chain's guides. */
    public void refresh()
    {
        manifest = null;
        loadedGuides.clear();
        chainIndex = 0;
        guideIndex = 0;
        stepIndex  = 0;
        manifest = loadManifest();
        if (manifest == null) manifest = new Manifest();
        ensureChainLoaded();
    }

    /** Call after the user selects a chain from the dropdown. */
    public void selectChain(int index)
    {
        if (manifest == null) return;
        int max = manifest.chains().size();
        if (max == 0) return;
        chainIndex = Math.max(0, Math.min(index, max - 1));
        guideIndex = 0;
        stepIndex  = 0;
        ensureChainLoaded();
    }

    /** Names of all available chains for the dropdown. */
    public List<String> chainNames()
    {
        if (manifest == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (ChainEntry e : manifest.chains())
        {
            names.add(e.name != null ? e.name : e.id);
        }
        return names;
    }

    /**
     * Returns the current step with any local overrides applied, or
     * {@code null} if the chain is complete or no data is loaded.
     */
    public GuideStep currentStep()
    {
        Guide g = currentGuide();
        if (g == null) return null;
        List<GuideStep> steps = g.steps();
        if (stepIndex >= steps.size()) return null;
        GuideStep step = steps.get(stepIndex);
        return applyOverride(step, g.id);
    }

    /** Current guide for display purposes (name, step count). */
    public Guide currentGuide()
    {
        ChainEntry chain = currentChain();
        if (chain == null) return null;
        List<String> guideFiles = chain.guides();
        if (guideIndex >= guideFiles.size()) return null;
        String filename = guideFiles.get(guideIndex);
        String guideId = filenameToId(filename);
        return loadedGuides.get(guideId);
    }

    /** Total step count across all guides in the current chain. */
    public int totalSteps()
    {
        ChainEntry chain = currentChain();
        if (chain == null) return 0;
        int total = 0;
        for (String f : chain.guides())
        {
            Guide g = loadedGuides.get(filenameToId(f));
            if (g != null) total += g.steps().size();
        }
        return total;
    }

    /** 1-based index of the current step across the whole chain. */
    public int globalStepNumber()
    {
        ChainEntry chain = currentChain();
        if (chain == null) return 0;
        int base = 0;
        for (int i = 0; i < guideIndex; i++)
        {
            if (i < chain.guides().size())
            {
                Guide g = loadedGuides.get(filenameToId(chain.guides().get(i)));
                if (g != null) base += g.steps().size();
            }
        }
        return base + stepIndex + 1;
    }

    /** Move forward one step; crosses guide boundaries if needed. */
    public boolean advance()
    {
        Guide g = currentGuide();
        if (g == null) return false;
        stepIndex++;
        if (stepIndex >= g.steps().size())
        {
            ChainEntry chain = currentChain();
            if (chain != null && guideIndex + 1 < chain.guides().size())
            {
                guideIndex++;
                stepIndex = 0;
                ensureGuideLoaded(chain.guides().get(guideIndex));
                return true;
            }
            stepIndex = g.steps().size(); // cap at end (chain complete)
            return false;
        }
        return true;
    }

    /** Move backward one step; crosses guide boundaries if needed. */
    public void back()
    {
        if (stepIndex > 0)
        {
            stepIndex--;
            return;
        }
        if (guideIndex > 0)
        {
            ChainEntry chain = currentChain();
            if (chain != null)
            {
                guideIndex--;
                Guide g = loadedGuides.get(filenameToId(chain.guides().get(guideIndex)));
                stepIndex = g != null && !g.steps().isEmpty() ? g.steps().size() - 1 : 0;
            }
        }
    }

    /** {@code true} when the last step of the chain has been completed. */
    public boolean isChainComplete()
    {
        ChainEntry chain = currentChain();
        if (chain == null) return true;
        Guide g = currentGuide();
        if (g == null) return true;
        boolean lastGuide = (guideIndex == chain.guides().size() - 1);
        boolean lastStep  = (stepIndex >= g.steps().size());
        return lastGuide && lastStep;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ChainEntry currentChain()
    {
        if (manifest == null) return null;
        List<ChainEntry> chains = manifest.chains();
        if (chainIndex >= chains.size()) return null;
        return chains.get(chainIndex);
    }

    private void ensureChainLoaded()
    {
        ChainEntry chain = currentChain();
        if (chain == null) return;
        for (String f : chain.guides())
        {
            ensureGuideLoaded(f);
        }
    }

    private void ensureGuideLoaded(String filename)
    {
        String id = filenameToId(filename);
        if (loadedGuides.containsKey(id)) return;
        Guide g = loadGuide(filename);
        if (g != null) loadedGuides.put(id, g);
    }

    // ── Override application ──────────────────────────────────────────────────

    private GuideStep applyOverride(GuideStep step, String guideId)
    {
        if (step == null) return null;
        File overrideFile = new File(new File(OVERRIDES_DIR, guideId), step.id + ".json");
        if (!overrideFile.exists()) return step;
        try (Reader r = new FileReader(overrideFile, StandardCharsets.UTF_8))
        {
            StepOverride ov = gson.fromJson(r, StepOverride.class);
            if (ov == null) return step;
            // Deep-copy step fields and patch
            GuideStep patched = new GuideStep();
            patched.id                  = step.id;
            patched.instruction         = ov.instruction         != null ? ov.instruction         : step.instruction;
            patched.detail              = ov.detail              != null ? ov.detail              : step.detail;
            patched.highlights          = ov.highlights          != null ? ov.highlights          : step.highlights;
            patched.completionConditions= ov.completionConditions!= null ? ov.completionConditions: step.completionConditions;
            patched.mapMarkers          = ov.mapMarkers          != null ? ov.mapMarkers          : step.mapMarkers;
            return patched;
        }
        catch (IOException e)
        {
            log.warn("Failed to read step override {}", overrideFile, e);
            return step;
        }
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
        conn.setRequestProperty("User-Agent", "runelite-guide-chain/0.1.0");
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
