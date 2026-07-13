package com.techdevgroup.guidechain.web;

import com.techdevgroup.guidechain.data.ChainEntry;
import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.Dedupe;
import com.techdevgroup.guidechain.data.GuideHint;
import com.techdevgroup.guidechain.data.GuideMedia;
import com.techdevgroup.guidechain.data.GuideRef;
import com.techdevgroup.guidechain.data.GuideStep;
import com.techdevgroup.guidechain.data.ConditionType;
import com.techdevgroup.guidechain.data.HighlightTarget;
import com.techdevgroup.guidechain.data.TargetType;
import com.techdevgroup.guidechain.data.MapMarker;
import com.techdevgroup.guidechain.reference.ReferenceEntry;
import com.techdevgroup.guidechain.reference.ReferenceStore;
import com.techdevgroup.guidechain.store.ConditionStatus;
import com.techdevgroup.guidechain.store.GuideStore;
import com.techdevgroup.guidechain.store.Lens;
import com.techdevgroup.guidechain.store.PlanRow;
import com.techdevgroup.guidechain.store.SessionMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final ReferenceStore reference;

    WebFragments(GuideStore store, ReferenceStore reference)
    {
        this.store = store;
        this.reference = reference;
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
            + "  <a class=\"btn btn-ghost\" hx-get=\"/fragments/plan\" hx-target=\"#plan\""
            + " href=\"#\" title=\"Back to the active checklist\">&#10003; Checklist</a>\n"
            + "  <a class=\"btn btn-ghost\" hx-get=\"/fragments/library\" hx-target=\"#plan\""
            + " href=\"#\" title=\"Browse all routes and reference guides\">&#9776; Library</a>\n"
            + "  <a class=\"btn btn-ghost\" hx-get=\"/fragments/reference\" hx-target=\"#plan\""
            + " href=\"#\" title=\"Browse quests, diaries, minigames, and unlocks\">&#128218; Reference</a>\n"
            + "  <div id=\"chains\" class=\"chains\" hx-get=\"/fragments/chains\""
            + " hx-trigger=\"load, guide-store-changed from:body\"></div>\n"
            + "  <button class=\"btn btn-ghost\" hx-post=\"/actions/refresh-guides\""
            + " hx-target=\"#plan\" title=\"Re-fetch guides from the configured source\">&#8635; Refresh guides</button>\n"
            + "</header>\n"
            + "<main class=\"layout\">\n"
            // Only the initial load is htmx-driven; the live refresh runs from
            // app.js so it can PAUSE while a browse view (Library/Reference, which
            // also render into #plan) is open — otherwise the 2s poll flips the
            // browse content back to the checklist.
            + "  <section id=\"plan\" class=\"pane\" hx-get=\"/fragments/plan\""
            + " hx-trigger=\"load\"></section>\n"
            + "  <aside id=\"detail\" class=\"pane\" hx-get=\"/fragments/step/current\""
            + " hx-trigger=\"load\"></aside>\n"
            // FRAMES_GALLERY §3: gallery pane is a sibling OUTSIDE the #plan/#detail
            // swap zones — #detail is replaced (hx-swap=outerHTML) on every 2s poll,
            // so anything stateful inside it is destroyed. This pane is refreshed only
            // by plain JS on step-focus-changed / guide-store-changed (see app.js).
            + "  <aside id=\"gallery\" class=\"pane gallery-pane\" hidden></aside>\n"
            + "</main>\n"
            + "<aside id=\"wikibox\" class=\"wikibox\" hidden>\n"
            + "  <header class=\"wikibox-header\">\n"
            + "    <span id=\"wikibox-title\" class=\"wikibox-title\"></span>\n"
            + "    <a id=\"wikibox-ext\" class=\"wikibox-ext\" target=\"_blank\" rel=\"noopener\">open on wiki &#8599;</a>\n"
            + "    <button id=\"wikibox-close\" class=\"wikibox-close\">&#x2715;</button>\n"
            + "  </header>\n"
            + "  <iframe id=\"wikibox-frame\" src=\"about:blank\" title=\"Wiki article\"></iframe>\n"
            + "</aside>\n"
            // FRAMES_GALLERY §3: media lightbox — structural twin of the router editor's
            // loadout-lightbox (full-screen overlay, click-outside or close-button to
            // dismiss). Outside the swap zones, so it survives #plan/#detail polling.
            + "<div id=\"media-lightbox\" class=\"media-lightbox\" hidden>\n"
            + "  <div class=\"media-lightbox-modal\">\n"
            + "    <div class=\"media-lightbox-hd\">\n"
            + "      <span id=\"media-lightbox-caption\" class=\"media-lightbox-caption\"></span>\n"
            + "      <button id=\"media-lightbox-close\" class=\"btn btn-ghost media-lightbox-close\">&#x2715;</button>\n"
            + "    </div>\n"
            + "    <div id=\"media-lightbox-media\" class=\"media-lightbox-media\"></div>\n"
            + "    <div id=\"media-lightbox-chips\" class=\"media-lightbox-chips\"></div>\n"
            + "    <div id=\"media-lightbox-refs\" class=\"media-lightbox-refs\"></div>\n"
            + "  </div>\n"
            + "</div>\n"
            + "<footer class=\"foot\">\n"
            + "  <span id=\"metrics\"></span>\n"
            + "  <span class=\"foot-links\"><a href=\"/api/state.json\">state.json</a>"
            + " &middot; <a href=\"/static/HTMX-LICENSE\">htmx license (0BSD)</a></span>\n"
            + "</footer>\n"
            + "<script src=\"/static/app.js\"></script>\n"
            + "</body>\n</html>\n";
    }

    // ── Corpus link fragment (CONSOLIDATION.md §6 — the chain <select> is
    // retired: manifest.json holds exactly 2 entries post-absorption
    // (full-progression, full-corpus) and Corpus is a different axis
    // (topo/coverage index, not a sibling progression), so a combo box is no
    // longer the right control. This renders a single link that opens Corpus
    // via the same POST /actions/select-chain route the Library page's cards
    // already use (libraryFragment/appendLibraryCategories, unchanged). ──

    private static final String CORPUS_CHAIN_ID = "full-corpus";

    String corpusLinkFragment()
    {
        if (findChain(CORPUS_CHAIN_ID) == null) return "";
        boolean active = CORPUS_CHAIN_ID.equals(store.activeChainId());
        StringBuilder sb = new StringBuilder("<div class=\"corpus-link\">\n");
        sb.append("<button class=\"btn btn-ghost\"").append(active ? " disabled" : "")
          .append(" hx-post=\"/actions/select-chain\" hx-vals='{\"chain\":\"")
          .append(CORPUS_CHAIN_ID).append("\"}' hx-target=\"#plan\">")
          .append("Full Corpus (reference) &rarr;</button>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    private ChainEntry findChain(String id)
    {
        for (ChainEntry c : store.chains())
        {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    // ── Lens segmented control (CHAIN_CONSOLIDATION.md §2/§3; PRIMARY
    // navigation as of CONSOLIDATION.md §6 — the chain <select> it used to
    // sit under is gone, so this is now the top-level way to shape the one
    // spine's plan). Lens IS the lookup table (no if-ladder) — one button
    // per Lens.values().

    String lensFragment()
    {
        StringBuilder sb = new StringBuilder("<nav class=\"lenses\">\n");
        String active = store.activeLensId();
        for (Lens l : Lens.values())
        {
            boolean on = l.id.equals(active);
            sb.append("<button class=\"btn lens").append(on ? " lens-on" : "")
              .append("\" hx-post=\"/actions/select-lens\" hx-vals='{\"lens\":\"")
              .append(l.id).append("\"}' hx-target=\"#plan\">").append(esc(l.label))
              .append("</button>\n");
        }
        sb.append("</nav>\n");
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
        // CHAIN_CONSOLIDATION.md §2/§3: the lens filters ROWS only; phase/checkpoint
        // dividers below are computed from the full spine as today, so a contiguous
        // run of lens-elided steps is flushed as one "N woven steps hidden" li right
        // before whichever comes first — the next divider, or the next visible row.
        Lens lens = store.activeLens();
        int hiddenRun = 0;
        for (PlanRow r : rows)
        {
            if (!r.guideId.equals(lastGuide))
            {
                hiddenRun = flushHiddenRun(sb, hiddenRun);
                sb.append("<li class=\"guide-divider\">").append(esc(r.guideName)).append("</li>\n");
                lastGuide = r.guideId;
                lastPhase = null;
                lastCheckpoint = null;
            }
            String phase = r.step.phase;
            if (phase != null && !phase.equals(lastPhase))
            {
                hiddenRun = flushHiddenRun(sb, hiddenRun);
                sb.append("<li class=\"phase-divider\">").append(esc(phase)).append("</li>\n");
                lastPhase = phase;
                lastCheckpoint = null;  // reset checkpoint context on phase boundary
            }
            // Checkpoint sub-header: checkpoint header records emit a .checkpoint-divider
            // list item and are NOT rendered as step-rows (they carry the instruction inline).
            String cp = r.step.checkpoint;
            if (cp != null && !cp.equals(lastCheckpoint))
            {
                hiddenRun = flushHiddenRun(sb, hiddenRun);
                sb.append("<li class=\"checkpoint-divider\">").append(esc(cp)).append("</li>\n");
                lastCheckpoint = cp;
            }
            // Checkpoint header records (id prefix "chkpt-") are rendered as dividers only —
            // structural, not lens-filtered content, so they never count toward hiddenRun.
            if (r.step.id != null && r.step.id.startsWith("chkpt-"))
            {
                continue;  // divider already emitted above; skip the step-row
            }
            if (!lens.matches(r.step))
            {
                hiddenRun++;
                continue;
            }
            hiddenRun = flushHiddenRun(sb, hiddenRun);
            sb.append(stepRow(r, true));
        }
        flushHiddenRun(sb, hiddenRun);
        sb.append("</ol>\n");
        if (store.isChainComplete())
        {
            sb.append("<p class=\"chain-complete\">Chain complete &#127881;</p>\n");
        }
        return sb.toString();
    }

    /**
     * Flushes a pending run of lens-elided steps as one "N woven steps
     * hidden" divider with a one-click escape back to the Full lens
     * (CHAIN_CONSOLIDATION.md §3 risk mitigation — filtered-out requisites
     * stay reachable). No-op (returns 0) when nothing is pending.
     */
    private static int flushHiddenRun(StringBuilder sb, int hiddenRun)
    {
        if (hiddenRun <= 0) return 0;
        sb.append("<li class=\"lens-hidden\">&middot; ").append(hiddenRun)
          .append(hiddenRun == 1 ? " woven step hidden " : " woven steps hidden ").append("&middot; ")
          .append("<button class=\"btn btn-ghost lens-reveal\" hx-post=\"/actions/select-lens\"")
          .append(" hx-vals='{\"lens\":\"full\"}' hx-target=\"#plan\">show all &rarr;</button></li>\n");
        return 0;
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
        // Match key for the plan↔detail focus highlight: the same r.key the
        // detail-card carries as data-step-key, so app.js can ring the row whose
        // detail is currently open.
        sb.append(" data-step-key=\"").append(esc(r.key)).append("\"");
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
        // De-dupe by rendered summary — dirty condition lists must not double a badge.
        for (CompletionCondition c : Dedupe.by(r.step.completionConditions(), WebFragments::conditionSummary))
        {
            sb.append("<span class=\"cond-badge\">").append(esc(conditionSummary(c))).append("</span>\n");
        }
        sb.append("</div>\n");
        // Opportunistic-lookahead breadcrumb: this step was re-pinned earlier so
        // its output is ready before a later consumer needs it — surface WHY the
        // player is collecting it now (OPPORTUNISTIC_GRANULARITY §2a.3).
        appendPayoff(sb, r.step.paysOff);
        // Skippable-alternative breadcrumb (branch{}, D10): this step is one
        // option of an alt_group re-pin — surface it the same place paysOff does.
        appendBranch(sb, r.step.branch);
        // Hint chips (GRANULARITY §4): rendered below conditions; advisory only.
        List<GuideHint> hints = Dedupe.hints(r.step.hints());
        if (!hints.isEmpty())
        {
            sb.append("<div class=\"hint-chips\">\n");
            for (GuideHint h : hints)
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
        List<GuideRef> refs = Dedupe.refs(r.step.refs());
        if (!refs.isEmpty())
        {
            sb.append("<div class=\"ref-chips\">\n");
            for (GuideRef ref : refs)
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
          // Refresh on real store changes only — the blind every-2s poll re-fetched
          // unchanging content and left a stuck htmx-request flicker (same root cause
          // as the removed #plan poll). guide-store-changed still fires on every action.
          .append("\" hx-trigger=\"guide-store-changed from:body\" hx-swap=\"outerHTML\"")
          // Resolved gid/sid key (even in "current"-follow mode) so app.js can detect
          // a step-focus change and fire the gallery fetch (FRAMES_GALLERY §3).
          .append(" data-step-key=\"").append(esc(row != null ? row.key : "")).append("\">\n");

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

        // A method PICKER supersedes a single prose detail line: when the step
        // carries 1:many training methods, the band's options are the content,
        // not a cited sentence. Fall back to detail only when no methods exist.
        List<GuideStep.TrainMethod> methods = step.methods != null ? step.methods : java.util.Collections.emptyList();
        if (!methods.isEmpty())
        {
            appendMethodPicker(sb, methods);
        }
        else if (step.detail != null && !step.detail.isEmpty())
        {
            sb.append("<p class=\"detail-text\">").append(esc(step.detail)).append("</p>\n");
        }

        // Granular sub-checklist (GRANULARITY W3 Faux-grain atoms, ATTACH
        // model): additive to the method-picker/detail-text above — this
        // step's own granular breakdown, not a replacement for it.
        if (step.subChecklist != null && !step.subChecklist.atoms().isEmpty())
        {
            appendSubChecklist(sb, step.subChecklist);
        }

        // REQUISITES / REWARDS blocks (NORMALIZATION.md §1d, gap-reqblocks-01):
        // structured info blocks, primary over the cond-badge row rendered
        // above in stepRow() — that row demotes to a supplement, never deleted.
        appendRequisites(sb, step);
        appendRewards(sb, step);

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

    // ── Media gallery fragment (FRAMES_GALLERY §3) ────────────────────────────

    /**
     * Thumbnail grid + wiki-ref chips for one step's {@code media[]}, or the
     * empty string when the step carries no media — the caller (app.js)
     * collapses the {@code #gallery} pane to nothing in that case. {@code key}
     * is the same {@code guideId/stepId} key every other step route uses.
     */
    String galleryFragment(String key)
    {
        PlanRow row = key != null && !key.isEmpty() ? store.planRow(key) : null;
        if (row == null || row.step.media().isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<h3 class=\"gallery-title\">Frames</h3>\n<div class=\"gallery-grid\">\n");
        List<GuideMedia> media = row.step.media();
        // De-dupe by path but keep the ORIGINAL index — the /media/{key}/{n} blob
        // route resolves n against the unfiltered media[], so re-indexing would 404.
        Set<String> seenMedia = new HashSet<>();
        for (int i = 0; i < media.size(); i++)
        {
            GuideMedia m = media.get(i);
            String mediaKey = m.path != null ? m.path : m.url;
            if (mediaKey != null && !seenMedia.add(mediaKey)) continue;
            sb.append(galleryThumb(row.key, i, m));
        }
        sb.append("</div>\n");
        List<GuideRef> refs = Dedupe.refs(row.step.refs());
        if (!refs.isEmpty())
        {
            sb.append("<div class=\"gallery-refs ref-chips\">\n");
            for (GuideRef ref : refs)
            {
                if (ref.title == null) continue;
                sb.append("<a class=\"ref-chip\" href=\"#\"")
                  .append(" data-wiki-title=\"").append(esc(ref.title)).append("\"")
                  .append(" data-wiki-url=\"").append(esc(ref.url != null ? ref.url : "")).append("\"")
                  .append(">&#128214; ").append(esc(ref.title)).append("</a>\n");
            }
            sb.append("</div>\n");
        }
        return sb.toString();
    }

    private String galleryThumb(String stepKey, int index, GuideMedia m)
    {
        String src = "/media/" + stepKey + "/" + index;
        Object rev = m.state().get("rev");
        Object scenario = m.state().get("scenario");
        Object tile = m.state().get("tile");
        Object captured = m.state().get("captured");
        String revStr = rev != null ? stateValueStr(rev) : null;
        String caption = m.caption != null ? m.caption : "";
        StringBuilder sb = new StringBuilder();
        sb.append("<a class=\"gallery-thumb\" href=\"#\"")
          .append(" data-media-src=\"").append(esc(src)).append("\"")
          .append(" data-media-kind=\"").append(esc(m.kind != null ? m.kind : "png")).append("\"")
          .append(" data-caption=\"").append(esc(caption)).append("\"")
          .append(" data-rev=\"").append(revStr != null ? esc(revStr) : "").append("\"")
          .append(" data-scenario=\"").append(scenario != null ? esc(stateValueStr(scenario)) : "").append("\"")
          .append(" data-tile=\"").append(tile != null ? esc(stateValueStr(tile)) : "").append("\"")
          .append(" data-captured=\"").append(captured != null ? esc(stateValueStr(captured)) : "").append("\"")
          .append(" title=\"").append(esc(caption)).append("\">\n")
          .append("<img src=\"").append(esc(src)).append("\" alt=\"").append(esc(caption))
          .append("\" loading=\"lazy\">\n");
        if (revStr != null)
        {
            sb.append("<span class=\"media-rev-chip\">r").append(esc(revStr)).append("</span>\n");
        }
        sb.append("</a>\n");
        return sb.toString();
    }

    /**
     * Renders a Gson-parsed {@code state{}} value for display. Gson decodes
     * bare JSON numbers inside {@code Map<String,Object>} as {@code Double}
     * (there is no static type to guide it), so a whole number like
     * {@code rev: 236} round-trips as {@code 236.0} — strip the trailing
     * {@code .0} rather than surface Gson's internal representation.
     */
    private static String stateValueStr(Object v)
    {
        if (v instanceof Double)
        {
            double d = (Double) v;
            return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (v instanceof List)
        {
            List<?> list = (List<?>) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++)
            {
                if (i > 0) sb.append(", ");
                sb.append(stateValueStr(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
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

    // ── Reference catalog (local analogue of the wiki's Supplemental-guides index) ──

    private static final List<String> REFERENCE_KINDS = Arrays.asList("quest", "diary", "minigame", "unlock");

    private static final Map<String, String> REFERENCE_KIND_LABELS = referenceKindLabels();

    private static Map<String, String> referenceKindLabels()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("quest", "Quests");
        m.put("diary", "Achievement diaries");
        m.put("minigame", "Minigames");
        m.put("unlock", "Unlocks");
        return m;
    }

    /**
     * Categorized, searchable catalog of every quest / achievement diary /
     * minigame / one-off unlock in {@link ReferenceStore} — the local analogue
     * of the wiki's Supplemental-guides index. {@code kindFilter} narrows to
     * one kind ({@code quest|diary|minigame|unlock}); null/blank shows every
     * kind, grouped. Cards reuse the same {@code .lib-card}/{@code .ref-chip}
     * markup as {@link #libraryFragment()} and the step rows, so the existing
     * delegated wiki-lightbox click handler in app.js catches these chips too.
     */
    String referenceFragment(String kindFilter)
    {
        List<ReferenceEntry> entries = reference.byKind(kindFilter);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"library reference\">\n");
        sb.append("<div class=\"lib-intro\"><h2>Reference</h2>\n")
          .append("<p>Quests, achievement diaries, minigames, and one-off unlocks pulled from the wiki ")
          .append("research corpus — browse for requirements, rewards, and citations. ")
          .append(entries.size()).append(" entries.</p></div>\n");
        sb.append(referenceKindTabs(kindFilter));
        sb.append("<div class=\"ref-search-row\"><input type=\"search\" id=\"ref-search\" class=\"ref-search\"")
          .append(" placeholder=\"Search the reference catalog…\" autocomplete=\"off\"></div>\n");
        if (entries.isEmpty())
        {
            sb.append("<p class=\"empty\">No reference entries loaded.</p>\n</div>\n");
            return sb.toString();
        }
        appendReferenceGroups(sb, entries);
        sb.append("</div>\n");
        return sb.toString();
    }

    private String referenceKindTabs(String active)
    {
        StringBuilder sb = new StringBuilder("<div class=\"ref-tabs\">\n");
        sb.append(referenceTab(null, "All", active));
        for (String kind : REFERENCE_KINDS)
        {
            sb.append(referenceTab(kind, REFERENCE_KIND_LABELS.get(kind), active));
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private String referenceTab(String kind, String label, String active)
    {
        boolean isActive = kind == null ? (active == null || active.isEmpty()) : kind.equals(active);
        String href = kind == null ? "/fragments/reference" : "/fragments/reference?kind=" + kind;
        return "<a class=\"ref-tab" + (isActive ? " ref-tab-active" : "") + "\" href=\"#\""
            + " hx-get=\"" + href + "\" hx-target=\"#plan\">" + esc(label) + "</a>\n";
    }

    private void appendReferenceGroups(StringBuilder sb, List<ReferenceEntry> entries)
    {
        Map<String, List<ReferenceEntry>> byKind = new LinkedHashMap<>();
        for (String kind : REFERENCE_KINDS) byKind.put(kind, new ArrayList<>());
        for (ReferenceEntry e : entries)
        {
            String kind = e.kind != null ? e.kind : "other";
            byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<ReferenceEntry>> group : byKind.entrySet())
        {
            List<ReferenceEntry> rows = group.getValue();
            if (rows.isEmpty()) continue;
            String label = REFERENCE_KIND_LABELS.getOrDefault(group.getKey(), group.getKey());
            sb.append("<section class=\"lib-cat ref-cat\"><h3>").append(esc(label))
              .append(" <span class=\"ref-count\">(").append(rows.size()).append(")</span></h3>\n")
              .append("<div class=\"lib-grid\">\n");
            for (ReferenceEntry e : rows) sb.append(referenceCard(e));
            sb.append("</div>\n</section>\n");
        }
    }

    /** Fact-label render order — the "information blocks" the card groups by. */
    private static final List<String> FACT_ORDER = Arrays.asList(
        "overview", "difficulty", "length", "start", "boss", "kills", "combat",
        "mechanics", "hazard", "unlock", "required-for", "xp-note", "items-note",
        "caveat", "removed");

    private String referenceCard(ReferenceEntry e)
    {
        String name = e.name != null && !e.name.isEmpty() ? e.name : (e.id != null ? e.id : "?");
        String reqsStr = summarizeRefJson(e.reqs);
        String rewardsStr = summarizeRefJson(e.rewards);
        boolean structured = !e.facts().isEmpty() || (e.summary != null && !e.summary.isEmpty());
        String search = (name + " " + (e.summary != null ? e.summary : "")
            + " " + factSearchBlob(e) + " " + (structured ? "" : notesOrEmpty(e))
            + " " + reqsStr + " " + rewardsStr + " " + summarizeStart(e.start)
            + " " + (e.length != null ? e.length : "")).toLowerCase();

        String startStr = summarizeStart(e.start);
        StringBuilder sb = new StringBuilder();
        sb.append("<article class=\"lib-card ref-card\" data-search=\"").append(esc(search)).append("\">\n");
        sb.append("<h4>").append(esc(name));
        if (e.length != null && !e.length.isEmpty())
        {
            sb.append(" <span class=\"ref-length-chip\">").append(esc(e.length)).append("</span>");
        }
        sb.append("</h4>\n");
        if (e.summary != null && !e.summary.isEmpty())
        {
            sb.append("<p class=\"ref-summary\">").append(esc(e.summary)).append("</p>\n");
        }
        if (!startStr.isEmpty())
        {
            sb.append("<p class=\"ref-line\"><span class=\"ref-line-label\">start</span> ")
              .append(esc(startStr)).append("</p>\n");
        }
        if (!reqsStr.isEmpty())
        {
            sb.append("<p class=\"ref-line\"><span class=\"ref-line-label\">reqs</span> ")
              .append(esc(reqsStr)).append("</p>\n");
        }
        appendReqItems(sb, e.reqItems());
        if (!rewardsStr.isEmpty())
        {
            sb.append("<p class=\"ref-line\"><span class=\"ref-line-label\">rewards</span> ")
              .append(esc(rewardsStr)).append("</p>\n");
        }
        appendFactBlocks(sb, e.facts());
        // Prose notes are the fallback ONLY when no structured facts exist
        // (a few gear unlocks the normalizer never reached) — otherwise the
        // typed blocks above replace the blob entirely.
        if (!structured && e.notes != null && !e.notes.isEmpty())
        {
            sb.append("<p class=\"lib-desc ref-notes\">").append(esc(truncate(e.notes, 220))).append("</p>\n");
        }
        appendRefChips(sb, Dedupe.refs(e.refs()));
        sb.append("</article>\n");
        return sb.toString();
    }

    /**
     * Renders a Gson-decoded reqs/rewards value as a short inline summary.
     * Source rows are heterogeneous (dict of skill levels, list of item
     * strings, a plain caveat string, or nested combinations of all three) —
     * this recurses through Map/List uniformly rather than assuming a shape,
     * the same defensive stance {@link #stateValueStr} takes for media state.
     */
    private static String summarizeRefJson(Object v)
    {
        if (v == null) return "";
        if (v instanceof Map)
        {
            Map<?, ?> map = (Map<?, ?>) v;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> en : map.entrySet())
            {
                String vs = summarizeRefJson(en.getValue());
                if (vs.isEmpty()) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(en.getKey()).append(": ").append(vs);
            }
            return sb.toString();
        }
        if (v instanceof List)
        {
            List<?> list = (List<?>) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++)
            {
                String vs = summarizeRefJson(list.get(i));
                if (vs.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(vs);
            }
            return sb.toString();
        }
        return stateValueStr(v);
    }

    /**
     * Renders {@link ReferenceEntry#start} defensively: an {@code {npc,
     * location}} object on most rows (quest_db.jsonl) as "npc, location", a
     * plain string verbatim, or — for any other shape a future source might
     * carry — falls back to {@link #summarizeRefJson} rather than assuming.
     */
    private static String summarizeStart(Object start)
    {
        if (start == null) return "";
        if (!(start instanceof Map)) return stateValueStr(start);
        Map<?, ?> m = (Map<?, ?>) start;
        List<String> parts = new ArrayList<>();
        String npc = stateValueStr(m.get("npc"));
        String location = stateValueStr(m.get("location"));
        if (!npc.isEmpty() && !"null".equals(npc)) parts.add(npc);
        if (!location.isEmpty() && !"null".equals(location)) parts.add(location);
        return !parts.isEmpty() ? String.join(", ", parts) : summarizeRefJson(start);
    }

    /**
     * Renders card-facts as grouped information blocks — one block per fact
     * label (overview, mechanics, combat, …), values listed under it in
     * {@link #FACT_ORDER}. This is the structured replacement for the prose
     * {@code notes} blob: every line is an objective, checkable statement.
     */
    private void appendFactBlocks(StringBuilder sb, List<ReferenceEntry.Fact> facts)
    {
        if (facts.isEmpty()) return;
        Map<String, List<String>> byLabel = new LinkedHashMap<>();
        for (ReferenceEntry.Fact f : facts)
        {
            if (f == null || f.value == null || f.value.isEmpty()) continue;
            String label = f.label != null && !f.label.isEmpty() ? f.label : "note";
            byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(f.value);
        }
        if (byLabel.isEmpty()) return;
        sb.append("<div class=\"ref-facts\">\n");
        for (String label : FACT_ORDER) appendFactGroup(sb, label, byLabel.remove(label));
        for (Map.Entry<String, List<String>> extra : byLabel.entrySet())
        {
            appendFactGroup(sb, extra.getKey(), extra.getValue());
        }
        sb.append("</div>\n");
    }

    private void appendFactGroup(StringBuilder sb, String label, List<String> values)
    {
        if (values == null || values.isEmpty()) return;
        sb.append("<div class=\"ref-fact\"><span class=\"ref-fact-label\">")
          .append(esc(label)).append("</span><ul class=\"ref-fact-vals\">\n");
        for (String v : values) sb.append("<li>").append(esc(v)).append("</li>\n");
        sb.append("</ul></div>\n");
    }

    /**
     * Renders the parsed requisite items as a checkable block: name × qty,
     * with parenthetical context and an "optional" badge where the source
     * flagged one. "??" quantities are shown verbatim — never guessed.
     */
    private void appendReqItems(StringBuilder sb, List<ReferenceEntry.ReqItem> items)
    {
        if (items.isEmpty()) return;
        sb.append("<div class=\"ref-block ref-reqitems\"><span class=\"ref-block-label\">items</span>")
          .append("<ul class=\"ref-items\">\n");
        for (ReferenceEntry.ReqItem it : items)
        {
            if (it == null || it.name == null || it.name.isEmpty()) continue;
            appendReqItemLi(sb, it.name, it.qty, it.note, it.optional);
        }
        sb.append("</ul></div>\n");
    }

    /**
     * One requisite-item {@code <li>}: name × qty, parenthetical note, and an
     * "optional" badge — the shared primitive behind {@link #appendReqItems}
     * (reference cards) and {@link #appendRequisites} (step detail), since
     * {@link ReferenceEntry.ReqItem} and {@link GuideStep.ReqItem} are the
     * same shape but distinct Java types (no common interface to dispatch on).
     */
    private static void appendReqItemLi(StringBuilder sb, String name, Object qty, String note, boolean optional)
    {
        sb.append("<li>").append(esc(name));
        String qtyStr = reqItemQty(qty);
        if (!qtyStr.isEmpty()) sb.append(" <span class=\"ref-item-qty\">×").append(esc(qtyStr)).append("</span>");
        if (note != null && !note.isEmpty())
        {
            sb.append(" <span class=\"ref-item-note\">(").append(esc(note)).append(")</span>");
        }
        if (optional) sb.append(" <span class=\"ref-item-opt\">optional</span>");
        sb.append("</li>\n");
    }

    private static String reqItemQty(Object qty)
    {
        if (qty == null) return "";
        if (qty instanceof Double)
        {
            double d = (Double) qty;
            return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d);
        }
        String s = qty.toString().trim();
        return "1".equals(s) ? "" : s; // ×1 is noise
    }

    private static String factSearchBlob(ReferenceEntry e)
    {
        StringBuilder sb = new StringBuilder();
        for (ReferenceEntry.Fact f : e.facts())
        {
            if (f != null && f.value != null) sb.append(f.value).append(' ');
        }
        for (ReferenceEntry.ReqItem it : e.reqItems())
        {
            if (it != null && it.name != null) sb.append(it.name).append(' ');
        }
        return sb.toString();
    }

    private static String notesOrEmpty(ReferenceEntry e)
    {
        return e.notes != null ? e.notes : "";
    }

    /**
     * Renders the 1:many training methods as a pick-one list — each option a
     * card with its band, a meta line (location · xp/hr · members) and its own
     * wiki breadcrumbs. Replaces the single sloppy cited detail line: the
     * player chooses a method, every option is wiki-grounded. "??" values are
     * shown verbatim, never guessed.
     */
    private void appendMethodPicker(StringBuilder sb, List<GuideStep.TrainMethod> methods)
    {
        sb.append("<h3>Training methods <span class=\"pick-hint\">— pick one</span></h3>\n");
        sb.append("<ul class=\"train-methods\">\n");
        for (GuideStep.TrainMethod m : methods)
        {
            if (m == null || m.method == null || m.method.isEmpty()) continue;
            sb.append("<li class=\"train-method\">\n");
            sb.append("<div class=\"tm-head\"><span class=\"tm-name\">").append(esc(m.method)).append("</span>");
            if (m.level_band != null && !m.level_band.isEmpty())
            {
                sb.append(" <span class=\"tm-band\">lv ").append(esc(m.level_band)).append("</span>");
            }
            sb.append("</div>\n");
            String meta = methodMeta(m);
            if (!meta.isEmpty()) sb.append("<div class=\"tm-meta\">").append(meta).append("</div>\n");
            String reqs = summarizeRefJson(m.reqs);
            if (!reqs.isEmpty())
            {
                sb.append("<div class=\"tm-reqs\"><span class=\"ref-line-label\">reqs</span> ")
                  .append(esc(reqs)).append("</div>\n");
            }
            appendRefChips(sb, Dedupe.refs(m.refs()));
            sb.append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    /** "⏩ grab now — pays off at ‹consumer›" breadcrumb for a re-pinned opportunistic step. */
    private void appendPayoff(StringBuilder sb, GuideStep.GuidePayoff payoff)
    {
        if (payoff == null || payoff.at == null || payoff.at.isEmpty()) return;
        String item = payoff.item != null && !payoff.item.isEmpty()
            ? payoff.item.replace('_', ' ') : "this";
        sb.append("<div class=\"payoff-chip\" title=\"Collected early while you're in position — saves a return trip.\">")
          .append("&#9193; grab now — <span class=\"payoff-item\">").append(esc(item))
          .append("</span> pays off at <span class=\"payoff-at\">").append(esc(payoff.at))
          .append("</span></div>\n");
    }

    /**
     * "alt: ‹alt_group›" chip for a {@code branch{}} skippable-alternative
     * step (opp-stub re-pins, GRANULARITY branch{}); {@code optional:true}
     * additionally renders a "skippable" badge. Same reused-in-both-contexts
     * placement as {@link #appendPayoff} — visible in the plan list row and
     * the detail header alike.
     */
    private void appendBranch(StringBuilder sb, GuideStep.Branch branch)
    {
        if (branch == null || branch.alt_group == null || branch.alt_group.isEmpty()) return;
        sb.append("<div class=\"branch-chip\" title=\"Part of a skippable alternative — done via a different route counts too.\">")
          .append("alt: <span class=\"branch-alt\">").append(esc(branch.alt_group)).append("</span>");
        if (branch.optional) sb.append(" <span class=\"branch-optional\">skippable</span>");
        sb.append("</div>\n");
    }

    /** location · xp/hr · members chips for one training method. */
    private static String methodMeta(GuideStep.TrainMethod m)
    {
        List<String> parts = new ArrayList<>();
        if (m.location != null && !m.location.isEmpty())
        {
            parts.add("<span class=\"tm-loc\">" + esc(m.location) + "</span>");
        }
        String xp = methodXpHr(m.xp_hr);
        if (!xp.isEmpty()) parts.add("<span class=\"tm-xp\">" + esc(xp) + "</span>");
        String members = methodMembers(m.members);
        if (!members.isEmpty()) parts.add("<span class=\"tm-mem\">" + esc(members) + "</span>");
        return String.join(" <span class=\"tm-sep\">·</span> ", parts);
    }

    private static String methodXpHr(Object xpHr)
    {
        if (xpHr == null) return "";
        if (xpHr instanceof Number)
        {
            long xp = ((Number) xpHr).longValue();
            if (xp <= 0) return "";
            return xp >= 1000 ? (xp / 1000) + "k xp/hr" : xp + " xp/hr";
        }
        String s = xpHr.toString().trim();
        return s.isEmpty() ? "" : "xp/hr " + s; // "??"
    }

    private static String methodMembers(Object members)
    {
        if (members == null) return "";
        if (members instanceof Boolean) return ((Boolean) members) ? "members" : "F2P";
        String s = members.toString().trim();
        return s.isEmpty() ? "" : "members " + s; // "??"
    }

    /**
     * Renders a step's opportunistic-granularity sub-checklist (GRANULARITY
     * W3 Faux-grain atoms, ATTACH model) as an ordered, equal-grade action
     * list beneath the coarse step's own detail/method-picker — the coarse
     * step stays the routing/grant anchor; this is its granular breakdown.
     * Checkpoint dividers ({@code checkpoint.start} = first member atom id)
     * split the list into named sub-groups, mirroring the main plan list's
     * {@code .checkpoint-divider}.
     */
    private void appendSubChecklist(StringBuilder sb, GuideStep.SubChecklist sc)
    {
        List<GuideStep.SubStep> atoms = sc.atoms();
        if (atoms.isEmpty()) return;
        Map<String, String> checkpointAt = new LinkedHashMap<>();
        for (GuideStep.Checkpoint cp : sc.checkpoints())
        {
            if (cp != null && cp.start != null && cp.label != null) checkpointAt.put(cp.start, cp.label);
        }
        sb.append("<h3>Sub-checklist <span class=\"pick-hint\">— granular steps</span></h3>\n");
        sb.append("<ol class=\"subchecklist\">\n");
        for (GuideStep.SubStep a : atoms)
        {
            if (a == null) continue;
            String cpLabel = a.id != null ? checkpointAt.get(a.id) : null;
            if (cpLabel != null)
            {
                sb.append("<li class=\"checkpoint-divider\">").append(esc(cpLabel)).append("</li>\n");
            }
            sb.append("<li class=\"substep\">\n");
            sb.append("<div class=\"substep-instruction\">").append(esc(a.label != null ? a.label : "")).append("</div>\n");
            String line = atomLine(a.atom);
            if (!line.isEmpty())
            {
                sb.append("<div class=\"substep-atom\">").append(esc(line)).append("</div>\n");
            }
            if (a.detail != null && !a.detail.isEmpty())
            {
                sb.append("<div class=\"substep-detail\">").append(esc(a.detail)).append("</div>\n");
            }
            List<GuideHint> hints = Dedupe.hints(a.hints());
            if (!hints.isEmpty())
            {
                sb.append("<div class=\"hint-chips\">\n");
                for (GuideHint h : hints)
                {
                    sb.append("<span class=\"hint-chip\" title=\"")
                      .append(esc(h.note != null ? h.note : ""))
                      .append("\">")
                      .append(esc(hintChipLabel(h)))
                      .append("</span>\n");
                }
                sb.append("</div>\n");
            }
            appendRefChips(sb, Dedupe.refs(a.refs()));
            sb.append("</li>\n");
        }
        sb.append("</ol>\n");
    }

    /**
     * Structured REQUISITES block for the step detail pane (NORMALIZATION.md
     * §1d, gap-reqblocks-01): the step's own {@code req_items} where present,
     * else a summary line per QUEST/SKILL completionCondition (the same
     * structured-vs-summary precedence {@link #referenceCard} uses for
     * facts vs notes). A "‹atom› — needs: …" line per sub-checklist atom
     * with non-empty {@code consumes} is always appended underneath, when
     * the step carries one. Renders nothing when all three are empty.
     */
    private void appendRequisites(StringBuilder sb, GuideStep step)
    {
        List<GuideStep.ReqItem> items = step.reqItems();
        List<String> prereqs = prereqSummaries(step.completionConditions());
        List<String> needs = subChecklistNeeds(step.subChecklist);
        if (items.isEmpty() && prereqs.isEmpty() && needs.isEmpty()) return;

        sb.append("<div class=\"ref-block step-block\"><span class=\"ref-block-label\">requisites</span>\n")
          .append("<ul class=\"ref-items\">\n");
        if (!items.isEmpty())
        {
            for (GuideStep.ReqItem it : items)
            {
                if (it == null || it.name == null || it.name.isEmpty()) continue;
                appendReqItemLi(sb, it.name, it.qty, it.note, it.optional);
            }
        }
        else
        {
            for (String r : prereqs) sb.append("<li>").append(esc(r)).append("</li>\n");
        }
        for (String n : needs) sb.append("<li class=\"step-needs\">").append(esc(n)).append("</li>\n");
        sb.append("</ul></div>\n");
    }

    /** QUEST/SKILL completionConditions summarized as plain requisite lines — the true "gate" conditions, as opposed to VARBIT/ITEM_HELD/REGION triggers already covered by the cond-badge row. */
    private static List<String> prereqSummaries(List<CompletionCondition> conds)
    {
        List<String> out = new ArrayList<>();
        for (CompletionCondition c : conds)
        {
            if (c != null && (c.type == ConditionType.QUEST || c.type == ConditionType.SKILL))
            {
                out.add(conditionSummary(c));
            }
        }
        return out;
    }

    /** "‹atom label› — needs: item×qty, …" per sub-checklist atom carrying a non-empty {@code consumes} map. */
    private static List<String> subChecklistNeeds(GuideStep.SubChecklist sc)
    {
        List<String> out = new ArrayList<>();
        if (sc == null) return out;
        for (GuideStep.SubStep a : sc.atoms())
        {
            if (a == null) continue;
            String needs = summarizeRefJson(a.consumes);
            if (needs.isEmpty()) continue;
            out.add((a.label != null && !a.label.isEmpty() ? a.label : a.id) + " — needs: " + needs);
        }
        return out;
    }

    /**
     * Structured REWARDS block for the step detail pane: the only
     * reward-shaped data a {@link GuideStep} carries today is its
     * {@code quest-xp} hints (reward-xp grants) — surfaced here as a typed
     * block additive to the existing hint-chip row. Renders nothing absent.
     */
    private void appendRewards(StringBuilder sb, GuideStep step)
    {
        List<String> rewards = new ArrayList<>();
        for (GuideHint h : step.hints())
        {
            if (h == null || !"quest-xp".equals(h.type) || h.value == null || h.value.isEmpty()) continue;
            rewards.add(h.value);
        }
        if (rewards.isEmpty()) return;
        sb.append("<div class=\"ref-block step-block\"><span class=\"ref-block-label\">rewards</span>\n<ul class=\"ref-items\">\n");
        for (String r : rewards) sb.append("<li>").append(esc(r)).append("</li>\n");
        sb.append("</ul></div>\n");
    }

    /** Compact "verb target ×count" line from an atom{} descriptor; empty when verb is absent. */
    private static String atomLine(GuideStep.Atom atom)
    {
        if (atom == null || atom.verb == null || atom.verb.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(atom.verb);
        if (atom.target != null && !atom.target.isEmpty()) sb.append(' ').append(atom.target);
        String countStr = atomCountStr(atom.count);
        if (!countStr.isEmpty()) sb.append(" ×").append(countStr);
        return sb.toString();
    }

    /**
     * Renders {@link GuideStep.Atom#count}: numeric (Gson deserializes a
     * JSON number into this {@code Object} field as {@code Double}) or the
     * literal "??" tuning placeholder — same polymorphic handling as {@link
     * #reqItemQty}, minus its "×1 is noise" suppression (an atom count of 1
     * is still meaningful, unlike a requisite-item quantity).
     */
    private static String atomCountStr(Object count)
    {
        if (count == null) return "";
        if (count instanceof Double)
        {
            double d = (Double) count;
            return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d);
        }
        return count.toString().trim();
    }

    /** Shared wiki ref-chip render (step detail + method picker). */
    private void appendRefChips(StringBuilder sb, List<GuideRef> refs)
    {
        if (refs.isEmpty()) return;
        sb.append("<div class=\"ref-chips\">\n");
        for (GuideRef ref : refs)
        {
            if (ref.title == null) continue;
            sb.append("<a class=\"ref-chip\" href=\"#\"")
              .append(" data-wiki-title=\"").append(esc(ref.title)).append("\"")
              .append(" data-wiki-url=\"").append(esc(ref.url != null ? ref.url : "")).append("\"")
              .append(">&#128214; ").append(esc(ref.title)).append("</a>\n");
        }
        sb.append("</div>\n");
    }

    private static String truncate(String s, int max)
    {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max).trim() + "…";
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
