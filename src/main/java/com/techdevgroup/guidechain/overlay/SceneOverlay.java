package com.techdevgroup.guidechain.overlay;

import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.GuideChainPlugin;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.data.TargetType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.GameObject;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;

/**
 * Draws clickbox outlines on scene objects, NPCs, and ground tiles
 * for whichever highlights are active on the current step.
 */
public class SceneOverlay extends Overlay
{
    private final Client client;
    private final GuideChainPlugin plugin;
    private final GuideChainConfig config;

    @Inject
    SceneOverlay(GuideChainPlugin plugin, GuideChainConfig config, Client client)
    {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        GuideStep step = plugin.getCurrentStep();
        if (step == null) return null;

        Color c    = config.highlightColor();
        Color fill = new Color(c.getRed(), c.getGreen(), c.getBlue(), 30);
        Point mouse = client.getMouseCanvasPosition();

        for (HighlightTarget t : step.highlights())
        {
            switch (t.type)
            {
                case OBJECT:
                    renderObjects(graphics, t, c, fill, mouse);
                    break;
                case NPC:
                    renderNpcs(graphics, t, c, fill, mouse);
                    break;
                case TILE:
                    renderTile(graphics, t, c, fill);
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    private void renderObjects(Graphics2D g, HighlightTarget t, Color c, Color fill, Point mouse)
    {
        if (client.getScene() == null) return;
        GameObject[] flatScene = flattenScene();
        for (GameObject obj : flatScene)
        {
            if (obj == null || obj.getId() != t.id) continue;
            // Optional world-position disambiguation
            if (t.worldX != 0 || t.worldY != 0)
            {
                WorldPoint wp = obj.getWorldLocation();
                if (wp.getX() != t.worldX || wp.getY() != t.worldY || wp.getPlane() != t.plane) continue;
            }
            Shape clickbox = obj.getClickbox();
            if (clickbox != null)
            {
                OverlayUtil.renderHoverableArea(g, clickbox, mouse, fill, c.darker(), c);
            }
        }
    }

    private void renderNpcs(Graphics2D g, HighlightTarget t, Color c, Color fill, Point mouse)
    {
        for (NPC npc : client.getNpcs())
        {
            if (npc == null || npc.getId() != t.id) continue;
            Shape clickbox = npc.getConvexHull();
            if (clickbox == null) clickbox = npc.getCanvasTilePoly();
            if (clickbox != null)
            {
                OverlayUtil.renderHoverableArea(g, clickbox, mouse, fill, c.darker(), c);
            }
        }
    }

    private void renderTile(Graphics2D g, HighlightTarget t, Color c, Color fill)
    {
        int plane = t.plane >= 0 ? t.plane : client.getPlane();
        if (plane != client.getPlane()) return;
        WorldPoint wp = new WorldPoint(t.worldX, t.worldY, plane);
        LocalPoint lp = LocalPoint.fromWorld(client, wp);
        if (lp == null) return;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;
        g.setColor(fill);
        g.fill(poly);
        g.setColor(c);
        g.draw(poly);
    }

    // Flatten the 3-D tile scene into a single array of distinct GameObjects
    private GameObject[] flattenScene()
    {
        // We iterate tile GameObjects via the tile grid
        // Use a simple collection; duplicates are harmless (same object ref).
        var tiles = client.getScene().getTiles();
        java.util.List<GameObject> objs = new java.util.ArrayList<>();
        int plane = client.getPlane();
        for (int x = 0; x < tiles[plane].length; x++)
        {
            for (int y = 0; y < tiles[plane][x].length; y++)
            {
                net.runelite.api.Tile tile = tiles[plane][x][y];
                if (tile == null) continue;
                for (GameObject obj : tile.getGameObjects())
                {
                    if (obj != null) objs.add(obj);
                }
            }
        }
        return objs.toArray(new GameObject[0]);
    }
}
