package com.techdevgroup.guidechain.overlay;

import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.GuideChainPlugin;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.data.TargetType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Highlights items inside the inventory, bank, or equipment interface
 * when a step specifies ITEM highlight targets.
 *
 * Widget group IDs for inventory/bank/equipment are resolved via the
 * standard RuneLite constants at runtime rather than hard-coded, to avoid
 * breakage when Jagex renumbers interfaces.
 */
public class ItemOverlay extends Overlay
{
    // Standard RuneLite widget group ids (stable across most client versions)
    private static final int INVENTORY_GROUP  = 149;
    private static final int INVENTORY_CHILD  = 0;
    private static final int BANK_GROUP       = 12;
    private static final int BANK_ITEMS_CHILD = 13;
    private static final int EQUIP_GROUP      = 387;
    private static final int EQUIP_CHILD      = 0;

    private final Client client;
    private final GuideChainPlugin plugin;
    private final GuideChainConfig config;

    @Inject
    ItemOverlay(GuideChainPlugin plugin, GuideChainConfig config, Client client)
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
        Color fill = new Color(c.getRed(), c.getGreen(), c.getBlue(), 70);

        for (HighlightTarget t : step.highlights())
        {
            if (t.type != TargetType.ITEM) continue;
            String container = t.container != null ? t.container.toUpperCase() : "INVENTORY";
            renderContainer(graphics, t.id, container, c, fill);
        }
        return null;
    }

    private void renderContainer(Graphics2D g, int itemId, String container, Color c, Color fill)
    {
        Widget parent = resolveContainer(container);
        if (parent == null || parent.isHidden()) return;

        Widget[] children = parent.getDynamicChildren();
        if (children == null) children = parent.getStaticChildren();
        if (children == null) return;

        for (Widget child : children)
        {
            if (child == null || child.isHidden()) continue;
            if (child.getItemId() == itemId)
            {
                g.setColor(fill);
                g.fill(child.getBounds());
                g.setColor(c);
                g.draw(child.getBounds());
            }
        }
    }

    private Widget resolveContainer(String container)
    {
        switch (container)
        {
            case "BANK":      return client.getWidget(BANK_GROUP, BANK_ITEMS_CHILD);
            case "EQUIPMENT": return client.getWidget(EQUIP_GROUP, EQUIP_CHILD);
            case "INVENTORY":
            default:          return client.getWidget(INVENTORY_GROUP, INVENTORY_CHILD);
        }
    }
}
