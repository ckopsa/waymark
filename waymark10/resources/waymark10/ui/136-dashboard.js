/* ── the dashboard screen (waymark-ggw): a user-composed surface ────
   A dashboard envelope is an ordinary resource envelope whose kind is
   "dashboard" and whose links.slots embed carries the ACTIVE
   dashboard_slot parts (the server's render contract — the router
   splices them). render() forks here by kind, the deploy-history
   tradition; an envelope without the embed falls back to the kv table.

   Each slot is one panel: label, live count, a handful of top rows
   (one small-page collection GET with the slot's stored where params),
   and a click-through to the full collection (+ view= when the slot
   deep-links one — client state, splitViewParam strips it before any
   fetch). Panels fill CONCURRENTLY and degrade alone: a slot whose
   target/where no longer resolves renders as a problem panel wearing
   the collection's own refusal, with the slot's edit door and a retry
   — never a broken page, never its neighbors' problem. */

/* the slot's target — kind name or plural, saved_view's spelling —
   resolved to its collection href off discovery */
function dashTargetHref(w, target) {
  const t = String(target || "");
  if (!t || !w) return null;
  for (const [kind, r] of Object.entries(w.resources || {}))
    if (kind === t || (r.href || "").split("/").pop() === t) return r.href;
  return null;
}

/* the stored wire params string ("state=pending&owner=ana") as a
   plain object mergeParams can stamp onto the collection href — a
   malformed pair drops rather than crashes (the guard gated the
   write; a redeploy may have stranded the string) */
function dashWhereParams(where) {
  const out = {};
  try {
    for (const [k, v] of new URLSearchParams(String(where || "")))
      if (k) out[k] = v;
  } catch { /* unparseable: filter nothing, the count stays honest */ }
  return out;
}

async function fillSlotPanel(panel, slot) {
  const f = slot.fields || {};
  panel.textContent = "";
  const name = f.label || pretty(String(f.target || "slot"));
  const problem = (msg, retry) => {
    panel.append(el("div", {class: "slot-problem"},
      el("div", {}, msg),
      el("div", {class: "slot-foot"},
        retry ? el("button", {onclick: () => fillSlotPanel(panel, slot)},
                   "Retry") : null,
        el("a", {href: "#" + slot.self,
                 title: "open this slot to revise or remove it"},
          "fix the slot ↗"))));
  };
  let w = null;
  try { w = await wellKnown(); } catch {}
  const colHref = dashTargetHref(w, f.target);
  const openParams = dashWhereParams(f.where);
  if (f.view) openParams.view = f.view;   // client state, hash-only
  const openHref = colHref ? mergeParams(colHref, openParams) : null;
  const head = el("div", {class: "slot-head"},
    el("b", {class: "slot-name"},
      openHref ? el("a", {href: "#" + openHref}, name) : name),
    el("a", {class: "slot-edit", href: "#" + slot.self,
             title: "open this slot (revise / remove)"}, "✎"));
  panel.append(head);
  if (!w) return problem("Cannot reach /api/.well-known/waymark.", true);
  if (!colHref)
    return problem(`target “${pretty(String(f.target || ""))}” names no ` +
                   "collection this engine serves — a redeploy may have " +
                   "retired it.", false);
  let env;
  try {
    const fetchParams = dashWhereParams(f.where);
    fetchParams["page[size]"] = "5";
    const {ok, body} = await api(mergeParams(colHref, fetchParams));
    if (!ok)
      return problem((body && (body.detail || body.title)) ||
                     "The collection refused this slot's filter.", true);
    env = body;
  } catch (e) {
    return problem("Fetch failed: " + ((e && e.message) || e), true);
  }
  const items = (env.data || {}).items || [];
  const total = (env.data || {}).total ?? items.length;
  panel.append(el("div", {class: "slot-count"},
    el("a", {href: "#" + openHref,
             title: `${total} matching — open the full collection`},
      String(total))));
  if (!items.length)
    panel.append(el("div", {class: "slot-none"}, "Nothing here."));
  else
    panel.append(el("div", {class: "slot-rows"}, items.map(item =>
      el("a", {class: "slot-row", href: "#" + (item.self || openHref)},
        item.summary || item.self))));
  if (total > items.length)
    panel.append(el("a", {class: "slot-more", href: "#" + openHref},
      `all ${total} ↗`));
}

async function renderDashboard(view, doc) {
  const panel = el("div", {class: "panel"});
  const colHref = doc.self.split("/").slice(0, 3).join("/");
  panel.append(el("div", {class: "crumbs"},
    el("a", {href: "#"}, "Workspace"), " / ",
    el("a", {href: "#" + colHref}, "Dashboards"), " / ",
    el("span", {class: "id", title: doc.self},
      doc.self.split("/").pop().slice(0, 8))));
  const data = doc.data || {};
  panel.append(el("h2", {class: "prose"},
    data.label || doc.summary || "Dashboard"));
  const meta = doc.meta || {};
  panel.append(el("div", {},
    el("span", {class: "statechip", title: "dashboard"}, doc.state),
    el("span", {class: "version"},
      `v${meta.version}` +
      (meta.law_revision != null ? ` · law r${meta.law_revision}` : "")),
    el("span", {class: "version", title: doc.self}, doc.self)));
  if (data.description)
    panel.append(el("p", {class: "prose slot-desc"}, data.description));
  const bar = el("div", {class: "actions"});
  const entries = Object.entries(doc.actions || {})
    .sort(([a, ea], [b, eb]) =>
      ((ea.display || {}).order ?? 99) - ((eb.display || {}).order ?? 99) ||
      a.localeCompare(b));
  for (const [name, entry] of entries)
    bar.append(actionButton({name, entry, doc, onDone: out => {
      if (out && out.self && out.self !== doc.self) go(out.self);
      else render();
    }}));
  const {blocked, grouped, gated} = splitRefusals(doc);
  for (const [name, entry] of blocked.concat(gated))
    bar.append(el("button", {class: "blocked", disabled: "",
      title: entry.reason || ""}, label(name, entry)));
  const slotsLink = (doc.links || {}).slots || {};
  if (slotsLink.href)
    bar.append(el("a", {class: "slot-manage", href: "#" + slotsLink.href,
      title: "this dashboard's slots as an ordinary collection — " +
             "add, revise, remove"}, "Slots ↗"));
  panel.append(bar);
  const notes = blockedNotes(blocked, doc);
  if (notes) panel.append(notes);
  view.append(panel);

  /* the slot grid: embedded ACTIVE parts (the link's own :where), in
     seat-then-label order; each panel fills concurrently and owns its
     failures */
  const slots = (slotsLink.embedded || [])
    .filter(s => s.state === "active")
    .sort((a, b) => {
      const fa = a.fields || {}, fb = b.fields || {};
      const sa = fa.seat ?? Infinity, sb = fb.seat ?? Infinity;
      return sa - sb ||
        String(fa.label || "").localeCompare(String(fb.label || ""));
    });
  if (!slots.length)
    view.append(el("div", {class: "panel"},
      el("p", {class: "muted"}, "No slots yet",
        slotsLink.href
          ? [" — ", el("a", {href: "#" + slotsLink.href}, "add the first"),
             "."]
          : ".")));
  else {
    const grid = el("div", {class: "slot-grid"});
    view.append(grid);
    for (const s of slots) {
      const p = el("div", {class: "slot-panel"},
        el("div", {class: "muted"}, "…"));
      grid.append(p);
      fillSlotPanel(p, s);   /* no await: the panels race, each alone */
    }
  }
  const dataPanel = el("div", {class: "panel"});
  dataPanel.append(historySection(`${doc.self}/-/events`));
  const footer = notNowFooter(grouped, gated, doc);
  if (footer) dataPanel.append(footer);
  view.append(dataPanel);
  watchScope({self: doc.self});
}
