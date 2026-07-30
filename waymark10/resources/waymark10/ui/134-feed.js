/* ── feed view: the collection, one full-screen panel at a time ────── */
/* VIEW_RENDERERS.feed (waymark-h50). The dispatch seam
   (110-discovery-routing.js) has already fetched the collection at the
   hash's href — view= stripped — and calls
   renderFeed(view, doc, hints, decl) with the envelope's advertised
   view entry {name, kind, where, card, display}. This renderer lays
   the view's :where over the params the hash already carries
   (refetching only when that changes the slice) and presents the items
   IN THE ENVELOPE'S ORDER — no ranking, no recommendation: the
   server's order is the order. Each panel is the item speaking for
   itself — display title, the :card fields (the table's grid columns
   when the view names none), the summary sentence, and the item's REAL
   action buttons off its own per-item envelope, so grants and state
   keep gating and the feed invents no affordance. ↑/↓ and
   PageUp/PageDown move exactly one panel; Escape or the ✕ chip
   rewrites the hash without view= and the table returns; nearing the
   end fetches the envelope's next link and appends, ending on an
   honest terminal panel when there is no next. */

const FEED_KEY_STEP = {ArrowDown: 1, PageDown: 1, ArrowUp: -1, PageUp: -1};

async function renderFeed(view, doc, hints, decl) {
  const kind = doc.kind.replace("_collection", "");
  hints = hints || {};
  decl = decl || {};
  const viewLabel = decl.display?.label || title(decl.name || "feed");

  /* the effective slice: the view's :where laid over what the hash
     (echoed by doc.self) already carries — the where wins its own
     keys, every other typed param stays. A changed slice starts from
     page 1; an unchanged one reuses the envelope render() already
     paid for. */
  const {path, params} = parseHrefQuery(doc.self);
  let moved = false;
  for (const [f, v] of Object.entries(decl.where || {}))
    if (params.get(f) !== String(v)) { params.set(f, String(v)); moved = true; }
  if (moved) params.delete("page[number]");

  /* chrome first: the overlay lives INSIDE #view, so the next render()
     tears the whole feed down with the screen it replaces */
  const pos = el("span", {class: "feed-pos", title: "position · envelope count"}, "…");
  const scroller = el("div", {class: "feed-scroll"});
  const root = el("div",
    {class: "feedview", role: "region",
     "aria-label": `${title(kind)}s — ${viewLabel}`},
    el("div", {class: "feed-top"},
      el("span", {class: "feed-name"}, title(kind) + "s",
        el("span", {class: "muted"}, ` · ${viewLabel}`)),
      pos,
      el("button", {class: "feed-close", "data-feed-close": "",
                    title: "back to the table (Esc)",
                    onclick: () => closeFeed()}, "✕ Table")),
    scroller);
  view.append(root);
  watchScope({kind});
  /* keys arm before any fetch — Escape must work even when the slice
     read below fails and the feed stands on its error panel (onKey and
     friends are function declarations: hoisted, safe to bind here) */
  document.addEventListener("keydown", onKey);

  /* the way out: the same hash, view= removed — the standard table */
  function closeFeed() {
    const {href} = splitViewParam(location.hash.slice(1) || "");
    go(href || doc.self);
  }

  if (moved) {
    const {ok, body} = await api(path + (params.toString() ? "?" + params : ""));
    if (!root.isConnected) return;      // a newer render superseded us
    if (!ok) {
      scroller.append(el("section", {class: "feed-panel"},
        el("div", {class: "feed-body"}, problemBox(body))));
      return;
    }
    doc = body;
  }

  const gq = doc.actions?.query?.input || null;
  const seen = new Set();     // one panel per row, even when pages shift
  const items = [];
  let nextHref = doc.links?.next?.href || null;
  let total = doc.data?.total ?? (doc.data?.items || []).length;
  let loading = false;

  /* ── one item's panel body, rebuilt in place after an action lands
     (the fresh envelope answers — the feed never guesses an outcome) */
  const orderOf = e => (e.display || {}).order ?? 99;
  function fillPanel(box, item) {
    box.textContent = "";
    const heading = item.display?.title || item.summary || title(kind);
    box.append(
      el("div", {class: "feed-meta"},
        el("span", {class: "statechip"}, item.state),
        el("span", {class: "version"},
          ((item.meta || {}).updated_at || "").slice(0, 16).replace("T", " "))),
      el("h2", {class: "feed-title prose"}, heading));
    /* the server's own sentence — skipped only when it IS the heading */
    if (item.summary && item.summary !== heading)
      box.append(el("div", {class: "feed-summary"}, item.summary));
    /* the card: the view's declared field subset, else the same grid
       columns the table shows — through the shared field helpers, so
       refs stay live links and values stay honest */
    const have = item.fields || {};
    const named = (decl.card || []).filter(f => f in have);
    const fields = named.length ? named
      : fieldColumns([item], gq ? gridColumns(gq) : null, hints);
    if (fields.length) {
      const t = el("table", {class: "kv feed-kv"});
      for (const f of fields)
        t.append(el("tr", {},
          el("td", {class: "k", title: f}, fieldLabel(hints, f)),
          el("td", {}, fieldCell(hints, f, have[f]))));
      box.append(t);
    }
    /* the item's real doors: its own actions (the full dialog flow)
       plus any download/external links — exactly the row's affordances */
    const bar = el("div", {class: "actions feed-actions"});
    for (const [name, entry] of Object.entries(item.actions || {})
           .sort(([a, ea], [b, eb]) =>
             orderOf(ea) - orderOf(eb) || a.localeCompare(b)))
      bar.append(actionButton({name, entry, doc: item,
        onDone: () => refreshPanel(box, item.self)}));
    for (const [rel, l] of Object.entries(item.links || {}))
      if (l && (l.download || l.external))
        bar.append(el("a", {class: "chip link-chip", href: l.href,
          target: "_blank", rel: "noopener", title: l.summary || rel},
          (l.download ? "⭳ " : "↗ ") + title(rel)));
    if (bar.childElementCount) box.append(bar);
    box.append(el("a", {class: "feed-open", href: "#" + item.self,
      title: item.self}, "Open full page ↗"));
  }
  async function refreshPanel(box, selfHref) {
    const {ok, body} = await api(selfHref);
    if (!box.isConnected) return;
    if (ok && body && body.self) fillPanel(box, body);
    else render();   // the row may be gone — rebuild rather than go stale
  }
  function appendItems(list) {
    for (const item of list || []) {
      if (!item || !item.self || seen.has(item.self)) continue;
      seen.add(item.self);
      items.push(item);
      const box = el("div", {class: "feed-body"});
      fillPanel(box, item);
      scroller.insertBefore(el("section", {class: "feed-panel"}, box),
                            endPanel);
    }
  }

  /* ── the terminal panel: a load sentinel while pages remain, the
     honest end when none do — the observer watches it either way ── */
  const endBody = el("div", {class: "feed-body feed-end-body"});
  const endPanel = el("section", {class: "feed-panel feed-end"}, endBody);
  scroller.append(endPanel);
  function paintEnd(problem) {
    endBody.textContent = "";
    if (problem)
      endBody.append(
        el("div", {class: "muted"}, `couldn't load the next page — ${problem}`),
        el("div", {}, el("button", {onclick: () => loadMore()}, "Retry")));
    else if (loading || nextHref)
      endBody.append(el("div", {class: "muted"},
        loading ? "loading more…" : "…"));
    else
      endBody.append(
        el("div", {class: "feed-fin"}, items.length
          ? `— end of collection · ${items.length} of ${total} —`
          : "nothing in this view"),
        el("div", {}, el("button", {onclick: () => closeFeed()},
          "Back to the table")));
  }
  async function loadMore() {
    if (!nextHref || loading) return;
    loading = true;
    paintEnd();
    try {
      const {ok, body} = await api(nextHref);
      if (!root.isConnected) return;
      if (!ok) { paintEnd((body || {}).summary || (body || {}).title ||
                          "the server refused"); return; }
      appendItems(body.data?.items);
      total = body.data?.total ?? total;
      nextHref = body.links?.next?.href || null;
      paintEnd();
      updatePos();
      /* re-arm: a short page can leave the sentinel inside the margin
         with no new intersection to report — re-observing forces one */
      io.unobserve(endPanel);
      io.observe(endPanel);
    } finally { loading = false; }
  }
  const io = new IntersectionObserver(entries => {
    if (entries.some(x => x.isIntersecting)) loadMore();
  }, {root: scroller, rootMargin: "200% 0px"});
  io.observe(endPanel);

  /* ── position + keyboard: exactly one panel per keypress ── */
  function panelIndex() {
    return Math.round(scroller.scrollTop / (scroller.clientHeight || 1));
  }
  function snapTo(i) {
    const count = scroller.querySelectorAll(".feed-panel").length;
    const j = Math.max(0, Math.min(i, count - 1));
    scroller.scrollTo({top: j * scroller.clientHeight, behavior: "smooth"});
  }
  function updatePos() {
    const i = Math.min(panelIndex(), items.length);
    pos.textContent = i < items.length
      ? `${i + 1} of ${total}`
      : (nextHref || loading ? "…" : (items.length ? "end" : "0 of 0"));
  }
  let posTick = false;
  scroller.addEventListener("scroll", () => {
    if (posTick) return;
    posTick = true;
    requestAnimationFrame(() => { posTick = false; updatePos(); });
  });
  function onKey(e) {
    /* the feed died with its screen — the listener goes with it */
    if (!root.isConnected) { document.removeEventListener("keydown", onKey); return; }
    const t = e.target;
    if (t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || t.isContentEditable))
      return;                        // typing owns the keys
    if ($("dialog[open]")) return;   // the action dialog owns them too
    if (e.key === "Escape") { e.preventDefault(); closeFeed(); return; }
    const step = FEED_KEY_STEP[e.key];
    if (step === undefined) return;
    e.preventDefault();              // one panel, never a free scroll
    snapTo(panelIndex() + step);
  }

  appendItems(doc.data?.items);
  paintEnd();
  updatePos();
}

/* the registry hookup: the dispatch seam calls this for any advertised
   view whose kind is "feed" (110-discovery-routing.js) */
VIEW_RENDERERS.feed = renderFeed;
