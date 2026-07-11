package com.techdevgroup.guidechain.overlay;

import com.techdevgroup.guidechain.GuideChainConfig;
import com.techdevgroup.guidechain.GuideChainPlugin;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.data.TargetType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;

/**
 * Draws a bobbing, downward-pointing arrow floating above the current
 * step's on-screen world target — a persistent "you are here" marker so
 * the player never mistakes an unfinished step for a finished one.
 *
 * <p><strong>Technique credit:</strong> the approach (an ABOVE_SCENE overlay
 * that resolves the target tile to a {@link LocalPoint}, projects it to a
 * canvas point above the target via {@link Perspective}, applies a sinusoidal
 * vertical bob keyed off {@link Client#getGameCycle()}, and draws a short
 * vertical stem plus a filled arrowhead with a black outline) is modelled on
 * quest-helper's {@code DirectionArrow.drawWorldArrow} /
 * {@code QuestHelperWorldArrowOverlay}
 * (<a href="https://github.com/Zoinkwiz/quest-helper">Zoinkwiz/quest-helper</a>,
 * BSD-2-Clause). This is an original re-implementation — no code copied.
 *
 * <h3>Persistence</h3>
 * The arrow renders every frame for as long as the current step is active.
 * It is driven entirely by the shared {@code GuideStore}'s notion of the
 * current step: the arrow vanishes only when that step changes — which
 * happens when the step's completion condition (QUEST / VARBIT / SKILL /
 * ITEM_HELD / REGION) auto-satisfies on a game tick, or when the user presses
 * Mark Done in the sidebar panel / web view. There is no separate timer or
 * dismiss: as long as {@code GuideStore.currentStep()} points at a step with
 * an on-screen world target, the arrow bobs overhead.
 *
 * <p>When the target tile is off-screen the arrow simply does not project;
 * the existing {@code WorldMapOverlay} world-map pin and edge directional
 * hint cover that case.
 */
public class WorldArrowOverlay extends Overlay
{
    /** Bob period divisor — larger = slower bob. */
    private static final double BOB_PERIOD    = 20.0;
    /** Bob amplitude in canvas pixels. */
    private static final double BOB_AMPLITUDE = 6.0;
    /** How high above the tile (world units) the arrow floats. */
    private static final int    HOVER_HEIGHT  = 220;
    /** Overall arrow height in canvas pixels (stem + head). */
    private static final int    ARROW_HEIGHT  = 26;
    /** Arrowhead half-width in canvas pixels. */
    private static final int    HEAD_HALF_W   = 8;
    /** Arrowhead height in canvas pixels. */
    private static final int    HEAD_HEIGHT   = 12;

    private final Client client;
    private final GuideChainPlugin plugin;
    private final GuideChainConfig config;

    @Inject
    WorldArrowOverlay(GuideChainPlugin plugin, GuideChainConfig config, Client client)
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
        if (!config.showWorldArrow()) return null;

        GuideStep step = plugin.getCurrentStep();
        if (step == null) return null;

        // Only the first world-locatable target gets the arrow, to avoid clutter.
        for (HighlightTarget t : step.highlights())
        {
            LocalPoint lp = resolveLocalPoint(t);
            if (lp == null) continue;
            drawArrowAbove(graphics, lp, config.worldArrowColor());
            break;
        }
        return null;
    }

    // ── Target resolution ─────────────────────────────────────────────────────

    /**
     * Resolve a highlight target to a scene {@link LocalPoint}, or {@code null}
     * if the target has no world location or is not currently in the scene /
     * on the current plane.
     */
    private LocalPoint resolveLocalPoint(HighlightTarget t)
    {
        if (t == null || t.type == null) return null;
        switch (t.type)
        {
            case NPC:    return npcLocation(t.id);
            case OBJECT: return objectLocation(t);
            case TILE:   return tileLocation(t.worldX, t.worldY, t.plane);
            default:     return null; // ITEM / WIDGET have no world location
        }
    }

    /** Live location of the first NPC in the scene matching the id. */
    private LocalPoint npcLocation(int npcId)
    {
        for (NPC npc : client.getNpcs())
        {
            if (npc != null && npc.getId() == npcId)
            {
                return npc.getLocalLocation();
            }
        }
        return null;
    }

    /**
     * Location of a scene game object matching the id (and world position if
     * the target specifies one for disambiguation).
     */
    private LocalPoint objectLocation(HighlightTarget t)
    {
        if (client.getScene() == null) return null;
        Tile[][][] tiles = client.getScene().getTiles();
        int plane = client.getPlane();
        for (int x = 0; x < tiles[plane].length; x++)
        {
            for (int y = 0; y < tiles[plane][x].length; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;
                for (GameObject obj : tile.getGameObjects())
                {
                    if (obj == null || obj.getId() != t.id) continue;
                    if (t.worldX != 0 || t.worldY != 0)
                    {
                        WorldPoint wp = obj.getWorldLocation();
                        if (wp.getX() != t.worldX || wp.getY() != t.worldY
                            || wp.getPlane() != t.plane) continue;
                    }
                    return obj.getLocalLocation();
                }
            }
        }
        return null;
    }

    /** Convert an absolute world tile to a scene {@link LocalPoint}. */
    private LocalPoint tileLocation(int worldX, int worldY, int plane)
    {
        int p = plane >= 0 ? plane : client.getPlane();
        if (p != client.getPlane()) return null;
        WorldPoint wp = new WorldPoint(worldX, worldY, p);
        return LocalPoint.fromWorld(client, wp);
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    private void drawArrowAbove(Graphics2D g, LocalPoint lp, Color color)
    {
        // Project the tile to a canvas point HOVER_HEIGHT world-units up.
        Point base = Perspective.localToCanvas(client, lp, client.getPlane(), HOVER_HEIGHT);
        if (base == null) return;

        // Sinusoidal bob keyed off the game cycle so it animates every frame.
        double bob = Math.sin(client.getGameCycle() / BOB_PERIOD) * BOB_AMPLITUDE;

        int tipX = base.getX();
        int tipY = (int) (base.getY() + bob);          // arrow tip (points down at the target)
        int topY = tipY - ARROW_HEIGHT;                 // top of the stem

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Stem
        Stroke outlineStroke = new BasicStroke(4.0f);
        Stroke fillStroke    = new BasicStroke(2.0f);

        g2.setStroke(outlineStroke);
        g2.setColor(Color.BLACK);
        g2.drawLine(tipX, topY, tipX, tipY - HEAD_HEIGHT + 2);

        g2.setStroke(fillStroke);
        g2.setColor(color);
        g2.drawLine(tipX, topY, tipX, tipY - HEAD_HEIGHT + 2);

        // Arrowhead (downward triangle, tip at the target)
        Polygon head = new Polygon();
        head.addPoint(tipX, tipY);                          // bottom tip
        head.addPoint(tipX - HEAD_HALF_W, tipY - HEAD_HEIGHT); // upper-left
        head.addPoint(tipX + HEAD_HALF_W, tipY - HEAD_HEIGHT); // upper-right

        g2.setStroke(outlineStroke);
        g2.setColor(Color.BLACK);
        g2.drawPolygon(head);
        g2.setColor(color);
        g2.fillPolygon(head);

        g2.dispose();
    }
}
