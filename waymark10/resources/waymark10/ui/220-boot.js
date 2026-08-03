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

$("#apphost").textContent = location.host;  // the honest app identity
render();
