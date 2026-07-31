// Reads the rendered Marketplace page, applies the same filters the desktop
// extension uses, and hands back only the listings worth waking someone for.
//
// All the judging happens here rather than in Kotlin, because the rules were
// written and tested in JavaScript against 123 real listing titles and porting
// them to another language would mean re-earning every one of those cases. This
// file is loaded straight after filters.js, which is copied verbatim from the
// extension so the phone and the desktop cannot disagree about what a television
// is.
//
// The placeholder on the CFG line below is substituted with the trader's settings
// by the service before this is evaluated. It is deliberately not named anywhere
// else in the file: JavaScript's String.replace swaps only the first occurrence,
// so a mention in a comment would be the one replaced and the real assignment
// would be left holding an undefined name.
(function () {
  var CFG = __CONFIG__;

  function out(payload) {
    try { Android.onListingsFound(JSON.stringify(payload)); }
    catch (e) { /* the bridge is gone; nothing useful left to do */ }
  }

  try {
    var links = document.querySelectorAll('a[href*="/marketplace/item/"]');

    // An empty page is not the same as a page with nothing worth alerting on.
    // Facebook returns a fully rendered shell with no results when it is rate
    // limiting, and the service needs to be able to tell those apart.
    if (!links.length) { out({ blank: true, listings: [] }); return; }

    // Best timestamp per id, preferring the pair whose id sat closest to it in
    // the payload.
    var ages = {};
    var pairs = window.__tvPairs__ || [];
    for (var p = 0; p < pairs.length; p++) {
      var pr = pairs[p];
      if (!ages[pr.id] || pr.dist < ages[pr.id].dist) ages[pr.id] = pr;
    }

    var blockWords = splitTerms(CFG.blockWords);
    var extra = splitTerms(CFG.excludeExtra || "");
    var seen = {};
    var kept = [];
    var stats = { total: 0, notTv: 0, tooSmall: 0, tooDear: 0, tooFar: 0, tooOld: 0, noDate: 0 };

    for (var i = 0; i < links.length; i++) {
      var link = links[i];
      var href = link.getAttribute("href") || "";
      var m = href.match(/\/marketplace\/item\/(\d+)/);
      if (!m || seen[m[1]]) continue;
      seen[m[1]] = true;
      stats.total++;

      var id = m[1];

      // Card text. Facebook nests everything in spans with no stable classes, so
      // the fields are recovered by shape: a price starts with a currency symbol
      // or is "Free", the title is the first substantial line after it, and the
      // place is the line after that.
      var spans = link.querySelectorAll("span");
      var texts = [];
      for (var s = 0; s < spans.length; s++) {
        var t = (spans[s].innerText || spans[s].textContent || "").trim();
        if (t.length > 0 && t.length < 200 && texts.indexOf(t) === -1) texts.push(t);
      }

      var priceText = "", title = "", place = "";
      for (var n = 0; n < texts.length; n++) {
        var txt = texts[n];
        if (/^(just listed|new)$/i.test(txt)) continue;
        if (!priceText && (/^[£$€\d]/.test(txt) || txt.toLowerCase() === "free")) priceText = txt;
        else if (!title && txt.length > 2 && !/^\d+\s*(miles?|km)/i.test(txt)) title = txt;
        else if (title && !place && txt.length > 2) place = txt;
      }
      if (!title) continue;

      var price = 0;
      if (/free/i.test(priceText)) price = 0;
      else {
        var pm = priceText.replace(/,/g, "").match(/(\d+(?:\.\d+)?)/);
        price = pm ? parseFloat(pm[1]) : 0;
      }

      // -- the same order of judgement the extension uses --------------------
      var verdict = classify(title, blockWords, extra);
      if (!verdict.ok) { stats.notTv++; continue; }

      if (isTooFar(place)) { stats.tooFar++; continue; }

      // Only applied when both the setting asks for it and the ported filter set
      // provides it. v15.1 has no size rule at all, so the function is absent
      // there and calling it would throw the whole scan away.
      if (CFG.minInches > 0 && typeof statedInches === "function") {
        var inches = statedInches(title);
        if (inches !== null && inches < CFG.minInches) { stats.tooSmall++; continue; }
      }

      if (CFG.maxPrice > 0 && price > 0 && price > CFG.maxPrice) { stats.tooDear++; continue; }

      // Age last, because it is the only test that needs the network capture and
      // the only one that can be unknown.
      var ageMinutes = null;
      if (ages[id]) ageMinutes = Math.max(0, Math.round((Date.now() - ages[id].time) / 60000));

      if (ageMinutes === null) {
        // No date from Facebook. Alerting anyway is how days-old stock reached
        // the trader on the desktop build, so it is held unless asked for.
        stats.noDate++;
        if (CFG.requireKnownAge) continue;
      } else if (ageMinutes > CFG.maxAgeMinutes) {
        stats.tooOld++;
        continue;
      }

      kept.push({
        id: id,
        title: title,
        price: priceText || "See listing",
        location: place || "",
        ageMinutes: ageMinutes,
        url: "https://www.facebook.com/marketplace/item/" + id
      });
    }

    out({ blank: false, listings: kept, stats: stats });
  } catch (e) {
    out({ blank: false, listings: [], error: String(e).slice(0, 200) });
  }
})();
