/* ── resource screen ───────────────────────────────────────────────── */
function label(name, entry) { return entry.display?.label || title(name); }

async function renderResource(view, doc, hints) {
  hints = hints || {};
  /* the ref labels this envelope carries, before any cell asks */
  noteRefs(doc);
  const panel = el("div", {class:"panel"});
  const kind = doc.kind;
  const colHref = doc.self.split("/").slice(0, 3).join("/");
  panel.append(el("div", {class:"crumbs"},
    el("a", {href:"#"}, "Workspace"), " / ",
    el("a", {href:"#"+colHref}, title(kind) + "s"), " / ",
    el("span", {class:"id", title: doc.self},
      doc.self.split("/").pop().slice(0, 8))));
  panel.append(el("h2", {class:"prose"}, doc.summary || title(kind)));
  const meta = doc.meta || {};
  panel.append(el("div", {},
    el("span", {class:"statechip", title: kind}, doc.state),
    el("span", {class:"version"},
      `v${meta.version}` +
      (meta.law_revision != null ? ` · law r${meta.law_revision}` : "") +
      (meta.updated_at ? ` · ${localStamp(meta.updated_at, {seconds: true})}` : "")),
    el("span", {class:"version", title: doc.self}, doc.self)));
  /* the viewing dots: who else is looking at this screen right now
     (painted from the known truth on mount — a presence that arrived
     before this screen rendered still shows — then repainted as
     frames arrive) */
  panel.append(el("div", {"data-presence": ""}));
  setTimeout(paintPresence, 0);   /* after this panel mounts */

  /* the read surface up front: declared links (badges riding) and the
     surfaces this row anchors — scent before the scroll */
  const chips = linksStrip(doc);
  if (chips) panel.append(chips);
  const surfBox = el("div", {});
  panel.append(surfBox);
  surfaceChips(doc).then(c => { if (c) surfBox.append(c); }).catch(() => {});

  /* buttons = actions, ordered by display.order. Parts are a
     REFINEMENT, not a replacement (the v10 envelope doctrine): a
     placed action renders here with its full schema AND re-renders
     per item below with the scope key const-bound. */
  /* suggested actions (co-presence): remedies on this doc's refusals
     may name a same-kind action that IS here — the way out gains a
     ring; nothing is demoted */
  const suggested = new Set();
  for (const entry of Object.values(doc.unavailable || {}))
    for (const t of entry.remedies || []) {
      const dot = String(t).indexOf(".");
      if (dot > 0 && t.slice(0, dot) === kind && (doc.actions || {})[t.slice(dot + 1)])
        suggested.add(t.slice(dot + 1));
    }
  const bar = el("div", {class:"actions"});
  const entries = Object.entries(doc.actions || {})
    .sort(([a, ea], [b, eb]) =>
      ((ea.display || {}).order ?? 99) - ((eb.display || {}).order ?? 99) ||
      a.localeCompare(b));
  for (const [name, entry] of entries) {
    const btn = actionButton({name, entry, doc, onDone: out => {
      /* approving an ask ANYWHERE follows its requester — the agent
         hands its human a link straight to the ask envelope, so the
         hand-off must not depend on the Access panel */
      const decision = ((wellKnownNow || {}).resources || {})[kind]?.decision;
      if (decision && decision.by && name === "approve")
        followRequester(doc.data?.[decision.by]);
      if (out && out.self && out.self !== doc.self) go(out.self);
      else render();
    }});
    if (suggested.has(name)) {
      btn.classList.add("suggested");
      btn.append(el("span", {class:"suggest-chip",
        title: "a refusal on this page names this action as the remedy"},
        "suggested"));
    }
    bar.append(btn);
  }
  /* refused actions keep their seats in the bar, dimmed; their honest
     reasons render just below, at the point of intent */
  const {blocked, grouped, gated} = splitRefusals(doc);
  /* one that names a way out is a tap away from a quest (shutDoor) */
  for (const [name, entry] of blocked.concat(gated, reachGrouped(grouped, doc)))
    bar.append(shutDoor(name, entry, doc));
  /* the grant scope (wire 10): a grant row is a scope SELECTOR its
     audience presents via X-Waymark-Grant — this session can put it on */
  if (kind === "grant") {
    const gid = doc.self.split("/").pop();
    const on = localStorage.getItem("wm10.grant") === gid;
    bar.append(el("button", {
      title: on ? "stop reading through this grant"
                : "send X-Waymark-Grant with this grant's id on every request "
                  + "— the principal must be the grant's audience",
      onclick: () => {
        if (on) localStorage.removeItem("wm10.grant");
        else localStorage.setItem("wm10.grant", gid);
        grantChip(); render();
      }}, on ? "✓ scoped to this grant" : "🔭 act under this grant"));
    /* the grant names its audience — the principal working under it.
       Follow them from the page where the trust was granted, and this
       screen goes where they look (the member page's affordance,
       offered at the moment you'd want it) */
    const aud = doc.data?.audience;
    if (aud) bar.append(el("button", {
      title: `follow ${aud} — this screen navigates where they look`,
      onclick: () => { follow({id: aud, display: aud});
                       toast(`following ${aud}`); }},
      `👁 Follow ${aud}`));
  }
  /* a sealed walk plays on this screen, read-only: its export is the
     one read the replay makes (200-events-follow.js) */
  if (kind === "walk" && doc.state === "sealed")
    bar.append(el("button", {"data-replay-walk": "",
      title: "play this recording on this screen, read-only — nothing is written",
      onclick: () => replayWalk(doc.self)}, "▶ Replay"));
  /* …and leaves as a file: the download is the only copy that outlives
     a demo engine (docs/spec-demo-clones.md §3) */
  if (kind === "walk" && doc.state === "sealed")
    bar.append(el("button", {"data-export-walk": "",
      title: "download this recording as a .ndjson file",
      onclick: () => exportWalk(doc.self)}, "⬇ Export"));
  /* a walkthrough's row page lists every step's note in order: the
     person sees the whole path before they agree to it
     (docs/spec-walkthrough.md §5; 200-events-follow.js) */
  if (kind === "walkthrough") panel.append(walkthroughSteps(doc));
  /* a quest's row page shows its plan as a checklist, with Go on the
     step to take now (docs/spec-quests.md; 200-events-follow.js) */
  if (kind === "quest") panel.append(questPlan(doc));
  /* an open invitation addressed to this viewer: one tap lands on the
     invited row with its door open in their own hand */
  if (kind === "invitation" && doc.state === "open" &&
      doc.data?.subject && doc.data.subject === viewerId())
    bar.prepend(el("button", {class: "primary", "data-invite-open": "",
      onclick: () => openInvitation(doc)}, "Take this step"));
  /* the follow affordance: a member envelope names a principal —
     follow them and this screen goes where they LOOK (the presence
     stream) as well as where they write (the firehose) */
  if (kind === "member") {
    const mid = doc.data?.subject || doc.self.split("/").pop();
    const mname = doc.data?.display || mid;
    bar.append(el("button", {
      title: `follow ${mname} — this screen navigates where they look`,
      onclick: () => { follow({id: mid, display: mname});
                       toast(`following ${mname}`); }},
      "👁 Follow"));
  }
  /* an uploaded attachment: offer its bytes back (the bytes route is
     the engine's static /api/attachments/{id}/bytes) */
  if (kind === "attachment" && doc.state === "uploaded") {
    const id = doc.self.split("/").pop();
    if (isViewable(doc.data?.media_type))
      bar.append(el("button", {title:"open in a new tab",
        onclick: () => viewAttachment(id)}, "View"));
    bar.append(el("button", {class:"primary", title:"save the file",
      onclick: () => downloadAttachment(id, doc.data?.name)}, "↓ Download"));
  }
  panel.append(bar);
  const notes = blockedNotes(blocked, doc);
  if (notes) panel.append(notes);

  /* the data document: fields a parts group re-renders (with buttons)
     leave the kv table */
  const partPaths = new Set(Object.keys(doc.parts || {}));
  const plainData = Object.fromEntries(
    Object.entries(doc.data || {}).filter(([k]) => !partPaths.has(k)));
  /* a worksheet row: the report IS the page — the staged lines and
     the raw report leave the kv table for their own panel */
  if (kind === "worksheet")
    for (const k of ["lines", "report", "tally"]) delete plainData[k];
  view.append(panel);
  if (kind === "worksheet")
    view.append(el("div", {class:"panel"},
      el("h3", {}, doc.state === "applied"
        ? "What the apply did"
        : "The plan — what apply would do"),
      worksheetReport(doc)));
  view.append(...partsSections(doc));
  if (kind !== "scheduled_action") view.append(scheduledSection(doc));
  const dataPanel = el("div", {class:"panel"},
    el("details", {open:""},
      el("summary", {class:"muted"}, "Data"),
      kvTable(plainData, hints)));
  for (const sec of await embeddedSections(doc, hints)) dataPanel.append(sec);
  /* the resource's own history: its transition log, replayed from the
     events stream (Last-Event-ID 0), collapsed by default */
  dataPanel.append(historySection(`${doc.self}/-/events`));
  const footer = notNowFooter(grouped, gated, doc);
  if (footer) dataPanel.append(footer);
  view.append(dataPanel);
  wireNotYet(view);
  watchScope({self: doc.self});
  paintPresence();
}

/* ── a row's pending scheduled actions (docs/spec-scheduled-actions.md
   R-7.3): the calls waiting on this row, each with its reschedule and
   cancel doors. The kind filters by state and by scheduler, not by
   target, so the page reads the viewer's pending rows — the own
   surface answers them, and one scheduler holds at most 100 — and
   keeps the ones aimed at this row. The panel stays hidden while there
   are none, and for a viewer the collection does not answer. ──────── */
const VALIDITY_WORDS = {strict: "only if nothing about it changes",
                        state: "as long as its state holds",
                        conditions: "only if its conditions hold"};
function scheduledSection(doc) {
  const box = el("div", {class: "panel", "data-scheduled": "",
                         style: "display:none"});
  const id = doc.self.split("?")[0].split("/").pop();
  api("/api/scheduled_actions?state=scheduled&page%5Bsize%5D=100").then(res => {
    const mine = ((res.ok && (res.body || {}).items) || []).filter(item => {
      const t = (item.fields || {}).target || {};
      return t.kind === doc.kind && t.id === id;
    });
    if (!mine.length) return;
    box.append(el("h3", {}, "Scheduled"));
    for (const item of mine) {
      const f = item.fields || {}, acts = item.actions || {};
      const conds = Object.entries(f.conditions || {})
        .map(([k, v]) => `${k}=${v}`).join(", ");
      /* the time as this viewer's clock reads it, the way the picker
         that wrote it did */
      const at = localStamp(f.run_at);
      box.append(el("div", {class: "actions", "data-scheduled-row": item.self},
        el("a", {href: "#" + item.self}, `${pretty(f.target.action)} · ${at}`),
        el("span", {class: "muted"},
          ` ${VALIDITY_WORDS[f.validity] || f.validity || ""}` +
          (conds ? ` (${conds})` : "")),
        acts.reschedule
          ? el("button", {"data-scheduled-reschedule": "", onclick: () =>
              actionDialog({name: "reschedule", entry: acts.reschedule, doc: item,
                prefill: {run_at: at.replace(" ", "T"),
                          zone: Intl.DateTimeFormat().resolvedOptions().timeZone},
                onDone: () => render()})}, "Reschedule")
          : null,
        acts.cancel
          ? el("button", {class: "danger", "data-scheduled-cancel": "",
              onclick: async () => {
                const res = await invokeBare(acts.cancel, item);
                if (res.ok) { toast("Cancelled"); render(); }
                else box.append(problemBox(res.body));
              }}, "Cancel")
          : null));
    }
    box.style.display = "";
  }).catch(() => {});
  return box;
}

/* ── the surface screen: the composed decision view (wire 10 shape:
   {name, anchor: the full envelope, members, showcase, attention}) ── */
async function renderSurface(view, doc) {
  const anchor = doc.anchor || {};
  const panel = el("div", {class:"panel"});
  panel.append(el("div", {class:"crumbs"},
    el("a", {href:"#"}, "Workspace"),
    anchor.self ? [" / ", el("a", {href:"#"+anchor.self, title: anchor.self},
                             title(anchor.kind || "anchor"))] : null,
    " / ", el("span", {class:"surface-mark", title: doc.self},
      "⧉ " + pretty(doc.name || "surface"))));
  panel.append(el("h2", {}, title(doc.name || "surface")));
  if (anchor.summary)
    panel.append(el("div", {class:"summary"}, anchor.summary));
  const flags = Object.entries(doc.attention || {});
  if (flags.length)
    panel.append(el("div", {class:"chips"}, flags.map(([f, up]) =>
      el("span", {class: "flag " + (up ? "up" : "down"),
                  title: up ? "attention: the declared value holds"
                            : "quiet"}, f + (up ? " ⚑" : " ·")))));
  /* showcased actions first and prominent; the anchor's other actions
     follow — order is the declaration's, not this client's */
  const showcase = new Set(doc.showcase || []);
  const bar = el("div", {class:"actions"});
  for (const name of doc.showcase || []) {
    const entry = (anchor.actions || {})[name];
    if (!entry) continue;
    const btn = actionButton({name, entry, doc: anchor, onDone: () => render()});
    btn.classList.add("primary", "suggested");
    btn.append(el("span", {class:"suggest-chip",
      title: "showcased by this surface's definition"}, "showcased"));
    bar.append(btn);
  }
  for (const [name, entry] of Object.entries(anchor.actions || {}))
    if (!showcase.has(name))
      bar.append(actionButton({name, entry, doc: anchor, onDone: () => render()}));
  const {blocked, grouped, gated} = splitRefusals(anchor);
  for (const [name, entry] of blocked.concat(reachGrouped(grouped, anchor)))
    bar.append(shutDoor(name, entry, anchor));
  panel.append(bar);
  const notes = blockedNotes(blocked, anchor);
  if (notes) panel.append(notes);
  wireNotYet(panel);
  if (anchor.self)
    panel.append(el("div", {class:"embed"},
      el("div", {class:"embed-head"}, el("b", {}, "Anchor"),
        el("a", {class:"open", href:"#"+anchor.self}, "open ↗")),
      el("div", {},
        el("span", {class:"statechip"}, anchor.state), " ",
        el("a", {href:"#"+anchor.self, class:"prose"}, anchor.summary))));
  view.append(panel);
  /* members arrive pre-embedded, in the declared order — each table
     gets its target kind's schema hints (from the items' own :kind),
     so refs render as live-labeled links here exactly as they do on
     the kind's own screens */
  for (const [mname, m] of Object.entries(doc.members || {})) {
    const items = m.items || [];
    const hints = items.length && items[0].kind
      ? await kindSchema(items[0].kind) : {};
    /* a collection member says its truthful count — the queue can
       outrun the items page, and the header should not undersell it */
    view.append(el("div", {},
      el("h3", {class:"sect"},
        title(mname) + (m.count != null ? " · " + m.count : "")),
      el("div", {class:"panel", style:"padding:10px 14px"},
        items.length ? itemTable(items, {hints})
                     : el("p", {class:"muted"}, "Nothing here."))));
  }
  if (anchor.self) {
    const dataPanel = el("div", {class:"panel"});
    kindSchema(anchor.kind || "").then(schema => {
      const partPaths = new Set(Object.keys(anchor.parts || {}));
      const plain = Object.fromEntries(
        Object.entries(anchor.data || {}).filter(([k]) => !partPaths.has(k)));
      dataPanel.append(el("details", {open:""},
        el("summary", {class:"muted"}, "Data"), kvTable(plain, schema)));
      dataPanel.append(historySection(`${anchor.self}/-/events`));
      const footer = notNowFooter(grouped, gated, anchor);
      if (footer) dataPanel.append(footer);
      wireNotYet(view);
    });
    view.append(dataPanel);
    watchScope({self: anchor.self});
  }
}

