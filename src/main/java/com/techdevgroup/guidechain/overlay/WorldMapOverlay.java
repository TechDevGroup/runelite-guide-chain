package com.techdevgroup.guidechain.overlay;

import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.GuideChainPlugin;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.MapMarker;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages world-map markers for the current step's map points and draws
 * a directional arrow at the edge of the viewport when the destination
 * is off-screen.
 */
public class WorldMapOverlay extends Overlay
{
    private static final int    ARROW_MARGIN = 28;
    private static final int    ARROW_SIZE   = 14;
    private static final Color  ARROW_COLOR  = new Color(255, 215, 0, 230);

    private final Client client;
    private final GuideChainPlugin plugin;
    private final GuideChainConfig config;
    private final WorldMapPointManager worldMapPointManager;

    /** Currently pinned map points so we can remove them on step change. */
    private final List<WorldMapPoint> activePoints = new ArrayList<>();
    private String lastStepId = null;

    @Inject
    WorldMapOverlay(GuideChainPlugin plugin,
                    GuideChainConfig config,
                    Client client,
                    WorldMapPointManager worldMapPointManager)
    {
        super(plugin);
        this.plugin               = plugin;
        this.config               = config;
        this.client               = client;
        this.worldMapPointManager = worldMapPointManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        GuideStep step = plugin.getCurrentStep();
        syncMapPoints(step);
        if (step == null) return null;

        // Directional hint arrow for each off-screen map marker
        for (MapMarker marker : step.mapMarkers())
        {
            WorldPoint wp = new WorldPoint(marker.x, marker.y, marker.plane);
            drawDirectionalHint(graphics, wp);
        }
        return null;
    }

    /** Called on plugin shutDown to clean up all active map points. */
    public void clearAll()
    {
        for (WorldMapPoint p : activePoints)
        {
            worldMapPointManager.remove(p);
        }
        activePoints.clear();
        lastStepId = null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void syncMapPoints(GuideStep step)
    {
        String newId = step != null ? step.id : null;
        if (java.util.Objects.equals(newId, lastStepId)) return;

        // Remove old pins
        for (WorldMapPoint p : activePoints)
        {
            worldMapPointManager.remove(p);
        }
        activePoints.clear();
        lastStepId = newId;

        if (step == null) return;

        // Add new pins
        for (MapMarker marker : step.mapMarkers())
        {
            WorldPoint wp = new WorldPoint(marker.x, marker.y, marker.plane);
            BufferedImage icon = buildMarkerIcon(config.highlightColor());
            WorldMapPoint wmp = WorldMapPoint.builder()
                .worldPoint(wp)
                .image(icon)
                .name(marker.label != null ? marker.label : "Destination")
                .build();
            worldMapPointManager.add(wmp);
            activePoints.add(wmp);
        }
    }

    private void drawDirectionalHint(Graphics2D g, WorldPoint destination)
    {
        if (client.getLocalPlayer() == null) return;
        WorldPoint player = client.getLocalPlayer().getWorldLocation();

        // Compute screen centre
        int vw = client.getViewportWidth();
        int vh = client.getViewportHeight();
        int cx = vw / 2;
        int cy = vh / 2;

        // World-to-pixel conversion (rough — actual rendering is Jagex-internal)
        // We only need the direction angle; we check if destination is on-screen
        // by converting via Perspective and checking if the tile poly is null.
        net.runelite.api.coords.LocalPoint lp =
            net.runelite.api.coords.LocalPoint.fromWorld(client, destination);

        boolean onScreen = (lp != null)
            && net.runelite.api.Perspective.getCanvasTilePoly(client, lp) != null;
        if (onScreen) return;

        // Calculate compass direction from player to destination
        double dx = destination.getX() - player.getX();
        double dy = destination.getY() - player.getY();
        double angle = Math.atan2(-dy, dx); // note: world Y increases north, screen Y increases down

        // Place arrow at edge of viewport
        int margin = ARROW_MARGIN;
        double ax = cx + (cx - margin) * Math.cos(angle);
        double ay = cy + (cy - margin) * Math.sin(angle);

        // Clamp to viewport edge
        ax = Math.max(margin, Math.min(vw - margin, ax));
        ay = Math.max(margin, Math.min(vh - margin, ay));

        drawArrow(g, (int) ax, (int) ay, angle);
    }

    private void drawArrow(Graphics2D g, int x, int y, double angle)
    {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(ARROW_COLOR);
        g2.setStroke(new BasicStroke(2.0f));

        AffineTransform at = AffineTransform.getRotateInstance(angle, x, y);
        g2.setTransform(at);

        int hs = ARROW_SIZE / 2;
        // Arrow pointing right (0 rad), rotated to angle
        int[] xp = { x - hs, x + hs, x + hs, x + hs, x - hs };
        int[] yp = { y - 4,  y - 4,  y - 8,  y + 8,  y + 4  };
        // Simple filled triangle arrow
        int[] tx = { x - hs, x + hs, x - hs };
        int[] ty = { y - 6,  y,      y + 6  };
        g2.fillPolygon(tx, ty, 3);
        g2.drawPolygon(tx, ty, 3);
        g2.dispose();
    }

    private static BufferedImage buildMarkerIcon(Color color)
    {
        int size = 12;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(1, 1, size - 2, size - 2);
        g.setColor(Color.WHITE);
        g.drawOval(1, 1, size - 2, size - 2);
        g.dispose();
        return img;
    }
}
