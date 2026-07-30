/* ── the deck view: swipe-triage over a declared queue ──────────────
   VIEW_RENDERERS.deck — registered against the :views dispatch seam
   (110-discovery-routing.js): render() calls it as
   renderer(view, body, hints, decl) when the envelope advertises a
   view named by the hash's view= param and its :kind is "deck".

   The envelope ADVERTISES the view (its :where slice, card fields,
   and two gestures wearing the bound actions' own labels) — the
   affordance itself stays PER ITEM: a gesture commits by invoking
   that item's own actions entry, so grants and state keep gating
   exactly as they do in the table; an entry under `unavailable`
   disables that direction for that card and shows the reason. Deck
   actions are reversible by declaration (checks.clj holds that
   line), so commits ride the bare-invocation path with the undo
   toast — never a confirm dialog.

   The queue drains itself: a committed card's action moves the row
   out of the view's :where filter, so removal is local; a small
   lookahead buffer refills through the envelope's own next link, and
   an emptied buffer re-reads the base slice once before declaring
   the queue drained.

   No automated harness drives the gestures — the repo verifies UI
   screens by hand (docs/waymark10-design.md §10) — so this stays
   small, readable, and defensive. */
VIEW_RENDERERS.deck = function renderDeck(view, doc, hints, decl) {
  const kind = doc.kind.replace("_collection", "");
  const gestures = decl.gestures || {};
  const viewLabel = decl.display?.label || title(decl.name);
  const LOOKAHEAD = 3;

  /* the deck's slice: the view's :where params applied ON TOP of
     whatever the hash already carries (the advertised where wins;
     the page resets — a narrowed filter starts at the top) */
  const rawHash = location.hash.slice(1) || doc.self;
  const {href: tableHref} = splitViewParam(rawHash);
  const {path, params} = parseHrefQuery(tableHref);
  for (const [f, v] of Object.entries(decl.where || {}))
    params.set(f, String(v));
  params.delete("page[number]");
  const deckHref = path + (params.toString() ? "?" + params : "");

  /* ── local state: the lookahead buffer IS the deck ─────────────── */
  let queue = [];         // undealt items, envelope order — [0] is the top card
  let nextHref = null;    // the envelope's own next link, when one rode in
  let serverTotal = 0;    // the filtered total, as last reported
  let drainedHere = 0;    // commits since that report
  let busy = false;       // one commit at a time
  let refilling = false;
  const dealt = new Set(); // item.self — never deal the same card twice

  /* ── chrome ─────────────────────────────────────────────────────── */
  const panel = el("div", {class: "panel deck"});
  panel.append(el("div", {class: "crumbs"},
    el("a", {href: "#"}, "Workspace"), " / ",
    el("a", {href: "#" + tableHref}, title(kind) + "s"), " / ", viewLabel));
  panel.append(el("h2", {}, viewLabel));
  const vs = viewSwitcher(doc.views);
  if (vs) panel.append(vs);
  const countLine = el("div", {class: "metaline deck-count"});
  const stack = el("div", {class: "deck-stack"});
  const reasonLine = el("div", {class: "deck-reason"});
  const btnFor = side => {
    const g = gestures[side] || {};
    const arrow = side === "left" ? "←" : "→";
    const b = el("button", {class: "deck-btn deck-btn-" + side,
      "data-gesture": side,
      title: `${g.label || pretty(g.action || side)} (${arrow} arrow key, or swipe)`,
      onclick: () => commit(side)});
    b.append(side === "left" ? arrow + " " : "", g.label || pretty(g.action || side),
             side === "right" ? " " + arrow : "");
    return b;
  };
  const btnLeft = btnFor("left"), btnRight = btnFor("right");
  /* the way out of a card neither gesture may take: deal it to the
     bottom of the LOCAL buffer — a judgment deferred, nothing written */
  const btnLater = el("button", {class: "deck-btn deck-later",
    title: "set this card aside — it returns at the end of the queue",
    onclick: () => {
      if (busy || queue.length < 2) return;
      queue.push(queue.shift());
      renderStack();
    }}, "later ↓");
  panel.append(countLine, stack,
    el("div", {class: "deck-controls"}, btnLeft, btnLater, btnRight),
    reasonLine);
  view.append(panel);
  /* the deck manages its own drain — a foreign transition must not
     re-render() mid-swipe, so the live-refetch scope watches nothing */
  watchScope({});

  /* ── the buffer ─────────────────────────────────────────────────── */
  function remaining() {
    return Math.max(queue.length, serverTotal - drainedHere);
  }
  async function fetchInto(href) {
    const {ok, body} = await api(href);
    if (!ok) { stack.replaceChildren(problemBox(body)); return false; }
    serverTotal = body.data?.total ?? 0;
    drainedHere = 0;
    nextHref = body.links?.next?.href || null;
    for (const it of body.data?.items || [])
      if (it.self && !dealt.has(it.self)) { dealt.add(it.self); queue.push(it); }
    return true;
  }
  async function refill() {
    if (refilling || queue.length >= LOOKAHEAD) return;
    refilling = true;
    const wasEmpty = !queue.length;
    try {
      if (nextHref) await fetchInto(nextHref);
      /* no next link and nothing local: one honest re-read of the
         base slice before the drained verdict — later pages shift
         down as page 1 drains, so the next link can run out early */
      else if (!queue.length) await fetchInto(deckHref);
    } finally { refilling = false; }
    if (wasEmpty) renderStack();
  }

  /* ── per-card affordances: the item's own entry, or its reason ──── */
  function gestureEntry(item, side) {
    const aname = (gestures[side] || {}).action;
    if (!aname) return {reason: "no " + side + " gesture declared"};
    const entry = (item.actions || {})[aname];
    if (entry) return {entry};
    const blocked = (item.unavailable || {})[aname];
    return {reason: (blocked && blocked.reason) ||
                    `${pretty(aname)} is not offered on this ${pretty(kind)}`};
  }
  function cardFieldsOf(item) {
    const fields = item.fields || {};
    const declared = (decl.card || []).filter(f => f in fields);
    if (declared.length) return declared;
    /* fallback: the same columns the table shows */
    return fieldColumns([item], gridColumns(doc.actions?.query?.input), hints);
  }

  /* ── the stack: the top card plus a visual lookahead ────────────── */
  function renderStack() {
    stack.replaceChildren();
    reasonLine.textContent = "";
    countLine.textContent = remaining()
      ? `${remaining()} to triage` : "";
    if (!queue.length) {
      btnLeft.disabled = btnRight.disabled = btnLater.disabled = true;
      stack.append(el("div", {class: "deck-done"},
        el("div", {class: "deck-done-mark"}, "✓"),
        el("div", {class: "prose"}, "Queue drained — nothing left to triage."),
        el("span", {class: "chip", style: "cursor:pointer",
          onclick: () => go(tableHref)}, "Back to the table")));
      return;
    }
    for (let i = Math.min(queue.length - 1, 2); i >= 1; i--)
      stack.append(el("div", {class: "deck-card under u" + i}));
    const item = queue[0];
    const card = buildCard(item);
    attachDrag(card, item);
    stack.append(card);
    for (const [side, btn] of [["left", btnLeft], ["right", btnRight]]) {
      const {reason} = gestureEntry(item, side);
      btn.disabled = !!reason;
      btn.title = reason || `${(gestures[side] || {}).label || ""} (arrow key, or swipe)`;
      if (reason) reasonLine.append(el("div", {class: "muted"},
        `${side === "left" ? "←" : "→"} ${(gestures[side] || {}).label || side}: ${reason}`));
    }
    btnLater.disabled = queue.length < 2;
  }

  function buildCard(item) {
    const card = el("div", {class: "deck-card top", tabindex: "0",
      role: "group", "aria-label": item.summary || pretty(kind)});
    card.append(
      el("div", {class: "deck-aff aff-left"}, (gestures.left || {}).label || "←"),
      el("div", {class: "deck-aff aff-right"}, (gestures.right || {}).label || "→"),
      el("div", {class: "deck-summary prose"},
        el("a", {href: "#" + item.self, title: item.self},
          item.summary || pretty(kind))));
    const kv = el("table", {class: "kv"});
    for (const f of cardFieldsOf(item))
      kv.append(el("tr", {},
        el("td", {class: "k", title: f}, fieldLabel(hints, f)),
        el("td", {}, fieldCell(hints, f, (item.fields || {})[f]))));
    card.append(kv);
    card.append(el("div", {class: "metaline"},
      el("span", {class: "statechip"}, item.state)));
    return card;
  }

  /* ── the gesture: pointer drag with a threshold commit ──────────── */
  function attachDrag(card, item) {
    let dragging = false, moved = false, startX = 0, dx = 0;
    const affL = card.querySelector(".aff-left");
    const affR = card.querySelector(".aff-right");
    const threshold = () =>
      Math.min(140, Math.max(70, stack.clientWidth * 0.35));
    const paint = () => {
      card.style.transform = dx
        ? `translateX(${dx}px) rotate(${dx * 0.05}deg)` : "";
      const t = threshold();
      affR.style.opacity = String(Math.min(1, Math.max(0, dx / t)));
      affL.style.opacity = String(Math.min(1, Math.max(0, -dx / t)));
      card.classList.toggle("will-right",
        dx > t && !gestureEntry(item, "right").reason);
      card.classList.toggle("will-left",
        dx < -t && !gestureEntry(item, "left").reason);
    };
    card.addEventListener("pointerdown", e => {
      if (busy || e.button > 0) return;
      dragging = true; moved = false; startX = e.clientX; dx = 0;
      try { card.setPointerCapture(e.pointerId); } catch (_e) {}
      card.classList.add("dragging");
    });
    card.addEventListener("pointermove", e => {
      if (!dragging) return;
      dx = e.clientX - startX;
      if (Math.abs(dx) > 6) moved = true;
      paint();
    });
    const release = () => {
      if (!dragging) return;
      dragging = false;
      card.classList.remove("dragging");
      const t = threshold();
      const side = dx > t ? "right" : dx < -t ? "left" : null;
      const allowed = side && !gestureEntry(item, side).reason;
      dx = 0;
      if (allowed) commit(side);
      else paint();               // springs back — the transition returns
    };
    card.addEventListener("pointerup", release);
    card.addEventListener("pointercancel", release);
    /* a drag that ends on the summary link must not also navigate */
    card.addEventListener("click", e => {
      if (moved) { e.preventDefault(); e.stopPropagation(); }
    }, true);
    card.addEventListener("keydown", e => {
      if (e.key === "Enter" && e.target === card) go(item.self);
    });
  }

  /* ── the commit: the per-item entry, bare-invoked ───────────────── */
  async function commit(side) {
    if (busy || !queue.length) return;
    const item = queue[0];
    const g = gestures[side] || {};
    const {entry, reason} = gestureEntry(item, side);
    if (!entry) { if (reason) toast(reason); return; }
    busy = true;
    const card = stack.querySelector(".deck-card.top");
    if (card) card.classList.add(side === "right" ? "fly-right" : "fly-left");
    const res = await invokeBare(entry, item);
    if (!res.ok) {
      /* the card returns to the stack, the problem speaks */
      busy = false;
      if (card) card.classList.remove("fly-right", "fly-left");
      const p = res.body || {};
      toast(`${g.label || pretty(g.action || side)} — ` +
            (p.detail || p.title || `HTTP ${res.status}`));
      return;
    }
    maybeUndoToast(g.action || side, item, res.body || {});
    setTimeout(() => {           // let the card finish leaving
      queue.shift();
      drainedHere++;
      busy = false;
      renderStack();
      refill();
    }, 180);
  }

  /* ── keyboard: the desktop's swipe ──────────────────────────────── */
  const onKey = e => {
    if (!document.contains(panel)) {
      document.removeEventListener("keydown", onKey);
      return;
    }
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    const t = e.target;
    if (t && /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)) return;
    if (document.querySelector("dialog[open]")) return;
    e.preventDefault();
    commit(e.key === "ArrowLeft" ? "left" : "right");
  };
  document.addEventListener("keydown", onKey);

  /* ── deal the first hand ────────────────────────────────────────── */
  countLine.textContent = "…";
  fetchInto(deckHref).then(ok => {
    if (!ok) { countLine.textContent = ""; return; }
    renderStack();
    refill();
  });
};
