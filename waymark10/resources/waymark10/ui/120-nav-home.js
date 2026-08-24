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
  /* both awaits happen BEFORE the bar is cleared: a probe resolving
     late must never append to a bar a newer render already emptied */
  const hasFeed = await feedDoor();
  const nav = $("#kinds"); nav.textContent = "";
  const entries = Object.entries(w.resources || {});
  const domains = w.domains || [];
  const active = current ? domainOf(w, current) : null;
  /* global navigation between applications: the drawer, behind ☰ —
     present exactly when the wire declares domains */
  $("#drawerbtn").classList.toggle("on", domains.length > 0);
  if (domains.length) fillDrawer(w, current, active);
  /* the tab bar has no header wordmark in reach — Home earns a tab */
  if (MOBILE) nav.append(el("a", {href: "#",
    style: !current ? "font-weight:700" : ""}, "Home"));
  /* the active application as a breadcrumb back to its home */
  if (active) {
    const home = domainHome(w, active);
    if (home) nav.append(el("a", {class: "nav-domain", href: "#" + home,
      style: "color:var(--ink);font-weight:700"}, title(active)));
  }
  /* the active domain's primary kinds (a domainless primary always) */
  for (const [kind, r] of entries)
    if (navTier(r) === "primary" && (!r.domain || r.domain === active))
      nav.append(el("a", {href: "#" + r.href,
        style: current === r.href ? "font-weight:700" : ""}, title(kind) + "s"));
  /* the day's own read (waymark-iqa.7). The feed cannot advertise
     itself on .well-known — the contribution table is closed at four
     — so the page knows the address and asks whether it is mounted
     for this reader (feedDoor, one probe a load). */
  if (hasFeed)
    nav.append(el("a", {href: "#/api/-/feed",
      style: current === "/api/-/feed" ? "font-weight:700" : "",
      title: "the day's feed — what to do now, what to answer, what "
           + "the house already finished"}, "Feed"));
  /* the hand-in-hand door: invite an agent, judge its ask, follow it */
  if (w.resources && w.resources.member && w.resources.approval_request)
    nav.append(el("a", {href: "#access",
      style: current === "access" ? "font-weight:700" : ""}, "Access"));
  /* secondary and system kinds fold behind ⋯ — domainless (the
     engine's own) always, a domain's own only while that domain is
     active */
  const tucked = entries.filter(([, r]) =>
    (navTier(r) === "secondary" || navTier(r) === "system")
    && (!r.domain || r.domain === active));
  if (tucked.length) nav.append(overflowMenu(tucked));
}

function overflowMenu(tuckedEntries) {
  const wrap = el("span", {class:"nav-more-wrap"});
  const menu = el("div", {class:"nav-menu", role:"menu"});
  const btn = el("button", {class:"nav-more", type:"button",
    "aria-haspopup":"true", "aria-expanded":"false",
    title: "more kinds — and the machinery's own resources"}, "⋯");
  const close = () => { menu.style.display = "none";
                        btn.setAttribute("aria-expanded", "false"); };
  const open = () => { menu.style.display = "block";
                       btn.setAttribute("aria-expanded", "true");
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
    el("a", {href: "#" + href, role:"menuitem", onclick: close},
      ...(kind === "definition" ? [el("span", {class:"law-mark"}, "⚖ ")] : []),
      title(kind) + "s");
  const domain = tuckedEntries.filter(([, r]) => navTier(r) === "secondary");
  const system = tuckedEntries.filter(([, r]) => navTier(r) === "system");
  domain.forEach(e => menu.append(item(e)));
  if (domain.length && system.length)
    menu.append(el("div", {class:"nav-menu-sect", role:"separator"}, "system"));
  system.forEach(e => menu.append(item(e)));
  /* the shell switch: a full reload with ?ui= beats the UA sniff,
     the hash (this screen) rides along */
  menu.append(el("a", {role: "menuitem",
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
  if (letterHref && principalId())
    settling.push(api(mergeParams(letterHref,
                                  {to: principalId(), state: "waiting"}))
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

  /* the weather panel (waymark-tti.1): today's sky per inhabitant and
     a one-touch setter for the viewer's own. Rows are append-only —
     the newest per owner wins, so a wrong tap is corrected by tapping
     again; no report today is simply absent. meta.updated_at stands
     in for created_at on the wire: a weather row is never edited. */
  const skyDot = {quiet: "#4A6B8A", steady: "var(--verdigris)",
                  loud: "var(--warn)"};
  const weatherHref = collectionHref(w, "weather");
  if (weatherHref) {
    const box = el("div");
    strip.after(box);
    const fill = async () => {
      let env;
      try {
        const r = await api(mergeParams(weatherHref,
          {sort: "-created_at", "page[size]": "30"}));
        if (!r.ok) return box.remove();   // degrade silently
        env = r.body;
      } catch { return box.remove(); }
      const today = new Date().toDateString();
      const latest = new Map();           // owner → its newest row TODAY
      for (const it of env.data?.items || []) {
        const o = it.fields?.owner, at = it.meta?.updated_at;
        if (o && !latest.has(o) && at
            && new Date(at).toDateString() === today)
          latest.set(o, it.fields);
      }
      const create = env.actions?.create;
      if (!latest.size && !create) return box.remove();
      box.textContent = "";
      box.append(el("h3", {class:"sect"}, "Weather"));
      if (latest.size)
        box.append(el("div", {class:"chips"}, [...latest].map(([o, f]) =>
          el("span", {class:"chip static", title: f.note || ""},
            el("span", {style: "color:" + (skyDot[f.sky] || "inherit")}, "● "),
            `${o} — ${f.sky}`))));
      /* one-touch: no owner in the body — the server stamps the caller */
      if (create)
        box.append(el("div", {class:"chips"},
          ["quiet", "steady", "loud"].map(sky =>
            el("button", {style: "font-size:12px;padding:3px 8px",
                          onclick: async () => {
              await api(create.href, {method: create.method || "POST",
                                      body: JSON.stringify({sky})});
              fill();                     // re-render this block only
            }}, sky))));
    };
    settling.push(fill());
  }

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

