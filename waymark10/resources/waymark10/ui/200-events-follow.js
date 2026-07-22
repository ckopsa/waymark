/* ── events: fetch-based SSE (principal headers ride along), parsing
   the wire's named frames — `event: transition` with ids,
   `event: derivation` without ─────────────────────────────────────── */
function parseFrame(frame) {
  let event = "message", id = null, data = null;
  for (const line of frame.split("\n")) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("id:")) id = line.slice(3).trim();
    else if (line.startsWith("data:")) data = line.slice(5).trim();
  }
  if (!data) return null;
  try { return {event, id, data: JSON.parse(data)}; } catch { return null; }
}
let sseRefusalToldFor = null;   // one narration per cause, not per retry
async function sse(href, onFrame) {
  while (true) {
    let refused = false;
    try {
      const res = await fetch(href, {headers: principalHeaders()});
      if (!res.ok) {
        /* a live surface answering a problem is a CAUSE, not noise —
           the classic: a leftover grant selector conceals the SSE
           routes (they are not grant-projected), and every live
           surface goes dark while looking merely quiet */
        refused = true;
        const cause = localStorage.getItem("wm10.grant")
          ? "live surfaces are concealed under a grant scope — "
            + "leave the grant (✕ on the chip) to watch again"
          : `the live stream ${href} answers ${res.status}`;
        if (sseRefusalToldFor !== cause) {
          sseRefusalToldFor = cause;
          $("#ticker").replaceChildren(el("span", {}, "⚠ " + cause));
          console.warn("waymark10 ui:", cause);
        }
      } else {
        if (sseRefusalToldFor) { sseRefusalToldFor = null;
                                 $("#ticker").textContent = ""; }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = "";
        while (true) {
          const {done, value} = await reader.read();
          if (done) break;
          buf += decoder.decode(value, {stream: true});
          let idx;
          while ((idx = buf.indexOf("\n\n")) >= 0) {
            const f = parseFrame(buf.slice(0, idx));
            buf = buf.slice(idx + 2);
            if (f) onFrame(f);
          }
        }
      }
    } catch (_e) { /* server restarting */ }
    await new Promise(r => setTimeout(r, refused ? 15000 : 2000));
  }
}
/* a one-shot replay: read frames until the burst goes quiet, then stop */
function fetchReplay(href, {idle = 500} = {}) {
  return new Promise(async resolve => {
    const ctl = new AbortController();
    const frames = [];
    let timer = null, settled = false;
    const settle = () => {
      if (settled) return; settled = true;
      clearTimeout(timer); ctl.abort(); resolve(frames);
    };
    const bump = () => { clearTimeout(timer); timer = setTimeout(settle, idle); };
    bump();
    try {
      const sep = href.includes("?") ? "&" : "?";
      const res = await fetch(href + sep + "last_event_id=0",
        {signal: ctl.signal, headers: principalHeaders()});
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const {done, value} = await reader.read();
        if (done) break;
        buf += decoder.decode(value, {stream: true});
        let idx;
        while ((idx = buf.indexOf("\n\n")) >= 0) {
          const f = parseFrame(buf.slice(0, idx));
          buf = buf.slice(idx + 2);
          if (f) { frames.push(f); bump(); }
        }
      }
    } catch (_e) { /* aborted on idle, or the server hiccupped */ }
    settle();
  });
}

/* ── follow a principal (supervision): their transitions steer this
   screen — and so does their GAZE. The firehose names the actor on
   every event (where they write); the presence stream below names
   where they look. Following is a client-side filter plus navigation
   on both. (9's follow-me, restored: the recorded gap closes.) ─────── */
let followId = localStorage.getItem("wm10.follow.id") || null;
let followName = localStorage.getItem("wm10.follow.name") || null;
/* the followed principal's last reported gaze: {self, at, live} —
   live while presence holds them, kept (faded) after they leave so
   silence reads as "last seen …", never as a broken chip */
let followGaze = null;
function follow(actor) {
  followId = actor.id;
  followName = actor.display || actor.id;
  followGaze = null;
  localStorage.setItem("wm10.follow.id", followId);
  localStorage.setItem("wm10.follow.name", followName);
  followChip();
  /* the balcony parks navigation — say so, or follow looks broken
     (delayed one beat: the call site's own toast speaks first) */
  if (hereHref() === "access")
    setTimeout(() => toast(`following ${followName} — navigation parks on `
      + `Access; leave this panel and your screen goes where they look`), 1500);
  /* meet them where they already are: if their gaze is on the board
     right now, jump immediately — following that only reacts to the
     NEXT event looks dead beside an idle agent (the balcony still
     parks, dialogs still guard). Deferred one tick: the ?follow=
     boot param calls this before the presence consts evaluate. */
  const id = followId;
  setTimeout(() => {
    const known = followId === id && PRESENCE.get(id);
    if (known && known.self) {
      followGaze = {self: known.self, at: known.at, live: true};
      followChip();
      if (known.self !== hereHref() && hereHref() !== "access" &&
          !$("dialog[open]"))
        location.hash = "#" + known.self;
    }
  }, 0);
}
function unfollow() {
  followId = followName = followGaze = null;
  localStorage.removeItem("wm10.follow.id");
  localStorage.removeItem("wm10.follow.name");
  followChip();
}
function followChip() {
  const chip = $("#followchip");
  chip.style.display = followId ? "inline-block" : "none";
  chip.textContent = "";
  if (!followId) return;
  chip.append(`following ${followName}`);
  /* the gaze state, always shown — a quiet agent and a broken pipe
     must not look identical */
  if (followGaze && followGaze.self) {
    const short = followGaze.self.replace(/^\/api\//, "");
    const label = short.length > 30 ? short.slice(0, 29) + "…" : short;
    chip.append(" · ", followGaze.live
      ? el("a", {href: "#" + followGaze.self,
          title: `${followName} is looking at ${followGaze.self} — click to go there`},
          "👁 " + label)
      : el("span", {class: "gaze-faded",
          title: `${followName} last reported gaze on ${followGaze.self}; `
               + `their presence has since faded (silence, not certainty)`},
          `last seen ${label}`));
  } else {
    chip.append(" · ", el("span", {class: "gaze-faded",
      title: "no gaze reported yet — grant-scoped reads mark presence "
           + "automatically; an agent outside a grant must POST "
           + "/api/-/presence to be seen"},
      "no gaze yet"));
  }
  if (hereHref() === "access")
    chip.append(" · ", el("span", {class: "gaze-faded",
      title: "follow-navigation parks on the Access panel — leave it and "
           + "this screen goes where they look"},
      "⏸ parked"));
  chip.append(el("button", {title: "stop following", onclick: unfollow}, "✕"));
}
window.addEventListener("hashchange", followChip);
const bootParams = new URLSearchParams(location.search);
if (bootParams.get("follow")) {
  follow({id: bootParams.get("follow"),
          display: bootParams.get("follow_name") || bootParams.get("follow")});
  history.replaceState(null, "", location.pathname + location.hash);
}
followChip();

