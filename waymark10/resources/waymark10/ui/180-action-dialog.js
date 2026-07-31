/* ── the action dialog: gate, form, fence, key, warnings, drafts ───── */
/* dry_run is honored on single-resource action invokes, creates
   (POST /api/{plural}) and bulk invokes (POST /api/{plural}/-/{action})
   — engine fact since §23; batch is the one door this page does not
   drive. dry_run=1 is the full rehearsal (the Check button);
   dry_run=partial judges only the fields provided (the blur judge). */
function dryRunnable(href) {
  return /^\/api\/[^/?]+$/.test(href || "") ||                        /* create */
         /^\/api\/[^/]+\/-\/[^/?]+$/.test(href || "") ||              /* bulk */
         /^\/api\/[^/]+\/(?!-\/)[^/]+\/-\/[^/?]+$/.test(href || "");  /* row action */
}
const dlgStamp = t => new Date(t || Date.now())
  .toTimeString().slice(0, 8);

async function actionDialog({name, entry, doc, bulkIds, prefill, onDone}) {
  const safety = entry.safety || {};
  const input = entry.input || null;
  /* rule 3 (Part IV): a non-idempotent action gets its key at dialog
     open — one logical attempt, one key, however many retries */
  const idemKey = safety.idempotent ? null : uuid();
  let acknowledged = [];

  /* drafts: half-written effort is server state. Wire 10: GET 404s
     until something was saved and answers {values, base_version,
     prefill, revs, authors}; the row's own current values are the
     fallback prefill for an :edit that was never drafted. */
  let draftView = null;
  if (entry.draft && entry.draft.href) {
    const d = await api(entry.draft.href);
    if (d.ok) draftView = d.body;
  }
  /* the row a collection hands us is an envelope-SUMMARY, and
     render.clj drops "data" from those — so prefillFromDoc would read
     an absent projection and open the form blank (the detail screen,
     holding the full envelope, prefilled fine). Fetch the document
     when this action wants prefill and the doc at hand has none;
     summaries carry :self, which is the whole cost of the round
     trip. A collection envelope has no :self row to fetch, and a bulk
     write must not seed from one row — both skip. */
  let source = doc;
  if (!bulkIds && (Array.isArray(entry.prefill) || entry.draft)
      && !doc.data && doc.self) {
    const d = await api(doc.self);
    if (d.ok && d.body && d.body.data) source = d.body;
  }
  /* the declared :edit {:prefill} — the fields the row already
     answers. Not for a bulk write: one row's values must never seed a
     many-row form. A drafted action keeps its wider doc projection. */
  const declared = (!bulkIds && Array.isArray(entry.prefill))
    ? prefillFromDoc(source, {properties: Object.fromEntries(
        entry.prefill.map((f) => [f, true]))})
    : {};
  const initialValues = Object.assign({},
    entry.draft && !bulkIds ? prefillFromDoc(source, input) : {},
    declared,
    prefill || {},
    (draftView || {}).prefill || {},
    (draftView || {}).values || {});
  const kind = (doc.kind || "").replace("_collection", "");
  const form = input ? buildForm(input, initialValues, kind) : el("div", {});

  const errBox = el("div", {});
  /* the blur judge's verdict line (§23): "✓ so far" is the partial
     rehearsal speaking — every field it can already judge, judged */
  const dryNote = el("span", {class: "drynote"});
  const savedNote = el("span", {},
    draftView ? "draft loaded from the server" : "");
  const peers = el("span", {class:"muted"});
  const draftBar = entry.draft
    ? el("p", {class: "draftnote", "data-draftnote": ""},
        savedNote, peers) : null;
  /* staleness by base_version: the draft was written against an older
     row — say so before anyone submits it */
  if (draftView && draftView.base_version != null && doc.meta &&
      doc.meta.version != null && draftView.base_version < doc.meta.version)
    draftBar.prepend(el("span", {class:"bad"},
      `⚠ draft from an older version of this ${pretty(kind)} — review before submitting · `));

  const h3 = el("h3", {}, entry.display?.label || title(name));
  if (entry.effort && entry.effort !== "assent")
    h3.append(el("span", {class:"effort-chip",
      title: `effort: ${entry.effort}`}, entry.effort));
  const dlg = el("dialog", {},
    el("div", {class: "dlghead"},
      h3,
      el("p", {class: "metaline"},
        el("span", {class:"mono"}, doc.state ? pretty(doc.state) : "—"),
        " → ", el("span", {class:"mono"}, pretty((entry.effect || {}).to || "?")),
        ((entry.effect || {}).terminal ? " (terminal)" : "") +
        (bulkIds ? ` · ${bulkIds.length} selected row(s)` : "") +
        (safety.fence ? " · fenced (If-Match)" : "") +
        (safety.idempotent ? "" : " · idempotency-key attached"))),
    el("div", {class: "dlgbody"},
      safety.confirm
        ? el("div", {class: "consequence"},
            el("b", {}, "Confirm: "),
            entry.display?.description || "This action requires confirmation.")
        : null,
      form, errBox, draftBar),
    el("div", {class: "dlgfoot"},
      el("span", {class: "hint"},
        entry.draft ? (entry.draft.shared ? "shared draft — saved on blur"
                                          : "draft — saved on blur") : ""),
      dryNote,
      entry.draft ? el("button", {onclick: async () => {
        disarmDraft();
        await api(entry.draft.href, {method: "DELETE"});
        toast("Draft discarded");
        closeDlg(); render();
      }}, "Discard draft") : null,
      input && dryRunnable(entry.href)
        ? el("button", {onclick: () => check()}, "Check") : null,
      el("button", {onclick: () => closeDlg()}, "Cancel"),
      el("button", {class: safety.confirm ? "danger" : "primary",
                    onclick: () => submit()},
        safety.confirm
          ? "Confirm & " + (entry.display?.label || title(name))
          : (entry.display?.label || title(name)))));

  /* ── the draft chrome: PUT on blur; a shared draft upgrades to the
     waymark-relay/2 websocket at {draft}/collab — per-field revisions,
     explicit staleness rejection, presence, regate. Losing the socket
     just falls back to the plain PUT. ──────────────────────────────── */
  let ws = null, wsTimer = null, disarmed = false;
  const revs = {...((draftView || {}).revs || {})};
  const lastKnown = {...initialValues};
  const dirty = new Set();
  const proseFields = new Set(
    [...form.querySelectorAll("textarea[data-prose]")].map(n => n.name));
  function disarmDraft() { disarmed = true; clearTimeout(wsTimer); }
  function closeDlg() {
    clearTimeout(wsTimer);
    if (ws) { try { ws.close(); } catch (_e) {} ws = null; }
    dlg.close(); dlg.remove();
  }
  const applyRemoteField = (k, v, rev) => {
    if (rev !== undefined) {
      if (rev <= (revs[k] || 0) && lastKnown[k] === v) return;
      revs[k] = rev;
    }
    lastKnown[k] = v;
    const node = form.querySelector(`[name="${CSS.escape(k)}"]`);
    if (!node) return;
    /* hold off only while the user has unsent keystrokes in this exact
       field — a stale send comes back as a rejection carrying the truth */
    if (node === document.activeElement && dirty.has(k)) return;
    const val = v == null ? ""
      : typeof v === "object" ? JSON.stringify(v) : String(v);
    if (node === document.activeElement) {
      const s = node.selectionStart, e = node.selectionEnd;
      node.value = val;
      try { node.setSelectionRange(s, e); } catch (_e) {}
    } else node.value = val;
  };
  const showPeers = participants => {
    const me = principalId() || "anonymous";
    const others = (participants || []).filter(p => p.id !== me);
    peers.textContent = others.length
      ? ` · editing with ${others.map(p => p.display || p.id).join(", ")}` : "";
  };
  if (entry.draft && entry.draft.shared) {
    try {
      const proto = location.protocol === "https:" ? "wss:" : "ws:";
      ws = new WebSocket(`${proto}//${location.host}${entry.draft.href}/collab`);
      ws.onmessage = ev => {
        let m; try { m = JSON.parse(ev.data); } catch (_e) { return; }
        if (m.type === "state" || m.type === "sync") {
          for (const [k, v] of Object.entries(m.values || {}))
            applyRemoteField(k, v, (m.revs || {})[k]);
          for (const [k, r] of Object.entries(m.revs || {}))
            if (r > (revs[k] || 0)) revs[k] = r;
          showPeers(m.participants);
          if (m.stale) savedNote.textContent =
            "shared draft from an older version — review before submitting";
        } else if (m.type === "update") {
          applyRemoteField(m.field, m.value, m.rev);
          savedNote.textContent =
            `${(m.author || {}).display || (m.author || {}).id || "someone"} `
            + `edited ${dlgStamp()}`;
        } else if (m.type === "edit") {
          const next = applyOps(lastKnown[m.field], m.ops);
          applyRemoteField(m.field, next, m.rev);
          savedNote.textContent =
            `${(m.author || {}).display || (m.author || {}).id || "someone"} `
            + `edited ${dlgStamp()}`;
        } else if (m.type === "ack") {
          revs[m.field] = m.rev;
          savedNote.textContent = `draft saved ${dlgStamp()}`;
        } else if (m.type === "stale") {
          /* our edit was based on a stale revision: the server sent the
             field's truth — show it and continue from there */
          dirty.delete(m.field);
          applyRemoteField(m.field, m.value, m.rev);
          savedNote.textContent = "edit overtaken — showing the latest";
        } else if (m.type === "presence") {
          showPeers(m.participants);
        } else if (m.type === "regate") {
          if (m.gone) { savedNote.textContent =
            "the draft was consumed elsewhere — compose anew"; }
          else {
            for (const [k, r] of Object.entries(m.revs || {})) revs[k] = r;
            savedNote.textContent =
              "the row moved underneath this draft — re-check before submitting";
          }
        } else if (m.type === "resync") {
          try { ws.send(JSON.stringify({type: "sync"})); } catch (_e) {}
        } else if (m.type === "error") {
          showFieldErrors({errors: m.errors || {}});
        }
      };
      ws.onclose = () => { ws = null; peers.textContent = ""; };
      ws.onerror = () => { try { ws && ws.close(); } catch (_e) {} };
    } catch (_e) { ws = null; }
  }
  const saveDraft = async () => {
    if (disarmed || !entry.draft) return;
    const all = input ? collectValues(form, input) : {};
    if (ws && ws.readyState === 1) {
      /* send only what changed, one relay/2 frame per field, each
         pinned to the revision it was based on — the server rejects
         stale edits with the truth instead of silently clobbering */
      for (const fname of Object.keys((input || {}).properties || {})) {
        const v = all[fname] === undefined ? null : all[fname];
        const known = lastKnown[fname] === undefined ? null : lastKnown[fname];
        if (JSON.stringify(v) === JSON.stringify(known)) continue;
        /* prose fields ride operation frames once a revision exists;
           the first write is a set (a rebase point, per relay/2) */
        if (proseFields.has(fname) && (revs[fname] || 0) > 0 &&
            typeof v === "string" && typeof known === "string") {
          ws.send(JSON.stringify({type: "edit", field: fname,
            rev: revs[fname] || 0, ops: diffOps(known, v)}));
        } else {
          ws.send(JSON.stringify({type: "set", field: fname, value: v,
                                  rev: revs[fname] || 0}));
        }
        lastKnown[fname] = v;
      }
      dirty.clear();
      return;
    }
    const res = await api(entry.draft.href,
      {method: "PUT", body: JSON.stringify(all)});
    dirty.clear();
    if (res.ok) {
      for (const [k, r] of Object.entries((res.body || {}).revs || {})) revs[k] = r;
      for (const [k, v] of Object.entries((res.body || {}).values || {}))
        lastKnown[k] = v;
      savedNote.textContent = `draft saved ${dlgStamp()}`;
    } else savedNote.textContent =
      "draft not saved: " + (((res.body || {}).detail) || res.status);
  };
  if (entry.draft) {
    form.addEventListener("input", ev => {
      if (ev.target && ev.target.name) dirty.add(ev.target.name);
      clearTimeout(wsTimer);
      wsTimer = setTimeout(saveDraft, ws ? 400 : 800);
    });
    form.addEventListener("focusout", () => {
      clearTimeout(wsTimer);
      saveDraft();
    });
  }

  /* blur-time dry-run, third chapter (design §23): the PARTIAL
     rehearsal. Only the fields you have TOUCHED ride to the server
     (?dry_run=partial), which judges exactly what it can already
     answer — a provided field's schema errors land inline, guard
     leaves those fields fully cover speak now (their sentence on the
     verdict line, never a modal), and everything else waits without
     nagging. "✓ so far" is that verdict; Check and submit remain the
     FULL rehearsal. */
  const touched = new Set();
  let dryTimer = null;
  if (input) form.addEventListener("input", ev => {
    if (ev.target && ev.target.name) touched.add(ev.target.name);
  });
  if (input && dryRunnable(entry.href)) {
    form.addEventListener("focusout", () => {
      clearTimeout(dryTimer);
      dryTimer = setTimeout(async () => {
        if (!touched.size) return;
        const all = collectValues(form, input);
        const partial = {};
        for (const k of touched) if (k in all) partial[k] = all[k];
        if (bulkIds) partial.ids = bulkIds;
        if (!Object.keys(partial).length) return;
        const sep = entry.href.includes("?") ? "&" : "?";
        const res = await api(entry.href + sep + "dry_run=partial",
          {method: "POST", body: JSON.stringify(partial),
           headers: actHeaders()});
        clearServerErrors();
        const b = res.body || {};
        if (res.ok) {
          const iffy = (b.verdicts || []).filter(v => v.verdict !== "ok");
          if (b.valid === false) {
            dryNote.textContent = "✗ " + ((iffy[0] || {}).reason || "would refuse");
            dryNote.className = "drynote bad";
          } else {
            dryNote.textContent = "✓ so far" +
              ((b.warnings || []).length ? " (with warnings)" : "");
            dryNote.className = "drynote ok";
          }
        } else {
          dryNote.textContent = "✗ " + (b.detail || b.title || res.status);
          dryNote.className = "drynote bad";
          showFieldErrors(b);
        }
      }, 120);
    });
  }

  function body() {
    const values = input ? collectValues(form, input) : {};
    if (bulkIds) values.ids = bulkIds;
    return Object.keys(values).length ? JSON.stringify(values) : null;
  }
  function actHeaders() {
    const h = {};
    if (safety.fence && (doc.meta || {}).etag) h["If-Match"] = doc.meta.etag;
    if (idemKey) h["Idempotency-Key"] = idemKey;
    if (acknowledged.length) h["Waymark-Acknowledge"] = acknowledged.join(",");
    return h;
  }
  function clearServerErrors() {
    for (const node of dlg.querySelectorAll("[data-srverr]"))
      node.textContent = "";
  }
  function showFieldErrors(problem) {
    for (const [field, msgs] of Object.entries((problem || {}).errors || {})) {
      const slot = dlg.querySelector('[data-srverr="' + field + '"]');
      const text = Array.isArray(msgs) ? msgs.join("; ") : String(msgs);
      if (slot) slot.textContent = text;
      else errBox.append(el("div", {class: "problem"}, field + ": " + text));
    }
  }
  function showErrors(problem) {
    errBox.replaceChildren();
    clearServerErrors();
    showFieldErrors(problem);
    if (!Object.keys((problem || {}).errors || {}).length)
      errBox.append(problemBox(problem || {}));
  }
  async function check() {           /* dry-run pre-validation (rule 5):
                                        the FULL rehearsal — every field,
                                        every guard */
    clearTimeout(dryTimer);          /* one door at a time — the blur
                                        judge stands down */
    const sep = entry.href.includes("?") ? "&" : "?";
    const res = await api(entry.href + sep + "dry_run=1",
      {method: "POST", body: body(), headers: actHeaders()});
    errBox.replaceChildren();
    if (res.ok) {
      const b = res.body || {};
      const warns = b.warnings || [];
      const iffy = (b.verdicts || []).filter(v => v.verdict !== "ok");
      if (b.valid === false) {
        /* a bulk rehearsal's refusing rows, each with the guard's
           own sentence */
        for (const v of iffy)
          errBox.append(el("div", {class: "problem"},
            (v.self ? v.self + ": " : "") + (v.reason || v.verdict)));
      } else {
        errBox.append(el("div", {class: "validok"},
          "✓ schema and guards accept this input" +
          (warns.length ? " (with warnings)" : "")));
        for (const w of warns)
          errBox.append(el("div", {class: "warnbox"},
            el("span", {class:"prose"}, w.reason || w.name),
            remedyChips(w.remedies, doc, () => closeDlg())));
      }
    } else showErrors(res.body);
  }
  async function submit() {
    clearTimeout(dryTimer);          /* a pending blur judge must not
                                        speak over the landing */
    disarmDraft();                   /* the invoke consumes the draft */
    const res = await api(entry.href,
      {method: "POST", body: body(), headers: actHeaders()});
    if (res.ok) {
      closeDlg();
      if ((res.body || {}).kind === "bulk_report") reportDialog(res.body);
      else maybeUndoToast(name, doc, res.body || {});
      onDone && onDone(res.body);
      return;
    }
    disarmed = false;
    const problem = res.body || {};
    /* the acknowledge protocol (guard names ride kebab on wire 10):
       warnings are a dialog, not a dead end — the problem body names
       its own header and names, so nothing is hardcoded */
    if (res.status === 409 && problem.acknowledge && problem.acknowledge.names) {
      errBox.replaceChildren(el("div", {class: "warnbox"},
        el("b", {}, "The server warns:"),
        el("ul", {}, (problem.warnings || []).map(w =>
          el("li", {}, (w.name ? w.name + ": " : "") + (w.reason || ""),
             remedyChips(w.remedies, doc, () => closeDlg())))),
        el("div", {class: "actions"},
          el("button", {class: "primary", onclick: () => {
            acknowledged = problem.acknowledge.names;
            submit();            /* same key: the retry is the same attempt */
          }}, "Acknowledge and retry"))));
      return;
    }
    if (res.status === 412) {
      errBox.replaceChildren(el("div", {class: "problem"},
        (problem.detail || "The resource changed since you read it."), " ",
        el("button", {onclick: async () => {
          const fresh = await api(doc.self);
          if (fresh.ok) { doc = fresh.body; errBox.replaceChildren(
            el("div", {class: "validok"}, "re-read — try again")); }
        }}, "Re-read")));
      return;
    }
    showErrors(problem);
  }

  document.body.append(dlg);
  dlg.addEventListener("close", () => dlg.remove());
  dlg.showModal();
}

/* ── the bulk report: N inputs → N verdicts, honestly partial ──────── */
function reportDialog(report) {
  const d = report.data || {};
  const dlg = el("dialog", {"data-report": ""},
    el("div", {class: "dlghead"},
      el("h3", {}, "Bulk report · " + pretty(report.action || ""))),
    el("div", {class: "dlgbody"},
      el("div", {class: "verdict-totals"},
        `succeeded ${d.succeeded ?? 0} · refused ${d.refused ?? 0} · `
        + `failed ${d.failed ?? 0}`),
      (d.refusals || []).length
        ? el("table", {class: "verdicts"},
            el("thead", {}, el("tr", {},
              el("th", {}, "row"), el("th", {}, "outcome"),
              el("th", {}, "reason"))),
            el("tbody", {}, d.refusals.map(r =>
              el("tr", {},
                el("td", {class:"mono"},
                  el("a", {href: "#" + r.self,
                           onclick: () => { dlg.close(); dlg.remove(); }},
                    String(r.self || "").split("/").pop().slice(0, 8))),
                el("td", {}, el("span", {class:"verdict-refused"}, "refused")),
                el("td", {class:"reason"}, r.reason || "")))))
        : el("p", {class: "validok"}, "every row succeeded")),
    el("div", {class: "dlgfoot"},
      el("button", {onclick: () => { dlg.close(); dlg.remove(); }}, "Close")));
  document.body.append(dlg);
  dlg.showModal();
}

/* ── undo: an inverse action present in the post-action document ───── */
async function invokeBare(entry, doc) {
  const h = {};
  if (entry.safety?.fence && doc.meta?.etag) h["If-Match"] = doc.meta.etag;
  if (entry.safety && entry.safety.idempotent === false)
    h["Idempotency-Key"] = uuid();
  return api(entry.href, {method: entry.method || "POST",
                          body: JSON.stringify({}), headers: h});
}
function maybeUndoToast(name, before, after) {
  const t = $("#toast");
  t.textContent = "";
  t.append(`${pretty(name)} ✓`);
  const backTo = before.state;
  const inverse = Object.entries(after.actions || {})
    .find(([, a]) => a.effect?.to === backTo);
  if (inverse && backTo && after.state !== backTo) {
    const [invName, invEntry] = inverse;
    t.append(el("button", {"data-undo": invName, onclick: async () => {
      await invokeBare(invEntry, after);
      t.style.display = "none";
      render();
    }}, `undo (${label(invName, invEntry)})`));
  }
  t.style.display = "block";
  setTimeout(() => t.style.display = "none", 6000);
}

function problemBox(p) {
  p = p || {};
  const box = el("div", {class:"problem"},
    el("b", {}, p.title || "Error"), " — ",
    el("span", {class:"detail"}, p.detail || ""));
  if (p.errors) box.append(el("ul", {},
    Object.entries(p.errors).map(([f, msgs]) =>
      el("li", {}, `${f}: ${Array.isArray(msgs) ? msgs.join("; ") : msgs}`))));
  if (p.resource && p.resource.self)
    box.append(el("div", {style:"margin-top:4px"},
      el("a", {href:"#"+p.resource.self}, "view current state")));
  return box;
}

