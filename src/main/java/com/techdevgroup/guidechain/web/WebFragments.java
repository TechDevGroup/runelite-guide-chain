package com.techdevgroup.guidechain.web;

import com.techdevgroup.guidechain.data.ChainEntry;
import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.data.MapMarker;
import com.techdevgroup.guidechain.store.ConditionStatus;
import com.techdevgroup.guidechain.store.GuideStore;
import com.techdevgroup.guidechain.store.PlanRow;
import com.techdevgroup.guidechain.store.SessionMetrics;

import java.util.List;

import static com.techdevgroup.guidechain.web.Html.esc;

/**
 * Renders the app shell and the htmx fragment partials. Plain Java, zero
 * RuneLite imports — all data comes from the shared {@link GuideStore}.
 *
 * <p>The step row partial ({@link #stepRow}) is deliberately reused in both
 * the plan list and the step detail header so the two contexts stay visually
 * and behaviorally identical.
 */
final class WebFragments
{
    private final GuideStore store;

    WebFragments(GuideStore store)
    {
        this.store = store;
    }

    // ── App shell ─────────────────────────────────────────────────────────────

    String shell()
    {
        return "<!doctype html>\n"
            + "<html lang=\"en\">\n<head>\n"
            + "<meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
            + "<title>Guide Chain</title>\n"
            + "<link rel=\"stylesheet\" href=\"/static/app.css\">\n"
            + "<script src=\"/static/htmx.min.js\"></script>\n"
            + "</head>\n<body>\n"
            + "<header class=\"topbar\">\n"
            + "  <h1>Guide Chain</h1>\n"
            + "  <div id=\"chains\" class=\"chains\" hx-get=\"/fragments/chains\""
            + " hx-trigger=\"load, guide-store-changed from:body\"></div>\n"
            + "  <button class=\"btn btn-ghost\" hx-post=\"/actions/refresh-guides\""
            + " hx-target=\"#plan\" title=\"Re-fetch guides from the configured source\">&#8635; Refresh guides</button>\n"
            + "</header>\n"
            + "<main class=\"layout\">\n"
            + "  <section id=\"plan\" class=\"pane\" hx-get=\"/fragments/plan\""
            + " hx-trigger=\"load, every 2s, guide-store-changed from:body\"></section>\n"
            + "  <aside id=\"detail\" class=\"pane\" hx-get=\"/fragments/step/current\""
            + " hx-trigger=\"load\"></aside>\n"
            + "</main>\n"
            + "<footer class=\"foot\">\n"
            + "  <span id=\"metrics\"></span>\n"
            + "  <span class=\"foot-links\"><a href=\"/api/state.json\">state.json</a>"
            + " &middot; <a href=\"/static/HTMX-LICENSE\">htmx license (0BSD)</a></span>\n"
            + "</footer>\n"
            + "<script src=\"/static/app.js\"></script>\n"
            + "</body>\n</html>\n";
    }

    // ── Chain picker fragment ─────────────────────────────────────────────────

    String chainsFragment()
    {
        List<ChainEntry> chains = store.chains();
        StringBuilder sb = new StringBuilder();
        sb.append("<label class=\"chain-label\">Chain\n");
        sb.append("<select name=\"chain\" hx-post=\"/actions/select-chain\"")
          .append(" hx-trigger=\"change\" hx-target=\"#plan\">\n");
        String active = store.activeChainId();
        if (chains.isEmpty())
        {
            sb.append("<option disabled selected>no chains loaded</option>\n");
        }
        for (ChainEntry c : chains)
        {
            sb.append("<option value=\"").append(esc(c.id)).append('"');
            if (c.id != null && c.id.equals(active)) sb.append(" selected");
            sb.append('>').append(esc(c.name != null ? c.name : c.id)).append("</option>\n");
        }
        sb.append("</select>\n</label>\n");
        return sb.toString();
    }

    // ── Plan fragment ─────────────────────────────────────────────────────────

    String planFragment()
    {
        List<PlanRow> rows = store.plan();
        ChainEntry chain = store.activeChain();
        int total = store.totalSteps();
        int current = Math.min(store.globalStepNumber(), total);
        int pct = total > 0 ? (int) Math.round(100.0 * (current - 1) / total) : 0;
        if (store.isChainComplete()) pct = 100;

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"plan-head\">\n");
        sb.append("<h2>").append(esc(chain != null && chain.name != null ? chain.name : "Plan")).append("</h2>\n");
        sb.append("<span class=\"badge ").append(store.isClientConnected() ? "badge-on" : "badge-off").append("\">")
          .append(store.isClientConnected() ? "client connected" : "client offline").append("</span>\n");
        sb.append("<span class=\"plan-progress-text\">step ").append(current)
          .append(" / ").append(total).append("</span>\n");
        sb.append("</div>\n");
        sb.append("<div class=\"progress\"><div class=\"progress-fill\" style=\"width:")
          .append(pct).append("%\"></div></div>\n");

        if (rows.isEmpty())
        {
            sb.append("<p class=\"empty\">No guides loaded. Check the guides source, then hit Refresh guides.</p>\n");
            return sb.toString();
        }

        sb.append("<ol class=\"plan-list\">\n");
        String lastGuide = null;
        for (PlanRow r : rows)
        {
            if (!r.guideId.equals(lastGuide))
            {
                sb.append("<li class=\"guide-divider\">").append(esc(r.guideName)).append("</li>\n");
                lastGuide = r.guideId;
            }
            sb.append(stepRow(r, true));
        }
        sb.append("</ol>\n");
        if (store.isChainComplete())
        {
            sb.append("<p class=\"chain-complete\">Chain complete &#127881;</p>\n");
        }
        return sb.toString();
    }

    // ── Step row partial (REUSED: plan list rows + detail card header) ───────

    /**
     * One step as a wrapping flex row: number, instruction + condition
     * badges, and Done/Skip actions. {@code compact} renders the list
     * context (click-to-open detail); the detail context omits the link.
     */
    String stepRow(PlanRow r, boolean compact)
    {
        String status = r.status.name().toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append("<li class=\"step-row st-").append(status).append("\"");
        if (r.status == PlanRow.Status.CURRENT) sb.append(" data-current=\"1\"");
        sb.append(">\n");
        sb.append("<span class=\"step-num\" title=\"").append(status).append("\">")
          .append(statusIcon(r.status)).append(' ').append(r.globalIndex).append("</span>\n");
        sb.append("<div class=\"step-main\">\n");
        if (compact)
        {
            sb.append("<a class=\"step-instruction\" href=\"#\" hx-get=\"/fragments/step/")
              .append(esc(r.key)).append("\" hx-target=\"#detail\">")
              .append(esc(r.step.instruction)).append("</a>\n");
        }
        else
        {
            sb.append("<span class=\"step-instruction\">").append(esc(r.step.instruction)).append("</span>\n");
        }
        sb.append("<div class=\"step-conds\">\n");
        for (CompletionCondition c : r.step.completionConditions())
        {
            sb.append("<span class=\"cond-badge\">").append(esc(conditionSummary(c))).append("</span>\n");
        }
        sb.append("</div>\n</div>\n");
        sb.append("<div class=\"step-actions\">\n");
        sb.append(actionButton(r.key, "done", "Done"));
        sb.append(actionButton(r.key, "skip", "Skip"));
        sb.append("</div>\n</li>\n");
        return sb.toString();
    }

    private String actionButton(String key, String action, String label)
    {
        return "<button class=\"btn btn-" + action + "\" hx-post=\"/actions/step/"
            + esc(key) + "/" + action + "\" hx-target=\"#plan\">" + label + "</button>\n";
    }

    // ── Step detail fragment ──────────────────────────────────────────────────

    /**
     * Detail card for one step. {@code key} may be the literal
     * {@code "current"} to follow the store's position; the card then
     * re-polls itself with the same pseudo-key so it keeps following.
     */
    String stepFragment(String key)
    {
        boolean followCurrent = "current".equals(key);
        String resolved = followCurrent ? store.currentStepKey() : key;
        PlanRow row = resolved != null ? store.planRow(resolved) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"detail-card\" hx-get=\"/fragments/step/")
          .append(esc(followCurrent ? "current" : key))
          .append("\" hx-trigger=\"every 2s, guide-store-changed from:body\" hx-swap=\"outerHTML\">\n");

        sb.append("<div class=\"detail-head\">\n<h2>")
          .append(followCurrent ? "Current step" : "Step detail").append("</h2>\n");
        if (!followCurrent)
        {
            sb.append("<a href=\"#\" class=\"follow-link\" hx-get=\"/fragments/step/current\"")
              .append(" hx-target=\"#detail\">follow current</a>\n");
        }
        sb.append("</div>\n");

        if (row == null)
        {
            sb.append(store.isChainComplete() && followCurrent
                ? "<p class=\"empty\">Chain complete — nothing left to do.</p>\n"
                : "<p class=\"empty\">No step loaded.</p>\n");
            sb.append("</div>\n");
            return sb.toString();
        }

        GuideStep step = row.step;
        sb.append("<p class=\"detail-guide\">").append(esc(row.guideName))
          .append(" &middot; step ").append(row.globalIndex).append(" / ").append(store.totalSteps())
          .append("</p>\n");

        sb.append("<ol class=\"plan-list detail-row\">").append(stepRow(row, false)).append("</ol>\n");

        if (step.detail != null && !step.detail.isEmpty())
        {
            sb.append("<p class=\"detail-text\">").append(esc(step.detail)).append("</p>\n");
        }

        // Conditions with live values
        List<CompletionCondition> conds = step.completionConditions();
        if (!conds.isEmpty())
        {
            List<ConditionStatus> live = store.liveConditionsFor(row.key);
            sb.append("<h3>Completion conditions</h3>\n<table class=\"cond-table\">\n")
              .append("<tr><th>type</th><th>target</th><th>live</th></tr>\n");
            for (int i = 0; i < conds.size(); i++)
            {
                CompletionCondition c = conds.get(i);
                ConditionStatus st = live != null && i < live.size() ? live.get(i) : null;
                sb.append("<tr class=\"").append(st != null && st.met ? "cond-met" : "cond-unmet").append("\">")
                  .append("<td>").append(esc(c.type != null ? c.type.name() : "?")).append("</td>")
                  .append("<td>").append(esc(conditionSummary(c))).append("</td>")
                  .append("<td>").append(st != null
                        ? esc(st.live) + (st.met ? " ✓" : "")
                        : "<span class=\"offline\">client offline</span>")
                  .append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }

        // Highlights + map markers (author data, useful for debugging guides)
        if (!step.highlights().isEmpty())
        {
            sb.append("<h3>Highlights</h3>\n<ul class=\"tiny-list\">\n");
            for (HighlightTarget h : step.highlights())
            {
                sb.append("<li>").append(esc(highlightSummary(h))).append("</li>\n");
            }
            sb.append("</ul>\n");
        }
        if (!step.mapMarkers().isEmpty())
        {
            sb.append("<h3>Map markers</h3>\n<ul class=\"tiny-list\">\n");
            for (MapMarker m : step.mapMarkers())
            {
                sb.append("<li>").append(esc((m.label != null ? m.label + " " : "")
                    + "(" + m.x + ", " + m.y + ", " + m.plane + ")")).append("</li>\n");
            }
            sb.append("</ul>\n");
        }

        sb.append("<div class=\"detail-actions\">\n")
          .append("<button class=\"btn\" hx-post=\"/actions/step/").append(esc(row.key))
          .append("/back\" hx-target=\"#plan\">&#8592; Back</button>\n")
          .append("</div>\n");

        sb.append("</div>\n");
        return sb.toString();
    }

    // ── Text summaries (plain Java — no client needed) ───────────────────────

    static String conditionSummary(CompletionCondition c)
    {
        if (c == null || c.type == null) return "unknown condition";
        switch (c.type)
        {
            case QUEST:     return "quest " + c.questName + " " + (c.state != null ? c.state : "FINISHED");
            case VARBIT:    return "varbit " + c.varbitId + " " + (c.op != null ? c.op : "EQ") + " " + c.value;
            case SKILL:     return c.skill + " ≥ " + c.level;
            case ITEM_HELD: return "item " + c.itemId + " × ≥" + c.qty;
            case REGION:    return "in region " + c.regionId;
            case MANUAL:    return "manual — mark done";
            default:        return c.type.name();
        }
    }

    private static String highlightSummary(HighlightTarget h)
    {
        if (h == null || h.type == null) return "unknown target";
        switch (h.type)
        {
            case OBJECT: return "object " + h.id + (h.worldX != 0 ? " @ (" + h.worldX + ", " + h.worldY + ", " + h.plane + ")" : "");
            case NPC:    return "npc " + h.id;
            case ITEM:   return "item " + h.id + " in " + (h.container != null ? h.container : "INVENTORY");
            case WIDGET: return "widget " + h.group + ":" + h.child;
            case TILE:   return "tile (" + h.worldX + ", " + h.worldY + ", " + h.plane + ")";
            default:     return h.type.name();
        }
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

    /** Short metrics line for the footer (also used by /api consumers). */
    String metricsLine()
    {
        SessionMetrics m = store.metrics();
        return "done " + m.stepsCompleted + " · skipped " + m.stepsSkipped
            + " · web actions " + m.webActions;
    }
}
