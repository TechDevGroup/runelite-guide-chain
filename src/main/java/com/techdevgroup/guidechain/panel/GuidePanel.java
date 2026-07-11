package com.techdevgroup.guidechain.panel;

import com.techdevgroup.guidechain.data.ConditionType;
import com.techdevgroup.guidechain.data.Guide;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.manager.GuideManager;
import com.techdevgroup.guidechain.store.GuideStore;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * Sidebar panel — the interactive guide surface in the RuneLite toolbar.
 *
 * <p>Opened via the Guide Chain {@link net.runelite.client.ui.NavigationButton}
 * (toolbar icon). Replaces the old on-canvas {@code PanelOverlay}; all click
 * targets here are real Swing components so they accept mouse input.
 *
 * <p>Contains:
 * <ul>
 *   <li>Chain picker — JComboBox that calls {@link GuideStore#selectChainByIndex}</li>
 *   <li>Progress counter — guide name + current/total step numbers</li>
 *   <li>Instruction text — current step instruction</li>
 *   <li>Detail text — optional longer context (hidden when absent)</li>
 *   <li>Mark Done button — visible for MANUAL-condition steps or steps with no
 *       auto-conditions; calls {@link GuideStore#markDone}</li>
 * </ul>
 *
 * <p>Back / skip arrow controls are intentionally absent — they lived on the
 * canvas overlay that this panel replaces.
 *
 * <p>Registered as a {@link GuideStore.Listener} by {@link
 * com.techdevgroup.guidechain.GuideChainPlugin} on startUp so the panel
 * refreshes whenever the store changes (plan load, chain switch, step advance).
 * Refreshes also on {@link #onActivate()} so the panel is always current when
 * the user opens it.
 *
 * <p><strong>Thread safety:</strong> the storeListener fires on the plugin
 * game-tick thread; all UI mutations are dispatched via
 * {@link SwingUtilities#invokeLater}.
 */
@Singleton
public class GuidePanel extends PluginPanel
{
    // Inline HTML body width keeps JLabel text wrapping reliably at ~200 px
    // regardless of container width negotiation in BoxLayout.Y_AXIS.
    private static final int TEXT_WRAP_PX = 200;

    private final GuideStore store;
    private final GuideManager guideManager;

    private final JComboBox<String> chainCombo    = new JComboBox<>();
    private final JLabel           progressLabel  = new JLabel(" ");
    private final JLabel           instructionLabel = new JLabel();
    private final JLabel           detailLabel    = new JLabel();
    private final JPanel           detailPanel    = new JPanel(new BorderLayout());
    private final JButton          markDoneButton = new JButton("Mark Done");

    /**
     * Guard against combo selection → store change → panel refresh →
     * combo update → store change loop.
     */
    private boolean updatingCombo = false;

    @Inject
    public GuidePanel(GuideStore store, GuideManager guideManager)
    {
        this.store        = store;
        this.guideManager = guideManager;
        buildUi();
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

        add(content, BorderLayout.NORTH);
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

        // ── Current step content ───────────────────────────────────────────────
        GuideStep step = store.currentStep();

        if (step == null)
        {
            String msg = store.isChainComplete() ? "Chain complete!" : "No active step.";
            instructionLabel.setText(htmlWrap(msg));
            detailPanel.setVisible(false);
            markDoneButton.setVisible(false);
        }
        else
        {
            instructionLabel.setText(htmlWrap(step.instruction != null ? step.instruction : ""));

            String detail = (step.detail != null && !step.detail.isEmpty()) ? step.detail : null;
            if (detail != null)
            {
                detailLabel.setText(htmlWrap(detail));
                detailPanel.setVisible(true);
            }
            else
            {
                detailPanel.setVisible(false);
            }

            // Show Mark Done button for MANUAL-condition steps or steps with no conditions
            boolean hasManual     = step.completionConditions().stream()
                                        .anyMatch(c -> c.type == ConditionType.MANUAL);
            boolean noConditions  = step.completionConditions().isEmpty();
            markDoneButton.setVisible(hasManual || noConditions);
        }

        revalidate();
        repaint();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Wraps text in an HTML snippet with a fixed body width so JLabel
     * wraps long instructions correctly inside BoxLayout.Y_AXIS.
     */
    private static String htmlWrap(String text)
    {
        return "<html><body style='width: " + TEXT_WRAP_PX + "px'>"
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
