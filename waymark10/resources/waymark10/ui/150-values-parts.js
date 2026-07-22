/* ── values: honest rendering of the data document ─────────────────── */
function valueCell(v, xd) {
  if (v === null || v === undefined) return el("span", {class:"muted"}, "—");
  if (typeof v === "boolean") return v ? "✓" : el("span", {class:"muted"}, "—");
  if (Array.isArray(v)) {
    if (!v.length) return el("span", {class:"muted"}, "—");
    if (v.every(x => x && typeof x === "object" && !Array.isArray(x)))
      return nestedTable(v);
    return el("span", {}, v.map(x =>
      el("span", {class:"chip", style:"margin-right:4px"}, String(x))));
  }
  if (typeof v === "object") return kvTable(v);
  if ((xd || {}).widget === "prose" || (typeof v === "string" && v.includes("\n")))
    return el("div", {class:"prose", style:"white-space:pre-wrap;max-width:640px;" +
      "max-height:320px;overflow-y:auto"}, String(v));
  return el("span", {}, String(v));
}
function nestedTable(rows) {
  const cols = [...new Set(rows.flatMap(r => Object.keys(r)))]
    .filter(c => rows.some(r => r[c] !== null && r[c] !== undefined));
  return el("div", {style:"overflow-x:auto"}, el("table", {class:"items"},
    el("thead", {}, el("tr", {}, cols.map(c => el("th", {title: c}, title(c))))),
    el("tbody", {}, rows.map(r => el("tr", {},
      cols.map(c => el("td", {}, valueCell(r[c]))))))));
}
/* one field's value, the ref-or-plain rule kvTable and the grid body
   both need — a ref field with a set value links to the target
   (labeled by a LIVE depth=summary fetch, resourceRef's own job:
   always current, unlike a denormalized label copy), anything else
   is valueCell's honest rendering. */
function fieldCell(schema, field, value) {
  const xd = xdisplay(schema, field);
  const ref = xref(((schema || {}).properties || {})[field]);
  if (ref && Array.isArray(value) && value.length)
    /* a ref ARRAY (a mirror's external-keyed :many — team members,
       team funds): one live-labeled link per id */
    return el("span", {},
      ...value.flatMap((id, i) =>
        i ? [", ", resourceRef(ref.kind, id)] : [resourceRef(ref.kind, id)]));
  return ref && value && !Array.isArray(value)
    ? resourceRef(ref.kind, value) : valueCell(value, xd);
}
function kvTable(obj, schema) {
  const t = el("table", {class:"kv"});
  /* same grouping (refs, data, raw ids) and hiding as the table */
  for (const k of groupFields(
         Object.keys(obj).filter(f => !fieldHidden(schema, f)), schema)) {
    const v = obj[k];
    t.append(el("tr", {},
      el("td", {class:"k", title: k}, fieldLabel(schema, k)),
      el("td", {}, fieldCell(schema, k, v))));
  }
  return t;
}

/* ── grid columns: folding a query-input-schema-shaped object (either
   doc.actions.query.input for a top-level collection, or an embed
   link's own .columns — same shape, per router.clj's splice-embeds)
   into one entry per LOGICAL column. The suffixed params
   (field_gte/_lte/_after/_before/_ne/_set/_contains) fold back onto
   field; the sort enum becomes the sortable set. One
   helper, every table (top-level and every embedded one) folds
   through it. */
function gridColumns(schema) {
  const props = (schema || {}).properties || {};
  const sortable = new Set((props.sort?.enum || []).map(s => s.replace(/^-/, "")));
  const cols = {};
  const ensure = f => cols[f] || (cols[f] = {field: f, ops: {}});
  for (const [name, prop] of Object.entries(props)) {
    if (name === "sort" || name.startsWith("page[")) continue;
    const m = /^(.*)_(gte|lte|after|before|ne|contains|set)$/.exec(name);
    if (m) {
      const c = ensure(m[1]);
      c.ops[m[2]] = true;
      // _set advertises boolean and _contains string whatever the
      // field's own type — neither may claim the column's type
      if (m[2] !== "set" && m[2] !== "contains") {
        c.type = c.type || prop.type; c.format = c.format || prop.format;
      }
    } else {
      const c = ensure(name);
      c.ops[prop["x-in"] ? "in" : "eq"] = true;
      c.type = prop.type; c.format = prop.format; c.enum = prop.enum;
    }
  }
  // a sortable field need not be filterable (meal's `name`, e.g.) — it
  // still needs a cols entry so its <th> can render sort arrows, even
  // though it never appears in the Filters popover (empty ops)
  for (const f of sortable) ensure(f);
  for (const c of Object.values(cols)) c.sortable = sortable.has(c.field);
  return cols;
}

/* field grouping, one rule for tables and kv panels: refs lead (a
   linked row beats its raw token), plain data follows, raw id
   plumbing trails (*_id/*_ids/external_id that ISN'T a ref — the
   machine correlation a human rarely reads first). */
function fieldGroup(hints, f) {
  if (xref(((hints || {}).properties || {})[f])) return 0;
  return (f === "external_id" || /_ids?$/.test(f)) ? 2 : 1;
}
function groupFields(fields, hints) {
  return fields
    .map((f, i) => [fieldGroup(hints, f), i, f])
    .sort((a, b) => a[0] - b[0] || a[1] - b[1])
    .map(t => t[2]);
}

/* the actual column list to render: query-declared fields first (a
   stable, schema-driven order), then anything present in the items'
   own :fields that the query schema doesn't know about (present but
   not independently filterable/sortable — still a real column) —
   then the whole list regrouped: refs, data, raw ids. */
function fieldColumns(items, query, hints) {
  const seen = new Set(), ordered = [];
  for (const f of Object.keys(query || {}))
    if (items.some(it => it.fields && f in it.fields) && !seen.has(f))
      { seen.add(f); ordered.push(f); }
  for (const it of items)
    for (const f of Object.keys(it.fields || {}))
      if (!seen.has(f)) { seen.add(f); ordered.push(f); }
  return groupFields(ordered.filter(f => !fieldHidden(hints, f)), hints);
}

/* A cross-resource reference: a link to the referenced resource,
   labeled lazily by its own summary. Raw ids are machine plumbing —
   humans get the thing, not the token. (wire 10 has no lookup route
   class; the summary ride is a plain depth=summary read.) */
function resourceRef(kind, id, text) {
  const a = el("a", {href: "#", class:"mono", title: String(id)},
    text || `${pretty(kind || "")} · ${String(id).slice(0, 8)}`);
  wellKnown().then(w => {
    const col = kind && collectionHref(w, kind);
    if (!col) return;
    a.href = "#" + col + "/" + id;
    if (!text) api(`${col}/${id}?depth=summary`).then(({ok, body}) => {
      if (ok && body.summary) { a.textContent = body.summary; a.className = ""; }
    }).catch(() => {});
  }).catch(() => {});
  return a;
}

/* ── the parts namespace: placed actions re-rendered per data item —
   the item rows render HERE (with their honest buttons and their
   per-item refusal narrations), not in the data table ──────────────── */
function partsSections(doc) {
  return Object.entries(doc.parts || {}).map(([scope, group]) => {
    const items = group.items || [];
    const cols = [...new Set(items.flatMap(it => Object.keys(it.item || {})))]
      .filter(c => items.some(it => (it.item || {})[c] !== null &&
                                    (it.item || {})[c] !== undefined));
    return el("div", {},
      el("h3", {class:"sect"}, title(scope)),
      el("div", {class:"panel", "data-part": scope, style:"padding:10px 14px"},
        el("div", {style:"overflow-x:auto"}, el("table", {class:"items cards"},
          el("thead", {}, el("tr", {},
            cols.map(c => el("th", {title: c}, title(c))), el("th", {}, ""))),
          el("tbody", {}, items.map(it =>
            el("tr", {"data-part-key": String(it.key)},
              cols.map(c => el("td", {class:"c-field", "data-label": title(c)},
                valueCell((it.item || {})[c]))),
              el("td", {class:"partactions"},
                Object.entries(it.actions || {}).map(([name, entry]) =>
                  actionButton({name, entry, doc, small: true,
                                onDone: () => render()})),
                Object.entries(it.unavailable || {}).map(([name, entry]) =>
                  el("button", {disabled: "", class:"blocked",
                                title: entry.reason || ""},
                     title(name)))))))))));
  });
}

/* an embedded table's filter/sort must mutate the PARENT resource's own
   hash with embed.<rel>.<param>=… overrides (router.clj's
   embed-override-re), not navigate to the embed's bare href — this is
   the embed-scoped flavor of hashFilterOps, prefixing/stripping
   "embed.<rel>." on the way in and out. */
function embedFilterOps(doc, rel) {
  /* doc.self is the resource's own canonical identity — it never
     echoes embed.<rel>.* overrides (unlike a collection's self,
     which DOES echo its own applied query). The live params are
     only visible in the browser's own current hash. */
  const {path, params} = parseHrefQuery(location.hash.slice(1) || doc.self);
  const prefix = `embed.${rel}.`;
  const scoped = new URLSearchParams();
  for (const [k, v] of params) if (k.startsWith(prefix)) scoped.set(k.slice(prefix.length), v);
  const nav = p => { p.delete(prefix + "page[number]"); go(path + (p.toString() ? "?" + p : "")); };
  return {
    scoped,
    apply: updates => {
      const p = new URLSearchParams(params);
      for (const [k, v] of Object.entries(updates)) {
        const full = prefix + k;
        if (v) p.set(full, v); else p.delete(full);
      }
      nav(p);
    },
    remove: name => { const p = new URLSearchParams(params); p.delete(prefix + name); nav(p); },
    onSort: next => { const p = new URLSearchParams(params); p.set(prefix + "sort", next); nav(p); },
    /* paging is the one mutation that must NOT go through nav's
       page[number]-reset (that reset is for filter/sort changes
       landing on page 1, not for paging itself) */
    setPage: n => {
      const p = new URLSearchParams(params);
      p.set(prefix + "page[number]", String(n));
      go(path + (p.toString() ? "?" + p : ""));
    }
  };
}

/* an embed's own pager. Unlike a top-level collection, an embed link
   carries no prev/next hrefs (router.clj's splice-embeds only stamps
   total/page/embed — precomputing dot-namespaced prev/next hrefs
   isn't worth it when the client already builds embed.<rel>.* urls
   for filter/sort). has-more is computed here from total/page.size/
   page.number, mirroring pagerOf's look. */
function embedPager(link, setPage) {
  const page = link.page || {};
  const number = page.number || 1;
  const size = page.size || 1;
  const total = link.total ?? 0;
  const pager = el("div", {class:"pager"});
  if (number > 1)
    pager.append(el("a", {href:"#",
      onclick: e => { e.preventDefault(); setPage(number - 1); }}, "← prev"));
  pager.append(el("span", {class:"muted"}, `page ${number} · ${total} total`));
  if (number * size < total)
    pager.append(el("a", {href:"#",
      onclick: e => { e.preventDefault(); setPage(number + 1); }}, "next →"));
  return pager;
}

/* embed=true links arrive with server-spliced envelope-minus-data items
   under links.<rel>.embedded — the rows are already in the envelope,
   and link.columns is the target's own filter/sort vocabulary, so each
   embedded table gets its own Filters popover and sort-arrows, exactly
   like a top-level collection screen. */
/* an embedded link's rows are the CHILD kind's — their ref fields,
   labels, and grouping come from the child's own published schema,
   never the parent's (a plan's schema knows nothing of a day's
   meal_id). */
function embedKindOf(link) {
  const k = link.kind || "";
  return k.endsWith("_collection") ? k.slice(0, -"_collection".length) : null;
}
async function embeddedSections(doc, hints) {
  return Promise.all(Object.entries(doc.links || {})
    .filter(([, link]) => (link.embedded || []).length)
    .map(async ([rel, link]) => {
      const child = embedKindOf(link);
      if (child) hints = await kindSchema(child);
      const gridQuery = link.columns;
      const {scoped, apply, remove, onSort, setPage} = embedFilterOps(doc, rel);
      const bar = el("div", {class: "filterbar"});
      if (gridQuery) {
        const fp = filterPopover(gridQuery, scoped, apply, hints);
        if (fp) bar.append(fp);
        if (MOBILE) {
          const ss = sortSelect(gridQuery, scoped.get("sort"),
            next => next ? onSort(next) : remove("sort"), hints);
          if (ss) bar.append(ss);
        }
        const fc = filterChips(gridQuery, scoped, remove, hints);
        if (fc) bar.append(fc);
      }
      return el("div", {class:"embed"},
        el("div", {class:"embed-head"},
          el("b", {}, link.summary || title(rel)),
          link.badge !== undefined && link.badge !== null
            ? el("span", {class:"badge", title:"count, per the server"},
                String(link.badge)) : null,
          el("a", {class:"open", href:"#"+link.href}, "open ↗")),
        bar.childElementCount ? bar : null,
        itemTable(link.embedded, {
          query: gridQuery ? gridColumns(gridQuery) : null, hints,
          currentSort: scoped.get("sort"),
          onSort: gridQuery ? onSort : null
        }),
        embedPager(link, setPage));
    }));
}

