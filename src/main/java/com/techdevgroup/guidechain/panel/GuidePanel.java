package com.techdevgroup.guidechain.panel;

import com.techdevgroup.guidechain.data.ConditionType;
import com.techdevgroup.guidechain.data.Guide;
import com.techdevgroup.guidechain.data.GuideHint;
import com.techdevgroup.guidechain.data.GuideRef;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.manager.GuideManager;
import com.techdevgroup.guidechain.store.GuideStore;
import com.techdevgroup.guidechain.store.PlanRow;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sidebar panel — the interactive guide surface in the RuneLite toolbar.
 *
 * <p>Opened via the Guide Chain {@link net.runelite.client.ui.NavigationButton}
 * (toolbar icon). Replaces the old on-canvas {@code PanelOverlay}; all click
 * targets here are real Swing components so they accept mouse input.
 *
 * <p>Two sections, top to bottom:
 * <ul>
 *   <li><b>Current step card</b> — chain picker, progress counter, the
 *       current step's instruction/detail, its steerKind badge / hint chips /
 *       wiki ref links / passiveOverlays badges, and the Mark Done button.</li>
 *   <li><b>Full plan list</b> (scrollable, SYNTHESIS §4 Lane 4 — parity with
 *       the web view) — guide/phase/checkpoint dividers, a collapsible
 *       supply-chain grouping, per-row steerKind/hint/ref chips, a two-way
 *       done/uncheck checkbox that calls the exact same
 *       {@link GuideStore#toggle}  the web view's checkbox calls, and a
 *       separate "Background loops" sub-list for {@code slotType ==
 *       "background"} steps with a live next-fire countdown (§S4). Passive
 *       steps never render their own card (§1a) — only as badges on a host.</li>
 * </ul>
 *
 * <p><strong>Hard rule:</strong> this panel is render/annotate only. Every
 * action here (checkbox, Mark Done, "Log now", ref-chip click) mutates the
 * shared {@link GuideStore} or opens a browser tab via {@link LinkBrowser} —
 * nothing here ever sends input to the game client.
 *
 * <p>Registered as a {@link GuideStore.Listener} by {@link
 * com.techdevgroup.guidechain.GuideChainPlugin} on startUp so the panel
 * refreshes whenever the store changes (plan load, chain switch, step
 * advance, or a RECURRING loop re-arming — the {@code "bg"} store event).
 * Refreshes also on {@link #onActivate()} so the panel is always current when
 * the user opens it, and on a 30s timer so loops-lane countdowns keep ticking
 * even with no store mutation.
 *
 * <p><strong>Thread safety:</strong> the storeListener fires on the plugin
 * game-tick thread; all UI mutations are dispatched via
 * {@link SwingUtilities#invokeLater}.
 *
 * <p><strong>Null-safety:</strong> every field this panel reads from {@link
 * GuideStep} (§1e's six new fields, refs, hints, checkpoint) is additive and
 * nullable on the wire — every render helper below treats absence as "don't
 * show this", never as an error, so older/newer guide JSON always renders.
 */
@Singleton
public class GuidePanel extends PluginPanel
{
    // Inline HTML body width keeps JLabel text wrapping reliably at ~200 px
    // regardless of container width negotiation in BoxLayout.Y_AXIS.
    private static final int TEXT_WRAP_PX = 200;
    private static final int ROW_WRAP_PX  = 170;
    private static final int COUNTDOWN_REFRESH_MS = 30_000;

    private final GuideStore store;
    private final GuideManager guideManager;

    private final JComboBox<String> chainCombo      = new JComboBox<>();
    private final JLabel            progressLabel   = new JLabel(" ");
    private final JLabel            instructionLabel = new JLabel();
    private final JLabel            detailLabel     = new JLabel();
    private final JPanel            detailPanel     = new JPanel(new BorderLayout());
    private final JPanel            currentBadges   = new JPanel();
    private final JButton           markDoneButton  = new JButton("Mark Done");
    private final JPanel            listContainer   = new JPanel();

    private Timer countdownTimer;

    /**
     * Guard against combo selection → store change → panel refresh →
     * combo update → store change loop.
     */
    private boolean updatingCombo = false;

    /**
     * Panel-local UI state (not shared with the web view / store): which
     * collapsible sections are expanded. Supply chains default COLLAPSED —
     * they repeat many near-identical steps that would otherwise flood a
     * ~200px sidebar; the loops lane defaults EXPANDED — it's usually short
     * and the countdown is the whole point of looking at it.
     */
    private final Set<String> expandedSupplyChains = new LinkedHashSet<>();
    private boolean loopsLaneExpanded = true;

    @Inject
    public GuidePanel(GuideStore store, GuideManager guideManager)
    {
        this.store        = store;
        this.guideManager = guideManager;
        buildUi();
        startCountdownTimer();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // ── Title ──────────────────────────────────────────────────────────────
        JLabel title = new JLabel("Guide Chain");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(8));

        // ── Chain picker ───────────────────────────────────────────────────────
        JLabel chainLabel = new JLabel("Chain:");
        chainLabel.setForeground(Color.LIGHT_GRAY);
        chainLabel.setFont(chainLabel.getFont().deriveFont(11f));
        chainLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(chainLabel);
        content.add(Box.createVerticalStrut(2));

        chainCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        chainCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        chainCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        chainCombo.setForeground(Color.WHITE);
        chainCombo.addActionListener(e ->
        {
            if (updatingCombo) return;
            int idx = chainCombo.getSelectedIndex();
            if (idx >= 0) store.selectChainByIndex(idx);
        });
        content.add(chainCombo);
        content.add(Box.createVerticalStrut(8));

        // ── Separator ──────────────────────────────────────────────────────────
        content.add(makeSeparator());
        content.add(Box.createVerticalStrut(6));

        // ── Progress ───────────────────────────────────────────────────────────
        progressLabel.setForeground(Color.LIGHT_GRAY);
        progressLabel.setFont(progressLabel.getFont().deriveFont(11f));
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(progressLabel);
        content.add(Box.createVerticalStrut(8));

        // ── Instruction ────────────────────────────────────────────────────────
        instructionLabel.setForeground(Color.WHITE);
        instructionLabel.setFont(instructionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        instructionLabel.setVerticalAlignment(SwingConstants.TOP);
        instructionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(instructionLabel);

        // ── Detail (hidden when blank) ─────────────────────────────────────────
        content.add(Box.createVerticalStrut(4));
        detailLabel.setForeground(Color.LIGHT_GRAY);
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.ITALIC, 11f));
        detailLabel.setVerticalAlignment(SwingConstants.TOP);
        detailPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        detailPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailPanel.add(detailLabel, BorderLayout.CENTER);
        content.add(detailPanel);

        // ── Badges/chips for the current step (steerKind, passiveOverlays, hints, refs) ──
        content.add(Box.createVerticalStrut(4));
        currentBadges.setLayout(new BoxLayout(currentBadges, BoxLayout.Y_AXIS));
        currentBadges.setBackground(ColorScheme.DARK_GRAY_COLOR);
        currentBadges.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(currentBadges);

        content.add(Box.createVerticalStrut(10));

        // ── Mark Done button ───────────────────────────────────────────────────
        markDoneButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        markDoneButton.setBackground(new Color(0, 130, 65));
        markDoneButton.setForeground(Color.WHITE);
        markDoneButton.setFocusPainted(false);
        markDoneButton.addActionListener(e ->
        {
            String key = store.currentStepKey();
            if (key != null) store.markDone(key);
        });
        content.add(markDoneButton);

        content.add(Box.createVerticalStrut(10));
        content.add(makeSeparator());

        add(content, BorderLayout.NORTH);

        // ── Full plan list (parity with the web view) ───────────────────────────
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        JScrollPane scroll = new JScrollPane(listContainer,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    /** Keeps loops-lane countdowns ticking even when nothing else changes the store. */
    private void startCountdownTimer()
    {
        countdownTimer = new Timer(COUNTDOWN_REFRESH_MS, e ->
        {
            if (isShowing()) refresh();
        });
        countdownTimer.setRepeats(true);
        countdownTimer.start();
    }

    /** Stops the countdown-refresh timer. Called from the plugin's shutDown(). */
    public void stopTimer()
    {
        if (countdownTimer != null) countdownTimer.stop();
    }

    // ── PluginPanel lifecycle ─────────────────────────────────────────────────

    /** Refresh state when the user opens the panel via the toolbar icon. */
    @Override
    public void onActivate()
    {
        refresh();
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Rebuild all displayed data from the live store state.
     * Must be called on the EDT (callers should dispatch via
     * {@link SwingUtilities#invokeLater} when coming from a non-EDT thread).
     */
    public void refresh()
    {
        // ── Chain combo ────────────────────────────────────────────────────────
        List<String> chainNames = guideManager.chainNames();
        int chainIdx = store.chainIndex();

        updatingCombo = true;
        try
        {
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            for (String name : chainNames) model.addElement(name);
            chainCombo.setModel(model);
            if (!chainNames.isEmpty() && chainIdx < chainNames.size())
            {
                chainCombo.setSelectedIndex(chainIdx);
            }
        }
        finally
        {
            updatingCombo = false;
        }

        // ── Progress ───────────────────────────────────────────────────────────
        int total   = store.totalSteps();
        int current = store.globalStepNumber();
        Guide guide = store.currentGuide();
        String guideName = (guide != null && guide.name != null) ? guide.name : "";

        if (total > 0)
        {
            progressLabel.setText(guideName + "  ·  " + current + " / " + total);
        }
        else
        {
            progressLabel.setText(chainNames.isEmpty() ? "Loading…" : "Chain ready");
        }

        refreshCurrentStep(store.currentStep());
        rebuildList();

        revalidate();
        repaint();
    }

    /** Rebuilds the current-step card: instruction, detail, badges/chips, Mark Done. */
    private void refreshCurrentStep(GuideStep step)
    {
        currentBadges.removeAll();

        if (step == null)
        {
            String msg = store.isChainComplete() ? "Chain complete!" : "No active step.";
            instructionLabel.setText(htmlWrap(msg, TEXT_WRAP_PX));
            detailPanel.setVisible(false);
            currentBadges.setVisible(false);
            markDoneButton.setVisible(false);
            return;
        }

        instructionLabel.setText(htmlWrap(step.instruction != null ? step.instruction : "", TEXT_WRAP_PX));

        String detail = (step.detail != null && !step.detail.isEmpty()) ? step.detail : null;
        if (detail != null)
        {
            detailLabel.setText(htmlWrap(detail, TEXT_WRAP_PX));
            detailPanel.setVisible(true);
        }
        else
        {
            detailPanel.setVisible(false);
        }

        boolean anyBadge = false;
        if (step.steerKind != null)
        {
            currentBadges.add(leftAligned(steerBadge(step.steerKind)));
            anyBadge = true;
        }
        JComponent overlays = passiveOverlaysRow(step);
        if (overlays != null) { currentBadges.add(overlays); anyBadge = true; }
        JComponent hints = hintChipsRow(step, TEXT_WRAP_PX);
        if (hints != null) { currentBadges.add(hints); anyBadge = true; }
        JComponent refs = refChipsRow(step);
        if (refs != null) { currentBadges.add(refs); anyBadge = true; }
        currentBadges.setVisible(anyBadge);

        // Show Mark Done for MANUAL/RECURRING-marker steps or steps with no conditions.
        // (A RECURRING current step should never actually happen — GuideStore's
        // pointer skips them — but this stays defensive rather than assuming that.)
        boolean needsManual = step.completionConditions().stream()
            .anyMatch(c -> c.type == ConditionType.MANUAL || c.type == ConditionType.RECURRING);
        boolean noConditions = step.completionConditions().isEmpty();
        markDoneButton.setVisible(needsManual || noConditions);
    }

    // ── Full plan list ─────────────────────────────────────────────────────────

    /**
     * Rebuilds the scrollable list below the current-step card: guide/phase/
     * checkpoint dividers, collapsible supply-chain grouping, per-row chips,
     * the two-way checkbox, and — pulled out into their own trailing section —
     * every {@code slotType == "background"} step (the loops lane, §S4).
     * {@code slotType == "passive"} steps are skipped entirely (§1a).
     */
    private void rebuildList()
    {
        listContainer.removeAll();

        List<PlanRow> rows = store.plan();
        if (rows.isEmpty())
        {
            listContainer.add(mutedLabel("No guides loaded.", TEXT_WRAP_PX));
            listContainer.revalidate();
            listContainer.repaint();
            return;
        }

        String lastGuide = null, lastPhase = null, lastCheckpoint = null, lastSupplyChain = null;
        boolean supplyCollapsedActive = false;
        List<PlanRow> loopRows = new ArrayList<>();

        for (PlanRow r : rows)
        {
            GuideStep s = r.step;
            if (s == null) continue;

            if (!r.guideId.equals(lastGuide))
            {
                listContainer.add(dividerLabel(r.guideName, 13f, Color.WHITE, 0));
                lastGuide = r.guideId;
                lastPhase = null;
                lastCheckpoint = null;
                lastSupplyChain = null;
                supplyCollapsedActive = false;
            }

            String phase = s.phase;
            if (phase != null && !phase.equals(lastPhase))
            {
                listContainer.add(dividerLabel(phase, 12f, new Color(180, 210, 255), 0));
                lastPhase = phase;
                lastCheckpoint = null;
                lastSupplyChain = null;
                supplyCollapsedActive = false;
            }

            String cp = s.checkpoint;
            if (cp != null && !cp.equals(lastCheckpoint))
            {
                listContainer.add(dividerLabel(cp, 11f, Color.LIGHT_GRAY, 10));
                lastCheckpoint = cp;
            }

            // Checkpoint header records (id prefix "chkpt-") are dividers only.
            if (s.id != null && s.id.startsWith("chkpt-")) continue;

            // Background steps never render inline — collected for the loops lane below.
            if ("background".equals(s.slotType))
            {
                loopRows.add(r);
                continue;
            }
            // Passive embeds never render as their own card (§1a) — only via
            // passiveOverlays badges on whatever host step carries them.
            if ("passive".equals(s.slotType)) continue;

            String chain = s.supplyChain;
            if (chain != null && !chain.equals(lastSupplyChain))
            {
                lastSupplyChain = chain;
                boolean expanded = expandedSupplyChains.contains(chain);
                listContainer.add(supplyChainHeader(chain, expanded));
                supplyCollapsedActive = !expanded;
            }
            else if (chain == null)
            {
                lastSupplyChain = null;
                supplyCollapsedActive = false;
            }
            if (supplyCollapsedActive) continue;

            listContainer.add(stepRow(r, chain != null ? 10 : 0));
        }

        if (!loopRows.isEmpty())
        {
            listContainer.add(Box.createVerticalStrut(6));
            listContainer.add(loopsLaneHeader(loopRows.size()));
            if (loopsLaneExpanded)
            {
                for (PlanRow r : loopRows) listContainer.add(loopRow(r));
            }
        }

        listContainer.revalidate();
        listContainer.repaint();
    }

    /** One ordinary (non-background, non-passive) plan row: checkbox + instruction + chips. */
    private JComponent stepRow(PlanRow r, int indentPx)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(3, indentPx, 3, 0));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);

        boolean checked = r.status == PlanRow.Status.DONE || r.status == PlanRow.Status.SKIPPED;
        JCheckBox check = new JCheckBox();
        check.setSelected(checked);
        check.setOpaque(false);
        // Two-way done/uncheck — the exact same entry point the web view's
        // per-row checkbox calls (POST .../toggle → GuideStore.toggle()).
        check.addActionListener(e -> store.toggle(r.key));
        top.add(check);

        JLabel numLabel = new JLabel(statusIcon(r.status) + " " + r.globalIndex);
        numLabel.setForeground(Color.GRAY);
        numLabel.setFont(numLabel.getFont().deriveFont(10f));
        top.add(numLabel);

        if (r.step.steerKind != null) top.add(steerBadge(r.step.steerKind));

        row.add(top);

        JLabel instr = new JLabel(htmlWrap(r.step.instruction != null ? r.step.instruction : "", ROW_WRAP_PX - indentPx));
        instr.setForeground(r.status == PlanRow.Status.CURRENT ? Color.WHITE : Color.LIGHT_GRAY);
        instr.setFont(instr.getFont().deriveFont(11f));
        instr.setAlignmentX(Component.LEFT_ALIGNMENT);
        instr.setBorder(new EmptyBorder(0, 6, 0, 0));
        row.add(instr);

        JComponent overlays = passiveOverlaysRow(r.step);
        if (overlays != null) { indent(overlays); row.add(overlays); }
        JComponent hints = hintChipsRow(r.step, ROW_WRAP_PX - indentPx);
        if (hints != null) { indent(hints); row.add(hints); }
        JComponent refs = refChipsRow(r.step);
        if (refs != null) { indent(refs); row.add(refs); }

        return row;
    }

    /** One background/RECURRING loops-lane row: instruction, countdown, lifecycle, chips, Log now. */
    private JComponent loopRow(PlanRow r)
    {
        GuideStep s = r.step;
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(4, 10, 4, 4));

        JLabel instr = new JLabel(htmlWrap(s.instruction != null ? s.instruction : "", ROW_WRAP_PX - 10));
        instr.setForeground(Color.WHITE);
        instr.setFont(instr.getFont().deriveFont(11f));
        instr.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(instr);

        JLabel countdown = new JLabel(countdownText(r.key));
        countdown.setForeground(new Color(255, 215, 150));
        countdown.setFont(countdown.getFont().deriveFont(10f));
        countdown.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(countdown);

        String lifecycle = store.liveLifecycleState(r.key);
        if (lifecycle != null)
        {
            JLabel lc = new JLabel("state: " + htmlEscape(lifecycle));
            lc.setForeground(Color.LIGHT_GRAY);
            lc.setFont(lc.getFont().deriveFont(10f));
            lc.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(lc);
        }

        JComponent hints = hintChipsRow(s, ROW_WRAP_PX - 10);
        if (hints != null) row.add(hints);
        JComponent refs = refChipsRow(s);
        if (refs != null) row.add(refs);

        JButton logNow = new JButton("Log now");
        logNow.setAlignmentX(Component.LEFT_ALIGNMENT);
        logNow.setFocusPainted(false);
        // Same entry point as the checkbox above — GuideStore.toggle() detects the
        // step is RECURRING and re-arms it instead of touching the main index.
        logNow.addActionListener(e -> store.toggle(r.key));
        row.add(Box.createVerticalStrut(2));
        row.add(logNow);

        return row;
    }

    /** {@code "next: 1h 20m"} / {@code "due now"} / {@code "no fixed cadence — check manually"}. */
    private String countdownText(String stepKey)
    {
        Long nextFire = store.nextFireEpochMs(stepKey);
        if (nextFire == null) return "no fixed cadence — check manually";
        long remainMs = nextFire - System.currentTimeMillis();
        if (remainMs <= 0) return "due now";
        long totalMin = remainMs / 60_000L;
        long h = totalMin / 60;
        long m = totalMin % 60;
        return "next: " + (h > 0 ? h + "h " : "") + m + "m";
    }

    // ── Badge / chip builders (shared by the current-step card and list rows) ──

    private JComponent steerBadge(String kind)
    {
        JLabel badge = new JLabel(steerBadgeText(kind));
        badge.setOpaque(true);
        badge.setBackground(steerBadgeColor(kind));
        badge.setForeground(Color.BLACK);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 9f));
        badge.setBorder(new EmptyBorder(1, 4, 1, 4));
        return badge;
    }

    private static String steerBadgeText(String kind)
    {
        if (kind == null) return "STEER";
        switch (kind)
        {
            case "access":          return "ACCESS";
            case "qol_gear":        return "QOL";
            case "supply_infra":    return "SUPPLY";
            case "progress_metric": return "★ PROGRESS";
            case "combat_spine":    return "COMBAT";
            default:                return kind.toUpperCase();
        }
    }

    private static Color steerBadgeColor(String kind)
    {
        if (kind == null) return ColorScheme.LIGHT_GRAY_COLOR;
        switch (kind)
        {
            case "access":          return new Color(255, 215, 0);
            case "qol_gear":        return new Color(120, 220, 255);
            case "supply_infra":    return new Color(180, 255, 150);
            case "progress_metric": return new Color(255, 170, 90);
            case "combat_spine":    return new Color(255, 130, 130);
            default:                return ColorScheme.LIGHT_GRAY_COLOR;
        }
    }

    /** {@code "+ label"} chips for pre-resolved passive-embed badges (pattern 6). */
    private JComponent passiveOverlaysRow(GuideStep step)
    {
        if (step == null || step.passiveOverlays().isEmpty()) return null;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String label : step.passiveOverlays())
        {
            if (label == null) continue;
            row.add(chipLabel("+ " + label, ColorScheme.DARKER_GRAY_COLOR, new Color(180, 220, 180), null));
        }
        return row;
    }

    /** Hint chips (GRANULARITY §4) — advisory only; the note becomes a tooltip. */
    private JComponent hintChipsRow(GuideStep step, int wrapPx)
    {
        if (step == null || step.hints().isEmpty()) return null;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (GuideHint h : step.hints())
        {
            if (h == null) continue;
            row.add(chipLabel(hintChipLabel(h), ColorScheme.DARKER_GRAY_COLOR, Color.LIGHT_GRAY, h.note));
        }
        return row;
    }

    private static String hintChipLabel(GuideHint h)
    {
        if (h == null || h.type == null) return "hint";
        String label = h.type;
        if (h.value != null && !h.value.isEmpty()) label += ": " + h.value;
        return label;
    }

    /**
     * Wiki citation chips — clicking opens the canonical URL in the OS default
     * browser via {@link LinkBrowser}. This never touches the game client
     * (same as the web view's ref-chip behavior); it's the plugin-panel
     * equivalent of the web view's {@code data-wiki-url} click handler.
     */
    private JComponent refChipsRow(GuideStep step)
    {
        if (step == null || step.refs().isEmpty()) return null;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (GuideRef ref : step.refs())
        {
            if (ref == null || ref.title == null) continue;
            String url = ref.url;
            JLabel lbl = chipLabel("📖 " + ref.title, ColorScheme.DARKER_GRAY_COLOR,
                new Color(120, 180, 255), url);
            if (url != null && !url.isEmpty())
            {
                lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                lbl.addMouseListener(new MouseAdapter()
                {
                    @Override
                    public void mouseClicked(MouseEvent e)
                    {
                        LinkBrowser.browse(url);
                    }
                });
            }
            row.add(lbl);
        }
        return row;
    }

    private JLabel chipLabel(String text, Color bg, Color fg, String tooltip)
    {
        JLabel lbl = new JLabel(htmlEscape(text));
        lbl.setOpaque(true);
        lbl.setBackground(bg);
        lbl.setForeground(fg);
        lbl.setFont(lbl.getFont().deriveFont(9.5f));
        lbl.setBorder(new EmptyBorder(1, 5, 1, 5));
        if (tooltip != null && !tooltip.isEmpty()) lbl.setToolTipText(htmlEscape(tooltip));
        return lbl;
    }

    // ── Collapsible section headers ───────────────────────────────────────────

    private JComponent supplyChainHeader(String chain, boolean expanded)
    {
        JLabel header = new JLabel((expanded ? "▾ " : "▸ ") + "Supply: " + htmlEscape(chain));
        header.setForeground(new Color(190, 230, 190));
        header.setFont(header.getFont().deriveFont(Font.BOLD, 11f));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setBorder(new EmptyBorder(6, 4, 2, 0));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (expandedSupplyChains.contains(chain)) expandedSupplyChains.remove(chain);
                else expandedSupplyChains.add(chain);
                rebuildList();
            }
        });
        return header;
    }

    private JComponent loopsLaneHeader(int count)
    {
        JLabel header = new JLabel((loopsLaneExpanded ? "▾ " : "▸ ") + "Background loops (" + count + ")");
        header.setForeground(new Color(255, 215, 150));
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setBorder(new EmptyBorder(4, 0, 2, 0));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                loopsLaneExpanded = !loopsLaneExpanded;
                rebuildList();
            }
        });
        return header;
    }

    // ── Small layout / text helpers ───────────────────────────────────────────

    private JComponent dividerLabel(String text, float size, Color fg, int indentPx)
    {
        JLabel label = new JLabel(htmlEscape(text != null ? text : ""));
        label.setForeground(fg);
        label.setFont(label.getFont().deriveFont(Font.BOLD, size));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(indentPx == 0 ? 8 : 4, indentPx, 2, 0));
        return label;
    }

    private JComponent mutedLabel(String text, int wrapPx)
    {
        JLabel label = new JLabel(htmlWrap(text, wrapPx));
        label.setForeground(Color.GRAY);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** Wraps a single component in a left-aligned holder (BoxLayout alignment quirk). */
    private JComponent leftAligned(JComponent c)
    {
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(c);
        return wrap;
    }

    private static void indent(JComponent c)
    {
        c.setBorder(new EmptyBorder(0, 6, 0, 0));
    }

    private static String statusIcon(PlanRow.Status s)
    {
        switch (s)
        {
            case CURRENT: return "▶";
            case DONE:    return "✓";
            case SKIPPED: return "↷";
            default:      return "·";
        }
    }

    /**
     * Wraps text in an HTML snippet with a fixed body width so JLabel
     * wraps long instructions correctly inside BoxLayout.Y_AXIS.
     */
    private static String htmlWrap(String text, int wrapPx)
    {
        return "<html><body style='width: " + wrapPx + "px'>"
            + htmlEscape(text)
            + "</body></html>";
    }

    private static String htmlEscape(String s)
    {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static JSeparator makeSeparator()
    {
        JSeparator sep = new JSeparator();
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return sep;
    }
}
