package com.techdevgroup.guidechain.overlay;

import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.GuideChainPlugin;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.data.TargetType;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;

/**
 * Draws a pulsed border around arbitrary RuneLite widgets
 * (group + child) specified in a step's WIDGET highlight targets.
 */
public class WidgetHighlightOverlay extends Overlay
{
    private final Client client;
    private final GuideChainPlugin plugin;
    private final GuideChainConfig config;

    @Inject
    WidgetHighlightOverlay(GuideChainPlugin plugin, GuideChainConfig config, Client client)
    {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        GuideStep step = plugin.getCurrentStep();
        if (step == null) return null;

        Color c    = config.highlightColor();
        Color fill = new Color(c.getRed(), c.getGreen(), c.getBlue(), 40);
        Stroke thick = new BasicStroke(2.0f);
        Stroke prev  = graphics.getStroke();

        for (HighlightTarget t : step.highlights())
        {
            if (t.type != TargetType.WIDGET) continue;
            Widget w = client.getWidget(t.group, t.child);
            if (w == null || w.isHidden()) continue;
            Rectangle bounds = w.getBounds();
            if (bounds == null) continue;
            graphics.setColor(fill);
            graphics.fill(bounds);
            graphics.setStroke(thick);
            graphics.setColor(c);
            graphics.draw(bounds);
            graphics.setStroke(prev);
        }
        return null;
    }
}
