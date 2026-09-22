/* ── the jump box (waymark-sv9v): ⌘K from any screen, one filter over
   every kind the wire admits. The ⋯ menu is a list to scan, and a
   deployable with thirty kinds is faster to type at than to read —
   so this is the same destinations, answering to letters.

   It invents no destination: the targets are exactly what the nav
   already builds from well-known (every resource, at whatever tier)
   plus the screens that are not kinds — home, the feed, the dashboard,
   access. A grant that admits nothing extra therefore offers nothing
   extra here, and a kind absent from discovery is absent from the box
   for the same reason it is absent from the bar. ─────────────────── */
const jumpQ = $("#jumpq"), jumpList = $("#jumplist");
/* both modifiers open the box; the hint names the one the reader's own
   keyboard has, so a phone's menu and a Mac's menu read differently */
const JUMPKEY = /Mac|iPhone|iPad|iPod/.test(navigator.platform
                                            || navigator.userAgent)
  ? "⌘K" : "Ctrl K";
let jumpAll = [];        // every target, in nav order
let jumpRows = [];       // the filtered ones, as shown
let jumpAt = 0;          // which row the keyboard is on
let jumpReturn = null;   // focus goes back where it came from

function jumpIsOpen() { return document.body.classList.contains("jump-open"); }

/* every letter of the query, in order, anywhere in the label: the
   reader types "chr" for Chores and "aprq" for Approval requests. A
   run that continues costs nothing, a jump costs the distance, and a
   hit at the very start is cheapest of all — so a lower score is a
   better match. The positions come back too, to bold what was typed. */
function jumpFuzzy(q, s) {
  const hay = s.toLowerCase();
  let from = 0, score = 0, last = -2;
  const marks = [];
  for (const ch of q.toLowerCase()) {
    if (ch === " ") continue;
    const at = hay.indexOf(ch, from);
    if (at < 0) return null;
    score += at === last + 1 ? 0 : (at === 0 ? 1 : 4 + Math.min(at - from, 9));
    marks.push(at); last = at; from = at + 1;
  }
  return {score, marks};
}

/* the destinations, rebuilt per open: a grant widened mid-session
   widens this too, and discovery is cached, so the cost is a map */
async function jumpTargets() {
  let w;
  try { w = await wellKnown(); } catch { return []; }
  const hasFeed = await feedDoor().catch(() => false);
  const out = [];
  const screen = (label, href, where) => out.push({label, href, where});
  screen("Home", "", hasFeed ? "the day" : "dashboard");
  if (hasFeed) screen("Feed", "feed", "census");
  screen("Dashboard", "dashboard", "counts");
  if (w.resources && w.resources.member && w.resources.approval_request)
    screen("Access", "access", "grants");
  /* the kinds themselves: the active domain's first, then the other
     applications', then the engine's own machinery — the order the
     drawer and the ⋯ menu already put them in */
  const entries = Object.entries(w.resources || {});
  const here = domainOf(w, (location.hash.slice(1) || "")
    .split("?")[0].split("/").slice(0, 3).join("/"));
  const rank = ([, r]) => (navTier(r) === "primary" ? 0
                         : navTier(r) === "secondary" ? 1 : 2)
                        + (r.domain === here ? 0 : r.domain ? 3 : 6);
  for (const [kind, r] of entries.slice().sort((a, b) => rank(a) - rank(b)))
    out.push({label: title(kind) + "s", href: r.href,
              where: r.domain ? title(r.domain)
                   : navTier(r) === "system" ? "engine" : ""});
  return out;
}

/* one row: the label with the typed letters bolded, and where it
   lives kept quiet at the end */
function jumpRow(t, marks, i) {
  const row = el("div", {class: "jump-item", role: "option", "data-i": i,
                         id: "jumpopt" + i, "aria-selected": "false"});
  const set = new Set(marks);
  const label = el("span");
  for (let c = 0; c < t.label.length; ) {
    const bold = set.has(c);
    let end = c;
    while (end < t.label.length && set.has(end) === bold) end++;
    const run = t.label.slice(c, end);
    label.append(bold ? el("b", {}, run) : document.createTextNode(run));
    c = end;
  }
  row.append(label);
  if (t.where) row.append(el("span", {class: "jump-where"}, t.where));
  row.addEventListener("click", () => jumpChoose(i));
  row.addEventListener("mousemove", () => { if (jumpAt !== i) jumpSelect(i); });
  return row;
}

function jumpPaint() {
  const q = jumpQ.value.trim();
  const scored = [];
  for (const t of jumpAll) {
    if (!q) { scored.push({t, score: 0, marks: []}); continue; }
    const m = jumpFuzzy(q, t.label);
    if (m) { scored.push({t, score: m.score, marks: m.marks}); continue; }
    /* the application's name is a way in too: "meal" finds every kind
       under Mealplan, whatever the kinds are called */
    const w = t.where && jumpFuzzy(q, t.where);
    if (w) scored.push({t, score: w.score + 40, marks: []});
  }
  if (q) scored.sort((a, b) => a.score - b.score
                            || a.t.label.length - b.t.label.length
                            || a.t.label.localeCompare(b.t.label));
  jumpRows = scored.map(s => s.t);
  jumpList.textContent = "";
  if (!scored.length) {
    jumpList.append(el("div", {class: "jump-empty"},
      jumpAll.length ? "no kind by that name" : "…"));
    return;
  }
  scored.forEach((s, i) => jumpList.append(jumpRow(s.t, s.marks, i)));
  jumpSelect(0);
}

function jumpSelect(i) {
  const rows = jumpList.children;
  if (!rows.length) return;
  jumpAt = Math.max(0, Math.min(i, rows.length - 1));
  for (const r of rows) r.setAttribute("aria-selected", "false");
  const row = rows[jumpAt];
  row.setAttribute("aria-selected", "true");
  jumpQ.setAttribute("aria-activedescendant", row.id);
  row.scrollIntoView({block: "nearest"});
}

function jumpChoose(i) {
  const t = jumpRows[i];
  if (!t) return;
  jumpClose();
  /* home is the empty hash; everything else is its own address, the
     same string the bar's own links carry */
  if (t.href) go(t.href);
  else if (location.hash) location.hash = "";
  else render();
}

async function jumpOpen() {
  if (jumpIsOpen()) return;
  jumpReturn = document.activeElement;
  document.body.classList.add("jump-open");
  jumpQ.value = "";
  jumpList.textContent = "";
  jumpQ.focus();
  const targets = await jumpTargets();
  if (!jumpIsOpen()) return;      // closed while discovery was in flight
  jumpAll = targets;
  jumpPaint();
}

function jumpClose() {
  if (!jumpIsOpen()) return;
  document.body.classList.remove("jump-open");
  jumpQ.removeAttribute("aria-activedescendant");
  if (jumpReturn && jumpReturn.isConnected) jumpReturn.focus();
  jumpReturn = null;
}

/* the shortcut is caught on the way DOWN: a screen with keys of its
   own (the deck, the feed) must not eat it, and the browser's own
   ⌘K must not either */
document.addEventListener("keydown", ev => {
  if ((ev.metaKey || ev.ctrlKey) && !ev.altKey
      && (ev.key === "k" || ev.key === "K")) {
    ev.preventDefault(); ev.stopPropagation();
    jumpIsOpen() ? jumpClose() : jumpOpen();
  }
}, true);

jumpQ.addEventListener("input", jumpPaint);
jumpQ.addEventListener("keydown", ev => {
  /* tab cycles the list rather than leaving: the input is the only
     focusable thing in the box, which is the whole focus trap */
  if (ev.key === "ArrowDown" || (ev.key === "Tab" && !ev.shiftKey)) {
    ev.preventDefault();
    jumpSelect(jumpAt + 1 >= jumpList.children.length ? 0 : jumpAt + 1);
  } else if (ev.key === "ArrowUp" || (ev.key === "Tab" && ev.shiftKey)) {
    ev.preventDefault();
    jumpSelect(jumpAt - 1 < 0 ? jumpList.children.length - 1 : jumpAt - 1);
  } else if (ev.key === "Enter") {
    ev.preventDefault(); jumpChoose(jumpAt);
  } else if (ev.key === "Escape") {
    ev.stopPropagation(); jumpClose();
  }
});
$("#jumpback").addEventListener("click", jumpClose);
