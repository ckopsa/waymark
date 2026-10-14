/* ── the In flight screen (ticket e64fb475): every open row, by who
   it waits on ──────────────────────────────────────────────────────
   GET /api/-/in-flight (the discover door `in_flight`, d0407b84)
   answers the groups already ordered — person, nobody, each seat,
   each source, most overdue first inside each — and the four counts;
   this screen draws them as the read gives them and orders nothing
   itself. The hash IS the read's address, so a filtered view is its
   own bookmark: a filter rewrites the hash and render() reads again.

   "Waiting on you" is the person's to-do list: each of its rows reads
   its own envelope, and the doors it affords that its waiting_on
   names become one-tap buttons through the action dialog (180). */

const IN_FLIGHT_FILTERS = ["domain", "repo", "kind", "holder"];

/* a holder token as its section is headed */
function inFlightHolder(h) {
  if (h === "person") return "Waiting on you";
  if (h === "nobody") return "Held by nobody";
  const m = /^(seat|source):(.*)$/.exec(String(h || ""));
  return m ? `${title(m[1])} · ${m[2]}` : String(h || "");
}

/* seconds as the bar's label reads them: the largest unit, whole */
function inFlightSpan(s) {
  if (s == null) return "—";
  const n = Number(s);
  if (n < 120) return `${Math.round(n)} s`;
  if (n < 7200) return `${Math.round(n / 60)} min`;
  if (n < 172800) return `${Math.round(n / 3600)} h`;
  return `${Math.round(n / 86400)} d`;
}

/* the age against the expected age: full at 100%, the warning colour
   past it; a state with no expected age draws an empty bar */
function inFlightBar(e) {
  const ratio = Number(e.overdue) || 0;
  const label = e.expected_seconds
    ? `${inFlightSpan(e.age_seconds)} of ${inFlightSpan(e.expected_seconds)}`
    : `${inFlightSpan(e.age_seconds)}, no expected age`;
  return el("span", {class: "flight-age"},
    el("span", {class: "flight-bar" + (ratio > 1 ? " late" : ""), title: label},
      el("span", {class: "flight-fill",
                  style: `width:${Math.min(100, Math.round(ratio * 100))}%`})),
    el("span", {class: "muted"}, label));
}

function inFlightHref(base, filters) {
  const p = new URLSearchParams();
  for (const k of IN_FLIGHT_FILTERS) if (filters[k]) p.set(k, filters[k]);
  return base + (p.toString() ? "?" + p : "");
}

/* the doors a "Waiting on you" row affords the viewer that its
   waiting_on names, read off the row's own envelope */
async function inFlightDoors(box, self, e) {
  const res = await api(self);
  if (!res.ok || !document.contains(box)) return;
  const doc = res.body;
  const named = new Set(((e.waiting_on || {}).next || []).map(n => n.door));
  for (const [name, entry] of Object.entries(doc.actions || {})) {
    if (!named.has(name)) continue;
    box.append(el("button", {type: "button", class: "flight-door",
      "data-surface": "in-flight.door", "data-row": self,
      onclick: () => actionDialog({name, entry, doc, onDone: () => render()})},
      title(name)));
  }
}

async function renderInFlight(view, href, seq) {
  const [res, w] = await Promise.all([api(href), wellKnown().catch(() => null)]);
  if (seq !== renderSeq) return;
  clearLiveTimers(); view.textContent = "";
  if (!res.ok) return view.append(problemBox(res.body));
  const doc = res.body || {}, counts = doc.counts || {};
  const groups = doc.groups || [], filters = doc.filters || {};
  const base = href.split("?")[0];
  view.append(el("h2", {}, "In flight"));
  const strip = el("div", {class: "flight-counts"});
  for (const [label, n, cls] of [["Waiting on you", counts.waiting_on_person, "you"],
                                 ["Held by nobody", counts.held_by_nobody, "nobody"],
                                 ["Overdue", counts.overdue, "late"],
                                 ["Total", counts.total, ""]])
    strip.append(el("div", {class: "flight-count " + cls},
      el("b", {}, String(n ?? 0)), el("span", {}, label)));
  view.append(strip);
  /* the filters' choices are what this answer holds, and the one in
     force even when it narrowed the answer to nothing */
  const seen = {domain: new Set(), repo: new Set(), kind: new Set(),
                holder: new Set(groups.map(g => g.holder))};
  for (const e of groups.flatMap(g => g.rows || []))
    for (const k of ["domain", "repo", "kind"]) if (e[k]) seen[k].add(e[k]);
  const bar = el("div", {class: "flight-filters"});
  for (const k of IN_FLIGHT_FILTERS) {
    if (filters[k]) seen[k].add(filters[k]);
    const sel = el("select", {"data-surface": "in-flight.filter", "data-row": k,
                              "aria-label": title(k)},
      el("option", {value: ""}, `any ${pretty(k)}`),
      [...seen[k]].sort().map(v => el("option", {value: v},
        k === "holder" ? inFlightHolder(v) : k === "kind" ? title(v) : v)));
    sel.value = filters[k] || "";
    sel.addEventListener("change",
      () => go(inFlightHref(base, {...filters, [k]: sel.value})));
    bar.append(sel);
  }
  view.append(bar);
  if (!groups.length) view.append(el("p", {class: "muted"}, "Nothing is in flight."));
  const later = [];
  for (const g of groups) {
    const sect = el("section", {class: "flight-group" + (g.holder === "person" ? " you" : "")},
      el("h3", {}, inFlightHolder(g.holder),
        el("span", {class: "muted"},
          ` ${g.total}` + (g.overdue ? ` · ${g.overdue} overdue` : ""))));
    for (const e of g.rows || []) {
      const col = collectionHref(w, e.kind);
      const self = col ? `${col}/${encodeURIComponent(e.id)}` : null;
      const doors = el("div", {class: "flight-doors"});
      sect.append(el("div", {class: "flight-row", "data-surface": "in-flight.row",
                             "data-row": self || e.id},
        el("div", {class: "flight-head"},
          self ? el("a", {href: "#" + self}, e.summary || e.id)
               : el("span", {}, e.summary || e.id),
          el("span", {class: "chip"}, pretty(e.state))),
        el("div", {class: "flight-verdict"}, (e.waiting_on || {}).verdict || ""),
        inFlightBar(e), doors));
      if (g.holder === "person" && self) later.push(() => inFlightDoors(doors, self, e));
    }
    view.append(sect);
  }
  for (const f of later) f().catch(() => {});
}
