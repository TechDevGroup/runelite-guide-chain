package com.techdevgroup.guidechain.data;

/**
 * Describes one entity that should be highlighted while a step is active.
 *
 * The {@code type} field is the discriminator; only the fields relevant to
 * that type need be set in JSON — all others default to 0 / null.
 *
 * <pre>
 * OBJECT  → id (game-object id), worldX/worldY/plane (optional, for disambiguation)
 * NPC     → id (npc id)
 * ITEM    → id (item id), container ("INVENTORY" | "BANK" | "EQUIPMENT")
 * WIDGET  → group (widget group id), child (widget child id)
 * TILE    → worldX, worldY, plane
 * </pre>
 */
public class HighlightTarget
{
    public TargetType type;

    /** Primary numeric id: object id / npc id / item id depending on type. */
    public int id;

    /** Absolute world X (OBJECT location disambiguation, or TILE x). */
    public int worldX;
    /** Absolute world Y (OBJECT location disambiguation, or TILE y). */
    public int worldY;
    /** Plane (OBJECT / TILE). Defaults to 0. */
    public int plane;

    /** ITEM only: "INVENTORY", "BANK", or "EQUIPMENT". */
    public String container;

    /** WIDGET only: widget group id. */
    public int group;
    /** WIDGET only: widget child id. */
    public int child;
}
