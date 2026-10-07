/* ── the nav: built from discovery on EVERY load — a deep link
   hard-refreshed must not arrive without its navigation. Domain kinds
   sit inline; the engine's own kinds tuck behind a quiet ⋯ menu. ───── */
/* ── the global-navigation drawer (multi-domain deployables) ────────
   A left push-over listing every application and its kinds. The bar
   stays within-app (the active domain as a breadcrumb, its primary
   kinds beside it); switching applications lives behind the ☰. */
function drawerIsOpen() {
  return document.body.classList.contains("drawer-open");
}
function setDrawer(open) {
  document.body.classList.toggle("drawer-open", open);
  $("#drawerbtn").setAttribute("aria-expanded", String(open));
  if (open) ($("#drawer").querySelector("a") || $("#drawer")).focus();
  else $("#drawerbtn").focus();
}
$("#drawerbtn").addEventListener("click", () => setDrawer(!drawerIsOpen()));
$("#drawerback").addEventListener("click", () => setDrawer(false));
$("#drawer").addEventListener("click", ev => {
  if (ev.target.closest("a")) setDrawer(false);
});
document.addEventListener("keydown", ev => {
  if (ev.key === "Escape" && drawerIsOpen()) {
    ev.stopPropagation(); setDrawer(false);
  }
});

function fillDrawer(w, current, active) {
  const d = $("#drawer"); d.textContent = "";
  const entries = Object.entries(w.resources || {});
  d.append(el("a", {class: "dr-home", href: "#"}, "Waymark"));
  for (const dom of (w.domains || [])) {
    const home = domainHome(w, dom);
    d.append(el(home ? "a" : "span",
      Object.assign({class: "dr-domain",
                     style: dom === active ? "color:var(--ink);font-weight:700" : ""},
                    home ? {href: "#" + home} : {}),
      title(dom)));
    for (const [kind, r] of entries)
      if (r.domain === dom)
        d.append(el("a",
          {class: navTier(r) === "primary" ? "dr-kind" : "dr-second",
           href: "#" + r.href,
           style: current === r.href ? "font-weight:700" : ""},
          title(kind) + "s"));
  }
  const engine = entries.filter(([, r]) =>
    navTier(r) === "secondary" && !r.domain);
  if (engine.length) {
    d.append(el("span", {class: "dr-domain"}, "Engine"));
    for (const [kind, r] of engine)
      d.append(el("a", {class: "dr-second", href: "#" + r.href},
        title(kind) + "s"));
  }
}

async function renderNav(current) {
  let w;
  try { w = await wellKnown(); } catch { return; }
  const nav = $("#kinds"); nav.textContent = "";
  const entries = Object.entries(w.resources || {});
  const domains = w.domains || [];
  const active = current ? domainOf(w, current) : null;
  /* global navigation between applications: the drawer, behind ☰ —
     present exactly when the wire declares domains */
  $("#drawerbtn").classList.toggle("on", domains.length > 0);
  if (domains.length) fillDrawer(w, current, active);
  /* the tab bar has no header wordmark in reach — Home earns a tab */
  if (MOBILE) nav.append(el("a", {href: "#", "data-surface": "nav-home",
    style: !current ? "font-weight:700" : ""}, "Home"));
  /* the active application as a breadcrumb back to its home */
  if (active) {
    const home = domainHome(w, active);
    if (home) nav.append(el("a", {class: "nav-domain", href: "#" + home,
      "data-surface": "nav-domain",
      style: "color:var(--ink);font-weight:700"}, title(active)));
  }
  /* the active domain's primary kinds (a domainless primary always) */
  for (const [kind, r] of entries)
    if (navTier(r) === "primary" && (!r.domain || r.domain === active))
      nav.append(el("a", {href: "#" + r.href, "data-surface": "nav." + kind,
        style: current === r.href ? "font-weight:700" : ""}, title(kind) + "s"));
  /* the hand-in-hand door: invite an agent, judge its ask, follow it */
  if (w.resources && w.resources.member && askKind(w))
    nav.append(el("a", {href: "#access", "data-surface": "nav-access",
      style: current === "access" ? "font-weight:700" : ""}, "Access"));
  /* every other decision kind is a queue with a tab of its own (the
     ask kind's queue is the Access panel) */
  for (const kind of decisionKinds(w)) {
    const r = w.resources[kind];
    if (kind === askKind(w) || navTier(r) === "primary") continue;
    if (r.domain && r.domain !== active) continue;
    nav.append(el("a", {href: "#" + r.href, "data-surface": "nav." + kind,
      style: current === r.href ? "font-weight:700" : ""}, title(kind) + "s"));
  }
  /* secondary and system kinds fold behind ⋯ — domainless (the
     engine's own) always, a domain's own only while that domain is
     active */
  const tucked = entries.filter(([, r]) =>
    (navTier(r) === "secondary" || navTier(r) === "system")
    && (!r.domain || r.domain === active));
  /* the ⋯ is unconditional since the jump box moved into it
     (waymark-sv9v): a deployable with nothing tucked away still has a
     way to the box and to the other shell — the menu is never empty */
  nav.append(overflowMenu(tucked));
}

function overflowMenu(tuckedEntries, extra = {}) {
  const wrap = el("span", {class:"nav-more-wrap"});
  const menu = el("div", {class:"nav-menu", role:"menu"});
  const btn = el("button", {class:"nav-more", type:"button",
    "data-surface": "nav-more",
    "aria-haspopup":"true", "aria-expanded":"false",
    title: "more kinds — and the machinery's own resources"}, "⋯");
  /* a deployable with enough kinds overruns the screen: cap the menu
     at the room it actually has and let it scroll inside that. The
     stylesheets carry a floor; this measures the real one, because the
     room depends on where the ⋯ sits — below it on the desktop, above
     the tab bar on a phone, where the menu is anchored to the bottom
     (040-mobile.css) and so grows upward off the top instead. */
  const fit = () => {
    menu.style.maxHeight = "";
    const box = menu.getBoundingClientRect();
    const room = MOBILE ? box.bottom - 8 : innerHeight - box.top - 8;
    menu.style.maxHeight = Math.max(120, room) + "px";
  };
  const close = () => { menu.style.display = "none";
                        btn.setAttribute("aria-expanded", "false");
                        /* the listener lives only as long as the menu is
                           open: renderNav builds a fresh menu per screen */
                        removeEventListener("resize", fit); };
  const open = () => { menu.style.display = "block";
                       btn.setAttribute("aria-expanded", "true");
                       fit();
                       /* a rotated phone changes the room mid-open */
                       addEventListener("resize", fit);
                       (menu.querySelector("a") || btn).focus(); };
  btn.addEventListener("click", () =>
    menu.style.display === "block" ? close() : open());
  wrap.addEventListener("keydown", ev => {
    if (ev.key === "Escape" && menu.style.display === "block") {
      ev.stopPropagation(); close(); btn.focus(); }
  });
  document.addEventListener("pointerdown", ev => {
    if (!wrap.contains(ev.target)) close(); });
  /* domain kinds first, then the engine's own machinery under a
     quiet divider — two tiers, one menu */
  const item = ([kind, {href}]) =>
    el("a", {href: "#" + href, role:"menuitem", "data-surface": "nav." + kind,
             onclick: close},
      ...(kind === "definition" ? [el("span", {class:"law-mark"}, "⚖ ")] : []),
      title(kind) + "s");
  const domain = tuckedEntries.filter(([, r]) => navTier(r) === "secondary");
  const system = tuckedEntries.filter(([, r]) => navTier(r) === "system");
  /* the jump box needs a way in that is not a keyboard: a phone has
     no ⌘K, and a menu is where someone looks for "where else can I
     go" (waymark-sv9v). The shortcut rides along as a hint. */
  menu.append(el("a", {role: "menuitem", href: "#", "data-nav": "jump",
                       "data-surface": "nav-jump", class: "jump-row",
                       onclick: ev => { ev.preventDefault(); close();
                                        jumpOpen(); }},
    "Jump to a kind…",
    MOBILE ? null : el("span", {class: "jump-key"}, JUMPKEY)));
  domain.forEach(e => menu.append(item(e)));
  if (domain.length && system.length)
    menu.append(el("div", {class:"nav-menu-sect", role:"separator"}, "system"));
  system.forEach(e => menu.append(item(e)));
  /* the shell switch: a full reload with ?ui= beats the UA sniff,
     the hash (this screen) rides along */
  menu.append(el("a", {role: "menuitem", "data-surface": "nav-shell",
    href: location.pathname + "?ui=" + (MOBILE ? "desktop" : "mobile")
        + location.hash},
    MOBILE ? "Desktop view" : "Mobile view"));
  wrap.append(btn, menu);
  return wrap;
}

/* Build a filtered link by merging params into the advertised href —
   the server owns its URL shapes; this client only ever adds a query. */
function mergeParams(href, params) {
  const [base, q] = href.split("?");
  const qs = new URLSearchParams(q || "");
  for (const [k, v] of Object.entries(params)) qs.set(k, v);
  const s = qs.toString();
  return s ? `${base}?${s}` : base;
}

/* ── home: the dashboard — one rows=none envelope per domain kind (a
   single COUNT, no per-row probes) whose x-facets carry the per-state
   counts; the engine kinds and declared surfaces below. ────────────── */
async function renderHome(view, seq) {
  let w;
  try { w = await wellKnown(); } catch {
    view.textContent = "";
    return view.append(el("div", {class:"problem"},
      "Cannot reach /api/.well-known/waymark"));
  }
  if (seq !== renderSeq) return;
  clearLiveTimers();
  view.textContent = "";
  const entries = Object.entries(w.resources || {});
  const primaries = entries.filter(([, r]) => navTier(r) === "primary");
  const domains = w.domains || [];

  const strip = el("div");             // fills only if something is nonzero
  view.append(strip);
  const attention = [];
  const settling = [];
  const gridOf = list => {
    const grid = el("div", {class:"dash-grid"});
    view.append(grid);
    for (const [kind, {href}] of list) {
      const card = el("div", {class:"kind-card"});
      grid.append(card);
      settling.push(fillDashCard(card, kind, href));
    }
  };
  if (domains.length) {
    /* one section per application: its primary kinds as cards, its
       own secondary kinds (if any) as chips beneath */
    for (const d of domains) {
      const mine = primaries.filter(([, r]) => r.domain === d);
      if (!mine.length) continue;
      view.append(el("h3", {class:"sect"}, title(d)));
      gridOf(mine);
      const tucked = entries.filter(([, r]) =>
        navTier(r) === "secondary" && r.domain === d);
      if (tucked.length)
        view.append(el("div", {class:"chips"}, tucked.map(([kind, {href}]) =>
          el("a", {href: "#" + href, class: "link-chip"}, title(kind) + "s"))));
    }
    const loose = primaries.filter(([, r]) => !r.domain);
    if (loose.length) gridOf(loose);
  } else {
    gridOf(primaries);
  }
  /* the engine vitals: running/queued jobs surface as attention (a
     kind the engine did not enroll simply contributes nothing) */
  const jobHref = collectionHref(w, "job");
  if (jobHref)
    settling.push(api(mergeParams(jobHref, {rows: "none"})).then(({ok, body}) => {
      if (!ok) return;
      const facets = body.actions?.query?.input?.properties
        ?.state?.["x-facets"] || {};
      const live = ["running", "queued"].filter(s => facets[s] > 0);
      const n = live.reduce((sum, s) => sum + facets[s], 0);
      if (n) attention.push(el("div", {class:"item"},
        el("a", {href: "#" + mergeParams(jobHref, {state: live.join(",")})},
          el("span", {class:"mono"}, String(n)),
          n === 1 ? " job is running or queued." : " jobs are running or queued.")));
    }).catch(() => {}));

  /* the breaker panel (waymark-kyg.1): a dark connection is an outage
     the family should meet at the front door, not discover row by row */
  const connHref = collectionHref(w, "connection");
  if (connHref)
    settling.push(api(mergeParams(connHref, {rows: "none", state: "dark"}))
      .then(({ok, body}) => {
        if (!ok) return;
        const n = body.data?.total ?? 0;
        if (n) attention.push(el("div", {class:"item"},
          el("a", {href: "#" + mergeParams(connHref, {state: "dark"})},
            el("span", {class:"mono"}, String(n)),
            n === 1 ? " connection is dark — a source has stopped answering."
                    : " connections are dark — sources have stopped answering.")));
      }).catch(() => {}));

  /* the letter shelf (waymark-tti.3): a waiting letter meets its
     recipient at the front door; opening happens on the row itself
     (the generated open action), so each item just links there. The
     shelf also carries the "leave a letter" door — the generated
     create dialog; :to is typed as a raw member id for now. */
  const letterHref = collectionHref(w, "letter");
  const shelf = el("div", {class:"chips"});
  strip.after(shelf);
  if (letterHref && viewerId())
    settling.push(api(mergeParams(letterHref,
                                  {to: viewerId(), state: "waiting"}))
      .then(({ok, body}) => {
        if (!ok) return;
        for (const it of (body.data?.items || [])) {
          const f = it.fields || {};
          attention.push(el("div", {class:"item"},
            el("a", {href: "#" + it.self},
              "a letter from " + (f.owner || "someone") + " waits"
              + (f.title ? " — " + f.title : "") + ".")));
        }
        const create = body.actions?.create;
        if (create) {
          const btn = actionButton({name: "create", entry: create, doc: body,
            label: "Leave a letter", small: true,
            onDone: b => { if (b?.self) go(b.self); }});
          shelf.append(btn);
        }
      }).catch(() => {}));

  /* seasons (waymark-tti.2): the last weeks as a shape — the rhythm
     door's weekly buckets as one compact line per moving kind (tiny
     text bars scaled off completed counts, no chart machinery), and
     the quietly aging kinds into the attention strip */
  const seasons = el("div");
  view.append(seasons);
  settling.push(api("/api/-/seasons?weeks=4").then(({ok, body}) => {
    if (!ok) { seasons.remove(); return; }           // degrade silently
    const wks = body.weeks || [], aging = body.aging || [];
    const rows = new Map();                          // kind → tallies
    const of = kind => {
      if (!rows.has(kind))
        rows.set(kind, {bars: wks.map(() => 0), done: 0, fresh: 0});
      return rows.get(kind);
    };
    wks.forEach((wk, i) => {
      for (const [kind, c] of Object.entries(wk.kinds || {})) {
        const t = of(kind);
        t.bars[i] = c.completed || 0;
        t.done += c.completed || 0;
        t.fresh += c.created || 0;
      }
    });
    const old = new Map(aging.map(a => [a.kind, a]));
    for (const a of aging) of(a.kind);   // an aging-only kind still gets a line
    if (!rows.size) { seasons.remove(); return; }
    const glyphs = "▁▂▃▄▅▆▇";
    seasons.append(el("h3", {class: "sect"}, "Seasons"));
    for (const [kind, t] of rows) {
      const max = Math.max(1, ...t.bars);
      const spark = t.bars.map(n =>
        glyphs[Math.round(n / max * (glyphs.length - 1))]).join("");
      const a = old.get(kind);
      const href = collectionHref(w, kind);
      seasons.append(el("div", {},
        href ? el("a", {href: "#" + href}, title(kind))
             : el("span", {}, title(kind)),
        " ",
        el("span", {class: "mono", title: "completed per week"}, spark),
        " ",
        el("span", {class: "mono"},
          `${t.done} done / ${t.fresh} new`
          + (a ? ` · ${a.open_older_than_14d} aging (oldest ${a.oldest_days}d)`
               : ""))));
    }
    for (const a of aging) {
      if (!(a.open_older_than_14d > 0)) continue;
      const href = collectionHref(w, a.kind);
      attention.push(el("div", {class: "item"},
        el("a", href ? {href: "#" + href} : {},
          el("span", {class: "mono"}, String(a.open_older_than_14d)),
          ` ${title(a.kind).toLowerCase()}${a.open_older_than_14d === 1 ? "" : "s"}`
          + ` open for over two weeks (oldest ${a.oldest_days}d).`)));
    }
  }).catch(() => { seasons.remove(); }));

  await Promise.allSettled(settling);
  if (seq !== renderSeq) return;
  if (attention.length) strip.append(el("div", {class:"attention"}, attention));
  else strip.remove();
  if (!shelf.childNodes.length) shelf.remove();

  const chipRow = (label, list) => {
    if (!list.length) return;
    view.append(el("h3", {class:"sect"}, label),
      el("div", {class:"chips"}, list.map(([kind, {href}]) =>
        el("a", {href: "#" + href, class: "link-chip"}, title(kind) + "s"))));
  };
  chipRow("More kinds",
          entries.filter(([, r]) => navTier(r) === "secondary" && !r.domain));
  chipRow("System",
          entries.filter(([, r]) => navTier(r) === "system"));
  const surfaces = Object.entries(w.surfaces || {});
  if (surfaces.length) {
    view.append(el("h3", {class:"sect"}, "Declared surfaces"),
      el("div", {class:"chips"}, surfaces.map(([name, {href}]) =>
        /* anchored surfaces open from a row; an anchorless one is a
           standing queue — its href works right here */
        href.includes("{anchor-id}")
          ? el("span", {class:"surface-link", title: href + " — open from an anchor row"},
              "⧉ " + name)
          : el("a", {href:"#" + href, class:"chip surface-link", title: href},
              "⧉ " + name + " ↗"))));
  }
}

/* One domain kind's card: humanized name, the count in mono, state-facet
   chips as filtered links, the advertised create affordance. */
async function fillDashCard(card, kind, href) {
  let env;
  try {
    const {ok, body} = await api(mergeParams(href, {rows: "none"}));
    if (!ok) { card.remove(); return null; }   // degrade silently
    env = body;
  } catch { card.remove(); return null; }
  card.textContent = "";
  card.append(el("div", {class:"kc-head"},
    el("h3", {}, el("a", {href: "#" + href}, title(kind) + "s"))));
  const total = env.data?.total ?? 0;
  if (!total)
    card.append(el("div", {class:"kc-none"}, "none yet"));
  else {
    card.append(el("div", {class:"kc-total", title: `${total} total`},
      String(total)));
    const facets = env.actions?.query?.input?.properties
      ?.state?.["x-facets"] || {};
    const chips = Object.entries(facets).filter(([, n]) => n > 0);
    if (chips.length)
      card.append(el("div", {class:"kc-chips"}, chips.map(([s, n]) =>
        el("a", {class: "chip", href: "#" + mergeParams(href, {state: s}),
                 title: `${pretty(s)} ${pretty(kind)}s`}, `${pretty(s)} ${n}`))));
  }
  const create = env.actions?.create;
  if (create) {
    const btn = actionButton({name: "create", entry: create, doc: env,
      label: "New", small: true, onDone: b => { if (b?.self) go(b.self); }});
    btn.classList.add("primary");
    card.append(el("div", {class:"kc-foot"}, btn));
  }
  return env;
}

/* ── the quest tracker (docs/spec-quests.md) ─────────────────────────
   The person's pinned active quest, in the header's own row, so it is
   on every page and covers none of a page's actions. It reads the
   quest row and holds no opinion of its own: the count is "k done, n
   known so far" and never a total, the head step is the first one not
   done, and Go opens that step's door on its row (openDoor,
   180-action-dialog.js). The quest's own event stream redraws it
   (questFollow, below). */
let questDoc = null;        // the pinned quest's envelope, when there is one
let questDone = null;       // the title of the quest that has just finished
let questDoneTimer = null;
const QUEST_DONE_MS = 4000;
const questTitle = doc => (doc.data || {}).title || doc.summary || "Quest";
function questHead(doc) {
  return ((doc.data || {}).plan || []).find(s => s.state !== "done") || null;
}
/* a step's row in words (ticket 5b3fa3f7). The quest stores the row's
   path and never its label: a rename is a change to the row, so it
   moves the screen and not the quest. The label is the row's own
   summary line, read under the reader's grant and kept for the page;
   when the row moves it is read again, in place. The path is the
   link's href and its title only. A row the reader may not see, and
   every row of a replay, which reads nothing, is its kind in words. */
const questRowSeen = {};    // a row's path → the promise of its summary
function questRowSay(node, self) {
  (questRowSeen[self] || (questRowSeen[self] =
    api(self + "?depth=summary")
      .then(r => (r.ok && r.body.summary) || null).catch(() => null)))
    .then(s => { if (s && node.getAttribute("title") === self) node.textContent = s; });
}
/* `link` false is plain text, for the tracker: its title and Go are
   the tap targets there */
function questRow(self, link = true) {
  const node = el(link ? "a" : "span",
                  {"data-quest-row": "", title: self, href: link ? "#" + self : null},
                  pretty(String(self).split("/")[2] || "row"));
  if (!replay) questRowSay(node, self);
  return node;
}
function questRowMoved(self) {
  if (replay || !(self in questRowSeen)) return;
  delete questRowSeen[self];
  for (const node of document.querySelectorAll("[data-quest-row]"))
    if (node.getAttribute("title") === self) questRowSay(node, self);
}
/* a seat's step and a held one are someone else's to take */
function questWaits(step) {
  return ["seat", "held"].includes(step.whose) || step.state === "waiting";
}
/* the tracker's Go; the quest page's own is questGo (200-events-follow.js) */
function questHeadGo() {
  const head = questDoc && questHead(questDoc);
  if (!head || questWaits(head)) return;
  /* the goal's own step: what the quest stored is in the form already,
     as the owner's own and not as a suggestion */
  const d = questDoc.data || {};
  const goal = head.self === d.self && head.door === d.action;
  openDoor({self: head.self, action: head.door, fields: head.needs || [],
            note: head.note, given: goal ? d.input || {} : {}},
           "The step's row");
}
/* the owner's own step moves the quest a moment later, in the engine's
   consumer, and the stream alone would have to say so: a frame that is
   late or lost left the tracker on the step just taken. So the quest is
   read again after a door taken through openDoor (180-action-dialog.js),
   a few times and further apart, until what the tracker holds is
   another plan or no quest at all. */
const QUEST_AFTER_GO_MS = [500, 1000, 2000, 4000, 8000, 15000];
const questMark = doc =>
  doc ? doc.self + " " + ((doc.data || {}).planned_at || "") : "";
async function questAfterGo() {
  const was = questMark(questDoc);
  if (!was) return;
  for (const ms of QUEST_AFTER_GO_MS) {
    await new Promise(r => setTimeout(r, ms));
    if (replay || questMark(questDoc) !== was) return;
    await refreshQuest();
  }
}
function questTracker() {
  /* a replay draws the quest its walk recorded (replayQuest,
     200-events-follow.js): it reads nothing, follows no stream, and
     every door is disabled */
  if (replay) {
    questDraw(replay.quest, replay.questDone);
    for (const b of $("#questbar").querySelectorAll("button")) b.disabled = true;
    return;
  }
  questFollow();
  questDraw(questDoc, questDone);
}
/* the tracker of one quest document; `finished` is the title of a
   quest that has just finished, shown in its place */
function questDraw(doc, finished) {
  const bar = $("#questbar");
  bar.setAttribute("data-surface", "tracker");
  bar.textContent = "";
  if (finished != null) {
    bar.hidden = false;
    bar.append(el("b", {"data-quest-complete": ""}, "Quest complete"),
               el("span", {class: "quest-line"}, finished));
    return;
  }
  bar.hidden = !doc;
  if (!doc) return;
  const d = doc.data || {}, plan = d.plan || [], acts = doc.actions || {};
  const done = plan.filter(s => s.state === "done").length;
  const head = questHead(doc);
  const waiting = !!head && questWaits(head);
  bar.append(el("a", {class: "quest-title", "data-quest-title": "",
                      href: "#" + doc.self}, questTitle(doc)));
  if (!d.planned_at) {
    /* born with no plan: the first one has not landed yet */
    bar.append(el("span", {class: "quest-line muted", "data-quest-planning": ""},
                  "planning…"));
  } else {
    bar.append(
      el("span", {class: "muted", "data-quest-count": ""},
         `${done} done, ${plan.length} known so far`),
      el("span", {class: "quest-meter", "aria-hidden": "true"},
         el("i", {style: `width:${plan.length
           ? Math.round(100 * done / plan.length) : 0}%`})));
    /* blocked hides Go and says why; a waiting head keeps Go, disabled */
    if (d.blocked_reason)
      bar.append(el("span", {class: "quest-line", "data-quest-blocked": ""},
                    d.blocked_reason));
    else if (waiting)
      bar.append(el("span", {class: "quest-line", "data-quest-waiting": ""},
        el("i", {class: "quest-live", "aria-hidden": "true"}),
        `waiting on ${head.waiting_on || d.waiting_on || "someone else"}`));
    else {
      /* the head step: its note, then its row and what its door asks
         for, both in words. The note keeps the line's width, and the
         words beside it give way first (020-base.css). */
      const line = el("span", {class: "quest-line quest-head"},
        el("span", {"data-quest-note": "", "data-surface": "tracker.next"},
           head ? head.note || head.door_label || pretty(head.door) : ""));
      const needs = head
        ? head.needs_labels || (head.needs || []).flat().map(prettyNeed) : [];
      if (head && head.self)
        line.append(el("span", {class: "quest-on muted"},
          questRow(head.self, false),
          needs.length
            ? el("span", {"data-quest-needs": ""}, ` · asks for ${needs.join(", ")}`)
            : null));
      bar.append(line);
    }
    if (head && !d.blocked_reason)
      bar.append(el("button", {class: "primary", "data-surface": "tracker.go",
                               disabled: waiting ? "" : null, onclick: questHeadGo},
                    "Go"));
  }
  /* the quest's own doors, as its row offers them to this person now */
  const doors = ["pause", "unpin", "replan"].filter(n => acts[n]);
  if (doors.length)
    bar.append(el("details", {class: "quest-menu"},
      el("summary", {"data-surface": "tracker.more", title: "this quest's doors", "aria-label": "quest menu"}, "⋯"),
      el("div", {class: "quest-menu-items"}, doors.map(n =>
        actionButton({name: n, entry: acts[n], doc, small: true,
                      onDone: () => { refreshQuest(); render(); }})))));
}
/* the collection is the read, so the tracker holds no quest id that
   could go stale: whichever quest is pinned now is the one shown */
async function refreshQuest() {
  /* a replay's tracker is its walk's: the live quest is read when it stops */
  if (replay) return;
  const me = viewerId();
  if (!me) { questDoc = null; questTracker(); return; }
  const res = await api("/api/quests?state=active&pinned=true&owner=" +
                        encodeURIComponent(me));
  /* no answer (a held replay, an engine restarting) changes nothing */
  if (!res.ok && res.status !== 404) return;
  const item = res.ok && ((res.body.data || {}).items || [])[0];
  /* none is pinned and one is in hand: it may have finished before its
     stream said so, as it does after the owner's own goal write. The
     row itself says which, and a finished one is said here. */
  const held = questDoc;
  if (!item && held) {
    const was = await api(held.self);
    if (was.ok && was.body.state === "finished" && questDoc === held) {
      questFinished();
      return;
    }
  }
  /* a collection item is a summary: the plan is on the row's envelope */
  const row = item ? await api(item.self) : null;
  if (row && !row.ok) return;
  questDoc = row ? row.body : null;
  questTracker();
}
/* the quest in hand is followed on its own event stream, as a row page
   follows one row. A grant that admits the quest admits that stream,
   and under a grant the one live stream carries no row events
   (220-boot.js): so every viewer hears the quest here, granted or not.
   The stream closes when the tracker lets the quest go, and another
   opens when it takes one up. Each time one opens the quest is read
   again, for what moved before it did. */
let questStream = null;     // the href of the stream that is open
function questFollow() {
  const href = questDoc ? questDoc.self + "/-/events" : null;
  if (href === questStream) return;
  if (questStream) sseClose(questStream);
  questStream = href;
  if (!href) return;
  const mine = () => questStream === href;
  sse(href,
      f => { if (mine() && f.event === "transition") onQuestRowFrame(f.data); },
      () => { if (mine()) refreshQuest().catch(() => {}); });
}
/* the quest in hand has finished: the tracker says so for a few
   seconds before it hides. The stream's frame and the read both come
   here, whichever learns it first. */
function questFinished() {
  questDone = questTitle(questDoc);
  questDoc = null;
  clearTimeout(questDoneTimer);
  questDoneTimer = setTimeout(() => {
    questDone = null;
    questTracker();
    refreshQuest();
  }, QUEST_DONE_MS);
  questTracker();
}
/* the stream's half: a transition of the quest in hand reads it again,
   and finish says so */
async function questRowFrame(ev) {
  if (!questDoc || replay) return;
  if (ev.action === "finish") { questFinished(); return; }
  /* a move on a plan's row plans the quest again, and under a grant
     this stream alone says so: the rows' labels are read again */
  for (const self of Object.keys(questRowSeen)) questRowMoved(self);
  await refreshQuest();
}
function onQuestRowFrame(ev) { questRowFrame(ev).catch(() => {}); }
/* the firehose's half (210-ledger.js): a pin takes whichever quest is
   pinned now. The quest in hand is heard on its own stream, above. */
function onQuestFrame(ev) {
  /* a row a step names has moved: its label is read again */
  questRowMoved(ev.self);
  if (ev.kind !== "quest" || ev.action !== "pin") return;
  refreshQuest().catch(() => {});
}
/* at boot, and whenever the dev box names another principal */
wellKnown().then(w => {
  if (!(w.resources || {}).quest) return;
  $who.addEventListener("change", () => refreshQuest());
  /* the backstop: a tab that comes back reads the pinned quest again */
  document.addEventListener("visibilitychange",
    () => { if (!document.hidden) refreshQuest().catch(() => {}); });
  return refreshQuest();
}).catch(() => {});

