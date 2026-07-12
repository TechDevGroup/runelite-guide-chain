/* Guide Chain web view — tiny vanilla helpers alongside htmx. */
(function () {
  'use strict';

  // Footer metrics + connection status, refreshed from the state API.
  function refreshMetrics() {
    fetch('/api/state.json')
      .then(function (r) { return r.json(); })
      .then(function (s) {
        var el = document.getElementById('metrics');
        if (!el) return;
        var m = s.metrics || {};
        el.textContent =
          (s.clientConnected ? 'client connected' : 'client offline') +
          ' · done ' + (m.stepsCompleted || 0) +
          ' · skipped ' + (m.stepsSkipped || 0) +
          ' · web actions ' + (m.webActions || 0);
      })
      .catch(function () { /* server going away; htmx polling will surface it */ });
  }
  refreshMetrics();
  setInterval(refreshMetrics, 5000);

  // Scroll to the current step EXACTLY ONCE — on the first plan render that has
  // a current step (page open). Never again: not on the 2s poll, and crucially
  // not when the user toggles a checkbox or clicks a step label (those re-render
  // #plan, and re-scrolling on every interaction is the "jumps to the bottom"
  // annoyance). After the initial orient, the user's scroll position is theirs.
  var didInitialScroll = false;
  document.body.addEventListener('htmx:afterSwap', function (e) {
    if (didInitialScroll || e.target.id !== 'plan') return;
    var cur = document.querySelector('[data-current="1"]');
    if (!cur) return;
    didInitialScroll = true;
    cur.scrollIntoView({ block: 'center' });
  });

  // Live plan refresh — driven here, not by an htmx `every 2s` on #plan, so it
  // can PAUSE while a browse view is open. Library/Reference render into #plan
  // with a `.library` root; the old poll re-fetched /fragments/plan every 2s and
  // flipped the browse content back to the checklist. Skip the refresh whenever
  // #plan is not currently showing the plan.
  function planIsShowing() {
    var plan = document.getElementById('plan');
    return !!(plan && !plan.querySelector('.library') &&
              plan.querySelector('.plan-head, .plan-list, .empty'));
  }
  function refreshPlan() {
    if (!planIsShowing() || typeof htmx === 'undefined') return;
    htmx.ajax('GET', '/fragments/plan', { target: '#plan', swap: 'innerHTML' });
  }
  // Refresh ONLY on real changes (a checklist action fires guide-store-changed),
  // never on a blind timer: the old every-2s re-swap made the .pane class flicker
  // htmx-request/settling every 1-2s and reset scroll mid-drag. A live game client,
  // when connected, dispatches guide-store-changed on state change — event-driven.
  document.body.addEventListener('guide-store-changed', refreshPlan);

  // Kill the native jump from every internal action anchor (href="#"): htmx /
  // our own handlers do the real work, and letting the browser resolve "#"
  // scrolls the page (to the top, or to the bottom via scroll-restoration on a
  // re-rendered list). Bubble-phase, so htmx's element handler has already run.
  document.addEventListener('click', function (e) {
    if (e.target.closest('a[href="#"]')) e.preventDefault();
  });

  // ── Wiki lightbox ──────────────────────────────────────────────────────────
  // ONE delegated handler on document survives all htmx swaps.
  function openWikibox(title, extUrl) {
    var box   = document.getElementById('wikibox');
    var frame = document.getElementById('wikibox-frame');
    var lbl   = document.getElementById('wikibox-title');
    var ext   = document.getElementById('wikibox-ext');
    if (!box || !frame) return;
    frame.src = '/wiki/page?title=' + encodeURIComponent(title);
    if (lbl) lbl.textContent = title;
    if (ext) ext.href = extUrl || ('https://oldschool.runescape.wiki/w/' + encodeURIComponent(title));
    box.hidden = false;
  }

  function closeWikibox() {
    var box = document.getElementById('wikibox');
    if (box) box.hidden = true;
    // intentionally do NOT clear iframe src — reopening is instant from cache
  }

  document.addEventListener('click', function (e) {
    var chip = e.target.closest('.ref-chip');
    if (!chip) return;
    e.preventDefault();
    openWikibox(chip.dataset.wikiTitle || '', chip.dataset.wikiUrl || '');
  });

  document.addEventListener('click', function (e) {
    if (e.target.id === 'wikibox-close') closeWikibox();
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { closeWikibox(); closeMediaLightbox(); }
  });

  // ── Reference catalog search ──────────────────────────────────────────────
  // One delegated 'input' listener (bubbles, unlike focus/blur) survives the
  // #plan htmx swaps that replace the #ref-search box itself on every kind-tab
  // click — filters .ref-card elements client-side against each card's own
  // pre-lowercased data-search blob (name + notes + reqs + rewards summary).
  document.addEventListener('input', function (e) {
    if (e.target.id !== 'ref-search') return;
    var q = e.target.value.trim().toLowerCase();
    document.querySelectorAll('.ref-card').forEach(function (card) {
      card.hidden = q !== '' && (card.dataset.search || '').indexOf(q) === -1;
    });
    document.querySelectorAll('.ref-cat').forEach(function (section) {
      var visible = section.querySelector('.ref-card:not([hidden])');
      section.hidden = q !== '' && !visible;
    });
  });

  // ── Media gallery + lightbox (FRAMES_GALLERY §3) ─────────────────────────
  // The #gallery pane lives OUTSIDE the #plan/#detail swap zones, so it is
  // refreshed here in plain JS — never via an htmx poll — on exactly two
  // triggers: the detail pane's step key changing, and guide-store-changed
  // (an htmx-dispatched DOM event from any action's HX-Trigger response
  // header). This is also what buys the poll-safety invariant: the gallery's
  // scroll position and an open lightbox are untouched by every #plan/#detail
  // 2s re-poll, since neither ever targets #gallery or #media-lightbox.
  var galleryKey = null;

  function loadGallery(key) {
    var pane = document.getElementById('gallery');
    if (!pane) return;
    if (!key) { pane.innerHTML = ''; pane.hidden = true; return; }
    fetch('/fragments/gallery/' + key.split('/').map(encodeURIComponent).join('/'))
      .then(function (r) { return r.text(); })
      .then(function (html) {
        pane.innerHTML = html;
        pane.hidden = html.trim() === ''; // steps without media collapse to nothing
      })
      .catch(function () { /* server going away; next trigger will retry */ });
  }

  document.body.addEventListener('htmx:afterSwap', function (e) {
    if (e.target.id !== 'detail') return;
    var card = e.target.querySelector('.detail-card');
    var key = card ? card.dataset.stepKey : '';
    if (key === galleryKey) return;
    galleryKey = key;
    document.body.dispatchEvent(new CustomEvent('step-focus-changed', { detail: { key: key } }));
  });

  document.body.addEventListener('step-focus-changed', function (e) {
    loadGallery(e.detail && e.detail.key);
  });

  document.body.addEventListener('guide-store-changed', function () {
    loadGallery(galleryKey);
  });

  function mediaChip(label, value) {
    if (!value) return '';
    return '<span class="media-chip">' + label + ': ' + value + '</span>';
  }

  function openMediaLightbox(thumb) {
    var box = document.getElementById('media-lightbox');
    if (!box) return;
    var mediaSlot = document.getElementById('media-lightbox-media');
    var caption = document.getElementById('media-lightbox-caption');
    var chips = document.getElementById('media-lightbox-chips');
    var refsSlot = document.getElementById('media-lightbox-refs');
    if (!mediaSlot || !caption || !chips || !refsSlot) return;

    var src = thumb.dataset.mediaSrc || '';
    var kind = thumb.dataset.mediaKind || 'png';
    var cap = thumb.dataset.caption || '';

    mediaSlot.innerHTML = kind === 'gif'
      ? '<img src="' + src + '" alt="' + cap + '">'
      : '<img src="' + src + '" alt="' + cap + '">';
    caption.textContent = cap;
    chips.innerHTML =
      mediaChip('rev', thumb.dataset.rev ? 'r' + thumb.dataset.rev : '') +
      mediaChip('scenario', thumb.dataset.scenario) +
      mediaChip('tile', thumb.dataset.tile) +
      mediaChip('captured', thumb.dataset.captured);

    // Frames render alongside wiki refs in one lightbox family (FRAMES_GALLERY §3):
    // the gallery pane already rendered this step's ref chips — mirror them in.
    var galleryRefs = document.querySelector('#gallery .gallery-refs');
    refsSlot.innerHTML = galleryRefs ? galleryRefs.innerHTML : '';

    box.hidden = false;
  }

  function closeMediaLightbox() {
    var box = document.getElementById('media-lightbox');
    if (box) box.hidden = true;
  }

  document.addEventListener('click', function (e) {
    var thumb = e.target.closest('.gallery-thumb');
    if (thumb) { e.preventDefault(); openMediaLightbox(thumb); return; }
    if (e.target.id === 'media-lightbox-close') { closeMediaLightbox(); return; }
    if (e.target.id === 'media-lightbox') { closeMediaLightbox(); } // click-outside
  });
})();
