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

$("#apphost").textContent = location.host;  // the honest app identity
render();
