/* ── the day: home is the day, planned or executed ────────────────────
   waymark-i89n.8. The feed document grows one key, `day`, and this
   file is what the page does with it: a header ABOVE the census that
   forks on `day.mode`. In EXECUTE mode it is the current block — its
   context's name, its stance, its decisions in the order the person
   set them, each with ONE chip built from the decision's own projected
   primary verb — and a one-line timeline of the day's blocks. In PLAN
   mode it is the shape's default blocks and the plan's create door,
   rendered through the ordinary action dialog with the date prefilled.

   HOME (#) IS THE DAY. render() lands here when the hash is empty:
   the feed document is the landing when the feed's door answers, and
   the dashboard — kept at #dashboard, behind ⋯ — when it does not.

   THREE THINGS THIS FILE DOES NOT DO. It invents no affordance: every
   chip is an action the document projected for this reader, and a
   decision whose verb was withheld shows its text and no chip. It
   names no kind and no door: the primary verb is whichever action
   the declaration styled primary, the shape toggle is the create
   form's own enum wearing its own choices, and the plan's verbs are
   whatever `day.plan.actions` carries. And it counts nothing: the
   header's rows are not feed cards, so the view beacon
   (135-feed-screen.js) never observes them.

   A DOCUMENT WITHOUT `day` IS TODAY'S FEED. The key is read in both
   spellings — the date string the document carried before this slice,
   and the object it carries now — and a missing or unrecognised value
   renders the feed exactly as it was. */

/* the feed's day, both spellings: a string is the date alone, an
   object is the day plan with its date inside it */
function feedDayDate(doc) {
  const d = (doc || {}).day;
  return typeof d === "string" ? d : ((d || {}).date || "");
}
function feedDayPlan(doc) {
  const d = (doc || {}).day;
  return d && typeof d === "object" && d.mode ? d : null;
}

/* ── the landing ─────────────────────────────────────────────────── */
async function renderLanding(view, seq) {
  const hasFeed = await feedDoor();
  if (seq !== renderSeq) return;
  if (!hasFeed) return renderHome(view, seq);
  const {ok, body} = await api("/api/-/feed");
  if (seq !== renderSeq) return;
  clearLiveTimers();
  view.textContent = "";
  lawStamp(ok ? body : null);
  if (!ok || !Array.isArray((body || {}).cards))
    return view.append(problemBox(body || {}));
  return renderFeedScreen(view, body);
}

/* ── the clock, in the household's zone ──────────────────────────── */
function dayClock(zone) {
  const opts = {hour: "2-digit", minute: "2-digit", hourCycle: "h23"};
  let fmt;
  try { fmt = new Intl.DateTimeFormat("en-GB", {...opts, timeZone: zone || undefined}); }
  catch { fmt = new Intl.DateTimeFormat("en-GB", opts); }
  return iso => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? "" : fmt.format(d);
  };
}
function dayHourIn(zone) {
  const opts = {hour: "2-digit", hourCycle: "h23"};
  let fmt;
  try { fmt = new Intl.DateTimeFormat("en-GB", {...opts, timeZone: zone || undefined}); }
  catch { fmt = new Intl.DateTimeFormat("en-GB", opts); }
  return parseInt(fmt.format(new Date()), 10) || 0;
}
function dayAfter(date) {
  const d = new Date(date + "T12:00:00Z");
  if (Number.isNaN(d.getTime())) return date;
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}
function spanWindow(s, clock) {
  return clock(s.starts_at) + "–" + clock(s.ends_at);
}
/* past, current or ahead — the server's `current` flag first, the
   span clock after; a block with no windows is ahead until it says */
function blockPhase(b, now) {
  if (b.current) return "current";
  const spans = b.spans || [];
  if (spans.length && spans.every(s => new Date(s.ends_at).getTime() <= now))
    return "past";
  return "ahead";
}
/* the verb the declaration styled primary — lowest order wins */
function primaryVerb(actions) {
  const orderOf = e => (e.display || {}).order ?? 99;
  const hits = Object.entries(actions || {})
    .filter(([, e]) => (e.display || {}).style === "primary")
    .sort(([, a], [, b]) => orderOf(a) - orderOf(b));
  return hits.length ? {name: hits[0][0], entry: hits[0][1]} : null;
}

/* ── one verb, one origin key, one fresh read ─────────────────────
   The feed's own tap discipline (135-feed-screen.js § fireVerb): a
   verb wanting a form or a confirmation goes through the dialog under
   the same key; anything else is one POST. Either way the document is
   re-read afterwards — the envelope answers, the header never guesses
   which block is current now. */
async function fireDayVerb({name, entry, subject, cardId, chip, problem, ctx}) {
  const key = feedOriginKey(ctx.day, cardId);
  if (entry.input || (entry.safety || {}).confirm) {
    actionDialog({name, entry, doc: subject, idemKey: key,
                  onDone: () => ctx.reread()});
    return;
  }
  if (chip) { chip.disabled = true; chip.setAttribute("aria-disabled", "true"); }
  const h = {"Idempotency-Key": key};
  if ((entry.safety || {}).fence && (subject.meta || {}).etag)
    h["If-Match"] = subject.meta.etag;
  const res = await api(entry.href, {method: entry.method || "POST",
                                     body: JSON.stringify({}), headers: h});
  if (!res.ok) {
    if (chip) { chip.disabled = false; chip.removeAttribute("aria-disabled"); }
    if (problem) problem.replaceChildren(problemBox(res.body || {}));
    return;
  }
  maybeUndoToast(name, subject, res.body || {});
  ctx.reread();
}
function dayVerbChip({name, entry, subject, cardId, problem, ctx}) {
  const chip = el("button",
    {class: "chip verb" + ((entry.display || {}).style === "primary" ? " primary" : ""),
     "data-action": name,
     title: (entry.display || {}).description || (entry.safety || {}).one_way || ""},
    label(name, entry), (entry.safety || {}).confirm ? " …" : "");
  chip.addEventListener("click", () =>
    fireDayVerb({name, entry, subject, cardId, chip, problem, ctx}));
  return chip;
}

/* ── a decision, one row ─────────────────────────────────────────
   The text links to the decision's own screen — skip and change live
   there, not on the card. The one chip is the launch wearing the
   primary verb: a link launch opens in a new tab AND fires the verb
   (the tap is the verdict); a service launch fires the verb and the
   server fires the device; a text launch is the sentence itself, and
   the chip is small. No primary verb projected, no chip. */
function dayGoChip(d, verb, row, ctx) {
  if (!verb) return null;
  const {name, entry} = verb;
  const launch = d.launch || {};
  const problem = row.querySelector("[data-day-problem]");
  const cardId = d.card_id || "now/decision/" + (d.id || String(d.self || "").split("/").pop());
  const href = d.launch_href || launch.href || null;
  if (launch.type === "href" && href) {
    const a = el("a", {class: "chip verb primary", href, target: "_blank",
                       rel: "noopener", "data-action": name, "data-launch": "href",
                       title: (entry.display || {}).description ||
                              (entry.safety || {}).one_way || href},
      label(name, entry) + " ↗");
    a.addEventListener("click", () => {
      if (a.getAttribute("aria-disabled")) return;
      fireDayVerb({name, entry, subject: d, cardId, chip: a, problem, ctx});
    });
    return a;
  }
  const chip = dayVerbChip({name, entry, subject: d, cardId, problem, ctx});
  chip.setAttribute("data-launch", launch.type || "");
  if (launch.type === "text") {
    chip.classList.remove("primary");
    chip.classList.add("small");
    chip.textContent = "did it";
  }
  return chip;
}
function dayDecisionRow(d, ctx) {
  const launch = d.launch || {};
  const row = el("li", {class: "day-decision", "data-decision": d.id || "",
                        "data-state": d.state || ""});
  row.append(el("a", {class: "day-decision-text prose",
                      href: d.self ? "#" + d.self : null,
                      title: "the decision's own screen — skip or change it there"},
    d.text || "…"));
  if (launch.type === "text" && launch.text)
    row.append(el("div", {class: "day-decision-launch prose"}, launch.text));
  const verbs = el("div", {class: "day-verbs feed-verbs"});
  row.append(verbs, el("div", {"data-day-problem": ""}));
  const chip = dayGoChip(d, primaryVerb(d.actions), row, ctx);
  if (chip) verbs.append(chip);
  else verbs.remove();
  return row;
}
function dayDecisionList(block, ctx) {
  const ds = [...(block.decisions || [])]
    .sort((a, b) => (a.order ?? 99) - (b.order ?? 99));
  if (!ds.length)
    return el("div", {class: "muted day-none"}, "nothing decided for this block");
  return el("ol", {class: "day-decisions"}, ds.map(d => dayDecisionRow(d, ctx)));
}

/* ── the timeline: one line, every block, tap to open ─────────────── */
function dayTimeline(dp, ctx) {
  const now = Date.now(), clock = dayClock(dp.zone);
  const line = el("div", {class: "day-timeline", role: "list"});
  const open = el("div", {class: "day-tl-open"});
  let shown = null;
  for (const b of dp.blocks || []) {
    const spans = b.spans || [];
    const btn = el("button", {class: "day-tl-block " + blockPhase(b, now),
                              type: "button", role: "listitem",
                              "data-block": b.id || "", "aria-expanded": "false",
                              title: spans.map(s => spanWindow(s, clock)).join(" · ")},
      b.context_name || "block",
      spans.length ? el("span", {class: "day-tl-when mono"},
                        spanWindow(spans[0], clock)) : null);
    btn.addEventListener("click", () => {
      for (const x of line.querySelectorAll("[aria-expanded]"))
        x.setAttribute("aria-expanded", "false");
      if (shown === b.id) { shown = null; open.replaceChildren(); return; }
      shown = b.id;
      btn.setAttribute("aria-expanded", "true");
      open.replaceChildren(el("div", {class: "day-tl-detail", "data-block": b.id || ""},
        el("b", {}, b.context_name || "block"),
        b.stance ? el("span", {class: "muted prose"}, " — " + b.stance) : null,
        el("span", {class: "muted mono day-tl-spans"},
          spans.map(s => spanWindow(s, clock)).join(" · ")),
        dayDecisionList(b, ctx)));
    });
    line.append(btn);
  }
  /* the plan's own verbs — replan, reshape, whatever was projected —
     on the line's right edge */
  const plan = dp.plan || null;
  const problem = el("div", {"data-day-problem": ""});
  if (plan && Object.keys(plan.actions || {}).length) {
    const bar = el("div", {class: "day-tl-verbs feed-verbs"});
    const orderOf = e => (e.display || {}).order ?? 99;
    for (const [name, entry] of Object.entries(plan.actions)
           .sort(([a, ea], [b, eb]) => orderOf(ea) - orderOf(eb) || a.localeCompare(b)))
      bar.append(dayVerbChip({name, entry, subject: plan,
        cardId: "now/plan/" + String(plan.self || "").split("/").pop(),
        problem, ctx}));
    line.append(bar);
  }
  return [line, problem, open];
}

/* ── EXECUTE: the current block, then the line ───────────────────── */
function dayExecutePanel(dp, ctx) {
  const blocks = dp.blocks || [];
  const now = Date.now(), clock = dayClock(dp.zone);
  const cur = blocks.find(b => b.id && b.id === dp.current_block_id) ||
              blocks.find(b => b.current) || null;
  const sec = el("section", {class: "day-head", "data-day-mode": "execute",
                             "data-day-date": dp.date || ""});
  if (cur) {
    sec.append(el("h2", {class: "day-name"}, cur.context_name || "Now"));
    if (cur.stance) sec.append(el("div", {class: "day-stance prose muted"}, cur.stance));
    sec.append(dayDecisionList(cur, ctx));
  } else {
    const next = blocks.find(b => blockPhase(b, now) === "ahead");
    sec.append(el("h2", {class: "day-name"}, next ? "Between blocks" : "The day is spent"));
    if (next && (next.spans || []).length)
      sec.append(el("div", {class: "day-stance muted"},
        "next: " + (next.context_name || "a block") + " at "
        + clock(next.spans[0].starts_at)));
  }
  sec.append(...dayTimeline(dp, ctx));
  return sec;
}

/* ── PLAN: the defaults, the shape, the one verb ─────────────────── */
function dayPlanPanel(dp, ctx) {
  const evening = dayHourIn(dp.zone) >= 20;
  const date = evening ? dayAfter(dp.date || "") : (dp.date || "");
  const sec = el("section", {class: "day-head", "data-day-mode": "plan",
                             "data-day-date": date});
  sec.append(el("h2", {class: "day-name"}, evening ? "Plan tomorrow" : "Plan today"));
  const defaults = [...(dp.defaults || [])]
    .sort((a, b) => (a.order ?? 99) - (b.order ?? 99));
  if (defaults.length)
    sec.append(el("ol", {class: "day-defaults"}, defaults.map(d =>
      el("li", {class: "day-default"},
        el("span", {class: "day-default-name"}, d.context_name || "block"),
        el("span", {class: "day-default-when mono muted"},
          (d.spans || []).map(s => s.from + "–" + s.to).join(" · "))))));

  const create = dp.create || null;
  const props = ((create || {}).input || {}).properties || {};
  const verbs = el("div", {class: "day-verbs feed-verbs"});
  const problem = el("div", {"data-day-problem": ""});
  /* the shape toggle IS the create form's own enum, worn as chips —
     nothing pressed means the server's default, as its help says */
  let shape = null;
  const shapeProp = props.shape ? schemaProp(props.shape) : null;
  const choices = ((props.shape || {})["x-display"] || (shapeProp || {})["x-display"] || {}).choices || {};
  if (shapeProp && (shapeProp.enum || []).length) {
    const row = el("div", {class: "day-shape chips", role: "group",
                           "aria-label": "shape of the day"});
    for (const v of shapeProp.enum) {
      const b = el("button", {class: "chip", type: "button", "data-shape": String(v),
                              "aria-pressed": "false"}, choices[String(v)] || String(v));
      b.addEventListener("click", () => {
        shape = shape === v ? null : v;
        for (const x of row.querySelectorAll("[data-shape]"))
          x.setAttribute("aria-pressed", String(x.dataset.shape === String(shape)));
      });
      row.append(b);
    }
    sec.append(row);
  }
  if (create) {
    const kind = kindAtHref(wellKnownNow, String(create.href || "").split("?")[0]) || "";
    const btn = el("button", {class: "chip verb primary", "data-action": "create",
                              title: (create.display || {}).description || ""},
      (create.display || {}).label || (evening ? "Plan tomorrow" : "Plan today"));
    btn.addEventListener("click", () => {
      const prefill = {};
      if (props.date && date) prefill.date = date;
      if (props.shape && shape) prefill.shape = shape;
      const me = principalId() || ((window.signedinPrincipal || {}).id);
      if (props.member && xref(props.member) && me) prefill.member = me;
      actionDialog({name: "create", entry: create, doc: {kind}, prefill,
                    idemKey: feedOriginKey(ctx.day, "now/plan/create"),
                    onDone: () => ctx.reread()});
    });
    verbs.append(btn);
  } else if (dp.plan && Object.keys(dp.plan.actions || {}).length) {
    /* a drafting plan already stands: its own projected doors */
    const orderOf = e => (e.display || {}).order ?? 99;
    for (const [name, entry] of Object.entries(dp.plan.actions)
           .sort(([a, ea], [b, eb]) => orderOf(ea) - orderOf(eb) || a.localeCompare(b)))
      verbs.append(dayVerbChip({name, entry, subject: dp.plan,
        cardId: "now/plan/" + String(dp.plan.self || "").split("/").pop(),
        problem, ctx}));
  }
  if (verbs.childElementCount) sec.append(verbs, problem);
  sec.append(el("p", {class: "muted day-or"}, "or ",
    el("a", {href: "/api/-/welcome", target: "_blank", rel: "noopener",
             title: "the connector's own instructions — an agent plans the day through the ordinary doors"},
      "ask Claude to plan it")));
  if (dp.plan && dp.blocks && dp.blocks.length) sec.append(...dayTimeline(dp, ctx));
  return sec;
}

/* the header, or nothing: `ctx` is {day, reread} — the date the
   origin keys name, and the feed's own re-read */
function dayHeader(dp, ctx) {
  if (!dp) return null;
  if (dp.mode === "execute") return dayExecutePanel(dp, ctx);
  if (dp.mode === "plan") return dayPlanPanel(dp, ctx);
  return null;
}
/* the current block's seam sentence, when the server sends one */
function dayPlanSeam(dp) {
  if (!dp || dp.mode !== "execute") return null;
  const cur = (dp.blocks || []).find(b => b.id && b.id === dp.current_block_id) ||
              (dp.blocks || []).find(b => b.current) || null;
  return cur && typeof cur.seam === "string" && cur.seam ? cur.seam : null;
}
