package com.techdevgroup.guidechain.overlay;

import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.GuideChainPlugin;
import com.techdevgroup.guidechain.data.Guide;
import com.techdevgroup.guidechain.data.GuideStep;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;

/**
 * Sidebar-style panel showing the current step instruction, progress
 * counter, and Back / Skip navigation.
 *
 * Skip is the manual advance trigger; Back revisits the previous step.
 * Both buttons appear as plain text lines — actual click interception
 * is handled by {@link GuideChainPlugin#onMenuOptionClicked}.
 */
public class PanelOverlay extends OverlayPanel
{
    private final GuideChainPlugin plugin;
    private final GuideChainConfig config;

    @Inject
    PanelOverlay(GuideChainPlugin plugin, GuideChainConfig config)
    {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        // Allow wider panel for long instructions
        setPreferredSize(new Dimension(220, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showPanel()) return null;

        GuideStep step = plugin.getCurrentStep();
        Guide guide    = plugin.getGuideManager().currentGuide();
        int total      = plugin.getGuideManager().totalSteps();
        int current    = plugin.getGuideManager().globalStepNumber();

        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(
            TitleComponent.builder().text("Guide Chain").build());

        if (guide != null && guide.name != null)
        {
            panelComponent.getChildren().add(
                LineComponent.builder()
                    .left(guide.name)
                    .right(current + "/" + total)
                    .build());
        }
        else
        {
            panelComponent.getChildren().add(
                LineComponent.builder()
                    .left("Step")
                    .right(current + "/" + total)
                    .build());
        }

        if (step == null)
        {
            panelComponent.getChildren().add(
                LineComponent.builder().left("Chain complete!").build());
            return super.render(graphics);
        }

        // Instruction text (wrapped)
        if (step.instruction != null)
        {
            panelComponent.getChildren().add(
                LineComponent.builder().left("─────────────────────").build());
            panelComponent.getChildren().add(
                LineComponent.builder().left(step.instruction).build());
        }

        // Detail text
        if (step.detail != null && !step.detail.isEmpty())
        {
            panelComponent.getChildren().add(
                LineComponent.builder().left("  " + step.detail).build());
        }

        panelComponent.getChildren().add(
            LineComponent.builder().left("─────────────────────").build());

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("← Back")
                .right("Skip →")
                .build());

        return super.render(graphics);
    }
}
