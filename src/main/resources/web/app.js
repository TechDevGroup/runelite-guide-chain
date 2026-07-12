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

  // Scroll the current step into view once the plan first renders.
  var scrolled = false;
  document.body.addEventListener('htmx:afterSwap', function (e) {
    if (scrolled || e.target.id !== 'plan') return;
    var cur = document.querySelector('[data-current="1"]');
    if (cur) {
      cur.scrollIntoView({ block: 'center' });
      scrolled = true;
    }
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
    if (e.key === 'Escape') closeWikibox();
  });
})();
