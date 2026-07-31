// Network interceptor. Must run BEFORE Facebook fires its first GraphQL request,
// so the service injects it on page start rather than page finish.
//
// This is the only way to learn how old a listing is. The cards themselves carry
// a "Just listed" badge on everything and no usable date, while the GraphQL
// payload behind them carries creation_time. Without this the app can only report
// "age unknown", and an age it cannot check is the difference between catching a
// deal and being told about one that sold twenty minutes ago.
//
// It reports candidate (id, time) pairs only. Deciding which id owns which
// timestamp is left to scan.js, which can cross-check against the ids actually
// rendered on the page.
(function () {
  if (window.__tvcap__) return;
  window.__tvcap__ = true;
  window.__tvPairs__ = [];

  var ID_PAT = /"(?:listing_id|marketplace_listing_id|id)"\s*:\s*"?(\d{8,20})"?/g;
  var TIME_PAT = /"(?:creation_time|created_time|publish_time)"\s*:\s*(\d{9,13})/g;

  function harvest(text) {
    if (!text || text.length < 80) return;
    if (text.indexOf("creation_time") === -1 &&
        text.indexOf("created_time") === -1 &&
        text.indexOf("publish_time") === -1) return;

    var ids = [], times = [], m;
    ID_PAT.lastIndex = 0; TIME_PAT.lastIndex = 0;
    while ((m = ID_PAT.exec(text)) !== null) ids.push({ v: m[1], i: m.index });
    while ((m = TIME_PAT.exec(text)) !== null) times.push({ v: parseInt(m[1], 10), i: m.index });
    if (!ids.length || !times.length) return;

    for (var t = 0; t < times.length; t++) {
      var ts = times[t].v;
      if (ts < 1e12) ts *= 1000;                                  // seconds -> ms
      if (ts < 1.5e12 || ts > Date.now() + 3600000) continue;      // sanity bounds

      // Nearest id before and after: Facebook nests the id inside the same
      // listing object, so both directions are worth reporting and the DOM
      // cross-check throws away whichever is wrong.
      var before = null, after = null;
      for (var k = 0; k < ids.length; k++) {
        var d = ids[k].i - times[t].i;
        if (d <= 0) { if (before === null || -d < before.dist) before = { id: ids[k].v, dist: -d }; }
        else        { if (after  === null ||  d < after.dist)  after  = { id: ids[k].v, dist: d }; }
      }
      if (before && before.dist < 3000) window.__tvPairs__.push({ id: before.id, time: ts, dist: before.dist });
      if (after  && after.dist  < 3000) window.__tvPairs__.push({ id: after.id,  time: ts, dist: after.dist });
    }
    if (window.__tvPairs__.length > 4000) window.__tvPairs__ = window.__tvPairs__.slice(-2000);
  }

  function interesting(u) {
    return typeof u === "string" &&
      (u.indexOf("/api/graphql") !== -1 || u.indexOf("graphql") !== -1 || u.indexOf("/ajax/") !== -1);
  }

  var origFetch = window.fetch;
  if (typeof origFetch === "function") {
    window.fetch = function () {
      var args = arguments;
      var p = origFetch.apply(this, args);
      try {
        var u = typeof args[0] === "string" ? args[0] : (args[0] && args[0].url) || "";
        if (interesting(u)) {
          p.then(function (res) {
            try { res.clone().text().then(harvest, function () {}); } catch (e) {}
          }, function () {});
        }
      } catch (e) {}
      return p;
    };
  }

  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function () {
    try { this.__tvUrl = arguments[1] || ""; } catch (e) {}
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    var self = this;
    try {
      if (interesting(self.__tvUrl)) {
        self.addEventListener("load", function () {
          // Instrumentation must never break the page it is watching.
          try { harvest(self.responseText || ""); } catch (e) {}
        });
      }
    } catch (e) {}
    return origSend.apply(this, arguments);
  };
})();
