/* ── the link page (spec-teams §7): the rows that name an outside
   party, and their doors ───────────────────────────────────────────
   GET /api/-/named answers every row the caller's grant admits by
   row, each with its href and the doors that grant opens on it now.
   An outside party's guest link lands here (oidc_rp guest-landing),
   so this screen is what they see: each row's summary and state, and
   its doors as one-tap buttons through the action dialog (180), read
   off the row's own envelope. */

async function namedDoors(box, row) {
  const res = await api(row.href);
  if (!res.ok || !document.contains(box)) return;
  const doc = res.body;
  const open = new Set(row.doors || []);
  for (const [name, entry] of Object.entries(doc.actions || {})) {
    if (!open.has(name)) continue;
    box.append(el("button", {type: "button", class: "flight-door",
      "data-surface": "named.door", "data-row": row.href,
      onclick: () => actionDialog({name, entry, doc, onDone: () => render()})},
      title(name)));
  }
}

async function renderNamed(view, href, seq) {
  const res = await api(href);
  if (seq !== renderSeq) return;
  clearLiveTimers(); view.textContent = "";
  if (!res.ok) return view.append(problemBox(res.body));
  const rows = (res.body || {}).rows || [];
  view.append(el("h2", {}, "Yours"));
  if (!rows.length)
    return view.append(el("p", {class: "muted"}, "Nothing names you right now."));
  const list = el("ul", {class: "named-rows"});
  for (const row of rows) {
    const doors = el("span", {class: "named-doors"});
    list.append(el("li", {"data-surface": "named.row", "data-row": row.href},
      el("a", {href: "#" + row.href},
        String(row.summary || `${title(row.kind)} ${row.id}`)),
      el("span", {class: "muted"}, ` · ${pretty(row.state || "")} `),
      doors));
    namedDoors(doors, row);
  }
  view.append(list);
}