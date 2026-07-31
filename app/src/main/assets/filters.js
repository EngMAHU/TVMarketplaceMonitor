const STRONG_TV = [
  "tv", "tvs", "television", "televisions", "telly", "tellies",
  "smart tv", "led tv", "lcd tv", "oled", "qled", "neo qled", "plasma",
  "flat screen", "flatscreen", "smart television", "roku tv", "android tv", "webos"
];

// A brand alone proves nothing - "Sony PlayStation 5" and "LG washing machine"
// are not TVs. A brand only counts when paired with a screen size or a
// resolution, which is how TV listings are actually written.
const BRANDS = [
  "samsung", "lg", "sony", "bravia", "hisense", "tcl", "panasonic", "philips",
  "toshiba", "bush", "jvc", "hitachi", "sharp", "vizio", "insignia",
  "blaupunkt", "cello", "logik", "luxor", "polaroid", "sanyo"
];
const SIZE_RE = /\b\d{2}\s*(?:inch|inches|in|")\b|\b\d{2}"/;
const RES_RE = /\b(?:4k|8k|uhd|fhd|full hd|1080p|2160p|hdr|freeview)\b/;

// Accessories, furniture and unrelated goods - never a TV sale.
const NOT_TV = [
  "tv unit", "tv units", "tv cabinet", "tv table", "tv bench", "tv shelf",
  "tv trolley", "tv remote", "tv aerial", "tv antenna", "tv licence",
  "tv guide", "tv box", "tv cable", "tv lead", "tv bed",
  "remote control", "ottoman", "wardrobe", "sofa", "mattress",
  // Furniture matched as whole phrases only, so "TV / Media Cabinet Unit" slid
  // past both "tv cabinet" and "tv unit" and was alerted as a TV. Match the
  // furniture nouns on their own instead of relying on the exact wording.
  "cabinet", "cabinets", "unit", "units", "sideboard", "dresser",
  "media wall", "panel", "panels", "teak", "shelf", "shelves",
  // Services and app-setup adverts touted in the TV feed - not stock a trader
  // can buy. "stick" and "packages" catch the reseller ads that flood this
  // category ("Box's stick smart TV App Setup & Packages Available"), which slip
  // past the Firestick block because they never say "fire".
  "sky tv", "iptv", "tv setup", "app setup", "setup", "setup help",
  "subscription", "subscriptions", "package", "packages", "stick", "sticks",
  "repair", "repairs", "installation", "installer", "fitting service",
  "jailbroken", "fully loaded", "loaded",
  // Wall-mounting tradesmen advertise in the TV feed at TV-like prices. The
  // bundle block list covers "mount"/"mounted" but not "mounting", and whole-word
  // matching means "mount" never matches inside "mounting" - which is how
  // "TV Wall Mounting Handyman" reached the trader as a Â£31 alert.
  "mounting", "wall mounting", "handyman", "service", "services",
  "fitter", "fitters", "fitting", "call out", "callout",
  "baby", "sleepyhead", "pram", "pushchair", "cot", "moses basket",
  "car seat", "highchair", "bouncer", "playmat",
  // Vehicles. A car advertised with a built-in TV or DVD screen contains the
  // word "tv" and passed as a television - a Toyota Corolla reached the trader
  // that way. Vauxhall "Insignia" is deliberately absent: Insignia is also a
  // television brand, and losing real TVs costs more than one stray car.
  "toyota", "corolla", "yaris", "avensis", "vauxhall", "astra", "corsa",
  "zafira", "peugeot", "renault", "citroen", "nissan", "qashqai", "juke",
  "micra", "mercedes", "bmw", "audi", "volkswagen", "skoda", "octavia",
  "hyundai", "kia", "mazda", "subaru", "mitsubishi", "jaguar",
  "land rover", "range rover", "ford fiesta", "ford focus", "ford mondeo",
  "transit", "mot", "mileage", "hatchback", "saloon", "diesel", "petrol",
  "seater", "alloys", "alloy wheels",
  // Merchandise and collectibles about television, which is not a television.
  "script", "scripts", "bobblehead", "bobbleheads", "poster", "posters",
  "box set", "boxset", "annual", "magazine", "figurine", "funko"
];

// Safety net on top of the ring radii. Facebook does not always honour a radius
// exactly, and a seller's stated town can sit well outside the feed's centre -
// a Sheffield-centred source produced Nottingham listings roughly 100 miles from
// Liverpool. These are the places beyond the 70-mile catchment that have
// actually turned up; a listing from one of them is not worth a drive.
const FAR_PLACES = [
  "nottingham", "stapleford", "newark", "mansfield", "derby", "ashbourne",
  "leeds", "bradford", "wakefield", "stanley", "barnsley", "doncaster",
  "rotherham", "sheffield", "chesterfield", "killamarsh", "cleckheaton",
  "huddersfield", "halifax", "york", "hull", "scunthorpe", "lincoln",
  "birmingham", "coventry", "wolverhampton", "leicester", "peterborough",
  "carlisle", "newcastle upon tyne", "sunderland", "middlesbrough",
  "bristol", "london", "cardiff", "swansea", "aberystwyth", "bangor",
  "holyhead", "caernarfon", "pwllheli", "dolgellau"
];

function isTooFar(place) {
  const p = normalise(place);
  if (!p) return false;
  for (const far of FAR_PLACES) if (hasTerm(p, far)) return true;
  return false;
}

function normalise(s) {
  return (s || "").toLowerCase().replace(/[\u2018\u2019]/g, "'").replace(/\s+/g, " ").trim();
}

function hasTerm(text, term) {
  const t = (term || "").trim();
  if (!t) return false;
  const escaped = t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const left = /^[\w]/.test(t) ? "\\b" : "";
  const right = /[\w]$/.test(t) ? "\\b" : "";
  try { return new RegExp(left + escaped + right).test(text); } catch { return false; }
}

// "no stand", "without stand", "doesn't come with a stand" all describe a TV the
// trader DOES want - the whole point of the block list is to skip TVs bundled
// with one. Blocking on the bare word threw those listings away.
const NEGATORS = /\b(no|not|without|w\/o|excludes?|missing|doesn'?t come with|does not come with|dont have|don'?t have|hasn'?t got)\b[^.,;]{0,14}$/;

function hasUnnegatedTerm(text, term) {
  const t = (term || "").trim();
  if (!t) return false;
  const escaped = t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const left = /^[\w]/.test(t) ? "\\b" : "";
  const right = /[\w]$/.test(t) ? "\\b" : "";
  let re;
  try { re = new RegExp(left + escaped + right, "g"); } catch { return false; }

  let m, sawAny = false;
  while ((m = re.exec(text)) !== null) {
    sawAny = true;
    const before = text.slice(Math.max(0, m.index - 30), m.index);
    if (!NEGATORS.test(before)) return true;   // a real, unnegated mention
    if (m.index === re.lastIndex) re.lastIndex++;
  }
  return sawAny ? false : false;               // every mention was negated
}

function splitTerms(s) {
  return normalise(s).split(",").map(x => x.trim()).filter(Boolean);
}

function classify(title, blockWords, extraExcludes) {
  const t = normalise(title);

  for (const term of NOT_TV) if (hasTerm(t, term)) return { ok: false, reason: "not-a-tv" };
  // Bundle words respect negation; "no stand" is a selling point, not a bundle.
  for (const term of blockWords) if (hasUnnegatedTerm(t, term)) return { ok: false, reason: "bundle" };
  for (const term of extraExcludes) if (hasUnnegatedTerm(t, term)) return { ok: false, reason: "custom" };

  for (const term of STRONG_TV) if (hasTerm(t, term)) return { ok: true };
  if (SIZE_RE.test(t) || RES_RE.test(t)) {
    for (const b of BRANDS) if (hasTerm(t, b)) return { ok: true };
  }
  return { ok: false, reason: "no-tv-keyword" };
}