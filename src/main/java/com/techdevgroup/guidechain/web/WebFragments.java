package com.techdevgroup.guidechain.web;

import com.techdevgroup.guidechain.data.ChainEntry;
import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.GuideHint;
import com.techdevgroup.guidechain.data.GuideRef;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.ConditionType;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.data.TargetType;
import com.techdevgroup.guidechain.data.MapMarker;
import com.techdevgroup.guidechain.store.ConditionStatus;
import com.techdevgroup.guidechain.store.GuideStore;
import com.techdevgroup.guidechain.store.PlanRow;
import com.techdevgroup.guidechain.store.SessionMetrics;

import java.util.ArrayList;
import java.util.Comparator;
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
            + "  <a class=\"btn btn-ghost\" hx-get=\"/fragments/library\" hx-target=\"#plan\""
            + " href=\"#\" title=\"Browse all routes and reference guides\">&#9776; Library</a>\n"
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
            + "<aside id=\"wikibox\" class=\"wikibox\" hidden>\n"
            + "  <header class=\"wikibox-header\">\n"
            + "    <span id=\"wikibox-title\" class=\"wikibox-title\"></span>\n"
            + "    <a id=\"wikibox-ext\" class=\"wikibox-ext\" target=\"_blank\" rel=\"noopener\">open on wiki &#8599;</a>\n"
            + "    <button id=\"wikibox-close\" class=\"wikibox-close\">&#x2715;</button>\n"
            + "  </header>\n"
            + "  <iframe id=\"wikibox-frame\" src=\"about:blank\" title=\"Wiki article\"></iframe>\n"
            + "</aside>\n"
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
        sb.append("<p class=\"plan-cov\"><a href=\"#\" hx-get=\"/fragments/index\" hx-target=\"#detail\">")
          .append("coverage index →</a></p>\n");

        if (rows.isEmpty())
        {
            sb.append("<p class=\"empty\">No guides loaded. Check the guides source, then hit Refresh guides.</p>\n");
            return sb.toString();
        }

        sb.append("<ol class=\"plan-list\">\n");
        String lastGuide = null, lastPhase = null, lastCheckpoint = null;
        for (PlanRow r : rows)
        {
            if (!r.guideId.equals(lastGuide))
            {
                sb.append("<li class=\"guide-divider\">").append(esc(r.guideName)).append("</li>\n");
                lastGuide = r.guideId;
                lastPhase = null;
                lastCheckpoint = null;
            }
            String phase = r.step.phase;
            if (phase != null && !phase.equals(lastPhase))
            {
                sb.append("<li class=\"phase-divider\">").append(esc(phase)).append("</li>\n");
                lastPhase = phase;
                lastCheckpoint = null;  // reset checkpoint context on phase boundary
            }
            // Checkpoint sub-header: checkpoint header records emit a .checkpoint-divider
            // list item and are NOT rendered as step-rows (they carry the instruction inline).
            String cp = r.step.checkpoint;
            if (cp != null && !cp.equals(lastCheckpoint))
            {
                sb.append("<li class=\"checkpoint-divider\">").append(esc(cp)).append("</li>\n");
                lastCheckpoint = cp;
            }
            // Checkpoint header records (id prefix "chkpt-") are rendered as dividers only.
            if (r.step.id != null && r.step.id.startsWith("chkpt-"))
            {
                continue;  // divider already emitted above; skip the step-row
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
        boolean checked = r.status == PlanRow.Status.DONE || r.status == PlanRow.Status.SKIPPED;
        sb.append("<input type=\"checkbox\" class=\"step-check\"")
          .append(" hx-post=\"/actions/step/").append(esc(r.key)).append("/toggle\"")
          .append(" hx-target=\"#plan\"")
          .append(checked ? " checked" : "")
          .append(">\n");
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
        sb.append("</div>\n");
        // Hint chips (GRANULARITY §4): rendered below conditions; advisory only.
        if (!r.step.hints().isEmpty())
        {
            sb.append("<div class=\"hint-chips\">\n");
            for (GuideHint h : r.step.hints())
            {
                sb.append("<span class=\"hint-chip\" title=\"")
                  .append(esc(h.note != null ? h.note : ""))
                  .append("\">")
                  .append(esc(hintChipLabel(h)))
                  .append("</span>\n");
            }
            sb.append("</div>\n");
        }
        // Ref chips: wiki citation links that survive all htmx swaps via delegated handler.
        if (!r.step.refs().isEmpty())
        {
            sb.append("<div class=\"ref-chips\">\n");
            for (GuideRef ref : r.step.refs())
            {
                if (ref.title == null) continue;
                sb.append("<a class=\"ref-chip\" href=\"#\"")
                  .append(" data-wiki-title=\"").append(esc(ref.title)).append("\"")
                  .append(" data-wiki-url=\"").append(esc(ref.url != null ? ref.url : "")).append("\"")
                  .append(">&#128214; ").append(esc(ref.title)).append("</a>\n");
            }
            sb.append("</div>\n");
        }
        sb.append("</div>\n");
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
                sb.append("<li>").append(highlightHtml(h)).append("</li>\n");
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

    // ── Guide library (reference directory, grouped by category) ─────────────

    /**
     * A directory of every chain grouped by category — the local analogue of the
     * wiki's supplemental-guides index. Each card opens the chain into the plan pane.
     */
    String libraryFragment()
    {
        List<ChainEntry> chains = store.chains();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"library\">\n");
        sb.append("<div class=\"lib-intro\"><h2>Guide library</h2>\n")
          .append("<p>Progression routes and reference guides. Open a route to play it step by step, ")
          .append("or jump to its coverage ledger.</p></div>\n");
        if (chains.isEmpty())
        {
            sb.append("<p class=\"empty\">No guides loaded.</p>\n</div>\n");
            return sb.toString();
        }
        appendLibraryCategories(sb, chains);
        sb.append("</div>\n");
        return sb.toString();
    }

    private void appendLibraryCategories(StringBuilder sb, List<ChainEntry> chains)
    {
        String lastCat = "\0";
        for (ChainEntry c : sortedByCategory(chains))
        {
            String cat = c.category != null ? c.category : "Guides";
            if (!cat.equals(lastCat))
            {
                if (!"\0".equals(lastCat)) sb.append("</div>\n</section>\n");
                sb.append("<section class=\"lib-cat\"><h3>").append(esc(cat)).append("</h3>\n<div class=\"lib-grid\">\n");
                lastCat = cat;
            }
            sb.append(libraryCard(c));
        }
        sb.append("</div>\n</section>\n");
    }

    private static List<ChainEntry> sortedByCategory(List<ChainEntry> chains)
    {
        List<ChainEntry> out = new ArrayList<>(chains);
        out.sort(Comparator.comparing(c -> c.category != null ? c.category : "Guides"));
        return out;
    }

    private String libraryCard(ChainEntry c)
    {
        String vals = "{\"chain\":\"" + esc(c.id) + "\"}";
        return "<article class=\"lib-card\">\n"
            + "<h4>" + esc(c.name != null ? c.name : c.id) + "</h4>\n"
            + "<p class=\"lib-desc\">" + esc(c.description != null ? c.description : "") + "</p>\n"
            + "<div class=\"lib-meta\"><span>" + c.guides().size() + " guide"
            + (c.guides().size() == 1 ? "" : "s") + "</span></div>\n"
            + "<div class=\"lib-actions\">\n"
            + "<button class=\"btn\" hx-post=\"/actions/select-chain\" hx-vals='" + vals
            + "' hx-target=\"#plan\">Open route →</button>\n"
            + "</div>\n</article>\n";
    }

    // ── Coverage index (semantic scoping: detail we have vs. stubs) ──────────

    /**
     * Ledger over every step in the active chain: cites id + label and marks
     * which enrichment dimensions each carries (detail / highlights / map /
     * auto-advance) versus which are still stubs. This is where enrichment
     * "unfolds" — a visible list of what exists vs. what is left to fill in.
     */
    String indexFragment()
    {
        List<PlanRow> rows = store.plan();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"detail-card\">\n<div class=\"detail-head\"><h2>Coverage index</h2>\n");
        sb.append("<a class=\"detail-follow\" href=\"#\" hx-get=\"/fragments/step/current\" hx-target=\"#detail\">← back to step</a></div>\n");
        if (rows.isEmpty())
        {
            sb.append("<p class=\"empty\">No guides loaded.</p>\n</div>\n");
            return sb.toString();
        }
        sb.append(coverageSummary(rows));
        sb.append("<table class=\"cov\">\n<thead><tr><th>#</th><th>id</th><th>step</th>")
          .append("<th>detail</th><th>hl</th><th>map</th><th>auto</th></tr></thead>\n<tbody>\n");
        appendCovRows(sb, rows);
        sb.append("</tbody>\n</table>\n</div>\n");
        return sb.toString();
    }

    private void appendCovRows(StringBuilder sb, List<PlanRow> rows)
    {
        String lastGroup = null;
        int n = 0;
        for (PlanRow r : rows)
        {
            n++;
            String group = r.step.phase != null ? r.step.phase : r.guideName;
            if (!group.equals(lastGroup))
            {
                sb.append("<tr class=\"cov-guide\"><td colspan=\"7\">").append(esc(group)).append("</td></tr>\n");
                lastGroup = group;
            }
            sb.append(covRow(r, n));
        }
    }

    private String covRow(PlanRow r, int n)
    {
        GuideStep s = r.step;
        boolean stub = isStub(s);
        StringBuilder sb = new StringBuilder();
        sb.append("<tr class=\"").append(stub ? "cov-stub-row" : "").append("\">");
        sb.append("<td class=\"cov-num\">").append(n).append("</td>");
        sb.append("<td><code>").append(esc(s.id != null ? s.id : r.key)).append("</code></td>");
        sb.append("<td><a href=\"#\" hx-get=\"/fragments/step/").append(esc(r.key))
          .append("\" hx-target=\"#detail\">").append(esc(s.instruction)).append("</a>")
          .append(stub ? " <span class=\"tag-stub\">stub</span>" : "").append("</td>");
        sb.append(covCell(hasDetail(s))).append(covCell(!s.highlights().isEmpty()))
          .append(covCell(!s.mapMarkers().isEmpty())).append(covCell(hasAutoAdvance(s)));
        sb.append("</tr>\n");
        return sb.toString();
    }

    private String coverageSummary(List<PlanRow> rows)
    {
        int n = rows.size(), d = 0, h = 0, m = 0, a = 0, stub = 0;
        for (PlanRow r : rows)
        {
            if (hasDetail(r.step)) d++;
            if (!r.step.highlights().isEmpty()) h++;
            if (!r.step.mapMarkers().isEmpty()) m++;
            if (hasAutoAdvance(r.step)) a++;
            if (isStub(r.step)) stub++;
        }
        return "<p class=\"cov-summary\">" + n + " steps &middot; " + covStat("detail", d, n)
            + covStat("highlights", h, n) + covStat("map", m, n) + covStat("auto-advance", a, n)
            + "<span class=\"tag-stub\">" + stub + " stubs</span></p>\n";
    }

    private static String covStat(String label, int got, int n)
    {
        return "<span class=\"cov-stat\">" + got + "/" + n + " " + label + "</span> ";
    }

    private static String covCell(boolean has)
    {
        return has ? "<td class=\"cov-yes\">✓</td>" : "<td class=\"cov-no\">·</td>";
    }

    static boolean isStub(GuideStep s)
    {
        if (s.id != null && s.id.startsWith("synth-")) return true;
        return s.detail != null && s.detail.startsWith("Synthetic step");
    }

    static boolean hasDetail(GuideStep s)
    {
        if (s.detail == null || s.detail.trim().isEmpty()) return false;
        return !s.detail.startsWith("Synthetic step");
    }

    static boolean hasAutoAdvance(GuideStep s)
    {
        return s.completionConditions().stream()
            .anyMatch(c -> c.type != null && c.type != ConditionType.MANUAL);
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

    /** Highlight row as HTML: ITEM targets get a lazy cache-backed icon. */
    private static String highlightHtml(HighlightTarget h)
    {
        if (h == null || h.type == null) return "unknown target";
        String label = esc(highlightSummary(h));
        return h.type == TargetType.ITEM ? itemIcon(h.id) + label : label;
    }

    /** &lt;img&gt; served by GuideWebServer's lazy /icon/item/{id}.png blob route. */
    static String itemIcon(int id)
    {
        if (id <= 0) return "";
        return "<img class=\"item-icon\" src=\"/icon/item/" + id + ".png\" loading=\"lazy\""
            + " width=\"20\" height=\"20\" alt=\"item " + id + "\"> ";
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

    /** Short label for a hint chip: type + optional value. */
    private static String hintChipLabel(GuideHint h)
    {
        if (h == null || h.type == null) return "hint";
        String label = h.type;
        if (h.value != null && !h.value.isEmpty()) label += ": " + h.value;
        return label;
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
