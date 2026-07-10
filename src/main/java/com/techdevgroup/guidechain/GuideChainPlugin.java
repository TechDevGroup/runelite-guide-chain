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
import com.techdevgroup.guidechain.overlay.PanelOverlay;
import com.techdevgroup.guidechain.overlay.SceneOverlay;
import com.techdevgroup.guidechain.overlay.WidgetHighlightOverlay;
import com.techdevgroup.guidechain.overlay.WorldMapOverlay;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.List;

/**
 * Guide Chain — plays back declarative, chained account-progression guides
 * as quest-helper-style zero-ambiguity step-by-step guidance.
 *
 * <p><strong>Hard rule:</strong> overlay and highlight only — this plugin
 * never automates player input of any kind.
 *
 * <p>Guides are authored as JSON files; see the project README and
 * {@code schema/guide.schema.json} for the format.  The active chain is
 * selected in the plugin config panel.
 */
@Slf4j
@PluginDescriptor(
    name        = "Guide Chain",
    description = "Plays back declarative chained account-progression guides as zero-ambiguity step-by-step guidance. Highlight-only; never automates input.",
    tags        = {"guide", "progression", "quest", "overlay", "highlight", "tutorial"}
)
public class GuideChainPlugin extends Plugin
{
    @Inject private Client                client;
    @Inject private OverlayManager        overlayManager;
    @Inject private GuideChainConfig      config;
    @Inject @Getter private GuideManager  guideManager;
    @Inject private ConditionEvaluator    conditionEvaluator;
    @Inject private SceneOverlay          sceneOverlay;
    @Inject private ItemOverlay           itemOverlay;
    @Inject private WidgetHighlightOverlay widgetHighlightOverlay;
    @Inject private PanelOverlay          panelOverlay;
    @Inject private DebugOverlay          debugOverlay;
    @Inject private WorldMapOverlay       worldMapOverlay;
    @Inject private Gson                  gson;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void startUp()
    {
        overlayManager.add(sceneOverlay);
        overlayManager.add(itemOverlay);
        overlayManager.add(widgetHighlightOverlay);
        overlayManager.add(panelOverlay);
        overlayManager.add(debugOverlay);
        overlayManager.add(worldMapOverlay);

        // Load data asynchronously to avoid blocking the EDT
        new Thread(() ->
        {
            guideManager.refresh();
            int chainIdx = config.selectedChain();
            if (chainIdx > 0) guideManager.selectChain(chainIdx);
        }, "guide-chain-load").start();
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(sceneOverlay);
        overlayManager.remove(itemOverlay);
        overlayManager.remove(widgetHighlightOverlay);
        overlayManager.remove(panelOverlay);
        overlayManager.remove(debugOverlay);
        overlayManager.remove(worldMapOverlay);
        worldMapOverlay.clearAll();
    }

    @Provides
    GuideChainConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GuideChainConfig.class);
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    /**
     * Returns the current step with overrides applied, or {@code null} if
     * the chain is complete or no data has loaded yet.
     */
    public GuideStep getCurrentStep()
    {
        return guideManager.currentStep();
    }

    /**
     * Manually advance past the current step (used by Skip button and by
     * the panel overlay's click handler).
     */
    public void skipStep()
    {
        guideManager.advance();
    }

    /** Navigate back to the previous step. */
    public void backStep()
    {
        guideManager.back();
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        checkAutoAdvance();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // Re-evaluate state after login / hop
    }

    // ── Auto-advance logic ────────────────────────────────────────────────────

    /**
     * Evaluates the current step's completion conditions each tick.
     * If ALL non-MANUAL conditions are satisfied (and there is at least one),
     * or if the conditions list contains only MANUAL entries but those are all
     * trivially done (handled by button click), the step auto-advances.
     */
    private void checkAutoAdvance()
    {
        GuideStep step = guideManager.currentStep();
        if (step == null) return;

        List<CompletionCondition> conditions = step.completionConditions();
        if (conditions.isEmpty()) return;

        boolean hasAutoCondition = false;
        boolean allAutoMet = true;

        for (CompletionCondition c : conditions)
        {
            if (c.type == ConditionType.MANUAL) continue;
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
            guideManager.advance();
        }
    }
}
