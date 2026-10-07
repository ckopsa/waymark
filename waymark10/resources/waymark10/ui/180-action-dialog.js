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

async function actionDialog({name, entry, doc, bulkIds, prefill, onDone,
                             idemKey: callerKey, suggest, given, invitation,
                             guided}) {
  const safety = entry.safety || {};
  const input = entry.input || null;
  /* rule 3 (Part IV): a non-idempotent action gets its key at dialog
     open — one logical attempt, one key, however many retries.
     A CALLER may hand one in instead, and one does: a feed card's
     verb rides feed/origin-key whether it lands in one tap or through
     this form, because a door counted only when it was tapped
     directly would be a metric that flattered the screen
     (waymark-iqa.7). */
  const idemKey = callerKey || (safety.idempotent ? null : uuid());
  let acknowledged = [];
  /* an invitation a walkthrough opened (docs/spec-walkthrough.md §5):
     {id, step, of, title}, and null for one that stands alone */
  const led = (invitation && invitation.walkthrough) || null;

  /* drafts: half-written effort is server state. Wire 10: GET 404s
     until something was saved and answers {values, base_version,
     prefill, revs, authors}; the row's own current values are the
     fallback prefill for an :edit that was never drafted. */
  let draftView = null;
  /* a guided dialog is someone else's: no draft is read or written */
  if (entry.draft && entry.draft.href && !guided) {
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
    /* a door's computed prefill (:edit {:prefill-fn}) outranks the
       row's own projection: the server knows the value the row does
       not hold. Never for a bulk write, as above. */
    (!bulkIds && entry.prefill_values) || {},
    prefill || {},
    /* what the person typed already (a quest's stored input): in the
       form as their own, with no mark */
    given || {},
    /* an invitation's suggestions: in the form, marked, and sent only
       by the person's own submit */
    suggest || {},
    (draftView || {}).prefill || {},
    (draftView || {}).values || {});
  const kind = (doc.kind || "").replace("_collection", "");
  const form = input ? buildForm(input, initialValues, kind) : el("div", {});

  const errBox = el("div", {"data-surface": "refusal"});
  /* "Do this later" (docs/spec-scheduled-actions.md R-7.3): a row's own
     door, in the person's own hand. A bulk write and a create have no
     one row to hold a rule against, and a scheduled action's own doors
     move the row that already is the later. */
  const laterable = !bulkIds && !guided && kind !== "scheduled_action" &&
    /^\/api\/[^/]+\/(?!-\/)[^/]+\/-\/[^/?]+$/.test(entry.href || "");
  const laterBox = el("div", {"data-later-panel": "", style: "display:none"});
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
  const dlg = el("dialog", {"data-surface": "dialog", "data-action": name,
                            "data-self": (doc.self || "").split("?")[0] || null},
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
      /* how far along the walkthrough is, above the form */
      led ? el("p", {class: "walk-step", "data-walk-step": ""},
                 stepLine(led.step, led.of, led.title))
          : null,
      safety.confirm
        ? el("div", {class: "consequence"},
            el("b", {}, "Confirm: "),
            entry.display?.description || "This action requires confirmation.")
        : null,
      form, laterBox, errBox, draftBar),
    el("div", {class: "dlgfoot"},
      el("span", {class: "hint"},
        entry.draft ? (entry.draft.shared ? "shared draft — saved on blur"
                                          : "draft — saved on blur") : ""),
      dryNote,
      entry.draft ? el("button", {"data-surface": "dialog.discard", onclick: async () => {
        disarmDraft();
        await api(entry.draft.href, {method: "DELETE"});
        toast("Draft discarded");
        closeDlg(); render();
      }}, "Discard draft") : null,
      input && dryRunnable(entry.href)
        ? el("button", {"data-surface": "dialog.check", onclick: () => check()},
            "Check")
        : null,
      invitation && ((invitation.doc || {}).actions || {}).decline
        ? el("button", {class: "danger", "data-invite-decline": "",
                        "data-surface": "dialog.decline",
                        onclick: () => declineInvitation()},
            led ? "Skip" : "Decline")
        : null,
      led ? el("button", {"data-walk-stop": "", "data-surface": "dialog.stop",
                          onclick: () => stopLed()}, "Stop")
          : null,
      el("button", {"data-surface": "dialog.cancel", onclick: () => closeDlg()},
         "Cancel"),
      laterable
        ? el("button", {"data-later": "", "data-surface": "dialog.later",
                        onclick: () => openLater()},
            "Do this later")
        : null,
      el("button", {class: safety.confirm ? "danger" : "primary",
                    "data-surface": "dialog.submit", onclick: () => submit()},
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
    const me = viewerId() || "anonymous";
    const others = (participants || []).filter(p => p.id !== me);
    peers.textContent = others.length
      ? ` · editing with ${others.map(p => p.display || p.id).join(", ")}` : "";
  };
  if (entry.draft && entry.draft.shared && !guided) {
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
  /* a nested argument's errors arrive as a map, or as a list with a
     hole for each entry that passed: each message goes to the slot its
     sub-field's widget is named by (shelf.label, items[1].name), which
     is how a recorded refusal already spells it (walks/refusal-errors) */
  function flatFieldErrors(path, v, out) {
    const say = (...msgs) => { (out[path] = out[path] || []).push(...msgs); };
    if (Array.isArray(v)) {
      if (v.every(x => typeof x === "string")) say(...v);
      else v.forEach((x, i) => {
        if (x != null) flatFieldErrors(path + "[" + i + "]", x, out);
      });
    } else if (v && typeof v === "object") {
      for (const [k, x] of Object.entries(v))
        flatFieldErrors(k === "malli/error" ? path : path + "." + k, x, out);
    } else if (v != null) say(String(v));
    return out;
  }
  function showFieldErrors(problem) {
    const flat = {};
    for (const [field, msgs] of Object.entries((problem || {}).errors || {}))
      flatFieldErrors(field, msgs, flat);
    for (const [field, msgs] of Object.entries(flat)) {
      const slot = dlg.querySelector('[data-srverr="' + field + '"]');
      const text = msgs.join("; ");
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
    const offer = questOffer((problem || {}).remedies);
    if (offer) errBox.append(offer);
  }
  /* the other way through a refusal (docs/spec-quests.md): a refusal
     that names a remedy can be kept as a goal instead of walked by
     hand. The button is offered only when the quests collection affords
     create to this reader — read off the wire, as every button is — and
     only for a door on ONE row, which is what a quest's goal is. The
     remedy chips stay as they are. A refusal of the quest's own create
     or pin offers none: a second quest for the same goal is no way
     through it. */
  let questAccepting = false, guidedKept = false;
  function questOffer(remedies) {
    if (!remedies || !remedies.length || bulkIds) return null;
    if (questAccepting) return null;
    if (/_collection$/.test(doc.kind || "") || !doc.self) return null;
    /* someone else's form (guided, below) reads nothing: the button is
       drawn when they kept the refusal as a goal (dlg.guidedRefuse),
       with nothing behind it to press */
    if (guided)
      return !guidedKept ? null
        : el("span", {class: "questoffer", "data-quest-offer": "offered"},
            el("button", {type: "button", class: "primary",
                          "data-quest-accept": "",
                          "data-surface": "dialog.accept"}, "Accept as quest"));
    /* the slot says how the read ended (data-quest-offer), so a refusal
       with no button tells why. A read that failed, and was not refused,
       is tried one more time. */
    const slot = el("span", {class: "questoffer", "data-quest-offer": "reading"});
    const says = s => slot.setAttribute("data-quest-offer", s);
    const read = async () => {
      const w = await wellKnown();
      const col = collectionHref(w, "quest");
      if (!col) return says("no-collection");
      const res = await api(col + "?page%5Bsize%5D=1");
      if (!res.ok && (res.status === 0 || res.status >= 500))
        throw new Error("quests read " + res.status);
      const create = res.ok && ((res.body || {}).actions || {}).create;
      says(create ? "offered" : res.ok ? "no-create" : "refused " + res.status);
      if (!create) return;
      slot.append(el("button", {type: "button", class: "primary",
        "data-quest-accept": "", "data-surface": "dialog.accept",
        title: "Keep this as a goal: the engine plans the steps to it",
        onclick: () => acceptQuest(create)}, "Accept as quest"));
    };
    read().catch(() => new Promise(r => setTimeout(r, 400)).then(read))
          .catch(e => says("failed: " + ((e && e.message) || e)));
    return slot;
  }
  /* one click: the goal is this row, this door and what the form
     holds; then the quest is pinned, the dialog closes on the row it
     was opened from and the tracker shows the quest (120-nav-home.js).
     A refused create or pin is shown as any refusal is, without the
     offer. */
  async function acceptQuest(create) {
    questAccepting = true;
    try { await makeQuest(create); }
    finally { questAccepting = false; }
  }
  async function makeQuest(create) {
    const goal = {self: doc.self.split("?")[0], action: name};
    const values = input ? collectValues(form, input) : {};
    if (Object.keys(values).length) goal.input = values;
    const h = {};
    if (create.safety && create.safety.idempotent === false)
      h["Idempotency-Key"] = uuid();
    const made = await api(create.href,
      {method: create.method || "POST", body: JSON.stringify(goal), headers: h});
    if (!made.ok) { showErrors(made.body); return; }
    const quest = made.body || {};
    const pin = (quest.actions || {}).pin;
    if (pin && !(quest.data || {}).pinned) {
      const pinned = await invokeBare(pin, quest);
      if (!pinned.ok) { showErrors(pinned.body); return; }
    }
    disarmDraft();
    closeDlg();
    await refreshQuest();
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
            remedyChips(w.remedies, doc, () => closeDlg()),
            questOffer(w.remedies)));
      }
    } else showErrors(res.body);
  }
  /* decline is one door, the invitation's own: no form between */
  async function declineInvitation() {
    const res = await invokeBare(invitation.doc.actions.decline, invitation.doc);
    if (!res.ok) { showErrors(res.body); return; }
    disarmDraft();
    closeDlg();
    toast(led ? "Skipped" : "Declined");
    onDone && onDone(res.body);
  }
  /* Stop pauses the whole walkthrough, through its own stop door; the
     engine takes the open invitation back */
  async function stopLed() {
    const row = await api("/api/walkthroughs/" + encodeURIComponent(led.id));
    const stop = row.ok && (row.body.actions || {}).stop;
    if (!stop) {
      toast("Stop is not open to you on this walkthrough right now");
      return;
    }
    const res = await invokeBare(stop, row.body);
    if (!res.ok) { showErrors(res.body); return; }
    disarmDraft();
    closeDlg();
    toast("Stopped");
    onDone && onDone(res.body);
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
             remedyChips(w.remedies, doc, () => closeDlg()),
             questOffer(w.remedies)))),
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

  /* ── do this later (docs/spec-scheduled-actions.md R-7.3): the same
     call, written as a scheduled_action and made at its time. The
     picker speaks the browser's zone and the panel names it; the rule
     the run is held to is chosen in plain words; and on a confirm door
     the panel shows the sentence, because the tap that schedules is
     the acknowledgment. ─────────────────────────────────────────────── */
  const laterZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const laterConds = {};
  /* the sentence as waymark10.confirm/consequence-of reads it */
  const consequence = entry.display?.description || entry.display?.label ||
    "This action requires confirmation.";
  function paintLaterConds(box) {
    box.replaceChildren(...Object.entries(laterConds).map(([k, v]) =>
      el("span", {class: "chip on", "data-later-cond": k}, `${k}=${v} `,
        el("span", {title: "remove", onclick: () => {
          delete laterConds[k]; paintLaterConds(box);
        }}, "×"))));
  }
  async function openLater() {
    if (laterBox.firstChild) {
      laterBox.style.display =
        laterBox.style.display === "none" ? "block" : "none";
      return;
    }
    const p = n => String(n).padStart(2, "0");
    const t = new Date(Date.now() + 86400000);
    const at = el("input", {type: "datetime-local", "data-later-at": "",
      value: `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())}T08:30`});
    const condChips = el("div", {});
    const condBox = el("div", {"data-later-conds": "", style: "display:none"},
      condChips);
    let condsBuilt = false;
    /* *Only if…*: the collection's own filter control over the kind's
       query input, so a condition is spelled the way a filter is */
    const showConds = async on => {
      condBox.style.display = on ? "block" : "none";
      if (!on || condsBuilt) return;
      condsBuilt = true;
      const col = await api(doc.self.split("?")[0].split("/").slice(0, 3).join("/") +
                            "?page%5Bsize%5D=1");
      const query = (((col.body || {}).actions || {}).query || {}).input;
      const fp = query && filterPopover(query, new URLSearchParams(), updates => {
        for (const [k, v] of Object.entries(updates))
          if (v !== "") laterConds[k] = v;
        paintLaterConds(condChips);
      });
      condBox.append(fp || el("span", {class: "muted"},
        `${pretty(kind)} declares no field a condition can read`));
    };
    const rule = (value, text) => {
      const radio = el("input", {type: "radio", name: "later_validity", value});
      radio.checked = value === "state";
      radio.addEventListener("change", () => showConds(value === "conditions"));
      return el("label", {style: "display:block"}, radio, " ", text);
    };
    laterBox.append(
      el("p", {}, el("b", {}, "Do this later: "), at, " ",
        el("span", {class: "muted", "data-later-zone": ""}, laterZone)),
      rule("strict", "Only if nothing about it changes"),
      rule("state", "As long as it is still " + pretty(doc.state || "as it is")),
      rule("conditions", "Only if…"),
      condBox,
      safety.confirm
        ? el("div", {class: "consequence"},
            el("b", {}, "Scheduling confirms: "), consequence)
        : null,
      el("div", {class: "actions"},
        el("button", {class: "primary", "data-later-go": "",
                      onclick: () => schedule()}, "Schedule")));
    laterBox.style.display = "block";
  }
  async function schedule() {
    clearTimeout(dryTimer);          /* as on submit: the blur judge
                                        stands down */
    const at = laterBox.querySelector("[data-later-at]").value;
    if (!at) {
      showErrors({title: "No time", detail: "Choose the time this runs at."});
      return;
    }
    const validity =
      laterBox.querySelector("input[name=later_validity]:checked")?.value || "state";
    /* the same target and input the submit would send */
    const call = {
      target: {kind, action: name, id: doc.self.split("?")[0].split("/").pop()},
      input: input ? collectValues(form, input) : {},
      run_at: at, zone: laterZone, validity};
    if (validity === "conditions") call.conditions = {...laterConds};
    if (safety.confirm) call.acknowledge = consequence;
    const btn = laterBox.querySelector("[data-later-go]");
    btn.disabled = true;             /* one tap, one scheduled action */
    const res = await api("/api/scheduled_actions",
      {method: "POST", body: JSON.stringify(call),
       headers: {"Idempotency-Key": uuid()}});
    btn.disabled = false;
    if (!res.ok) { showErrors(res.body); return; }
    closeDlg();
    toast(`${pretty(name)} scheduled for ${at.replace("T", " ")} (${laterZone})`);
    render();
  }

  /* an invitation (docs/spec-guided-follow.md §3): the suggested
     values wear their mark until the person types over them; the
     invited field scrolls into view, lit, with the author's note
     beside it */
  for (const k of Object.keys(suggest || {})) {
    const node = form.querySelector(`[name="${CSS.escape(k)}"]`);
    if (!node) continue;
    node.classList.add("suggested-value");
    node.title = "suggested — yours to change";
    node.addEventListener("input",
      () => node.classList.remove("suggested-value"), {once: true});
  }
  /* guided follow (docs/spec-guided-follow.md §2): someone else's
     dialog on this screen, read-only — every input disabled, only
     Cancel left in the footer, the reporter named above the form.
     Their typing lands through dlg.guidedSet. */
  if (guided) {
    dlg.setAttribute("data-guided", guided.key);
    for (const n of form.querySelectorAll("input, select, textarea, button"))
      n.disabled = true;
    /* the button that writes is the footer's last */
    const foot = dlg.querySelector(".dlgfoot"), write = foot.lastElementChild;
    for (const b of dlg.querySelectorAll(".dlgfoot button"))
      if (b.textContent !== "Cancel") b.remove();
    /* the moment of their write: that button drawn again, lit as an
       invited field is, with nothing behind it to press. A replayed
       write shows it before its form closes (200-events-follow.js). */
    dlg.guidedWrite = () => {
      const lit = el("button", {class: write.className + " invited",
                               type: "button", "data-guided-write": "",
                               "data-surface": "dialog.submit"},
        write.textContent);
      foot.append(lit);
      return lit;
    };
    /* the refusal their write got, as a self walk recorded it (a
       `refusal` frame, 200-events-follow.js): drawn by the code that
       draws this person's own (showErrors, above), with "Accept as
       quest" under it when `kept` says they kept it as a goal */
    dlg.guidedRefuse = (problem, kept) => {
      guidedKept = !!kept;
      showErrors(problem);
    };
    /* a refusal they kept as a goal (questOffer, above), in a walk
       recorded before a walk held its refusals: a replay has that
       button drawn here, unlit, with nothing behind it to press, for
       its pointer (200-events-follow.js) */
    dlg.guidedAccept = () => {
      const kept = el("button", {class: "primary", type: "button",
                                 "data-quest-accept": "",
                                 "data-surface": "dialog.accept"},
                      "Accept as quest");
      foot.append(kept);
      return kept;
    };
    form.prepend(el("p", {class: "guided-note", "data-guided-note": ""},
      guided.note || `${guided.name} is filling this in`));
    /* a note beside the fields it names, as an invitation's: a replayed
       caption's anchor (docs/spec-agent-demo-walks.md §3) */
    dlg.guidedMark = (names, text) => markInvited(form, names, text);
    /* the field a staged call is typing, lit alone */
    dlg.guidedLight = name => markTyped(form, name);
    /* a replayed typing beat's field, for the pointer to go to
       (200-events-follow.js): the field, or its label when the field
       itself is not drawn */
    dlg.guidedField = name => {
      const node = name ? form.querySelector(`[name="${CSS.escape(name)}"]`) : null;
      if (!node) return null;
      return node.getClientRects().length ? node
        : node.closest("label") || node.parentElement;
    };
    /* the pointer's click on it: that field alone wears the ring a
       focused field does (030-screens.css), since a disabled field
       takes no focus */
    dlg.guidedClick = name => {
      for (const n of form.querySelectorAll("[data-replay-click]"))
        n.removeAttribute("data-replay-click");
      const spot = dlg.guidedField(name);
      if (spot) spot.setAttribute("data-replay-click", "");
    };
    /* a replay hands `labelOf` (200-events-follow.js, guidedLabel): a
       ref field then reads as a live picker names its row. A replay
       fetches no collection, so a select is seated with that one entry,
       [id, label], and with the id alone when no label is known. */
    dlg.guidedSet = (fields, labelOf) => {
      for (const [k, v] of Object.entries(fields || {})) {
        const node = form.querySelector(`[name="${CSS.escape(k)}"]`);
        if (!node) continue;
        const named = labelOf ? (Array.isArray(v) ? v : [v])
          .map((id, i) => labelOf(k, id, Array.isArray(v) ? i : undefined)) : [];
        if (node.type === "checkbox") node.checked = !!v;
        else if (labelOf && node.tagName === "SELECT" && typeof v === "string" && v) {
          let seat = [...node.options].find(o => o.value === v);
          if (!seat) node.append(seat = el("option", {value: v}, named[0] || v));
          else if (named[0] && seat.textContent === v) seat.textContent = named[0];
          node.value = v;
        }
        else if (named.some(Boolean))
          node.value = named.map((l, i) => l || String(Array.isArray(v) ? v[i] : v)).join(", ");
        else node.value = v == null ? ""
          : typeof v === "object" ? (v.elided ? "…" : JSON.stringify(v))
          : String(v);
      }
    };
    /* closed by this person's own hand: not reopened for the same step */
    dlg.addEventListener("close", () => {
      if (!dlg.dataset.guidedAuto && guided.onDismiss) guided.onDismiss();
    });
  } else if (!bulkIds) {
    /* share my screen: this dialog, and its values as typed (debounced
       150 ms, secrets removed), cleared when it closes */
    const shareFields = () => {
      try { return input ? shareableValues(collectValues(form, input), input) : {}; }
      catch (_e) { return {}; }
    };
    let shareTimer = null;
    form.addEventListener("input", () => {
      clearTimeout(shareTimer);
      shareTimer = setTimeout(() => shareUi({fields: shareFields()}), 150);
    });
    dlg.addEventListener("close", () => {
      clearTimeout(shareTimer);
      shareUi({dialog: null, fields: null});
    });
    shareUi({dialog: {self: doc.self, action: name}, fields: shareFields()});
  }
  document.body.append(dlg);
  dlg.addEventListener("close", () => dlg.remove());
  dlg.showModal();
  if (invitation)
    markInvited(form, invitation.fields
      || (invitation.field ? [invitation.field] : []), invitation.note);
  /* a walkthrough's step says how far along it is, above the form */
  if (invitation && invitation.step && invitation.of)
    form.prepend(el("p", {class: "invite-step", "data-invite-step": ""},
      `Step ${invitation.step} of ${invitation.of}`));
}
/* a note and the fields it points at: an invitation's note in the
   invited person's dialog, and a replayed caption's beside its field
   (docs/spec-agent-demo-walks.md §3). Every named field is lit, in the
   author's reading order; one this person's own form does not show is
   passed over without a message. → the note */
function markInvited(form, names, text) {
  const lit = names.map(f => {
    const node = form.querySelector(`[name="${CSS.escape(f)}"]`);
    return node && {node, spot: node.closest("label") || node.parentElement};
  }).filter(l => l && l.spot);
  const node = lit.length ? lit[0].node : null;
  const spot = lit.length ? lit[0].spot : null;
  const note = el("p", {class: "invite-note", "data-invite-note": ""},
    text || "");
  /* the note sits beside the first, once */
  if (spot) spot.after(note);
  else form.prepend(note);
  lit.forEach((l, i) => {
    l.spot.classList.add("invited");
    /* the ordinal the note's "then" points at */
    if (i > 0) l.spot.prepend(el("span",
      {class: "invite-ordinal", "data-invite-ordinal": ""}, String(i + 1)));
  });
  requestAnimationFrame(() => {
    (spot || note).scrollIntoView({behavior: "smooth", block: "center"});
    if (node) node.focus({preventScroll: true});
  });
  return note;
}
/* the field a staged call is typing (docs/spec-agent-demo-walks.md §2):
   lit as an invited field is, and only that one, with no note. The
   field typed before it goes dark, unless a note still points at it. */
function markTyped(form, name) {
  for (const s of form.querySelectorAll("[data-typed]")) {
    s.removeAttribute("data-typed");
    const next = s.nextElementSibling;
    if (!next || !next.hasAttribute("data-invite-note"))
      s.classList.remove("invited");
  }
  const node = name ? form.querySelector(`[name="${CSS.escape(name)}"]`) : null;
  const spot = node && (node.closest("label") || node.parentElement);
  if (!spot) return;
  spot.classList.add("invited");
  spot.setAttribute("data-typed", "");
  spot.scrollIntoView({behavior: "smooth", block: "center"});
}

/* ── the bulk report: N inputs → N verdicts, honestly partial ──────── */
function reportDialog(report) {
  const d = report.data || {};
  const dlg = el("dialog", {"data-report": "", "data-surface": "report"},
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
      el("button", {"data-surface": "report.close",
                    onclick: () => { dlg.close(); dlg.remove(); }}, "Close")));
  document.body.append(dlg);
  dlg.showModal();
}

/* ── a door on a row, opened in the person's own hand: the screen goes
   to the row, and the door's dialog opens with the named fields lit and
   the note beside them. An invitation and a quest's Go (120-nav-home.js)
   both come through here; `invitation` is what only an invitation adds,
   and `what` names the row in the sentence that says it was not read.
   A door taken here may be a step of the quest in hand, so the tracker
   reads its quest again after it (questAfterGo) ── */
async function openDoor({self, action, fields, note, suggest, given, invitation}, what) {
  const res = await api(self);
  if (!res.ok) {
    toast(`${what} cannot be read: ${(res.body || {}).detail || res.status}`);
    return;
  }
  const target = res.body;
  const entry = (target.actions || {})[action];
  /* a door addressed to this person opens in their own hand, over any
     guided dialog (#613) */
  closeGuided();
  go(target.self);
  if (!entry) {
    toast(`${pretty(action)} is not open to you on this row right now`);
    return;
  }
  actionDialog({name: action, entry, doc: target, suggest: suggest || {},
                given: given || {},
                invitation: {note, fields: fields || [], ...(invitation || {})},
                onDone: () => { render(); questAfterGo().catch(() => {}); }});
}
/* ── an invitation, opened in the person's own hand: the invited row,
   its door's dialog with the inputs live, and the engine answering the
   invitation when the person submits — the page does nothing extra ── */
async function openInvitation(inv) {
  const d = inv.data || {};
  return openDoor({self: d.self, action: d.action, note: d.note,
                   suggest: d.suggest || {},
                   /* a quest's stored input: the person's own values */
                   given: d.given || {},
                   /* a row born before `fields` holds `field` alone */
                   fields: d.fields || (d.field ? [d.field] : []),
                   invitation: {doc: inv,
                                walkthrough: d.walkthrough ? ledStep(d) : null}},
                  "The invited row");
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
/* The acknowledgement, and the hand-off to the stack (waymark-qmo6).

   This function used to BE the undo: one entry, six seconds, and it
   hid itself whether the undo succeeded or was refused. Two things
   were wrong with that and both are the same thing — it was a page
   holding an opinion. Six seconds is not the window the engine keeps,
   and a refusal that vanishes is a sentence the house said and nobody
   read.

   So the toast goes back to being a receipt, and the way back moves to
   `185-undo-stack.js`, which holds the last few taps THIS page made
   and asks the engine, entry by entry, whether each is still takeable
   back. Every call site is unchanged: the card surface, the deck's
   swipe and the feed's chips all funnel through here, so all three get
   the stack without knowing it exists. */
function maybeUndoToast(name, before, after) {
  toast(`${pretty(name)} ✓`);
  recordUndoable(name, before, after || {});
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

