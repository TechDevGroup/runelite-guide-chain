package com.techdevgroup.guidechain;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("guidechain")
public interface GuideChainConfig extends Config
{
    // ── Data source ───────────────────────────────────────────────────────────

    @ConfigSection(
        name = "Data Source",
        description = "Where guides are fetched from",
        position = 0
    )
    String dataSourceSection = "dataSource";

    @ConfigItem(
        keyName     = "guidesRepo",
        name        = "Guides Repo",
        description = "GitHub owner/repo containing the guides branch (e.g. TechDevGroup/runelite-guide-chain).",
        section     = "dataSource",
        position    = 1
    )
    default String guidesRepo() { return "TechDevGroup/runelite-guide-chain"; }

    @ConfigItem(
        keyName     = "guidesBranch",
        name        = "Guides Branch",
        description = "Branch name on the repo where manifest.json and guide files live.",
        section     = "dataSource",
        position    = 2
    )
    default String guidesBranch() { return "guides"; }

    // ── Chain selection ───────────────────────────────────────────────────────

    @ConfigSection(
        name = "Chain",
        description = "Which guide chain to follow",
        position = 10
    )
    String chainSection = "chain";

    @ConfigItem(
        keyName     = "selectedChain",
        name        = "Selected Chain",
        description = "Index of the chain to play from the manifest (0-based).",
        section     = "chain",
        position    = 11
    )
    default int selectedChain() { return 0; }

    // ── Display ───────────────────────────────────────────────────────────────

    @ConfigSection(
        name = "Display",
        description = "Overlay appearance settings",
        position = 20
    )
    String displaySection = "display";

    @ConfigItem(
        keyName     = "showPanel",
        name        = "Show Instruction Panel",
        description = "Show the sidebar panel with the current step instruction.",
        section     = "display",
        position    = 21
    )
    default boolean showPanel() { return true; }

    @Alpha
    @ConfigItem(
        keyName     = "highlightColor",
        name        = "Highlight Color",
        description = "Color used for all scene, NPC, item, tile, and widget highlights.",
        section     = "display",
        position    = 22
    )
    default Color highlightColor() { return new Color(0, 200, 255, 200); }

    // ── Web view ──────────────────────────────────────────────────────────────

    @ConfigSection(
        name = "Web View",
        description = "Embedded localhost web view of the guide state (never automates input).",
        position = 25
    )
    String webSection = "web";

    @ConfigItem(
        keyName     = "webServerEnabled",
        name        = "Enable Web View Server",
        description = "Serve the guide plan on localhost (127.0.0.1 only). All actions go through the same shared state as the in-game overlay.",
        section     = "web",
        position    = 26
    )
    default boolean webServerEnabled() { return false; }

    @ConfigItem(
        keyName     = "webServerPort",
        name        = "Web View Port",
        description = "TCP port for the localhost web view.",
        section     = "web",
        position    = 27
    )
    @Range(min = 1024, max = 65535)
    default int webServerPort() { return 7780; }

    @ConfigItem(
        keyName     = "webViewUrl",
        name        = "Open Web View",
        description = "With the server enabled, open this address in your browser (adjust if you changed the port).",
        section     = "web",
        position    = 28
    )
    default String webViewUrl() { return "http://127.0.0.1:7780/"; }

    // ── Debug ─────────────────────────────────────────────────────────────────

    @ConfigSection(
        name = "Debug",
        description = "Diagnostic overlays",
        position = 30
    )
    String debugSection = "debug";

    @ConfigItem(
        keyName     = "showDebug",
        name        = "Show Debug Panel",
        description = "Show the debug panel with live condition values and active targets.",
        section     = "debug",
        position    = 31
    )
    default boolean showDebug() { return false; }
}
