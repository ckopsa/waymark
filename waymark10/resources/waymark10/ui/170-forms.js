/* ── actions: buttons, forms from input schema, confirm, dry-run ───── */
/* effort-aware emphasis: an assent action is one click — prominent
   unless the declaration styles it; display.style always wins. */
function actionButton({name, entry, doc, label: lbl, small, onDone, prefill}) {
  const style = entry.display?.style;
  const cls = style === "primary" ? "primary"
            : style === "danger" ? "danger"
            : entry.effort === "assent" ? "primary" : "";
  const btn = el("button",
    {class: cls, "data-action": name, "data-effort": entry.effort || "",
     style: small ? "font-size:12px;padding:3px 8px" : "",
     title: entry.display?.description || ""},
    lbl || label(name, entry),
    entry.safety?.confirm ? " …" : "");
  if (!small && entry.effort && entry.effort !== "assent")
    btn.append(el("span", {class:"effort-chip",
      title: `effort: ${entry.effort}`}, entry.effort));
  btn.addEventListener("click", () =>
    actionDialog({name, entry, doc, prefill, onDone}));
  return btn;
}

/* ── forms from JSON schemas (x-display honored, enums folded) ─────── */
/* unwrap an optional property: `X | null` arrives as oneOf/anyOf (or a
   type array) — the non-null branch is the one a form judges by */
function schemaProp(prop) {
  if (!prop || typeof prop !== "object") return {};
  const alts = prop.oneOf || prop.anyOf;
  const sub = alts ? alts.find(o => o && o.type !== "null") || {} : prop;
  if (Array.isArray(sub.type)) {
    const t = sub.type.find(t => t !== "null");
    return Object.assign({}, sub, {type: t});
  }
  return sub;
}
/* the wire speaks RFC 3339 instants; datetime-local speaks the viewer's
   wall clock with no zone — translate at the boundary, both directions */
function instantToLocal(v) {
  if (v === undefined || v === null || v === "") return "";
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return "";
  const p = n => String(n).padStart(2, "0");
  return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) +
         "T" + p(d.getHours()) + ":" + p(d.getMinutes());
}
function fieldWidget(name, rawProp, value) {
  const prop = schemaProp(rawProp);
  const xd = rawProp["x-display"] || prop["x-display"] || {};
  /* enum prose (waymark-0ee): x-display.choices maps the wire token to
     the sentence a person reads. Without it the option wears the token,
     which is what the usability battery warns about. */
  if (prop.enum) {
    const choices = xd.choices || {};
    return el("select", {name},
      el("option", {value: ""}, "…"),
      prop.enum.map(v => el("option",
        {value: String(v), selected: String(value ?? "") === String(v) ? "" : null},
        choices[String(v)] || String(v))));
  }
  if (xd.widget === "prose" || xd.widget === "textarea") {
    const ta = el("textarea", {name, "data-prose": ""}, value ?? "");
    /* scaffolding, never a value: the first declared example rides as
       the placeholder so a blank textarea is not a blank page */
    const ex = (prop.examples || rawProp.examples || [])[0];
    if (ex !== undefined) ta.setAttribute("placeholder", String(ex));
    return ta;
  }
  if (prop.type === "boolean")
    return el("select", {name}, el("option", {value: ""}, "…"),
      el("option", {value: "true", selected: value === true ? "" : null}, "true"),
      el("option", {value: "false", selected: value === false ? "" : null}, "false"));
  if (prop.type === "integer" || prop.type === "number")
    return el("input", {type: "number", name, value: value ?? "",
                        step: prop.type === "integer" ? "1" : "any"});
  if (prop.format === "date")
    return el("input", {type: "date", name, value: value ?? ""});
  if (prop.format === "date-time")
    return el("input", {type: "datetime-local", name,
                        value: instantToLocal(value)});
  if (prop.type === "array") {
    const items = prop.items || {};
    if ((items.type === "string" || items.type === undefined) && !items.properties)
      return el("input", {type: "text", name, "data-array": "csv",
        placeholder: "comma-separated",
        value: Array.isArray(value) ? value.join(", ") : (value ?? "")});
    return el("textarea", {name, "data-array": "json", placeholder: "JSON array"},
      value !== undefined && value !== null ? JSON.stringify(value, null, 1) : "");
  }
  if (prop.type === "object")
    return el("textarea", {name, "data-array": "json", placeholder: "JSON object"},
      value !== undefined && value !== null ? JSON.stringify(value, null, 1) : "");
  return el("input", {type: "text", name, value: value ?? "",
    placeholder: prop.format || ""});
}
/* a waymark-ref field offers the target kind's rows, labeled by summary.
   A guard-folded enum on the field is the ADMITTED set (the render
   layer's relation fold — e.g. which meals serve this day's theme):
   the picker then offers exactly those rows, labeled where the page
   had labels — the form never offers a value the server already knows
   it will refuse. Without labels (fetch failed, or an admitted id
   past the first page) the id keeps its seat raw rather than vanish. */
async function refOptions(select, rawProp, current, admitted) {
  const prop = schemaProp(rawProp);
  const xref = (rawProp["x-ref"] || prop["x-ref"]) || {};
  const kind = xref.kind;
  const admit = admitted ? admitted.map(String) : null;
  const append = (id, label) =>
    select.append(el("option", {value: id, selected: id === current ? "" : null},
                     label || id));
  let w;
  try { w = await wellKnown(); } catch { (admit || []).forEach(id => append(id)); return; }
  const href = kind && collectionHref(w, kind);
  if (!href) { (admit || []).forEach(id => append(id)); return; }
  /* the declared pick query filters the picker — a,b spells in-list */
  const params = {"page[size]": "100"};
  for (const [f, v] of Object.entries(xref.pick || {}))
    params[f] = Array.isArray(v) ? v.join(",") : String(v);
  /* the whole collection, not the first page: next links are followed
     until the edge (a kind past the sanity cap offers its first
     thousand — a picker that big wants a filter, not a scroll) */
  let {ok, body} = await api(mergeParams(href, params));
  if (!ok) { (admit || []).forEach(id => append(id)); return; }
  const summaries = new Map();
  for (let pages = 0; pages < 10; pages++) {
    for (const item of (body.data || {}).items || [])
      summaries.set(item.self.split("/").pop(), item.summary);
    const next = body.links?.next?.href;
    if (!next) break;
    ({ok, body} = await api(next));
    if (!ok) break;
  }
  const entries = (admit || [...summaries.keys()])
    .map(id => [id, summaries.get(id) || id]);
  entries.forEach(([id, label]) => append(id, label));
  /* past a scrollable handful, a select is a haystack: upgrade to a
     combobox — the select stays as the hidden value carrier (its
     [name] is what collectValues reads), a filter input fronts it */
  if (entries.length > 20) comboUpgrade(select, entries, current);
}
/* type-to-filter over an already-loaded ref picker: filtering is
   client-side (refOptions fetched the whole collection), selection
   writes through to the hidden select so submit paths never change */
function comboUpgrade(select, entries, current) {
  select.style.display = "none";
  const label0 = current != null
    ? (entries.find(([id]) => id === String(current)) || [])[1] : "";
  const input = el("input", {type: "text", value: label0 || "",
    placeholder: "type to search " + entries.length + " options…",
    autocomplete: "off"});
  const list = el("div", {class: "combo-list", hidden: ""});
  const wrap = el("div", {class: "combo"}, input, list);
  select.after(wrap);
  const CAP = 50;
  let visible = [];
  const show = q => {
    const needle = (q || "").trim().toLowerCase();
    visible = needle
      ? entries.filter(([, l]) => (l || "").toLowerCase().includes(needle))
      : entries;
    list.replaceChildren(
      ...visible.slice(0, CAP).map(([id, l]) =>
        el("div", {class: "combo-item", "data-id": id}, l)),
      ...(visible.length > CAP
        ? [el("div", {class: "combo-more"},
              "… " + (visible.length - CAP) + " more — keep typing")]
        : []),
      ...(visible.length ? [] :
        [el("div", {class: "combo-more"}, "no match")]));
    list.hidden = false;
  };
  const pick = (id, l) => {
    select.value = id;
    select.dispatchEvent(new Event("change", {bubbles: true}));
    input.value = l;
    list.hidden = true;
  };
  input.addEventListener("focus", () => show(input.value === label0 ? "" : input.value));
  input.addEventListener("input", () => {
    /* an edited label is no longer a choice — until picked again.
       The clearing is a change like any other: a listening filter
       must hear "no one selected", not keep the last pick. */
    if (select.value && input.value !==
        (entries.find(([id]) => id === select.value) || [])[1])
      { select.value = "";
        select.dispatchEvent(new Event("change", {bubbles: true})); }
    show(input.value);
  });
  input.addEventListener("keydown", e => {
    if (e.key === "Enter" && visible.length)
      { e.preventDefault(); pick(visible[0][0], visible[0][1]); }
    if (e.key === "Escape") list.hidden = true;
  });
  input.addEventListener("blur", () => setTimeout(() => {
    list.hidden = true;
    /* a blur with no picked value clears a half-typed label */
    if (!select.value) input.value = "";
  }, 150));
  list.addEventListener("mousedown", e => {
    const item = e.target.closest(".combo-item");
    if (item) { e.preventDefault();
      pick(item.dataset.id, item.textContent); }
  });
}
/* keystroke validation: the JSON schema judges as you type */
function clientMessages(raw, rawProp, required) {
  const prop = schemaProp(rawProp);
  const msgs = [];
  if (raw === "" || raw === null || raw === undefined) {
    if (required) msgs.push("required");
    return msgs;
  }
  if (prop.type === "integer" || prop.type === "number") {
    const n = Number(raw);
    if (Number.isNaN(n)) msgs.push("must be a number");
    else {
      if (prop.type === "integer" && !Number.isInteger(n))
        msgs.push("must be an integer");
      if (prop.minimum !== undefined && n < prop.minimum)
        msgs.push("must be ≥ " + prop.minimum);
      if (prop.maximum !== undefined && n > prop.maximum)
        msgs.push("must be ≤ " + prop.maximum);
    }
    return msgs;
  }
  if (typeof raw === "string") {
    if (prop.minLength !== undefined && raw.length < prop.minLength)
      msgs.push("at least " + prop.minLength + " character(s)");
    if (prop.maxLength !== undefined && raw.length > prop.maxLength)
      msgs.push("at most " + prop.maxLength + " characters (" +
                raw.length + " typed)");
    if (prop.pattern && !(new RegExp(prop.pattern)).test(raw))
      msgs.push("must match " + prop.pattern);
    if (prop.enum && !prop.enum.map(String).includes(raw))
      msgs.push("must be one of the offered values");
  }
  return msgs;
}
/* vocab fields: a datalist combobox — facet counts from the target
   collection's advertised query schema (x-facets) when faceted there */
function vocabProp(rawProp) {
  const prop = schemaProp(rawProp);
  return prop.format === "waymark-vocab" ||
         ((prop.items || {}).format === "waymark-vocab") ||
         !!rawProp["x-vocab"] || !!prop["x-vocab"];
}
let vocabListSeq = 0;
async function vocabOptions(listNode, kind, field) {
  let w;
  try { w = await wellKnown(); } catch { return; }
  const href = collectionHref(w, kind);
  if (!href) return;
  const {ok, body} = await api(href);
  if (!ok) return;
  const prop = (((((body || {}).actions || {}).query || {}).input || {})
                .properties || {})[field] || {};
  for (const [value, count] of Object.entries(prop["x-facets"] || {}))
    listNode.append(el("option", {value, label: value + " · " + count}));
}
/* ── runtime vocabularies (waymark-8sg): x-options ──────────────────
   Some fields are judged against a vocabulary only the RUNNING engine
   enumerates — the kinds it serves, one kind's data fields, its
   actions, its declared views, its filter grammar. The guard for such
   a field escapes the closure rule with :open, and for as long as the
   schema kept quiet about it every client drew a blank rectangle
   where a picker belonged. x-options is the schema saying it out
   loud: {from, of, href, at, each, composes, note} — a ONE-HOP recipe
   out of documents this client already holds (well-known, and one
   kind's published schema).

   What it earns is a datalist and a row of chips beside the field's
   own box, never a cage. The annotation ADVERTISES and the guard
   still ENFORCES, so a token the source cannot list — a slot's
   "sv-<id>", minted per row — stays typeable, and the refusal that
   names it is the refusal it always was. */
function xoptionsOf(rawProp) {
  const prop = schemaProp(rawProp);
  return rawProp["x-options"] || prop["x-options"] || null;
}
const OPT_HOLE = /\{([A-Za-z_][A-Za-z_0-9]*)\}/g;
/* {target} in an href or an :at segment is the CURRENT value of the
   sibling field of that name — the recipe is resolved against the form
   in front of the person, not against anything the server remembers */
function optFill(s, values, encode) {
  return String(s).replace(OPT_HOLE, (_m, f) =>
    encode ? encodeURIComponent(values[f] ?? "") : String(values[f] ?? ""));
}
function optHoles(xo) {
  const out = [];
  const scan = s => String(s).replace(OPT_HOLE, (m, f) => { out.push(f); return m; });
  scan(xo.href || "");
  (xo.at || []).forEach(scan);
  return out;
}
/* one fetch per resolved href, shared by every field that names it:
   :right, :left and :where on the same form all read well-known */
const optionDocCache = {};
function optionDoc(href) {
  if (!(href in optionDocCache))
    optionDocCache[href] = api(href).catch(() => ({ok: false}));
  return optionDocCache[href];
}
/* null (not []) when a sibling the recipe needs is still unanswered —
   "pick a target first" is a different sentence from "no options" */
async function optionTokens(xo, values) {
  for (const f of optHoles(xo)) if (!values[f]) return null;
  const {ok, body} = await optionDoc(optFill(xo.href, values, true));
  if (!ok) return [];
  let node = body;
  for (const seg of xo.at || []) {
    if (!node || typeof node !== "object") return [];
    node = node[optFill(seg, values, false)];
  }
  if (Array.isArray(node)) return node.map(String);
  if (node && typeof node === "object") return Object.keys(node).map(String);
  return [];
}
let optionListSeq = 0;
function attachOptions(form, input, xo) {
  const listId = "opts-" + (++optionListSeq);
  const list = el("datalist", {id: listId});
  input.setAttribute("list", listId);
  input.setAttribute("data-options", xo.from);
  form.append(list);
  const chips = el("div", {class: "opt-chips"});
  input.after(chips);
  const siblings = () => {
    const out = {};
    for (const n of form.querySelectorAll("[name]"))
      out[n.getAttribute("name")] = n.value;
    return out;
  };
  /* a chip writes the token the way THIS field spells one: a whole
     value, one entry of a comma list, or a name= waiting for its
     value in a filter string */
  const put = tok => {
    if (xo.each) {
      const have = input.value.split(",").map(s => s.trim()).filter(Boolean);
      const at = have.indexOf(tok);
      if (at >= 0) have.splice(at, 1); else have.push(tok);
      input.value = have.join(", ");
    } else if (xo.composes === "query") {
      if (!(new RegExp("(^|&)" + tok + "=")).test(input.value))
        input.value += (input.value && !input.value.endsWith("&") ? "&" : "") +
                       tok + "=";
    } else {
      input.value = tok;
    }
    input.dispatchEvent(new Event("input", {bubbles: true}));
    input.focus();
  };
  const CAP = 24;
  let seq = 0;
  const refresh = async () => {
    const mine = ++seq;
    const tokens = await optionTokens(xo, siblings());
    if (mine !== seq) return;              /* a later keystroke won the race */
    list.replaceChildren();
    if (tokens === null) {
      chips.replaceChildren(el("span", {class: "muted"},
        "answer " + xo.of + " first — the options are " + xo.note));
      return;
    }
    for (const t of tokens) list.append(el("option", {value: t}));
    chips.replaceChildren(
      ...tokens.slice(0, CAP).map(t => {
        const c = el("button", {type: "button", class: "chip", title: xo.note}, t);
        c.addEventListener("click", e => { e.preventDefault(); put(t); });
        return c;
      }),
      ...(tokens.length > CAP
        ? [el("span", {class: "muted"},
              "… " + (tokens.length - CAP) + " more — type to search")]
        : []),
      ...(tokens.length
        ? []
        : [el("span", {class: "muted"}, "nothing offered — " + xo.note)]));
  };
  refresh();
  for (const f of optHoles(xo)) {
    const sib = form.querySelector('[name="' + f + '"]');
    if (sib) { sib.addEventListener("change", refresh);
               sib.addEventListener("input", refresh); }
  }
}
function buildForm(schema, prefill, kind) {
  const form = el("div", {});
  const required = new Set((schema.required || []).map(String));
  /* x-options wiring waits for the whole form: a recipe interpolates
     SIBLING values, and a sibling declared later is not in the DOM yet */
  const pendingOptions = [];
  for (const [name, rawProp] of Object.entries(schema.properties || {})) {
    if (name === "ids") continue;           /* bulk ids ride the selection */
    const prop = schemaProp(rawProp);
    /* a const field is pre-bound (a part-scope key): shown, fixed,
       still submitted */
    if (prop.const !== undefined || rawProp.const !== undefined) {
      const c = prop.const !== undefined ? prop.const : rawProp.const;
      form.append(el("div", {class: "field"},
        el("label", {}, el("b", {}, name), " (bound)"),
        el("div", {class: "const", "data-const": name}, String(c)),
        el("input", {type: "hidden", name, value: String(c)})));
      continue;
    }
    /* the declared default seeds an unanswered field — prefill wins */
    const seed = (prefill || {})[name] !== undefined
      ? (prefill || {})[name]
      : (rawProp.default !== undefined ? rawProp.default : prop.default);
    let widget;
    if (xref(rawProp)) {
      widget = el("select", {name}, el("option", {value: ""}, "…"));
      /* prop.enum here is the guard-folded admitted set — honor it */
      refOptions(widget, rawProp, seed, prop.enum);
    } else {
      widget = fieldWidget(name, rawProp, seed);
    }
    if (vocabProp(rawProp) && widget.tagName === "INPUT") {
      const listId = "vocab-" + (++vocabListSeq);
      const list = el("datalist", {id: listId});
      widget.setAttribute("list", listId);
      widget.setAttribute("data-vocab", name);
      const ph = ((rawProp["x-vocab"] || prop["x-vocab"] || {}).placeholder);
      if (ph) widget.setAttribute("placeholder", ph);
      form.append(list);
      if (kind) vocabOptions(list, kind, name);
    }
    const errSlot = el("div", {class: "err", "data-err": name});
    if (widget.tagName === "INPUT" || widget.tagName === "TEXTAREA") {
      widget.addEventListener("input", () => {
        errSlot.textContent =
          clientMessages(widget.value, rawProp, required.has(name)).join("; ");
      });
    }
    const xd = rawProp["x-display"] || prop["x-display"] || {};
    form.append(el("div", {class: "field", "data-field": name},
      el("label", {title: name}, el("b", {}, xd.label || name),
         required.has(name) ? el("span", {class:"req"}, " *") : "",
         vocabProp(rawProp) ? el("span", {class:"muted"}, " (vocab)") : ""),
      widget,
      errSlot,
      el("div", {class: "err srv", "data-srverr": name})));
    if (xd.help) form.append(el("div", {class:"muted",
      style:"font-size:11px;margin:-6px 0 8px"}, xd.help));
    const xo = xoptionsOf(rawProp);
    if (xo && xo.href && widget.tagName === "INPUT")
      pendingOptions.push([widget, xo]);
  }
  for (const [widget, xo] of pendingOptions) attachOptions(form, widget, xo);
  return form;
}
function collectValues(form, schema) {
  const values = {};
  const props = (schema || {}).properties || {};
  for (const node of form.querySelectorAll("[name]")) {
    const name = node.getAttribute("name");
    const prop = schemaProp(props[name] || {});
    let raw = node.value;
    if (raw === "" || raw === null) continue;
    if (node.dataset.array === "csv") {
      const arr = raw.split(",").map(s => s.trim()).filter(Boolean);
      if (arr.length) values[name] = arr;
      continue;
    }
    if (node.dataset.array === "json") {
      try { values[name] = JSON.parse(raw); }
      catch (_e) { values[name] = raw; }    /* the server's 422 narrates */
      continue;
    }
    if (node.type === "datetime-local") {
      values[name] = new Date(raw).toISOString();
      continue;
    }
    if (prop.type === "integer") values[name] = parseInt(raw, 10);
    else if (prop.type === "number") values[name] = parseFloat(raw);
    else if (prop.type === "boolean") values[name] = raw === "true";
    else values[name] = raw;
  }
  return values;
}
function prefillFromDoc(doc, input) {
  const out = {};
  for (const name of Object.keys((input || {}).properties || {}))
    if ((doc.data || {})[name] !== undefined) out[name] = doc.data[name];
  return out;
}

/* ── relay/2 prose ops: a prefix/suffix diff makes the op, the server
   transforms and relays; incoming ops apply positionally ───────────── */
function diffOps(a, b) {
  a = String(a ?? ""); b = String(b ?? "");
  let p = 0;
  while (p < a.length && p < b.length && a[p] === b[p]) p++;
  let s = 0;
  while (s < a.length - p && s < b.length - p &&
         a[a.length - 1 - s] === b[b.length - 1 - s]) s++;
  const ops = [];
  if (p) ops.push({retain: p});
  if (a.length - p - s > 0) ops.push({delete: a.length - p - s});
  if (b.slice(p, b.length - s).length) ops.push({insert: b.slice(p, b.length - s)});
  if (s) ops.push({retain: s});
  return ops;
}
function applyOps(s, ops) {
  s = String(s ?? "");
  let i = 0, out = "";
  for (const op of ops || []) {
    if (op.insert !== undefined) out += op.insert;
    else if (op.retain !== undefined) { out += s.slice(i, i + op.retain); i += op.retain; }
    else if (op.delete !== undefined) i += op.delete;
  }
  return out + s.slice(i);
}

