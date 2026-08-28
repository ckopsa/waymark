/* ── presence: the where-they-look surface (GET/POST /api/-/presence,
   ephemeral — never law). The stream carries a snapshot on connect,
   then join/move/leave frames; while following, a move for the
   followed principal steers this screen (debounced — transitions
   still steer as before); resource screens get viewing dots. This
   page holds only the firehose, so it reports its own gaze by
   explicit heartbeat: on navigation, and every 10s (the server
   evicts after 3 missed 15s heartbeats). ───────────────────────────── */
const PRESENCE = new Map();   // pid → {principal, self, source, at}
function hereHref() {
  return decodeURIComponent(location.hash.slice(1).split("?")[0]);
}
function paintPresence() {
  const box = $("[data-presence]");
  if (!box) return;
  const here = hereHref();
  const me = principalId();
  box.replaceChildren(...[...PRESENCE.values()]
    .filter(p => p.self === here && p.principal.id !== me)
    .map(p => el("span", {class: "viewing",
        title: `${p.principal.display || p.principal.id} is viewing this `
             + `screen right now (${p.source || "presence"})`},
      el("span", {class: "actor-mark " + (p.principal.type || "human")},
        ACTOR_MARKS[p.principal.type] || "●"),
      ` ${p.principal.display || p.principal.id} is here`)));
}
let followMoveTimer = null;
sse("/api/-/presence", ({event, data: f}) => {
  if (event !== "presence") return;
  if (f.event === "snapshot") {
    PRESENCE.clear();
    for (const p of f.presences || []) PRESENCE.set(p.principal.id, p);
  } else if (f.event === "leave") PRESENCE.delete(f.principal.id);
  else PRESENCE.set(f.principal.id, f);   // join | move
  paintPresence();
  /* the chip's gaze state: live while presence holds the followed
     principal, kept-but-faded once they leave */
  if (followId) {
    const p = PRESENCE.get(followId);
    if (p && p.self) followGaze = {self: p.self, at: p.at, live: true};
    else if (followGaze) followGaze.live = false;
    followChip();
  }
  /* follow-me: go where they LOOK — debounced, and never yank a human
     out of an open dialog (the transition-steering discipline) or off
     the Access panel (the balcony parks BOTH steering signals) */
  if ((f.event === "move" || f.event === "join") &&
      followId && f.principal.id === followId && f.self) {
    clearTimeout(followMoveTimer);
    followMoveTimer = setTimeout(() => {
      /* an armed jump (a fresh approve) spends itself leaving the
         balcony; passive following still parks there */
      if (f.self !== hereHref() &&
          (hereHref() !== "access" || followJumpArmed) &&
          !$("dialog[open]")) {
        followJumpArmed = false;
        location.hash = "#" + f.self;
      }
    }, 250);
  }
});
async function presenceBeat() {
  /* the curtain (wm10.curtain, set by the toggle below): a drawn
     curtain stops the client beating as COURTESY traffic reduction
     only — the SERVER's suppression (presence.clj, the member row's
     :curtain) is the law, and holds for clients that ignore this */
  if (localStorage.getItem("wm10.curtain")) return;
  const here = hereHref();
  if (!principalId() || !here.startsWith("/api/")) return;
  try {
    await fetch("/api/-/presence", {method: "POST",
      headers: Object.assign({"Content-Type": "application/json"},
                             principalHeaders()),
      body: JSON.stringify({self: here})});
  } catch (_e) { /* engine not started, or restarting */ }
}
setInterval(presenceBeat, 10000);
window.addEventListener("hashchange", presenceBeat);
presenceBeat();

/* ── the curtain toggle (waymark-tti.4): one press by your own
   identity draws/opens YOUR member row's curtain through the normal
   action path (POST /api/members/<id>/-/draw_curtain|open_curtain —
   the server is the law).

   THE CHIP'S TRUTH IS THE ROW, never this browser: the curtain can be
   drawn from another device, another client, or the household's
   recovery-admin valve, so we READ the member row on boot, on an
   identity switch and after every toggle, and paint from that. The
   localStorage flag is DERIVED from what we read and keeps its old
   job — quieting the beat above, courtesy only, never the law. When
   the row cannot be read we paint nothing: a chip that guesses about
   a privacy switch is worse than a blank one. ───────────────────── */
const $curtain = $("#curtainbtn");
function curtainId() {
  return principalId()
      || (window.signedinPrincipal && window.signedinPrincipal.id) || "";
}
function curtainChip(drawn) {
  if (drawn !== true && drawn !== false) {   /* unknown: do not guess */
    $curtain.textContent = "⛨ ?";
    $curtain.removeAttribute("aria-pressed");
    $curtain.title = "the house did not say whether your curtain is drawn"
                   + " — click to ask again";
    return;
  }
  $curtain.textContent = drawn ? "⛨ curtained" : "⛨";
  $curtain.setAttribute("aria-pressed", String(drawn));
  $curtain.title = drawn
    ? "your curtain is drawn — the house stops publishing your presence."
      + " Drawing it is itself visible, like curtains seen from the street."
      + " Click to open"
    : "draw your presence curtain — the house stops publishing where you look";
}
/* MY member row, whatever it is called: /api/members/<principal id>
   when the row id IS the principal id (provision!'s shape), else the
   row a :bind wrote — whose id is a minted uuid, NOT the principal id,
   so the by-id read 404s. That 404 used to end the story and a bound
   member's chip sat at "⛨ ?" forever, its toggle posting to a row that
   does not exist. members?subject= is the same second resolution the
   server does (gate!, followRequester above); the href it hands back
   is what both the read and the toggle must use.
   → {href, body} | null when the house cannot say */
async function curtainRow() {
  const me = curtainId();
  if (!me) return null;
  const byId = `/api/members/${encodeURIComponent(me)}`;
  try {
    const r = await api(byId);
    if (r.ok) return {href: byId, body: r.body};
    if (r.status !== 404) return null;
    const col = await api(`/api/members?subject=${encodeURIComponent(me)}`);
    const hit = (col.ok && (col.body.data?.items || [])[0]) || null;
    if (!hit || !hit.self) return null;
    const env = await api(hit.self);
    return env.ok ? {href: hit.self, body: env.body} : null;
  } catch (_e) { return null; }   /* engine restarting, or offline */
}
/* → true | false from the member row, null when it cannot be read */
function curtainDrawn(row) {
  if (!row) return null;
  const drawn = !!(row.body && row.body.data && row.body.data.curtain === true);
  if (drawn) localStorage.setItem("wm10.curtain", "1");
  else localStorage.removeItem("wm10.curtain");
  return drawn;
}
async function curtainState() { return curtainDrawn(await curtainRow()); }
async function refreshCurtain() { curtainChip(await curtainState()); }
$curtain.addEventListener("click", async () => {
  const me = curtainId();
  if (!me) { toast("no principal — set an identity first"); return; }
  const row = await curtainRow();
  const drawn = curtainDrawn(row);
  if (drawn === null) {
    curtainChip(null);
    toast("could not read your curtain — nothing was changed");
    return;
  }
  const act = drawn ? "open_curtain" : "draw_curtain";
  try {
    const {ok, status} = await api(`${row.href}/-/${act}`, {method: "POST"});
    if (!ok) toast(`${act.replace("_", " ")} refused (${status})`);
  } catch (_e) {
    toast(`${act.replace("_", " ")} failed — the house did not answer`);
  }
  /* whatever the click hoped, the ROW says what happened */
  const now = await curtainState();
  curtainChip(now);
  if (now === false) presenceBeat();   /* opened: announce yourself again now */
});
/* a dev-box identity switch is a different person's curtain */
$("#who").addEventListener("change", refreshCurtain);
refreshCurtain();

/* ── intents: the considering/asking stream (GET /api/-/intents,
   ephemeral like presence — never law). An agent's dry-run arrives as
   a quiet "considering…" card that vanishes on its close frame; a
   warning wall it hit lingers as a question with an "answer yes"
   button. The answer only DELIVERS (POST /api/-/intents/answer) — the
   agent's retry still passes the guard through its own acknowledge
   header, so this button can never override anything. Concealment is
   the server's job: a card this viewer may not see never arrives. */
const INTENTS = new Map();   // intent id → freshest entry
async function answerIntent(id) {
  try {
    const res = await fetch("/api/-/intents/answer", {method: "POST",
      headers: Object.assign({"Content-Type": "application/json"},
                             principalHeaders()),
      body: JSON.stringify({id})});
    if (!res.ok) toast(`answer refused (${res.status})`);
  } catch (_e) { /* engine restarting; the stream restates the truth */ }
}
function paintIntents() {
  const box = $("#intents");
  if (!box) return;
  /* questions outrank shadows — an ask must never hide under a pile
     of considerings when the stack caps at four */
  const all = [...INTENTS.values()]
    .sort((a, b) => (a.status === "considering") - (b.status === "considering"));
  const more = all.length - 4;
  box.replaceChildren(...all.slice(0, 4).map(i =>
    el("div", {class: "intent"},
      el("div", {},
        el("span", {class: "actor-mark " + (i.principal.type || "agent")},
          ACTOR_MARKS[i.principal.type] || "◆"),
        ` ${i.principal.display || i.principal.id} — `,
        el("b", {}, pretty(i.action)), " on ",
        el("a", {href: "#" + i.self, title: i.self},
          i.self.replace(/^\/api\//, ""))),
      i.question ? el("div", {class: "q"}, i.question)
                 : el("div", {class: "muted"}, "considering…"),
      i.status === "asking"
        ? el("button", {onclick: () => answerIntent(i.id)}, "answer yes")
        : i.status === "answered"
          ? el("div", {class: "muted"}, "✓ answered by "
              + ((i.answer && i.answer.by
                  && (i.answer.by.display || i.answer.by.id)) || "someone"))
          : null)),
    ...(more > 0 ? [el("div", {class: "intent-more"},
                      `… and ${more} more`)] : []));
}
sse("/api/-/intents", ({event, data: f}) => {
  if (event !== "intent") return;
  if (f.event === "snapshot") {
    INTENTS.clear();
    for (const i of f.intents || []) INTENTS.set(i.id, i);
  } else if (f.event === "close") INTENTS.delete(f.id);
  else INTENTS.set(f.id, f);   // open | update
  paintIntents();
});

/* ── the theme control (waymark-88k) ───────────────────────────────
   Three states, and only ONE of them is a colour: "system" is the
   ABSENCE of a stamp, which is why it keeps working while the page
   is open — a reader whose desktop flips to dark at dusk sees this
   page follow with no reload, because prefers-color-scheme is a live
   query and nothing on <html> is overriding it. "light"/"dark" stamp
   <html data-theme=…>, and the stylesheet honours that in BOTH
   directions: dark on a light system, light on a dark one.
   The head's theme boot already applied the stored word before the
   first paint; all that is left here is wiring the seats and marking
   which one is pressed. Storage is best-effort at every touch — a
   browser that refuses it still switches for this tab and simply
   forgets by the next visit. */
const THEMES = ["system", "light", "dark"];
const themeSeats = () => document.querySelectorAll("#themepick button");
function storedTheme() {
  try {
    const t = localStorage.getItem("waymark.theme");
    return THEMES.includes(t) ? t : "system";
  } catch (_e) { return "system"; }   /* storage can throw, not just miss */
}
function applyTheme(t) {
  if (t === "light" || t === "dark")
    document.documentElement.setAttribute("data-theme", t);
  else document.documentElement.removeAttribute("data-theme");
  for (const b of themeSeats())
    b.setAttribute("aria-pressed", String(b.dataset.themeChoice === t));
}
function setTheme(t) {
  try {
    if (t === "system") localStorage.removeItem("waymark.theme");
    else localStorage.setItem("waymark.theme", t);
  } catch (_e) { /* this tab only, then */ }
  applyTheme(t);
}
for (const b of themeSeats())
  b.addEventListener("click", () => setTheme(b.dataset.themeChoice));
applyTheme(storedTheme());

$("#apphost").textContent = location.host;  // the honest app identity
render();
