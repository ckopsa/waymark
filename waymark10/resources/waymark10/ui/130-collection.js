/* ── collection screen ─────────────────────────────────────────────── */
function parseHrefQuery(href) {
  const [path, q] = href.split("?");
  return {path, params: new URLSearchParams(q || "")};
}

/* Active scalar filters whose names exactly match create-input
   properties become prefills — the user was already looking at that
   slice, so the form starts there (still editable). */
function filterPrefill(selfHref, entry) {
  const props = entry.input?.properties || {};
  const out = {};
  for (const [k, v] of new URLSearchParams(selfHref.split("?")[1] || "")) {
    if (["page", "sort", "rows"].includes(k) || k.startsWith("page[")) continue;
    if (/_(gte|lte)$/.test(k)) continue;
    if (!v || v.includes(",")) continue;
    if (k in props) out[k] = v;
  }
  return out;
}

/* the Filters button: pick a column (from ones with any declared op),
   pick an operator that column actually supports, a value control
   matching its type/format/enum — then Apply. Sort moved to clickable
   column headers (itemTable's onSort); this is filtering only now. */
const FILTER_OP_LABELS = {eq: "=", in: "in list", ne: "≠", gte: "≥", lte: "≤",
                          between: "between", after: "after", before: "before",
                          contains: "contains", set: "is set"};
function opsFor(col) {
  const out = [];
  if (col.ops.eq) out.push("eq");
  if (col.ops.in) out.push("in");
  if (col.ops.ne) out.push("ne");
  if (col.ops.gte && col.ops.lte) out.push("between");
  else { if (col.ops.gte) out.push("gte"); if (col.ops.lte) out.push("lte"); }
  if (col.ops.after) out.push("after");
  if (col.ops.before) out.push("before");
  if (col.ops.contains) out.push("contains");
  if (col.ops.set) out.push("set");
  return out;
}
/* the top-level-collection flavor of apply/remove: mutate doc.self's
   own hash directly via go(). An embedded table needs a different
   flavor (embed.<rel>.* prefixed params on the PARENT's hash — see
   embeddedSections), so this stays a named, swappable pair rather
   than baked into filterPopover/filterChips themselves. */
function hashFilterOps(path, params) {
  const nav = p => { p.delete("page[number]"); go(path + (p.toString() ? "?" + p : "")); };
  return {
    apply: updates => {
      const p = new URLSearchParams(params);
      for (const [k, v] of Object.entries(updates)) if (v) p.set(k, v); else p.delete(k);
      nav(p);
    },
    remove: name => {
      const p = new URLSearchParams(params);
      p.delete(name);
      nav(p);
    }
  };
}

/* the declaration-showcased filters, standing above the table: a
   schema entry advertising :x-display {:showcase true} (read off the
   same kind-schema hints that label the columns) gets its filter
   rendered as an always-visible control instead of waiting inside
   the popover. Enum'd fields (declared enums and facet-spliced vocab
   values) become a select — facet counts ride the options, and
   filterChips/facetChips skip showcased fields so each filter has
   one home. A showcased x-in field trades the chips' multi-toggle
   for the select's single pick — the declaration chose at-hand over
   exhaustive. Ranged fields get a min–max pair, :after a date input,
   plain :eq a text input applying on change. Same unprefixed
   params/apply contract as filterPopover below. */
function showcaseFilters(query, params, apply, hints) {
  const gc = gridColumns(query);
  const props = (query || {}).properties || {};
  const cols = Object.values(gc).filter(c =>
    Object.keys(c.ops).length && xdisplay(hints, c.field).showcase);
  if (!cols.length) return null;
  const input = (name, col, ph) => {
    const i = el("input", {
      type: col.format === "date" ? "date"
        : (col.type === "integer" || col.type === "number") ? "number" : "text",
      step: "any", placeholder: ph || col.format || col.type || ""});
    i.value = params.get(name) || "";
    i.addEventListener("change", () => apply({[name]: i.value}));
    return i;
  };
  const controls = cols.map(col => {
    const f = col.field;
    const lbl = fieldLabel(hints, f);
    let body;
    const facets = props[f]?.["x-facets"];
    if ((col.enum || facets || col.type === "boolean") &&
        (col.ops.eq || col.ops.in)) {
      /* options: the declared enum, a boolean's two values, or the
         observed facet values (an open vocab has no declared enum —
         what the data holds IS the option list). The active value
         always keeps its seat even when the now-filtered facets no
         longer carry it. */
      const counts = facets || {};
      const active = params.get(f) || "";
      const options = col.enum ||
        (col.type === "boolean" ? ["true", "false"] : Object.keys(counts));
      if (active && !options.includes(active)) options.push(active);
      const s = el("select", {});
      s.append(el("option", {value: ""}, "All"));
      for (const v of options)
        s.append(el("option", {value: v},
          pretty(v) + (v in counts ? ` · ${counts[v]}` : "")));
      s.value = active;
      s.addEventListener("change", () => apply({[f]: s.value}));
      body = [s];
    } else if (col.ops.gte || col.ops.lte) {
      body = [input(f + "_gte", col, "min"), "–", input(f + "_lte", col, "max")];
    } else if (col.ops.after) {
      body = [input(f + "_after", col)];
    } else if (col.ops.before) {
      body = [input(f + "_before", col)];
    } else if (col.ops.contains) {
      body = [input(f + "_contains", col)];
    } else if (col.ops.eq || col.ops.in) {
      body = [input(f, col)];
    } else {
      return null;  // ne/set-only: the popover's home, no standing control
    }
    return el("label", {class: "showcase-filter"}, lbl, body);
  }).filter(Boolean);
  if (!controls.length) return null;
  return el("div", {class: "showcase-filters"}, controls);
}

/* query: gridColumns-input-schema shape. params: the CURRENT active
   params, already unprefixed (an embedded table passes its own
   embed.<rel>.* slice, stripped of the prefix — see embeddedSections).
   apply(updates): {name: value|""} to set/clear, called on Apply —
   the caller decides what that MEANS (go() for a top-level
   collection, an embed.<rel>.* param mutation for an embedded
   table); this fn only builds the picker. */
function filterPopover(query, params, apply, hints) {
  const gc = gridColumns(query);
  const cols = Object.values(gc).filter(c => Object.keys(c.ops).length);
  if (!cols.length) return null;
  const mkValueInput = (name, col) => {
    if (col.enum) {
      const s = el("select", {name});
      s.append(el("option", {value: ""}, "—"));
      for (const v of col.enum) s.append(el("option", {value: v}, pretty(v)));
      return s;
    }
    return el("input", {name, type: (col.type === "integer" || col.type === "number") ? "number" : "text",
      step: "any", placeholder: col.format || col.type || ""});
  };
  const valueRow = el("div", {"data-role": "values"});
  const fieldSel = el("select", {});
  fieldSel.append(el("option", {value: ""}, "Column…"));
  for (const c of cols)
    fieldSel.append(el("option", {value: c.field}, fieldLabel(hints, c.field)));
  const opSel = el("select", {});
  const refreshValue = () => {
    valueRow.replaceChildren();
    const col = gc[fieldSel.value], op = opSel.value;
    if (!col || !op) return;
    if (op === "between")
      valueRow.append(mkValueInput(col.field + "_gte", col), " – ", mkValueInput(col.field + "_lte", col));
    else if (op === "set") {
      const s = el("select", {name: col.field + "_set"});
      s.append(el("option", {value: ""}, "—"),
               el("option", {value: "true"}, "set"),
               el("option", {value: "false"}, "not set"));
      valueRow.append(s);
    }
    else if (op === "gte" || op === "lte" || op === "after" ||
             op === "before" || op === "ne" || op === "contains")
      valueRow.append(mkValueInput(col.field + "_" + op, col));
    else
      valueRow.append(mkValueInput(col.field, col));
  };
  const refreshOps = () => {
    opSel.replaceChildren();
    const col = gc[fieldSel.value];
    if (!col) return;
    for (const op of opsFor(col)) opSel.append(el("option", {value: op}, FILTER_OP_LABELS[op] || op));
    refreshValue();
  };
  fieldSel.addEventListener("change", refreshOps);
  opSel.addEventListener("change", refreshValue);
  const panel = el("div", {class: "filterpanel", style: "display:none"});
  const doApply = () => {
    const updates = {};
    for (const inp of valueRow.querySelectorAll("input,select")) updates[inp.name] = inp.value;
    panel.style.display = "none";
    apply(updates);
  };
  panel.append(el("div", {}, fieldSel, opSel), valueRow,
    el("button", {class: "primary", onclick: doApply}, "Apply"));
  const btn = el("button", {onclick: () =>
    panel.style.display = panel.style.display === "none" ? "block" : "none"}, "Filters ▾");
  return el("div", {class: "filterwrap", style: "position:relative;display:inline-block"}, btn, panel);
}

/* removable chips for whatever's currently applied — mirrors
   facetChips' own chip markup so the two visually match. params/
   remove(name) follow the same unprefixed-params contract as
   filterPopover above. */
function filterChips(query, params, remove, hints) {
  const gc = gridColumns(query);
  const props = (query || {}).properties || {};
  const SUFFIX_LABEL = {gte: "≥", lte: "≤", after: "after", before: "before",
                        ne: "≠", contains: "contains", set: "set"};
  const chips = [];
  for (const [name, value] of params) {
    if (name === "sort" || name.startsWith("page[")) continue;
    const m = /^(.*)_(gte|lte|after|before|ne|contains|set)$/.exec(name);
    const field = m ? m[1] : name;
    // facets render their own chips (facetChips) and a showcased
    // field's standing control already displays its own value — skip
    // both here so an active filter doesn't get a redundant second UI
    if (!(field in gc) || (!m && props[field]?.["x-facets"]) ||
        xdisplay(hints, field).showcase) continue;
    const lbl = fieldLabel(hints, field);
    const opTxt = m ? SUFFIX_LABEL[m[2]] : "=";
    chips.push(el("span", {class: "chip on"},
      `${lbl} ${opTxt} ${value}`,
      el("span", {style: "cursor:pointer;margin-left:4px", onclick: () => remove(name)}, "✕")));
  }
  return chips.length ? el("div", {class: "chips"}, chips) : null;
}

/* facet chips: x-facets counts per faceted field, current filters lit —
   a chip click toggles membership in the comma list (x-in). A
   showcased field's values live in its standing select (counts and
   all), so it gets no chips row here. */
function facetChips(query, selfHref, hints) {
  const {path, params} = parseHrefQuery(selfHref);
  const rows = [];
  for (const [field, prop] of Object.entries(query.input?.properties || {})) {
    if (!prop["x-facets"] || xdisplay(hints, field).showcase) continue;
    const active = (params.get(field) || "").split(",").filter(Boolean);
    const chips = Object.entries(prop["x-facets"]).map(([value, count]) => {
      const on = active.includes(value);
      return el("span", {class: "chip" + (on ? " on" : ""),
        onclick: () => {
          const next = on ? active.filter(v => v !== value) : [...active, value];
          const p = new URLSearchParams(params);
          if (next.length) p.set(field, next.join(",")); else p.delete(field);
          p.delete("page[number]");
          go(path + (p.toString() ? "?" + p : ""));
        }}, `${value} · ${count}`);
    });
    if (chips.length)
      rows.push(el("div", {class:"chips"},
        el("span", {class:"chip static"}, el("b", {}, field)), chips));
  }
  return rows;
}

function pagerOf(doc) {
  const page = doc.data?.page || {};
  const pager = el("div", {class:"pager"});
  if (doc.links?.prev) pager.append(el("a", {href:"#"+doc.links.prev.href}, "← prev"));
  pager.append(el("span", {class:"muted"},
    `page ${page.number || 1} · ${doc.data?.total ?? 0} total`));
  if (doc.links?.next) pager.append(el("a", {href:"#"+doc.links.next.href}, "next →"));
  return pager;
}

/* the mobile shell's sort control: the card layout has no column
   headers to click, so the query's sortable set folds into one
   select (field ↑ / field ↓). Same onSort contract as the headers —
   the caller decides what a pick means. */
function sortSelect(query, currentSort, onSort, hints) {
  const sortable = Object.values(gridColumns(query)).filter(c => c.sortable);
  if (!sortable.length || !onSort) return null;
  const s = el("select", {title: "sort"});
  s.append(el("option", {value: ""}, "Sort…"));
  for (const c of sortable) {
    const lbl = fieldLabel(hints, c.field);
    s.append(el("option", {value: c.field}, lbl + " ↑"),
             el("option", {value: "-" + c.field}, lbl + " ↓"));
  }
  s.value = currentSort || "";
  s.addEventListener("change", () => onSort(s.value));
  return s;
}

/* Collection items as a table. Items now carry :fields (a bounded,
   real projection of :data — render.clj's grid-fields), so columns
   are schema-derived, not hardcoded — State stays pinned first (the
   machine's own governing fact, not just another field) and Summary/
   Updated/Actions stay pinned last. opts.query (gridColumns-shaped)
   supplies column order/typing/sortability; a sortable header click
   calls opts.onSort(nextSortParam) — the CALLER decides what that
   means (go() for a top-level collection, an embed.<rel>.sort
   override for an embedded table), itemTable only knows "a header
   was clicked." */
function itemTable(items, opts) {
  opts = opts || {};
  if (!items.length) return el("p", {class:"muted"}, "No rows.");
  const anyActions = !!opts.rowAction ||
    items.some(i => (i.actions && Object.keys(i.actions).length) ||
                    Object.values(i.links || {}).some(l => l && (l.download || l.external)));
  const cols = fieldColumns(items, opts.query, opts.hints);
  const sortField = (opts.currentSort || "").replace(/^-/, "");
  const sortDesc = (opts.currentSort || "").startsWith("-");
  const colHead = f => {
    const c = (opts.query || {})[f];
    const lbl = fieldLabel(opts.hints, f);
    if (!c || !c.sortable || !opts.onSort) return el("th", {title: f}, lbl);
    const active = sortField === f;
    return el("th", {title: f, class: "sortable" + (active ? " sorted" : ""),
      onclick: () => opts.onSort(active && !sortDesc ? "-" + f : f)},
      lbl, el("span", {class: "sort-arrow"}, active ? (sortDesc ? " ↓" : " ↑") : " ↕"));
  };
  const head = el("tr", {},
    opts.selectable ? el("th", {}, "") : null,
    el("th", {}, "State"),
    el("th", {}, "Summary"),
    ...cols.map(colHead),
    el("th", {}, "Updated"),
    anyActions ? el("th", {}, "") : null);
  const tbody = el("tbody", {});
  for (const item of items) {
    const row = el("tr", {},
      opts.selectable ? el("td", {class:"c-check"},
        el("input", {type: "checkbox", "data-bulk-check": "",
          onclick: e => e.stopPropagation()})) : null,
      el("td", {class:"c-state"}, el("span", {class:"statechip"}, item.state)),
      el("td", {class:"c-summary"}, el("a", {class:"rowlink",
        href:"#"+item.self, title: item.self}, item.summary)),
      ...cols.map(f => el("td",
        {class:"c-field", "data-label": fieldLabel(opts.hints, f)},
        fieldCell(opts.hints, f, (item.fields || {})[f]))),
      el("td", {class:"metaline mono c-updated"},
        ((item.meta || {}).updated_at || "").slice(0, 16).replace("T", " ")));
    if (opts.selectable) {
      const box = row.querySelector("[data-bulk-check]");
      const id = item.self.split("/").pop();
      box.addEventListener("change", () =>
        box.checked ? opts.selected.add(id) : opts.selected.delete(id));
    }
    if (anyActions) {
      const cell = el("td", {class:"rowactions partactions"});
      const extra = opts.rowAction ? opts.rowAction(item) : null;
      if (extra) cell.append(extra);
      for (const [name, entry] of Object.entries(item.actions || {}))
        cell.append(actionButton({name, entry, doc: item, small: true,
                                  onDone: () => render()}));
      /* download and external links are row affordances too — byte
         routes and cross-engine hops both ride the row as real
         browser navigations (see linksStrip) */
      for (const [rel, l] of Object.entries(item.links || {}))
        if (l && (l.download || l.external))
          cell.append(el("a", {class:"chip link-chip", href: l.href,
            target:"_blank", rel:"noopener", title: l.summary || rel,
            onclick: e => e.stopPropagation()},
            (l.download ? "⭳ " : "↗ ") + title(rel)));
      row.append(cell);
    }
    tbody.append(row);
  }
  const table = el("table", {class:"items cards"}, el("thead", {}, head), tbody);
  return el("div", {style:"overflow-x:auto"}, table);
}

function renderCollection(view, doc, hints) {
  const kind = doc.kind.replace("_collection", "");
  const panel = el("div", {class:"panel"});
  const colName = title(kind) + "s";
  panel.append(el("div", {class:"crumbs"},
    el("a", {href:"#"}, "Workspace"), " / ", colName));
  panel.append(el("h2", {}, colName));
  /* the server's own sentence, verbatim — it carries the honest filter
     echo ("filtered: state=retired") and count */
  if (doc.summary) panel.append(el("div", {class:"metaline"}, doc.summary));

  const {path, params} = parseHrefQuery(doc.self);
  const query = doc.actions?.query;
  const gridQuery = query?.input;
  if (query) {
    const {apply, remove} = hashFilterOps(path, params);
    const bar = el("div", {class: "filterbar"});
    const sf = showcaseFilters(gridQuery, params, apply, hints);
    if (sf) bar.append(sf);
    const fp = filterPopover(gridQuery, params, apply, hints);
    if (fp) bar.append(fp);
    if (MOBILE) {
      const ss = sortSelect(gridQuery, params.get("sort"), next => {
        const p = new URLSearchParams(params);
        if (next) p.set("sort", next); else p.delete("sort");
        p.delete("page[number]");
        go(path + (p.toString() ? "?" + p : ""));
      }, hints);
      if (ss) bar.append(ss);
    }
    const fc = filterChips(gridQuery, params, remove, hints);
    if (fc) bar.append(fc);
    panel.append(bar);
    panel.append(...facetChips(query, doc.self, hints));
  }

  /* bulk affordances: a checkbox column plus one button per bulk action */
  const bulkActions = Object.entries(doc.actions || {})
    .filter(([, e]) => e.effect && e.effect.bulk);
  const selected = new Set();
  const bar = el("div", {class:"actions"});
  const create = doc.actions?.create;
  if (create) {
    const prefill = filterPrefill(doc.self, create);
    if (kind === "attachment")
      bar.append(uploadButton({entry: create, doc, prefill,
        onDone: b => { if (b?.self) go(b.self); else render(); }}));
    else
      bar.append(actionButton({name: "create", entry: create, doc,
        label: "New " + kind, prefill,
        onDone: b => { if (b?.self) go(b.self); else render(); }}));
  }
  for (const [name, entry] of bulkActions)
    bar.append(el("button", {"data-bulk": name, onclick: () => {
      if (!selected.size) { toast("Select rows first"); return; }
      actionDialog({name, entry, doc, bulkIds: [...selected],
                    onDone: () => render()});
    }}, (entry.display?.label || title(name)) + " (selected)"));
  /* the worksheet round-trip, when the kind declares one: the link
     already carries THIS view's filters; an upload STAGES as a
     worksheet row and this screen goes there */
  const ws = doc.links?.worksheet;
  if (ws) {
    bar.append(el("button", {"data-worksheet":"download",
      title: ws.summary || "download this view as an editable workbook",
      onclick: () => downloadWorksheet(ws.href)}, "⬇ Excel"));
    bar.append(el("button", {"data-worksheet":"import",
      title: "upload an edited workbook — it stages as a worksheet you "
        + "review, revalidate, and apply",
      onclick: () => pickWorksheet(ws.href)}, "⬆ Import"));
  }
  panel.append(bar);

  const items = doc.data?.items || [];
  panel.append(itemTable(items, {
    selectable: bulkActions.length > 0, selected,
    query: gridQuery ? gridColumns(gridQuery) : null, hints,
    currentSort: params.get("sort"),
    onSort: gridQuery ? (next) => {
      const p = new URLSearchParams(params);
      p.set("sort", next); p.delete("page[number]");
      go(path + "?" + p);
    } : null,
    ...(kind === "attachment" ? {rowAction: it => downloadButton(it, true)} : {})}));
  panel.append(pagerOf(doc));
  view.append(panel);
  watchScope({kind});
}

/* ── the deploy history: the definitions collection, rendered as what
   it is — the record of what the law has been, when. Violet is spent
   here and nowhere else. (v10 collection items are envelope-minus-data,
   so each row speaks through its stored summary; the full fingerprint
   diff lives one click in, on the definition row itself.) ──────────── */
function renderDeployHistory(view, doc) {
  const panel = el("div", {class:"panel"});
  panel.append(el("div", {class:"crumbs"},
    el("a", {href:"#"}, "Workspace"), " / ", "Deploy history"));
  panel.append(el("h2", {}, "Deploy history"));
  if (doc.summary) panel.append(el("div", {class:"metaline"}, doc.summary));
  const {path, params} = parseHrefQuery(doc.self);
  const query = doc.actions?.query;
  if (query) {
    const {apply, remove} = hashFilterOps(path, params);
    const fp = filterPopover(query.input, params, apply);
    if (fp) panel.append(fp);
    const fc = filterChips(query.input, params, remove);
    if (fc) panel.append(fc);
  }
  const items = doc.data?.items || [];
  for (const item of items) {
    /* the stored summary is "Law of {kind} · revision {n} · {state}" —
       the row names itself; the id stays in the href */
    panel.append(el("div", {class:"rev-row"},
      el("div", {class:"rev-head"},
        el("a", {href:"#"+item.self, class:"rev-token", title: item.self}, "⚖"),
        el("span", {class:"rev-summary"},
          el("a", {href:"#"+item.self}, item.summary)),
        el("span", {class:"chip " + (item.state === "current" ? "ok" : ""),
                    title: item.state}, item.state))));
  }
  if (!items.length) panel.append(el("div", {class:"muted"}, "no revisions"));
  panel.append(pagerOf(doc));
  view.append(panel);
  watchScope({kind: "definition"});
}

