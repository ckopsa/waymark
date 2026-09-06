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
   Since waymark-i89n.14 the day stands ALONE on home when the document
   carries a plan: the census moved behind #feed (the nav's Feed link,
   ⋯ on a phone), one quiet link at the foot of the day leads there,
   and a document without a plan still lands on the feed as it was.
   Below the current block every block of the day is a row you read
   without a tap — its window, its stance, its decisions in order —
   and the clock reads AM/PM, the meridiem shared across a window.

   THE LOOP CLOSES HERE (waymark-i89n.12). An open block offers the
   create door the document put on it — 'add a decision', the form
   with this block answered — and the plan panel offers the template's
   create door beside the defaults, so a template, a plan and a
   decision are all made from the one screen.

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
  /* the day alone when the document carries one (waymark-i89n.14);
     the feed exactly as it was when it does not */
  if (feedDayPlan(body)) return renderDayScreen(view, body);
  return renderFeedScreen(view, body);
}
/* #feed: the census for a reader who wants it — the day still heads
   it, so the seam reads the block's own sentence */
async function renderFeedRoute(view, seq) {
  const {ok, body} = await api("/api/-/feed");
  if (seq !== renderSeq) return;
  clearLiveTimers();
  view.textContent = "";
  lawStamp(ok ? body : null);
  if (!ok || !Array.isArray((body || {}).cards))
    return view.append(problemBox(body || {}));
  return renderFeedScreen(view, body);
}
/* the day screen: the header, then one quiet link to the feed */
function renderDayScreen(view, doc) {
  const day = feedDayDate(doc);
  const col = el("div", {class: "feed-col day-screen"});
  const node = dayHeader(feedDayPlan(doc), {day, reread: () => render()});
  if (node) col.append(node);
  col.append(el("p", {class: "muted day-feed-link"},
    el("a", {href: "#feed",
             title: "what to do now, what to answer, what the house already finished"},
      "the feed →")));
  view.append(col);
}

/* ── the clock, in the household's zone ──────────────────────────── */
function dayClock(zone) {
  /* AM/PM (waymark-i89n.14): the house reads a 12-hour clock */
  const opts = {hour: "numeric", minute: "2-digit", hour12: true};
  let fmt;
  try { fmt = new Intl.DateTimeFormat("en-US", {...opts, timeZone: zone || undefined}); }
  catch { fmt = new Intl.DateTimeFormat("en-US", opts); }
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
/* a window, the meridiem said once when both ends share it: 7:00–8:35 AM */
function spanWindow(s, clock) {
  const a = clock(s.starts_at), b = clock(s.ends_at);
  const half = t => (t.match(/([AP]M)$/) || [])[1] || "";
  return (half(a) && half(a) === half(b) ? a.replace(/\s*[AP]M$/, "") : a) + "–" + b;
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
function dayDecisionRow(d, ctx, compact) {
  const launch = d.launch || {};
  const row = el("li", {class: "day-decision" + (compact ? " compact" : ""),
                        "data-decision": d.id || "",
                        "data-state": d.state || ""});
  row.append(el("a", {class: "day-decision-text prose",
                      href: d.self ? "#" + d.self : null,
                      title: "the decision's own screen — skip or change it there"},
    d.text || "…"));
  /* a preview row is the sentence alone: no launch, no chip — the
     block's own turn brings those to the header */
  if (compact) return row;
  if (launch.type === "text" && launch.text)
    row.append(el("div", {class: "day-decision-launch prose"}, launch.text));
  const verbs = el("div", {class: "day-verbs feed-verbs"});
  row.append(verbs, el("div", {"data-day-problem": ""}));
  const chip = dayGoChip(d, primaryVerb(d.actions), row, ctx);
  if (chip) verbs.append(chip);
  else verbs.remove();
  return row;
}
/* the block's own create door, when the document projected one: the
   ordinary action dialog over the create form with this block already
   answered. No door projected (the leash does not confer it), no chip
   — the list ends where the decisions end. */
function dayAddChip(block, ctx) {
  const create = block.create || null;
  if (!create) return null;
  const props = (create.input || {}).properties || {};
  const kind = kindAtHref(wellKnownNow, String(create.href || "").split("?")[0]) || "";
  const btn = el("button", {class: "chip verb small day-add", type: "button",
                            "data-action": "create",
                            title: (create.display || {}).description || ""},
    (create.display || {}).label || "add one");
  btn.addEventListener("click", () => {
    const prefill = {};
    if (props.block_id && block.id) prefill.block_id = block.id;
    actionDialog({name: "create", entry: create, doc: {kind}, prefill,
                  idemKey: feedOriginKey(ctx.day, "now/block/" + (block.id || "") + "/create"),
                  onDone: () => ctx.reread()});
  });
  return el("div", {class: "day-verbs feed-verbs"}, btn);
}
function dayDecisionList(block, ctx, {compact = false, addable = true} = {}) {
  const ds = [...(block.decisions || [])]
    .sort((a, b) => (a.order ?? 99) - (b.order ?? 99));
  const add = addable ? dayAddChip(block, ctx) : null;
  const list = ds.length
    ? el("ol", {class: "day-decisions"}, ds.map(d => dayDecisionRow(d, ctx, compact)))
    : el("div", {class: "muted day-none"}, "nothing decided for this block");
  return add ? el("div", {class: "day-decided"}, list, add) : list;
}

/* ── the day, block by block: every block previewed, no tap ────────
   (waymark-i89n.14) One row per block in window order — the name, the
   window(s), the stance, and its decisions in the person's order,
   read without a tap. The current block is the header above, so its
   row is a marker; past blocks read dim; every block still ahead
   carries the add chip the document projected. The plan's own verbs
   — replan, reshape, whatever was projected — sit under the list. */
function dayList(dp, ctx, {skipCurrent = true} = {}) {
  const now = Date.now(), clock = dayClock(dp.zone);
  const list = el("ol", {class: "day-list", role: "list"});
  for (const b of dp.blocks || []) {
    const phase = blockPhase(b, now);
    const spans = b.spans || [];
    const row = el("li", {class: "day-row " + phase, "data-block": b.id || ""});
    row.append(el("div", {class: "day-row-head"},
      el("b", {class: "day-row-name"}, b.context_name || "block"),
      el("span", {class: "day-row-when mono muted"},
        spans.map(s => spanWindow(s, clock)).join(" · ")),
      phase === "current" ? el("span", {class: "day-row-now"}, "now") : null));
    if (phase === "current" && skipCurrent) { list.append(row); continue; }
    if (b.stance) row.append(el("div", {class: "day-row-stance muted prose"}, b.stance));
    row.append(dayDecisionList(b, ctx, {compact: phase !== "current",
                                        addable: phase !== "past"}));
    list.append(row);
  }
  const plan = dp.plan || null;
  const problem = el("div", {"data-day-problem": ""});
  const out = [list, problem];
  if (plan && Object.keys(plan.actions || {}).length) {
    const bar = el("div", {class: "day-list-verbs feed-verbs"});
    const orderOf = e => (e.display || {}).order ?? 99;
    for (const [name, entry] of Object.entries(plan.actions)
           .sort(([a, ea], [b, eb]) => orderOf(ea) - orderOf(eb) || a.localeCompare(b)))
      bar.append(dayVerbChip({name, entry, subject: plan,
        cardId: "now/plan/" + String(plan.self || "").split("/").pop(),
        problem, ctx}));
    out.push(bar);
  }
  return out;
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
  sec.append(...dayList(dp, ctx));
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
  /* the template's create door, when projected: a new default block
     for the shape — the pressed shape, if any, seeds the form */
  const tmpl = dp.template_create || null;
  if (tmpl) {
    const tprops = (tmpl.input || {}).properties || {};
    const tkind = kindAtHref(wellKnownNow, String(tmpl.href || "").split("?")[0]) || "";
    const tbtn = el("button", {class: "chip verb small day-add", type: "button",
                               "data-action": "create",
                               title: (tmpl.display || {}).description || ""},
      (tmpl.display || {}).label || "new template");
    tbtn.addEventListener("click", () => {
      const prefill = {};
      if (tprops.default_shapes && shape) prefill.default_shapes = [shape];
      actionDialog({name: "create", entry: tmpl, doc: {kind: tkind}, prefill,
                    idemKey: feedOriginKey(ctx.day, "now/template/create"),
                    onDone: () => ctx.reread()});
    });
    verbs.append(tbtn);
  }
  if (verbs.childElementCount) sec.append(verbs, problem);
  sec.append(el("p", {class: "muted day-or"}, "or ",
    el("a", {href: "/api/-/welcome", target: "_blank", rel: "noopener",
             title: "the connector's own instructions — an agent plans the day through the ordinary doors"},
      "ask Claude to plan it")));
  if (dp.plan && dp.blocks && dp.blocks.length)
    sec.append(...dayList(dp, ctx, {skipCurrent: false}));
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
