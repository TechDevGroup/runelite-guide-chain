package com.techdevgroup.guidechain.overlay;

import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.GuideChainPlugin;
import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.manager.ConditionEvaluator;
import com.techdevgroup.guidechain.manager.GuideManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Debug panel (toggled in config) showing:
 * <ul>
 *   <li>Current guide id + step id</li>
 *   <li>Each completion condition with its LIVE value vs expected value</li>
 *   <li>List of active highlight targets</li>
 * </ul>
 *
 * Misbehaving steps can be diagnosed here and fixed via override JSON files.
 */
public class DebugOverlay extends OverlayPanel
{
    private static final Color OK_COLOR   = new Color(0, 200, 80);
    private static final Color FAIL_COLOR = new Color(220, 60, 60);

    private final GuideChainPlugin plugin;
    private final GuideChainConfig config;
    private final ConditionEvaluator evaluator;
    private final GuideManager manager;

    @Inject
    DebugOverlay(GuideChainPlugin plugin,
                 GuideChainConfig config,
                 ConditionEvaluator evaluator,
                 GuideManager manager)
    {
        super(plugin);
        this.plugin    = plugin;
        this.config    = config;
        this.evaluator = evaluator;
        this.manager   = manager;
        setPosition(OverlayPosition.TOP_RIGHT);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showDebug()) return null;

        GuideStep step  = plugin.getCurrentStep();
        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(
            TitleComponent.builder().text("GC Debug").build());

        // IDs
        String chainName = manager.currentGuide() != null
            ? manager.currentGuide().id : "none";
        panelComponent.getChildren().add(
            LineComponent.builder().left("Guide").right(chainName).build());

        String stepId = step != null ? step.id : "none";
        panelComponent.getChildren().add(
            LineComponent.builder().left("Step").right(stepId).build());

        if (step == null) return super.render(graphics);

        // Completion conditions
        panelComponent.getChildren().add(
            LineComponent.builder().left("── Conditions ──").build());
        for (CompletionCondition cond : step.completionConditions())
        {
            boolean met   = evaluator.evaluate(cond);
            String live   = evaluator.liveValue(cond);
            String expect = evaluator.expectedValue(cond);
            String type   = cond.type != null ? cond.type.name() : "?";

            panelComponent.getChildren().add(
                LineComponent.builder()
                    .left(type)
                    .right(live + " / " + expect)
                    .rightColor(met ? OK_COLOR : FAIL_COLOR)
                    .build());
        }

        // Highlight targets
        panelComponent.getChildren().add(
            LineComponent.builder().left("── Highlights ──").build());
        for (HighlightTarget t : step.highlights())
        {
            String tstr = t.type != null ? t.type.name() : "?";
            String desc = tstr + "#" + t.id;
            panelComponent.getChildren().add(
                LineComponent.builder().left(desc).build());
        }

        return super.render(graphics);
    }
}
