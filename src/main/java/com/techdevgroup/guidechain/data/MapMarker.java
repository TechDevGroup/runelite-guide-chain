package com.techdevgroup.guidechain.data;

/**
 * A point of interest to pin on the world map and use for the
 * directional hint arrow when the destination is off-screen.
 */
public class MapMarker
{
    /** Absolute world X coordinate. */
    public int x;
    /** Absolute world Y coordinate. */
    public int y;
    /** Plane (default 0). */
    public int plane;
    /** Short label shown on the world-map tooltip. */
    public String label;
}
