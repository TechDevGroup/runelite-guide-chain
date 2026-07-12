package com.techdevgroup.guidechain;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.ConditionType;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.manager.ConditionEvaluator;
import com.techdevgroup.guidechain.manager.GuideManager;
import com.techdevgroup.guidechain.overlay.DebugOverlay;
import com.techdevgroup.guidechain.overlay.ItemOverlay;
import com.techdevgroup.guidechain.overlay.SceneOverlay;
import com.techdevgroup.guidechain.overlay.WidgetHighlightOverlay;
import com.techdevgroup.guidechain.overlay.WorldArrowOverlay;
import com.techdevgroup.guidechain.overlay.WorldMapOverlay;
import com.techdevgroup.guidechain.panel.GuidePanel;
import com.techdevgroup.guidechain.store.CharacterSnapshot;
import com.techdevgroup.guidechain.store.ConditionStatus;
import com.techdevgroup.guidechain.store.GuideStore;
import com.techdevgroup.guidechain.store.PlanRow;
import com.techdevgroup.guidechain.web.GuideWebServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Guide Chain — plays back declarative, chained account-progression guides
 * as quest-helper-style zero-ambiguity step-by-step guidance.
 *
 * <p><strong>Hard rule:</strong> overlay and highlight only — this plugin
 * never automates player input of any kind. The optional embedded web view
 * is a read/annotate surface over the same shared state; it cannot touch
 * the game either.
 *
 * <p>Since v0.2.0 all mutable state (position, marks, character snapshot,
 * metrics) lives in the shared {@link GuideStore} — ONE state instance
 * behind THREE surfaces: the sidebar panel, the canvas highlights, and the
 * localhost web view. The plugin pushes a character snapshot + live condition
 * values into the store each game tick and consumes store mutations on the
 * next render, so a click in the browser or sidebar panel reflects in-game
 * immediately.
 *
 * <p>Since v0.3.0 the interactive guide UI lives in a RuneLite
 * {@link GuidePanel} (Swing sidebar panel opened via a toolbar
 * {@link NavigationButton}). The game canvas retains only purely visual,
 * non-interactive guidance: object/NPC/tile clickbox highlights, item
 * highlights, and world-map markers. Nothing on the canvas requires a click.
 */
@Slf4j
@PluginDescriptor(
    name        = "Guide Chain",
    description = "Plays back declarative chained account-progression guides as zero-ambiguity step-by-step guidance. Highlight-only; never automates input.",
    tags        = {"guide", "progression", "quest", "overlay", "highlight", "tutorial", "web"}
)
public class GuideChainPlugin extends Plugin
{
    @Inject private Client                  client;
    @Inject private OverlayManager          overlayManager;
    @Inject private ClientToolbar           clientToolbar;
    @Inject private GuideChainConfig        config;
    @Inject private ConfigManager           configManager;
    @Inject @Getter private GuideManager    guideManager;
    @Inject @Getter private GuideStore      guideStore;
    @Inject private ConditionEvaluator      conditionEvaluator;
    @Inject private SceneOverlay            sceneOverlay;
    @Inject private ItemOverlay             itemOverlay;
    @Inject private WidgetHighlightOverlay  widgetHighlightOverlay;
    @Inject private DebugOverlay            debugOverlay;
    @Inject private WorldMapOverlay         worldMapOverlay;
    @Inject private WorldArrowOverlay       worldArrowOverlay;
    @Inject private GuidePanel              guidePanel;
    @Inject private Gson                    gson;

    private NavigationButton navButton;
    private GuideWebServer   webServer;

    /** Quest enum names referenced by the active chain; refreshed on plan changes. */
    private volatile Set<String> trackedQuests = new LinkedHashSet<>();
    private volatile boolean trackedQuestsDirty = true;

    /** §S4: re-run once per login (cleared on LOGIN_SCREEN/HOPPING) so a world
     *  hop or account switch re-hydrates from this account's config keys. */
    private volatile boolean recurringHydrated = false;

    private final GuideStore.Listener storeListener = (store, what) ->
    {
        if ("plan".equals(what) || "chain".equals(what) || "overrides".equals(what))
        {
            trackedQuestsDirty = true;
        }
        if ("chain".equals(what))
        {
            // Keep the config panel's selected chain in sync with web-view / panel picks.
            configManager.setConfiguration("guidechain", "selectedChain", store.chainIndex());
        }
        if ("bg".equals(what))
        {
            // §S4: mirror a RECURRING re-arm into this account's RuneLite config
            // keys so it survives relog/world-hop even though GuideStore's own
            // state.json is one global file, not per-account.
            persistRecurringToConfig();
        }
        // Refresh the sidebar panel on structural changes.
        // Skip "character" and liveConditions — they fire every tick.
        if ("plan".equals(what)   || "chain".equals(what)
            || "overrides".equals(what) || "marks".equals(what)
            || "position".equals(what) || "bg".equals(what))
        {
            SwingUtilities.invokeLater(guidePanel::refresh);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void startUp()
    {
        // Canvas overlays — display-only, no click targets
        overlayManager.add(sceneOverlay);
        overlayManager.add(itemOverlay);
        overlayManager.add(widgetHighlightOverlay);
        overlayManager.add(debugOverlay);
        overlayManager.add(worldMapOverlay);
        overlayManager.add(worldArrowOverlay);

        // Sidebar panel — the interactive surface
        navButton = buildNavButton();
        clientToolbar.addNavigation(navButton);

        guideStore.addListener(storeListener);

        // Load data asynchronously to avoid blocking the EDT
        new Thread(() ->
        {
            guideManager.refresh();
            int chainIdx = config.selectedChain();
            if (chainIdx != guideStore.chainIndex()) guideManager.selectChain(chainIdx);
        }, "guide-chain-load").start();

        if (config.webServerEnabled()) startWebServer();
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(sceneOverlay);
        overlayManager.remove(itemOverlay);
        overlayManager.remove(widgetHighlightOverlay);
        overlayManager.remove(debugOverlay);
        overlayManager.remove(worldMapOverlay);
        overlayManager.remove(worldArrowOverlay);
        worldMapOverlay.clearAll();

        clientToolbar.removeNavigation(navButton);
        navButton = null;
        guidePanel.stopTimer();

        stopWebServer();
        guideStore.setClientConnected(false);
        guideStore.removeListener(storeListener);
    }

    @Provides
    GuideChainConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GuideChainConfig.class);
    }

    /**
     * The ONE shared state instance. Plain Java (zero RuneLite imports
     * inside the store package); only this provider knows the RuneLite dir.
     */
    @Provides
    @Singleton
    GuideStore provideGuideStore(Gson gson)
    {
        return new GuideStore(new File(RuneLite.RUNELITE_DIR, "guide-chain"), gson);
    }

    // ── Navigation button ─────────────────────────────────────────────────────

    private NavigationButton buildNavButton()
    {
        BufferedImage icon;
        try
        {
            icon = ImageUtil.loadImageResource(GuideChainPlugin.class, "icon.png");
        }
        catch (RuntimeException e)
        {
            log.warn("Guide Chain: could not load toolbar icon", e);
            // Fallback: solid cyan 16x16 square
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = icon.createGraphics();
            g.setColor(new java.awt.Color(0, 200, 255));
            g.fillRect(0, 0, 16, 16);
            g.dispose();
        }
        return NavigationButton.builder()
            .tooltip("Guide Chain")
            .icon(icon)
            .priority(7)
            .panel(guidePanel)
            .build();
    }

    // ── Web server lifecycle ──────────────────────────────────────────────────

    private void startWebServer()
    {
        stopWebServer();
        try
        {
            webServer = new GuideWebServer(guideStore, config.webServerPort(), gson,
                guideManager::refresh);
            webServer.start();
            log.info("Guide Chain web view available at {}", webServer.url());
        }
        catch (IOException e)
        {
            log.warn("Could not start Guide Chain web view on port {}", config.webServerPort(), e);
            webServer = null;
        }
    }

    private void stopWebServer()
    {
        if (webServer != null)
        {
            webServer.stop();
            webServer = null;
        }
    }

    // ── Public accessors (overlays read through these → the store) ──────────

    /**
     * Returns the current step with overrides applied, or {@code null} if
     * the chain is complete or no data has loaded yet.
     */
    public GuideStep getCurrentStep()
    {
        return guideStore.currentStep();
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        checkAutoAdvance();
        pushLiveState();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGIN_SCREEN
            || event.getGameState() == GameState.HOPPING)
        {
            guideStore.setClientConnected(false);
            recurringHydrated = false; // re-hydrate from config on the next login (S4)
        }
        else if (event.getGameState() == GameState.LOGGED_IN && !recurringHydrated)
        {
            hydrateRecurringFromConfig();
            recurringHydrated = true;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!"guidechain".equals(event.getGroup())) return;
        switch (event.getKey())
        {
            case "webServerEnabled":
            case "webServerPort":
                if (config.webServerEnabled()) startWebServer();
                else stopWebServer();
                break;
            case "selectedChain":
                guideManager.selectChain(config.selectedChain());
                break;
            default:
                break;
        }
    }

    // ── Store push (cheap, once per game tick) ───────────────────────────────

    /**
     * Pushes the character snapshot and the current step's live condition
     * values into the shared store, so the web view (and state API) can show
     * them. The store only persists the snapshot when it actually changed.
     */
    private void pushLiveState()
    {
        CharacterSnapshot snap = new CharacterSnapshot();
        snap.loggedIn = true;
        snap.playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        for (Skill s : Skill.values())
        {
            snap.skills.put(s.name(), client.getRealSkillLevel(s));
        }
        for (String questName : trackedQuestNames())
        {
            try
            {
                QuestState qs = Quest.valueOf(questName).getState(client);
                snap.quests.put(questName, qs != null ? qs.name() : "UNKNOWN");
            }
            catch (IllegalArgumentException badName)
            {
                snap.quests.put(questName, "UNKNOWN");
            }
        }
        snap.updatedAtMs = System.currentTimeMillis();
        guideStore.updateCharacter(snap);

        GuideStep step = guideStore.currentStep();
        String key = guideStore.currentStepKey();
        List<ConditionStatus> statuses = new ArrayList<>();
        if (step != null)
        {
            for (CompletionCondition c : step.completionConditions())
            {
                statuses.add(new ConditionStatus(
                    c.type != null ? c.type.name() : "?",
                    conditionEvaluator.expectedValue(c),
                    conditionEvaluator.liveValue(c),
                    conditionEvaluator.evaluate(c)));
            }
        }
        guideStore.updateLiveConditions(key, statuses);
    }

    /** Quest enum names referenced by the active chain's conditions. */
    private Set<String> trackedQuestNames()
    {
        if (trackedQuestsDirty)
        {
            Set<String> names = new LinkedHashSet<>();
            for (PlanRow row : guideStore.plan())
            {
                for (CompletionCondition c : row.step.completionConditions())
                {
                    if (c.type == ConditionType.QUEST && c.questName != null)
                    {
                        names.add(c.questName);
                    }
                }
            }
            trackedQuests = names;
            trackedQuestsDirty = false;
        }
        return trackedQuests;
    }

    // ── Auto-advance logic ────────────────────────────────────────────────────

    /**
     * Evaluates the current step's completion conditions each tick.
     * If ALL non-MANUAL conditions are satisfied (and there is at least one),
     * the step is marked done in the store and the position advances.
     */
    private void checkAutoAdvance()
    {
        GuideStep step = guideStore.currentStep();
        if (step == null) return;

        List<CompletionCondition> conditions = step.completionConditions();
        if (conditions.isEmpty()) return;

        boolean hasAutoCondition = false;
        boolean allAutoMet = true;

        for (CompletionCondition c : conditions)
        {
            // MANUAL and RECURRING are completion-semantics markers, never
            // themselves live-evaluated (S4) — their sibling VARBIT/ITEM_HELD
            // conditions (if any) are what actually gate auto-advance/re-arm.
            if (c.type == ConditionType.MANUAL || c.type == ConditionType.RECURRING) continue;
            hasAutoCondition = true;
            if (!conditionEvaluator.evaluate(c))
            {
                allAutoMet = false;
                break;
            }
        }

        if (hasAutoCondition && allAutoMet)
        {
            log.debug("Auto-advancing past step {}", step.id);
            guideStore.markDone(guideStore.currentStepKey());
        }
    }

    // ── RECURRING background loops — RuneLite config persistence (§S4) ───────

    /**
     * Config key prefix is intentionally {@code stepId} only (not
     * {@code guideId/stepId}) — matches the literal SYNTHESIS §S4 key shape
     * {@code guidechain.bg.<stepId>.nextFireEpochMs}. Background step ids are
     * unique across the corpus in practice (they're author-chosen slugs like
     * {@code bg-farm-allotment-setup}), so this doesn't collide in the guides
     * this plugin actually ships.
     */
    private static String bgConfigKey(String stepId, String field)
    {
        return "bg." + stepId + "." + field;
    }

    /**
     * Reads this account's persisted RECURRING timers/lifecycle back into the
     * shared {@link GuideStore} at login. Config is authoritative once a
     * character is logged in — it overwrites whatever
     * {@link GuideStore#setPlan} seeded as a fresh default on plugin startup
     * (that seed only exists so the loops lane has *something* to show before
     * the player has ever logged in this session).
     */
    private void hydrateRecurringFromConfig()
    {
        for (PlanRow row : guideStore.plan())
        {
            GuideStep step = row.step;
            if (step == null || step.id == null || !"background".equals(step.slotType)) continue;

            Long nextFire = null;
            String nextFireStr = configManager.getConfiguration("guidechain", bgConfigKey(step.id, "nextFireEpochMs"));
            if (nextFireStr != null)
            {
                try
                {
                    nextFire = Long.parseLong(nextFireStr);
                }
                catch (NumberFormatException e)
                {
                    log.warn("Guide Chain: bad stored nextFireEpochMs for {}", step.id, e);
                }
            }
            String lifecycle = configManager.getConfiguration("guidechain", bgConfigKey(step.id, "lifecycleState"));

            if (nextFire != null || lifecycle != null)
            {
                guideStore.seedBackgroundState(row.key, nextFire, lifecycle);
            }
        }
    }

    /**
     * Mirrors the shared store's current RECURRING timers/lifecycle out to
     * this account's RuneLite config keys, called whenever the store fires a
     * {@code "bg"} change (a loops-lane step just re-armed).
     */
    private void persistRecurringToConfig()
    {
        for (PlanRow row : guideStore.plan())
        {
            GuideStep step = row.step;
            if (step == null || step.id == null || !"background".equals(step.slotType)) continue;

            Long nextFire = guideStore.nextFireEpochMs(row.key);
            if (nextFire != null)
            {
                configManager.setConfiguration("guidechain", bgConfigKey(step.id, "nextFireEpochMs"), nextFire);
            }
            String lifecycle = guideStore.liveLifecycleState(row.key);
            if (lifecycle != null)
            {
                configManager.setConfiguration("guidechain", bgConfigKey(step.id, "lifecycleState"), lifecycle);
            }
        }
    }
}
