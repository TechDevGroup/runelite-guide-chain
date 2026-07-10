package com.techdevgroup.guidechain.data;

/** Discriminator for what kind of entity a {@link HighlightTarget} refers to. */
public enum TargetType
{
    /** A scene game-object identified by its object id. */
    OBJECT,
    /** An NPC identified by its npc id. */
    NPC,
    /** An item in a specific container (inventory / bank / equipment). */
    ITEM,
    /** A RuneLite widget addressed by group + child indices. */
    WIDGET,
    /** A world tile at absolute (x, y, plane) coordinates. */
    TILE
}
