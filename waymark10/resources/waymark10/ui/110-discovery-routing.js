/* ── discovery: well-known drives everything ───────────────────────── */
let wellKnownCache = null;   // caches the PROMISE: concurrent callers share
/* the RESOLVED document, kept synchronously beside the promise. A field
   cell renders in one pass and cannot await, so the address rule (150)
   reads this when discovery is warm — which it is for every render
   after the first — and upgrades its cells in place when it is not. */
let wellKnownNow = null;
function wellKnown() {
  if (!wellKnownCache)
    wellKnownCache = api("/api/.well-known/waymark").then(
      r => { if (!r.ok) throw new Error("no discovery");
             reflectIdentity(r.body.principal);
             wellKnownNow = r.body;
             return r.body; },
      err => { wellKnownCache = null; throw err; });
  return wellKnownCache;
}
/* nav rides the wire: well-known stamps each resource with its nav
   tier (secondary = a domain kind behind the ⋯ menu, system = the
   engine's own machinery, grouped apart in the same menu) and, under
   a multi-domain deployable, its domain — plus a top-level ordered
   domains list. The old client-side ENGINE_KINDS set is retired; the
   engine's own kinds declare :nav :system and fold behind the ⋯ from
   the wire alone. */
function navTier(r) { return (r || {}).nav || "primary"; }
function domainOf(w, href) {
  for (const r of Object.values(w.resources || {}))
    if (r.href === href && r.domain) return r.domain;
  return null;
}
function domainHome(w, d) {
  for (const r of Object.values(w.resources || {}))
    if (r.domain === d && navTier(r) === "primary") return r.href;
  for (const r of Object.values(w.resources || {}))
    if (r.domain === d) return r.href;
  return null;
}
function collectionHref(idx, kind) {
  return (((idx || {}).resources || {})[kind] || {}).href || null;
}
/* collectionHref read backwards: which kind does "/api/<plural>" name?
   The legal plurals are the ENGINE's own registry, straight off
   well-known's resources — this page keeps no list of its own, so a
   deployable that serves a plural and one that does not are told apart
   by the wire and never by a name kept here. */
function kindAtHref(idx, href) {
  for (const [kind, r] of Object.entries(((idx || {}).resources) || {}))
    if (r && r.href === href) return kind;
  return null;
}

/* published data schemas: x-display hints per kind */
const dataHintsCache = {};
async function kindSchema(kind) {
  if (!(kind in dataHintsCache)) {
    try {
      const {ok, body} = await api(`/api/schemas/${kind}`);
      dataHintsCache[kind] = ok ? body : {};
    } catch { dataHintsCache[kind] = {}; }
  }
  return dataHintsCache[kind];
}
function xdisplay(schema, field) {
  const prop = ((schema || {}).properties || {})[field] || {};
  return prop["x-display"] || schemaProp(prop)["x-display"] || {};
}
function xref(prop) {
  if (!prop || typeof prop !== "object") return null;
  const sub = schemaProp(prop);
  return prop["x-ref"] || sub["x-ref"] ||
    (prop.format === "waymark-ref" || sub.format === "waymark-ref" ? {} : null);
}
/* a field's human label: the declared x-display label, else — for a
   ref, whose cell shows the target's live summary — the bare thing
   ("fund_id" → "Fund", "member_ids" → "Members"), else the titled
   field name. */
function fieldLabel(hints, f) {
  const xd = xdisplay(hints, f);
  if (xd.label) return xd.label;
  if (xref(((hints || {}).properties || {})[f])) {
    const m = /^(.*?)_(ids?)$/.exec(f);
    if (m) return title(m[1]) + (m[2] === "ids" ? "s" : "");
  }
  return title(f);
}
function fieldHidden(hints, f) { return !!xdisplay(hints, f).hidden; }

/* ── routing: the URL hash IS the resource href ────────────────────── */
function go(href) { location.hash = href; }
window.addEventListener("hashchange", render);

/* ── collection view renderers: kind → (view, body, hints, viewDecl).
   The registry is the dispatch seam — the deck (swipe-triage) and feed
   (sequential) renderers register here when they land; an advertised
   view whose kind has no entry falls back to renderCollection. */
const VIEW_RENDERERS = {};

/* view=<name> is CLIENT state, never a query param: it names which of
   the envelope's advertised views renders this collection, and the
   server would 422 it as an unknown parameter — so it is parsed OUT of
   the hash query before the fetch. Returns {href, viewName}. */
function splitViewParam(href) {
  if (!href || !href.includes("?")) return {href, viewName: null};
  const [path, q] = href.split("?");
  const p = new URLSearchParams(q);
  const viewName = p.get("view");
  if (viewName === null) return {href, viewName: null};
  p.delete("view");
  return {href: path + (p.toString() ? "?" + p : ""), viewName};
}

let renderSeq = 0;
async function render() {
  const seq = ++renderSeq;               // fetch first, swap after — a
  const raw = location.hash.slice(1) || null;
  const {href, viewName} = splitViewParam(raw);
  const view = $("#view");               // superseded render never blanks
  renderNav(href ? href.split("?")[0].split("/").slice(0, 3).join("/") : null);
  if (!href) { lawStamp(null); return renderHome(view, seq); }
  if (href === "access") {
    lawStamp(null);
    return renderAccess(view, seq);
  }
  if (/^\/api\/surfaces\//.test(href)) {
    const {ok, body} = await api(href);
    if (seq !== renderSeq) return;
    clearLiveTimers(); view.textContent = ""; lawStamp(null);
    if (!ok) return view.append(problemBox(body));
    return renderSurface(view, body);
  }
  const {ok, body} = await api(href);
  let hints = {};
  /* "feed" names a DOCUMENT, not a resource kind (waymark-iqa.2), so
     there is no published schema to read and the screen learns its
     hints per CARD kind instead */
  if (ok && body && body.kind && body.kind !== "feed")
    hints = await kindSchema(String(body.kind).replace(/_collection$/, ""));
  if (seq !== renderSeq) return;
  clearLiveTimers();
  view.textContent = "";
  lawStamp(ok ? body : null);
  if (!ok) return view.append(problemBox(body));
  if (body.kind === "definition_collection") renderDeployHistory(view, body);
  else if (body.kind && body.kind.endsWith("_collection")) {
    /* view dispatch: the envelope must advertise the named view AND a
       renderer for its kind must be registered — anything less falls
       back to the table, gracefully */
    const decl = viewName &&
      (body.views || []).find(v => v.name === viewName) || null;
    const renderer = decl && VIEW_RENDERERS[decl.kind];
    if (renderer) renderer(view, body, hints, decl);
    else renderCollection(view, body, hints);
  }
  /* the feed fork (waymark-iqa.7), the same tradition one document
     later: GET /api/-/feed answers a document that NAMES ITSELF, and
     a card census has no collection to be a view of */
  else if (body.kind === "feed" && Array.isArray(body.cards))
    renderFeedScreen(view, body);
  /* the dashboard fork (waymark-ggw), the deploy-history tradition:
     kind picks the renderer — but only when the envelope carries the
     framework kind's render contract (the embedded :slots link), so
     an app's own unrelated "dashboard" kind keeps its kv table */
  else if (body.kind === "dashboard" && ((body.links || {}).slots || {}).embedded)
    renderDashboard(view, body);
  else renderResource(view, body, hints);
}

/* ── the law stamp: the envelope's own meta.law_revision, read straight
   off the wire — the deploy history is one click away ──────────────── */
function lawStamp(doc) {
  const box = $("#lawstamp");
  box.textContent = "";
  const rev = doc?.meta?.law_revision;
  if (rev == null || !doc.kind) return;
  const kind = String(doc.kind).replace(/_collection$/, "");
  wellKnown().then(w => {
    const col = collectionHref(w, "definition");
    if (!col || !document.contains(box)) return;
    box.append(el("a",
      {href: `#${col}?target_kind=${encodeURIComponent(kind)}`,
       title: `the law in force for ${pretty(kind)} — revision ${rev}. `
            + `Click for the deploy history.`},
      `⚖ rev ${rev}`));
  }).catch(() => {});
}

