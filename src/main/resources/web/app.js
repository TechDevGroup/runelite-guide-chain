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
})();
