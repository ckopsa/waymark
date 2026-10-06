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
/* ── a hidden tab holds NO connection (waymark-dxnp) ────────────────
   http-kit is HTTP/1.1 and Chromium caps 6 connections per host, so
   this page's three boot streams (/api/-/events, /api/-/presence,
   /api/-/intents) plus any per-document history stream already sit at
   the cap: open a SECOND tab and its plain GETs — .well-known,
   collections — queue behind connections that will never finish, and
   the screen stays blank. curl answers in milliseconds meanwhile,
   which is why this reads as a server hang and is not one.

   So: while document.hidden, every sse() fetch is ABORTED and its
   retry loop parks (no backoff timer either — a paused loop that
   still reconnected every 2s would hold the same pool). On
   visibilitychange back to visible each stream reopens, resuming from
   the last id it saw where the route honours Last-Event-ID (the
   firehose does; presence and intents carry no ids at all and send a
   fresh snapshot on connect instead). A stream that cannot prove it
   resumed missed nothing costs the screen ONE refetch on return.

   Not fixed here: two VISIBLE tabs still collide — that is the
   multiplexed-stream bead (waymark-p5tg), filed alongside. */
const SSE_STREAMS = new Set();
let ssePaused = typeof document !== "undefined" && !!document.hidden;
let sseResumeTimer = null;
function ssePause() {
  if (ssePaused) return;
  ssePaused = true;
  /* abort the in-flight body: the loop wakes in its catch, sees the
     pause and parks instead of backing off */
  for (const s of SSE_STREAMS) { try { if (s.ctl) s.ctl.abort(); } catch (_e) {} }
}
function sseResume() {
  if (!ssePaused) return;
  ssePaused = false;
  let blind = false;   // any stream that cannot replay what it missed
  for (const s of SSE_STREAMS) {
    if (s.lastId == null) blind = true;
    const wake = s.wake; s.wake = null;
    if (wake) wake();
  }
  /* …and for those, the screen itself is the resume point: one
     refetch, debounced so the three streams ask for it once */
  if (blind && typeof render === "function") {
    clearTimeout(sseResumeTimer);
    sseResumeTimer = setTimeout(render, 0);
  }
}
if (typeof document !== "undefined" && document.addEventListener)
  document.addEventListener("visibilitychange",
    () => (document.hidden ? ssePause() : sseResume()));
/* reopen a stream now, from the top: its href (a function) is asked
   again, and the loop reconnects without a backoff. Guided follow
   reopens the live stream this way to add or drop ?ui=. */
function sseReopen(href) {
  for (const s of SSE_STREAMS)
    if (s.href === href && s.ctl) {
      s.reopen = true;
      try { s.ctl.abort(); } catch (_e) {}
    }
}
/* close a stream for good: it leaves the registry, its body is aborted
   and its loop ends. A parked one is simply never woken. The quest
   tracker closes the stream of a quest it no longer holds
   (120-nav-home.js). */
function sseClose(href) {
  for (const s of [...SSE_STREAMS])
    if (s.href === href) {
      s.closed = true;
      SSE_STREAMS.delete(s);
      try { if (s.ctl) s.ctl.abort(); } catch (_e) {}
    }
}
/* onOpen, when given, is told each time the stream connects: what
   moved before that is the caller's to read */
async function sse(href, onFrame, onOpen) {
  /* lastId is this stream's resume point — set only by frames that
     actually carry an id line, which is how a resumable route
     (the firehose) tells itself apart from an ephemeral one */
  const stream = {href, ctl: null, lastId: null, wake: null, reopen: false};
  SSE_STREAMS.add(stream);
  while (true) {
    while (ssePaused) await new Promise(r => { stream.wake = r; });
    if (stream.closed) return;
    let refused = false;
    const ctl = typeof AbortController === "function"
      ? new AbortController() : null;
    stream.ctl = ctl;
    try {
      const headers = principalHeaders();
      if (stream.lastId != null) headers["Last-Event-ID"] = String(stream.lastId);
      /* a function href is asked again on every (re)connect: the live
         stream's ?ui= follows whoever is followed in guided mode */
      const url = typeof href === "function" ? href() : href;
      const res = await fetch(url, ctl ? {headers, signal: ctl.signal}
                                       : {headers});
      if (!res.ok) {
        /* a live surface answering a problem is a CAUSE, not noise —
           the classic: a leftover grant selector conceals the SSE
           routes (they are not grant-projected), and every live
           surface goes dark while looking merely quiet */
        refused = true;
        const cause = localStorage.getItem("wm10.grant")
          ? "live surfaces are concealed under a grant scope — "
            + "leave the grant (✕ on the chip) to watch again"
          : `the live stream ${url} answers ${res.status}`;
        if (sseRefusalToldFor !== cause) {
          sseRefusalToldFor = cause;
          $("#ticker").replaceChildren(el("span", {}, "⚠ " + cause));
          console.warn("waymark10 ui:", cause);
        }
      } else {
        if (sseRefusalToldFor) { sseRefusalToldFor = null;
                                 $("#ticker").textContent = ""; }
        if (onOpen) onOpen();
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
            if (f) { if (f.id) stream.lastId = f.id; onFrame(f); }
          }
        }
      }
    } catch (_e) { /* server restarting, or the hide-abort landed */ }
    stream.ctl = null;
    /* closed on purpose by sseClose: the loop ends here */
    if (stream.closed) return;
    /* aborted by the hide: park at the top of the loop with no timer
       pending, rather than sleeping and reconnecting into a hidden tab */
    if (ssePaused) continue;
    /* aborted on purpose by sseReopen: straight back, no backoff */
    if (stream.reopen) { stream.reopen = false; continue; }
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
/* one-shot: an APPROVE is an expressed intent to go where the agent
   goes — it may leave the Access balcony once, where passive
   following stays parked. Armed by follow(actor, {jump:true}) when
   no gaze is known yet; the next move spends it. */
let followJumpArmed = false;
/* guided follow (docs/spec-guided-follow.md §2): besides where they
   look, apply what their screen shows — the open dialog read-only,
   the collection query, the focused row. Asked for on the live stream
   with ?ui=<pid>; nothing arrives unless they share their screen. */
let followUi = !!followId && localStorage.getItem("wm10.follow.ui") === "1";
function liveHref() {
  return followUi && followId
    ? "/api/-/live?ui=" + encodeURIComponent(followId) : "/api/-/live";
}
function follow(actor, opts) {
  const jump = !!(opts && opts.jump);
  const wasUi = followUi;
  followUi = !!(opts && opts.ui);
  if (followUi) localStorage.setItem("wm10.follow.ui", "1");
  else { localStorage.removeItem("wm10.follow.ui"); closeGuided(); }
  guidedSeq = -1;
  guidedDismissed = null;
  followId = actor.id;
  followName = actor.display || actor.id;
  followGaze = null;
  localStorage.setItem("wm10.follow.id", followId);
  localStorage.setItem("wm10.follow.name", followName);
  followChip();
  if (followUi || wasUi) sseReopen(liveHref);
  /* the balcony parks navigation — say so, or follow looks broken
     (delayed one beat: the call site's own toast speaks first) */
  if (!jump && hereHref() === "access")
    setTimeout(() => toast(`following ${followName} — navigation parks on `
      + `Access; leave this panel and your screen goes where they look`), 1500);
  /* meet them where they already are: if their gaze is on the board
     right now, jump immediately — following that only reacts to the
     NEXT event looks dead beside an idle agent (the balcony still
     parks, dialogs still guard). Deferred one tick: the ?follow=
     boot param calls this before the presence consts evaluate. */
  const id = followId;
  followJumpArmed = jump;
  setTimeout(() => {
    const known = followId === id && PRESENCE.get(id);
    if (known && known.self) {
      followGaze = {self: known.self, at: known.at, live: true};
      followChip();
      if (known.self !== hereHref() &&
          (jump || hereHref() !== "access") && !$("dialog[open]")) {
        followJumpArmed = false;
        location.hash = "#" + known.self;
      }
    }
  }, 0);
}
/* the approve hand-off: follow whoever filed the ask, jumping even
   off the Access balcony — the approver just said yes to watching
   this agent work. The display resolves from the member the
   principal bound to; the id alone still follows. */
async function followRequester(pid) {
  if (!pid) return;
  let display = pid;
  try {
    const col = await api("/api/members?subject=" + encodeURIComponent(pid));
    const hit = (col.ok && (col.body.data?.items || [])[0]) || null;
    if (hit) {
      const env = await api(hit.self);
      if (env.ok && env.body.data?.display) display = env.body.data.display;
    } else {
      /* the credential-less door binds a member to its own id */
      const env = await api("/api/members/" + encodeURIComponent(pid));
      if (env.ok && env.body.data?.display) display = env.body.data.display;
    }
  } catch (_e) { /* the id is enough */ }
  follow({id: pid, display}, {jump: true});
  toast(`approved — following ${display}`);
}
function unfollow() {
  const wasUi = followUi;
  followId = followName = followGaze = null;
  followUi = false;
  localStorage.removeItem("wm10.follow.id");
  localStorage.removeItem("wm10.follow.name");
  localStorage.removeItem("wm10.follow.ui");
  closeGuided();
  guidedFocus = null;
  paintGuidedFocus();
  followChip();
  /* the live stream without ?ui=: today's frames, byte for byte */
  if (wasUi) sseReopen(liveHref);
}

/* a replay in progress (the last section of this file), or null. While
   one plays it holds this screen: live frames do not steer it, and
   nothing is read from the engine or written to it. */
let replay = null;
/* a `move` applied: go where they look. Never out of an open dialog,
   and off the Access panel only for an armed jump (a fresh approve)
   or a replay; passive following parks there. */
function applyFollowMove(self) {
  if (self === hereHref() || $("dialog[open]") ||
      (hereHref() === "access" && !followJumpArmed && !replay)) return;
  followJumpArmed = false;
  location.hash = "#" + self;
}

/* ── guided follow, the follower's side: a `ui` frame applied ──────── */
let guidedSeq = -1;          // the last seq applied; an older one drops
let guidedFocus = null;      // their focused row's self
let guidedTyping = null;     // the field their staged call is typing
let guidedLastFields = {};   // their form, as last reported
let guidedLastLabels = {};   // its ref fields' rows, as the beat names them
let guidedOpening = null;    // the dialog key being fetched right now
let guidedDismissed = null;  // the dialog key this person closed by hand
function markGuidedFocus(row, on) {
  row.toggleAttribute("data-guided-focus", on);
  row.style.outline = on ? "2px solid #7a5cff" : "";
}
function paintGuidedFocus() {
  for (const r of document.querySelectorAll("tr[data-self]"))
    markGuidedFocus(r, !!guidedFocus && r.dataset.self === guidedFocus);
}
function closeGuided() {
  const g = $("dialog[open][data-guided]");
  if (g) { g.dataset.guidedAuto = "1"; g.close(); }
}
/* a ref field's row as a form names it: the label the beat itself
   carries (a staged call's, read under the recorder's own grant; a list
   of refs has one for each id), else, in a replay, the summary line of
   the walk's last `doc` for that row. null when neither is known, and
   the id stays. */
function guidedLabel(name, id, i) {
  const own = guidedLastLabels[name];
  const said = Array.isArray(own) ? own[i] : i === undefined ? own : null;
  if (typeof said === "string" && said) return said;
  if (replay && typeof id === "string" && id)
    for (const [self, doc] of replay.docs)
      if (self.split("/").length === 4 && self.endsWith("/" + id) &&
          doc && typeof doc.summary === "string" && doc.summary)
        return doc.summary;
  return null;
}
/* how long the read of a guided dialog's row may take. A read that
   fails, or is not answered in this time, gives the key back, so the
   next frame or heartbeat for that dialog opens it again */
const GUIDED_READ_MS = 4000;
async function openGuidedDialog(d, name, key) {
  guidedOpening = key;
  let res = null, mine = false;
  /* a replay reads nothing: its dialog is built from the frames */
  try {
    res = replay ? replayDialogDoc(d) : await Promise.race([api(d.self),
      new Promise(done => setTimeout(done, GUIDED_READ_MS, null))]);
  } catch (_e) { res = null; }
  finally {
    mine = guidedOpening === key;        // else overtaken by a newer frame
    if (mine) guidedOpening = null;
  }
  if (!mine || !res) return;
  if (!res.ok || !(followUi || replay) || $("dialog[open]")) return;
  const entry = (res.body.actions || {})[d.action];
  if (!entry) return;                    // not a door this person sees
  await actionDialog({name: d.action, entry, doc: res.body,
    guided: {name, key, onDismiss: () => { guidedDismissed = key; }}});
  const g = $("dialog[open][data-guided]");
  if (g && g.guidedSet) g.guidedSet(guidedLastFields, replay ? guidedLabel : null);
  if (g && g.guidedLight && !replay) g.guidedLight(guidedTyping);
  /* a replayed caption about this form is drawn in it */
  if (replay) replayCaption();
  /* and so is the refusal the form got before it was drawn */
  if (replay) replayRefuse(replay.refused);
}
function applyGuidedUi(f) {
  if (replay || !followUi || !followId || !f || !f.ui ||
      (f.principal || {}).id !== followId) return;
  /* seq counts up per principal: a frame arriving late across
     processes is dropped */
  if (typeof f.seq === "number") {
    if (f.seq <= guidedSeq) return;
    guidedSeq = f.seq;
  }
  applyUiFrame(f);
}
/* one `ui` frame applied to this screen: a live one, or a replay's.
   The navigation, the dialog and the focused row are the same code
   either way. */
function applyUiFrame(f) {
  const ui = f.ui, d = ui.dialog, c = ui.collection;
  guidedLastFields = ui.fields || {};
  guidedLastLabels = ui.labels || {};
  /* `focus` is their focused row, or, on a typing beat of a staged call
     (docs/spec-agent-demo-walks.md §2), the name of the field the beat
     adds: that field alone is lit in the dialog */
  const typing = !!d && typeof ui.focus === "string" && !ui.focus.startsWith("/");
  guidedTyping = typing ? ui.focus : null;
  guidedFocus = typing ? null : ui.focus || null;
  /* the existing guards: the Access panel parks, and a dialog this
     person opened themselves is never replaced; nor is a replayed
     invitation's, which stands where the invited person's own stood */
  if ((replay || hereHref() !== "access") &&
      !$("dialog[open]:not([data-guided]), dialog[open][data-replay-invite]")) {
    const target = c && c.self ? collectionHrefOf(c) : f.self;
    const here = c && c.self
      ? collectionHrefOf(collectionShareOf(location.hash.slice(1)) || {self: ""})
      : hereHref();
    if (target && target !== here) location.hash = "#" + target;
    const g = $("dialog[open][data-guided]");
    const key = d ? d.self + " " + d.action : null;
    if (!d) { guidedDismissed = null; closeGuided(); }
    else if (g && g.getAttribute("data-guided") === key) {
      g.guidedSet(guidedLastFields, replay ? guidedLabel : null);
      /* a replay's pointer clicks the field instead (replayGesture) */
      if (!replay) g.guidedLight(guidedTyping);
    }
    else if (key !== guidedDismissed && key !== guidedOpening) {
      closeGuided();
      openGuidedDialog(d, f.principal.display || f.principal.id, key);
    }
  }
  paintGuidedFocus();
}

/* ── share my screen, the reporter's side (§2): per tab, OFF by
   default. While on, every beat carries this tab's `ui` part — the
   dialog and its fields (180-action-dialog.js, 170-forms.js), the
   collection query (130-collection.js) and the focused row. A
   person's form is never broadcast because someone chose to watch. */
const UI_SHARE = {dialog: null, fields: null, focus: null};
let uiShareTimer = null, uiShareClear = false;
function uiSharing() {
  try { return sessionStorage.getItem("wm10.share.ui") === "1"; }
  catch (_e) { return false; }
}
function uiShareState() {
  return {dialog: UI_SHARE.dialog,
          fields: UI_SHARE.dialog ? UI_SHARE.fields : null,
          collection: collectionShareOf(location.hash.slice(1)),
          focus: UI_SHARE.focus};
}
function shareUi(part) {
  Object.assign(UI_SHARE, part);
  if (!uiSharing()) return;
  clearTimeout(uiShareTimer);
  uiShareTimer = setTimeout(presenceBeat, 0);
}
function shareChip() {
  const b = $("#sharebtn");
  if (!b) return;
  const on = uiSharing();
  b.setAttribute("aria-pressed", String(on));
  b.textContent = on ? "⧉ sharing" : "⧉";
  b.title = on
    ? "sharing this tab's dialog, form, filters and focused row with "
      + "whoever follows you in guided mode — click to stop"
    : "share my screen with followers: this tab's dialog, form, filters "
      + "and focused row (off by default, this tab only)";
}
function toggleShareUi() {
  const on = !uiSharing();
  try {
    if (on) sessionStorage.setItem("wm10.share.ui", "1");
    else sessionStorage.removeItem("wm10.share.ui");
  } catch (_e) { /* no storage, no sharing */ }
  /* turning it off clears what followers were shown: one beat with
     every part empty, then beats carry no ui at all */
  uiShareClear = !on;
  shareChip();
  presenceBeat();
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
  /* the parked note earns its width on a desktop; on a phone the chip
     is already fighting for the header, so the toast alone says it */
  if (hereHref() === "access" &&
      document.documentElement.getAttribute("data-ui") !== "mobile")
    chip.append(" · ", el("span", {class: "gaze-faded",
      title: "follow-navigation parks on the Access panel — leave it and "
           + "this screen goes where they look"},
      "⏸ parked"));
  /* guided mode, offered here and marked here */
  chip.append(" · ", followUi
    ? el("button", {class: "guided", "data-guided-mark": "",
        title: `guided: ${followName}'s open dialog, form, filters and `
             + `focused row show here, read-only, while they share their `
             + `screen — click to follow their gaze only`,
        onclick: () => follow({id: followId, display: followName})},
        "🧭 guided")
    : el("button", {title: `guided follow: also show ${followName}'s open `
             + `dialog, form, filters and focused row, read-only — only `
             + `while they share their screen`,
        onclick: () => follow({id: followId, display: followName}, {ui: true})},
        "guide me"));
  chip.append(el("button", {title: "stop following", onclick: unfollow}, "✕"));
}
window.addEventListener("hashchange", followChip);

/* ── a walkthrough in hand (docs/spec-walkthrough.md §5): once the
   person starts one it is in hand in this tab, as a follow is, and a
   chip beside the follow chip says how far along it is on every
   screen. The row is the truth: the chip is redrawn from a fresh read
   on every transition of that row the firehose carries. ───────────── */
let walkthroughId = localStorage.getItem("wm10.walkthrough.id") || null;
let walkthroughDoc = null;
/* the author this walkthrough's Start made the tab follow, or null */
let walkthroughFollow = localStorage.getItem("wm10.walkthrough.follow") || null;
function stepLine(n, of, title) {
  return `Step ${n} of ${of}` + (title ? ` · ${title}` : "");
}
/* what the dialog is told about an invitation a walkthrough opened; the
   title is the one in hand, so no second read is made for it */
function ledStep(d) {
  const held = walkthroughDoc && walkthroughId === d.walkthrough;
  return {id: d.walkthrough, step: d.step, of: d.of,
          title: held ? (walkthroughDoc.data || {}).title || "" : ""};
}
function takeWalkthrough(id) {
  walkthroughId = id;
  localStorage.setItem("wm10.walkthrough.id", id);
  return refreshWalkthrough();
}
function dropWalkthrough() {
  walkthroughId = walkthroughDoc = null;
  localStorage.removeItem("wm10.walkthrough.id");
  /* the follow this walkthrough began ends with it */
  if (walkthroughFollow && followId === walkthroughFollow) unfollow();
  walkthroughFollow = null;
  localStorage.removeItem("wm10.walkthrough.follow");
  walkthroughChip();
}
/* watching an agent step (docs/spec-walkthrough.md §5): Start and
   Resume also follow the author in guided mode in this tab. The tap is
   the follower's ask docs/spec-guided-follow.md §2 requires; the author
   shares its `ui` frames or it does not. A follow the person already
   held stands when the walkthrough leaves the hand. */
function followAuthor() {
  const author = ((walkthroughDoc || {}).data || {}).author;
  if (!author || author === viewerId()) return;
  const held = followId === author;
  if (held && followUi) return;
  if (!held) {
    walkthroughFollow = author;
    localStorage.setItem("wm10.walkthrough.follow", author);
  }
  follow({id: author, display: held ? followName : author}, {ui: true});
}
/* on an agent step the screen goes to the step's `self`, where the
   person watches what the agent reports. Never out of a dialog the
   person opened; a guided one is the agent's own and does not hold it. */
function showAgentStep() {
  const doc = walkthroughDoc, d = (doc || {}).data || {};
  if (!doc || doc.state !== "running" || d.waiting_on !== "agent") return;
  const self = ((d.steps || [])[(d.current || 1) - 1] || {}).self;
  if (!self || self === hereHref() || $("dialog[open]:not([data-guided])")) return;
  location.hash = "#" + self;
}
async function refreshWalkthrough() {
  const id = walkthroughId;
  if (!id) return;
  const res = await api("/api/walkthroughs/" + encodeURIComponent(id));
  if (walkthroughId !== id) return;
  /* no answer (a held replay, an engine restarting) changes nothing */
  if (!res.ok && res.status !== 404) return;
  /* past its end, or out of this viewer's sight: nothing is in hand */
  if (!res.ok || !["running", "stopped"].includes(res.body.state)) {
    if (res.ok && res.body.state === "finished")
      toast(`Finished: ${(res.body.data || {}).title || "the walkthrough"}`);
    dropWalkthrough();
    return;
  }
  walkthroughDoc = res.body;
  walkthroughChip();
}
/* the open invitation of the step in hand. The collection is the read,
   so the chip holds no invitation id that could go stale */
async function ledInvitation() {
  const res = await api("/api/invitations?state=open&walkthrough=" +
                        encodeURIComponent(walkthroughId));
  const item = res.ok && ((res.body.data || {}).items || [])[0];
  if (!item) toast("This step is not open yet");
  return item || null;
}
async function takeLedStep() {
  const item = await ledInvitation();
  if (item) openInvitationRow(item);
}
/* Skip is the invitation's own decline door, in the person's own hand */
async function skipLedStep() {
  const item = await ledInvitation();
  if (!item) return;
  const res = await api(item.self);
  const decline = res.ok && (res.body.actions || {}).decline;
  if (!decline) { toast("Skip is not open to you on this step right now"); return; }
  const out = await invokeBare(decline, res.body);
  toast(out.ok ? "Skipped"
               : `Skip was refused: ${(out.body || {}).detail || out.status}`);
}
/* stop and resume are the walkthrough's own doors */
async function walkLedDoor(name, done) {
  const entry = ((walkthroughDoc || {}).actions || {})[name];
  if (!entry) return;
  const res = await invokeBare(entry, walkthroughDoc);
  toast(res.ok ? done
               : `${pretty(name)} was refused: ${(res.body || {}).detail || res.status}`);
  if (res.ok) { refreshWalkthrough(); render(); }
}
function walkthroughChip() {
  const chip = $("#walkchip");
  const doc = walkthroughDoc;
  chip.style.display = doc ? "inline-block" : "none";
  chip.textContent = "";
  if (!doc) return;
  const d = doc.data || {}, steps = d.steps || [], of = steps.length;
  const n = Math.min(d.current || 1, of);
  const acts = doc.actions || {};
  const btn = (mark, label, onclick) => el("button", {[mark]: "", onclick}, label);
  const stop = acts.stop
    ? btn("data-walk-stop", "Stop", () => walkLedDoor("stop", "Stopped")) : null;
  if (doc.state === "stopped") {
    chip.append(`Stopped at step ${n} of ${of}`);
    if (acts.resume)
      chip.append(btn("data-walk-resume", "Resume",
                      () => walkLedDoor("resume", "Resumed")));
  } else if (d.waiting_on === "agent") {
    /* the agent's step: the person watches, so there is no Skip */
    chip.append(`Step ${n} of ${of} · `, principalRef(d.author || ""),
                ` is working: ${(steps[n - 1] || {}).note || ""}`);
    if (stop) chip.append(stop);
  } else {
    chip.append(el("a", {href: "#" + doc.self}, stepLine(n, of, d.title)),
                btn("data-walk-take", "Take this step", takeLedStep),
                btn("data-walk-skip", "Skip", skipLedStep));
    if (stop) chip.append(stop);
  }
}
/* the row page's step list: every step's note in order, whose step it
   is, how an ended one ended, and which one is open */
function walkthroughSteps(doc) {
  const d = doc.data || {};
  const ended = new Map((d.outcomes || []).map(o => [o.step, o.outcome]));
  return el("ol", {class: "walk-steps", "data-walk-steps": ""},
    (d.steps || []).map((s, i) => el("li",
      {class: doc.state === "running" && d.current === i + 1 ? "current" : ""},
      el("span", {class: "muted"}, `${s.who || "person"} · `),
      s.note || "",
      ended.has(i + 1)
        ? el("span", {class: "muted"}, ` · ${ended.get(i + 1)}`) : null)));
}
/* a quest's row page: the plan as a checklist, in order. A done step is
   struck through, the next one is lit and carries Go, a waiting one
   names who it waits on, a later one is dim, and a choice says what
   must be picked. The page is redrawn on the quest's own transitions
   (scopeHit, 210-ledger.js), so a new plan shows with no reload
   (docs/spec-quests.md, the step vocabulary) */
function questPlan(doc) {
  const d = doc.data || {};
  const steps = d.plan || [];
  const done = steps.filter(s => s.state === "done").length;
  return el("div", {class: "quest-plan", "data-quest-plan": ""},
    d.blocked_reason
      ? el("p", {class: "quest-blocked", "data-quest-blocked": ""}, d.blocked_reason)
      : null,
    steps.length
      /* a plan is never a total: "k done, n known so far" */
      ? el("p", {class: "muted", "data-quest-count": ""},
          `${done} done, ${steps.length} known so far`)
      : el("p", {class: "muted"}, "No step is known yet."),
    el("ol", {class: "quest-steps"}, steps.map(s => questStep(doc, s))),
    d.plan_is_estimate
      ? el("p", {class: "muted", "data-quest-estimate": ""},
          "This plan is an estimate: more steps may appear after these are taken.")
      : null);
}
function questStep(doc, s) {
  const needs = (s.needs || []).map(pretty).join(", ");
  const row = el("li", {class: "quest-step", "data-quest-step": s.state},
    el("span", {class: "quest-note"}, s.note || pretty(s.door)));
  /* the refusal's other remedies: any one of them is this step */
  if (s.state !== "done")
    for (const a of s.alternatives || [])
      row.append(el("small", {class: "muted quest-alt", "data-quest-alt": ""},
        ` or ${pretty(a.door)}`));
  if (s.whose === "choice" && s.state !== "done")
    row.append(el("span", {class: "muted", "data-quest-choice": ""},
      needs ? ` · choose ${needs}` : " · a choice is yours to make"));
  if (s.state === "waiting")
    /* a held call waits for the owner's own tap: the link is the row
       the tap is made on */
    row.append(el("span", {class: "muted", "data-quest-waiting": ""}, " · waiting on ",
      s.whose === "held"
        ? el("a", {href: "#" + s.self}, "your tap")
        : s.waiting_on || "someone else"));
  if (s.state === "next" && doc.state === "active")
    row.append(el("button", {class: "primary small", "data-quest-go": "",
      onclick: () => questGo(s)}, "Go"));
  return row;
}
/* Go: the viewer's open invitation for this step opens in their own
   hand (openInvitationRow, 130-collection.js); with no invitation the
   screen goes to the step's row */
async function questGo(step) {
  const me = viewerId();
  const res = me
    ? await api(`/api/invitations?state=open&subject=${encodeURIComponent(me)}`)
    : null;
  const inv = res && res.ok
    ? (res.body.data?.items || []).find(i =>
        (i.fields || {}).self === step.self && (i.fields || {}).action === step.door)
    : null;
  if (inv) openInvitationRow(inv);
  else go(step.self);
}
/* the next step arrives by itself: the firehose's half (210-ledger.js
   hands every transition here). A transition of the row in hand redraws
   the chip; the person's own start or resume takes the row in hand and
   follows its author in guided mode; an agent step takes the screen to
   its row; an invitation born for the walkthrough in hand and addressed
   to the viewer opens, over a guided dialog, unless a dialog the person
   opened is on screen. */
async function ledFrame(ev) {
  if (replay) return;
  const me = viewerId();
  if (ev.kind === "walkthrough") {
    const id = String(ev.self || "").split("/").pop();
    /* the person's own Start or Resume */
    const tapped = !!me && ["start", "resume"].includes(ev.action) &&
      String((ev.actor || {}).id || "").replace(/^member:/, "") === me;
    if (id === walkthroughId) await refreshWalkthrough();
    else if (!walkthroughId && tapped) await takeWalkthrough(id);
    else return;
    if (tapped) followAuthor();
    /* one beat later: a follow's first jump goes where the author looks
       now, and the step's own row is where the screen rests */
    setTimeout(showAgentStep, 0);
    return;
  }
  if (ev.kind !== "invitation" || ev.action !== "create" || !walkthroughId) return;
  const res = await api(ev.self);
  const d = (res.body || {}).data || {};
  if (!res.ok || res.body.state !== "open" ||
      d.walkthrough !== walkthroughId || d.subject !== me) return;
  if ($("dialog[open]:not([data-guided])")) return;
  openInvitation(res.body);
}
function onLedFrame(ev) { ledFrame(ev).catch(() => {}); }
/* a tab with nothing in hand asks once, at boot, for the person's
   newest running walkthrough and takes it in hand */
wellKnown().then(async w => {
  if (walkthroughId) return refreshWalkthrough();
  const me = viewerId();
  if (!me || !(w.resources || {}).walkthrough) return;
  const res = await api("/api/walkthroughs?state=running&subject=" +
                        encodeURIComponent(me));
  const item = res.ok && ((res.body.data || {}).items || [])[0];
  if (item) takeWalkthrough(item.self.split("/").pop());
}).catch(() => {});

const bootParams = new URLSearchParams(location.search);
if (bootParams.get("follow")) {
  follow({id: bootParams.get("follow"),
          display: bootParams.get("follow_name") || bootParams.get("follow")});
  history.replaceState(null, "", location.pathname + location.hash);
}
followChip();
const $share = $("#sharebtn");
if ($share) $share.addEventListener("click", toggleShareUi);
shareChip();
/* a new screen has no focused row until one is picked */
window.addEventListener("hashchange", () => { UI_SHARE.focus = null; });

/* ── record myself (§4): a walk of my own screen, nobody following.
   ● Record creates a walk whose `followed` is me and turns ⧉ sharing
   on for this tab, so each beat carries the `ui` part the engine
   writes to the walk. ■ Stop seals it and opens its row page, where
   ▶ Replay and the export are. The open walk is read from the engine
   at boot, so a reload keeps recording. */
let recording = null, recordTimer = null;   // {self, since}
/* the walks door and who I am to the engine; null when this engine
   serves no walks */
async function recordDoor() {
  let w = null;
  try { w = await wellKnown(); } catch (_e) { return null; }
  const href = ((w.resources || {}).walk || {}).href;
  const p = w.principal || {};
  const me = principalId() || p.id || "";
  return href ? {href, me, name: principalId() || p.display || me} : null;
}
function recordChip() {
  const b = $("#recordbtn");
  if (!b) return;
  clearInterval(recordTimer);
  b.setAttribute("aria-pressed", String(!!recording));
  if (!recording) {
    b.textContent = "● Record";
    b.title = "record my own screen as a walk: this tab's screens, dialogs "
      + "and writes, to replay or export afterwards";
    return;
  }
  const tick = () => {
    const s = Math.max(0, Math.floor((Date.now() - recording.since) / 1000));
    b.textContent = `■ Stop ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  };
  b.title = "recording this tab — click to stop and open the walk";
  tick();
  recordTimer = setInterval(tick, 1000);
}
function recordingOf(doc) {
  const since = Date.parse((doc.data || {}).started_at || "");
  return {self: doc.self, since: Number.isNaN(since) ? Date.now() : since};
}
async function startRecording() {
  const door = await recordDoor();
  if (!door || !door.me) { toast("Recording needs a named principal"); return; }
  const title = prompt("What does this walk show?",
                       `${door.name}, ${new Date().toLocaleString()}`);
  if (title === null) return;
  const r = await api(door.href, {method: "POST",
    body: JSON.stringify({followed: door.me, title: title.trim() || door.name})});
  if (!r.ok || !r.body || !r.body.self) {
    toast("The recording could not start");
    return;
  }
  recording = recordingOf(r.body);
  recordChip();
  demoRefresh();
  /* the walk takes `ui` frames only while this tab shares; sharing
     this button turned on goes off again at the stop */
  if (uiSharing()) presenceBeat();
  else {
    toggleShareUi();
    try { sessionStorage.setItem("wm10.record.shared", "1"); }
    catch (_e) { /* no storage, no sharing */ }
  }
}
async function stopRecording() {
  const self = recording.self;
  const r = await api(self + "/-/seal", {method: "POST", body: "{}"});
  if (!r.ok) { toast("The recording could not be stopped"); return; }
  recording = null;
  recordChip();
  demoRefresh();
  let mine = false;
  try {
    mine = sessionStorage.getItem("wm10.record.shared") === "1";
    sessionStorage.removeItem("wm10.record.shared");
  } catch (_e) { /* no storage */ }
  if (mine && uiSharing()) toggleShareUi();
  location.hash = "#" + self;
}
/* my open self walk, read at boot: a reload keeps recording */
async function resumeRecording() {
  const b = $("#recordbtn");
  const door = await recordDoor();
  if (!door) { if (b) b.style.display = "none"; return; }
  if (!door.me) return;
  const me = encodeURIComponent(door.me);
  const r = await api(`${door.href}?recorder=${me}&followed=${me}&state=recording`);
  const item = r.ok && ((r.body.data || {}).items || [])[0];
  if (!item || !item.self) return;
  const doc = await api(item.self);
  if (doc.ok && doc.body.state === "recording" && !recording) {
    recording = recordingOf(doc.body);
    recordChip();
  }
}
const $record = $("#recordbtn");
if ($record) {
  $record.addEventListener("click",
    () => (recording ? stopRecording() : startRecording()));
  recordChip();
  resumeRecording().catch(() => { /* engine not started, or restarting */ });
}

/* ── the demo engine's end (docs/spec-demo-clones.md §3): an engine
   whose well-known carries `expires_at` is a demo clone, and the
   reaper takes every row with it. The banner says when, on every
   screen; it turns to a warning at 15 minutes and again at 5, and it
   names each walk of mine the reaper would take: one still recording,
   or one sealed and not exported in this browser. A working engine
   sets no expiry and shows no banner. */
const DEMO_WARN_MS = 15 * 60000, DEMO_LAST_MS = 5 * 60000;
const DEMO_TICK_MS = 30000;
let demoEnds = null, demoWalks = [];   // ms since the epoch; my unexported walks
/* the walks this browser downloaded. The engine keeps no record of an
   export, so the browser's own storage is the only one there is. */
function exportedWalks() {
  try {
    const v = JSON.parse(localStorage.getItem("wm10.walk.exported") || "[]");
    return Array.isArray(v) ? v : [];
  } catch (_e) { return []; }
}
function markWalkExported(self) {
  try {
    localStorage.setItem("wm10.walk.exported",
      JSON.stringify([...new Set([...exportedWalks(), self])]));
  } catch (_e) { /* no storage: the banner keeps naming the walk */ }
}
/* my walks the reaper would take. `state=` clears the collection's
   default, and the two states are picked here. */
async function unexportedWalks() {
  const door = await recordDoor();
  if (!door || !door.me) return [];
  const r = await api(`${door.href}?recorder=${encodeURIComponent(door.me)}&state=`);
  const items = r.ok ? ((r.body.data || {}).items || []) : [];
  const done = new Set(exportedWalks());
  return items.filter(i => i && i.self &&
    (i.state === "recording" || (i.state === "sealed" && !done.has(i.self))));
}
function demoLevel(left) {
  return left <= DEMO_LAST_MS ? "last" : left <= DEMO_WARN_MS ? "warn" : "note";
}
function demoBanner() {
  const box = $("#demobanner");
  if (!box) return;
  if (demoEnds === null) { box.hidden = true; return; }
  box.hidden = false;
  box.setAttribute("data-demo-level", demoLevel(demoEnds - Date.now()));
  box.textContent = "";
  const at = new Date(demoEnds)
    .toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"});
  box.append(`Demo engine. It ends at ${at} and takes everything with it.`);
  for (const w of demoWalks)
    box.append(" ", el("span", {"data-demo-walk": w.state},
      "Your walk is not exported. ",
      w.state === "recording" ? "Seal and export it now: " : "Export it now: ",
      el("a", {href: "#" + w.self}, w.summary || "the walk"), "."));
}
async function demoRefresh() {
  if (demoEnds === null) return;
  /* a replay reads nothing from the engine and a hidden tab holds no
     connection: both repaint from what is already known */
  if (!replay && !document.hidden) {
    try { demoWalks = await unexportedWalks(); }
    catch (_e) { /* the engine is restarting: keep the last answer */ }
  }
  demoBanner();
}
/* the export as a download in this browser: the one thing that leaves
   a demo clone. The walk is marked exported only once the file is
   handed to the browser. */
async function exportWalk(self) {
  let text = null;
  try {
    const res = await fetch(self + "/export", {headers: principalHeaders()});
    if (res.ok) text = await res.text();
  } catch (_e) { /* told below */ }
  if (text === null) { toast("This walk's export could not be read"); return; }
  const url = URL.createObjectURL(new Blob([text], {type: "application/x-ndjson"}));
  const a = el("a", {href: url,
    download: `walk-${self.split("/").pop().slice(0, 8)}.ndjson`});
  document.body.append(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  markWalkExported(self);
  demoRefresh();
}
async function demoBoot() {
  const w = await wellKnown();
  const at = Date.parse(w.expires_at || "");
  if (Number.isNaN(at)) return;
  demoEnds = at;
  demoBanner();
  await demoRefresh();
  setInterval(demoRefresh, DEMO_TICK_MS);
}
demoBoot().catch(() => { /* engine not started, or restarting */ });

/* ── replay (docs/spec-guided-follow.md §4): a sealed walk's export,
   `waymark-walk/1`, played on this screen on a timer. A `move` goes
   through applyFollowMove and a `ui` frame through applyUiFrame, the
   code the live streams use. It is read-only: the one request is the
   export GET (none for a file), render() draws each screen from the
   recording (renderReplay), the dialog is built from the frame, a
   `transition` renders from its own body, and the presence beat is
   held. An `invitation` opens its door's dialog through actionDialog's
   own invitation code, read-only. The cast's display names say who is
   acting. ───────────────────────────────────────────────────────── */
const REPLAY_SPEEDS = [1, 2, 4];
/* a long silence in the recording is cut to this many ms, before speed */
const REPLAY_MAX_GAP = 3000;
/* the one floor: after any change of the screen, the next change waits
   at least this long, before speed, counted from the moment the first
   is drawn (replayDrawn, replayStillLeft). A longer hold stays longer. */
const REPLAY_MIN_STILL = 1000;
/* the beats of one connector call are recorded milliseconds apart
   (docs/spec-agent-demo-walks.md §2): two frames closer than
   REPLAY_BURST_MS are played REPLAY_BURST_GAP apart, before speed, and
   that is the floor. No browser makes such a burst, since a form's
   reports are debounced. */
const REPLAY_BURST_MS = 50, REPLAY_BURST_GAP = REPLAY_MIN_STILL;
/* the frame after a caption waits for the caption to be read
   (docs/spec-agent-demo-walks.md §3): REPLAY_READ_MS for each
   character, at least REPLAY_READ_MIN and at most REPLAY_READ_MAX,
   before speed, and on top of the gap the long-silence cut allows. */
const REPLAY_READ_MS = 55, REPLAY_READ_MIN = 1500, REPLAY_READ_MAX = 6000;
let replayGazeTimer = null;
/* a write and the screen it lands on are held for the eye: the screen
   is still at least REPLAY_WRITE_HOLD after a `transition`, before
   speed. A hold is stillness, as the floor is: a floor under the gap
   and no addition to it, so REPLAY_MAX_GAP is still the most a
   recorded silence plays. */
const REPLAY_WRITE_HOLD = 1500;
/* the gesture before an act: a pointer glides to the link or the button
   a person would press for REPLAY_GLIDE_MS, and that element is lit for
   REPLAY_PRESS_MS, before speed; then the frame is applied. The gesture
   starts only after the screen it leaves has been still for its floor
   (below), and the frame waits for both. The pointer is the only way
   the screen changes: a move whose link is not on screen goes by the
   navigation bar and the kind's list (replayHop), a dialog beat, a
   write or an invitation on a row that is not on screen goes to that
   row the same way first (replayWalkOf), a typing beat's field
   and a write's submit button are pressed in the form, and only a step
   with no click behind it (replayNotice) is applied with no gesture. */
const REPLAY_GLIDE_MS = 600, REPLAY_PRESS_MS = 300;
/* the floor is stillness: the pointer leaves a screen only after it
   has been still a while. The gesture toward the next act starts no
   sooner than REPLAY_MIN_STILL after any change of the screen (a move,
   a hop's list, a walk's row, a dialog opening or closing, a typed
   value, a caption, a `doc` that draws the screen again), and no
   sooner than REPLAY_WRITE_HOLD after a transition lands, before speed.
   The stillness is counted from the moment the change is drawn, and
   not from the frame that asked for it (replayStillLeft).
   The gesture's glide and press come after the stillness and not
   inside it, so the frame waits the larger of its recorded gap and
   stillness + glide + press. A frame that changes nothing on screen
   waits nothing (replayShows). */
/* the moment of a look: the screen the gaze arrived at keeps its
   outline this long, before speed. It is the screen's stillness, so the
   outline is off when the pointer leaves. */
const REPLAY_GAZE_MS = REPLAY_MIN_STILL;
function parseWalk(text) {
  let docs;
  try {
    docs = String(text || "").split("\n").filter(l => l.trim())
      .map(l => JSON.parse(l));
  } catch (_e) { return null; }
  const [header, ...frames] = docs;
  if (!header || header.format !== "waymark-walk/1") return null;
  return {header,
          frames: replayFormMoves(frames.filter(f => f && typeof f.type === "string")
                        .sort((a, b) => (a.t || 0) - (b.t || 0)))};
}
/* a `move` to the row a hand's open form stands on moves no screen.
   The engine records one when their presence was elsewhere with no beat
   of their tab's (a stream the page opened, a read) and the form's next
   beat brought it back: the beat that closes a form kept as a quest,
   after the tracker opened the quest's stream. It is left out, so it
   does not end the form before the press that closes it. */
function replayFormMoves(frames) {
  const on = new Map();          // who → the row their open form is on
  return frames.filter(f => {
    const row = String(f.self || "").split("?")[0];
    if (f.type === "ui") {
      if ((f.ui || {}).dialog) on.set(f.who, row);
      else on.delete(f.who);
      return true;
    }
    return !(f.type === "move" && on.get(f.who) === row);
  });
}
function startReplay(text) {
  const walk = parseWalk(text);
  if (!walk) { toast("That is not a waymark-walk/1 recording"); return false; }
  /* where the person was: stopping goes back there */
  const back = replay ? replay.back : location.hash;
  stopReplay(true);
  closeGuided();
  const r = {title: walk.header.title || "a walk", engine: walk.header.engine,
             cast: walk.header.cast || {}, frames: walk.frames,
             at: 0, speed: 1, playing: false, timer: null, who: null, back,
             caption: null,       // the caption frame on screen
             gaze: null,          // the address last outlined
             beat: null,          // the `ui` beat last played (replayBeat)
             recorder: null,      // the cast alias whose hand the pointer is
             notice: null,        // the line of a step with no click behind it
             refusals: [],        // the lines of the refusals no form was open for
             quest: null,         // the pinned active quest's document (replayQuest)
             questDone: null,     // the title of the quest that has just finished
             hopped: null,        // the frame a hop to its kind's list was made for
             walked: null,        // the frame a walk to its row was made for
             held: null,          // the frames waiting behind an open form
             drawn: 0,            // when the screen's last change was drawn
             drawing: 0,          // the draws that are not done
             asked: 0,            // when the first of those began
             still: 0,            // the stillness the frame at the playhead waits for
             rows: new Map(),     // self → {kind, state, summary, log}
             docs: new Map(),     // self, the document recorded for it so far
             known: new Set(),    // every row self the recording names
             fields: new Map()};  // dialog key → its field names
  for (const f of walk.frames) {
    /* the recorder is the one whose screen the walk follows: the first
       to move or to report a `ui` beat */
    if (!r.recorder && (f.type === "move" || f.type === "ui")) r.recorder = f.who || null;
    const ui = (f.type === "ui" && f.ui) || {};
    /* an invitation names a dialog as well: its row's door, with the
       invited fields and the suggested ones; and so does a caption
       anchored to a field */
    const inv = (f.type === "invitation" || (f.type === "caption" && f.field))
      && f.self && f.action;
    const d = inv ? {self: String(f.self).split("?")[0], action: f.action}
                  : ui.dialog;
    /* a typing beat's `focus` names a field, not a row */
    const row = String(ui.focus || "").startsWith("/") ? ui.focus : null;
    for (const s of [f.self, row, d && d.self])
      if (s) r.known.add(String(s).split("?")[0]);
    if (d) {
      const key = d.self + " " + d.action;
      const names = r.fields.get(key) || new Set();
      const typed = inv ? [f.field, ...(f.fields || []),
                           ...Object.keys(f.suggest || {})]
                        : Object.keys(ui.fields || {});
      for (const k of typed) if (k) names.add(k);
      r.fields.set(key, names);
    }
  }
  replay = r;
  /* the live quest's tracker gives way to the walk's own */
  questTracker();
  /* a walk that carries its screens has them drawn by the live screen
     code, whose reads are held for as long as the replay lasts */
  apiHeld = walk.frames.some(f => f.type === "doc");
  guidedFocus = null;
  guidedLastFields = {};
  guidedLastLabels = {};
  guidedDismissed = null;
  render();
  playReplay();
  return true;
}
function replayActor(f) {
  const c = replay.cast[f.who] || {};
  return {id: f.who || "", display: c.display || f.who || "someone",
          type: c.type || "human"};
}
/* the row (or, for a create, the collection) and the door a recorded
   dialog names, as a document
   actionDialog can draw: every field the recording typed into, as
   text. The export carries no schema, so none is invented. */
function replayDialogDoc(d) {
  /* a walk that carries this row's document
     (docs/spec-agent-demo-walks.md §8a) has the dialog drawn from that
     document's own input schema */
  const held = replay.docs.get(d.self);
  if (held && (held.actions || {})[d.action]) return {ok: true, body: held};
  const names = replay.fields.get(d.self + " " + d.action) || new Set();
  /* a create's dialog is on the collection: there is no row behind it,
     and the kind is the recorded screen's when the walk carries one */
  const row = replay.rows.get(d.self) || {};
  return {ok: true, body: {
    self: d.self, kind: row.kind || (held && held.kind) || "",
    state: row.state || null,
    actions: {[d.action]: {
      safety: {idempotent: true},
      input: {type: "object", properties: Object.fromEntries(
        [...names].map(k =>
          [k, {type: "string", "x-display": {widget: "textarea"}}]))}}}}};
}
/* an `invitation` frame applied: the invited row, and its door's
   dialog as actionDialog draws a live invitation — the suggestions
   marked, every invited field lit, the note beside the first, a
   walkthrough's "Step 2 of 4" line above the form — read-only, with
   only Cancel in the footer. It holds the screen as an invited
   person's own dialog does, until the transition that answers it. A
   row that is not on screen was walked to before this (replayWalkOf):
   the hash is set here only for one with no link to press. */
async function openReplayInvitation(f, actor) {
  const r = replay;
  const d = {self: String(f.self).split("?")[0], action: f.action};
  const subject = (r.cast[f.subject] || {}).display || f.subject || "someone";
  closeGuided();
  if ($("dialog[open]")) return;
  if (d.self !== hereHref()) location.hash = "#" + d.self;
  const doc = replayDialogDoc(d).body;
  await actionDialog({name: d.action, entry: doc.actions[d.action], doc,
    suggest: f.suggest || {},
    /* a frame recorded before `fields` holds `field` alone */
    invitation: {doc: {}, note: f.note, step: f.step, of: f.of,
                 fields: f.fields || (f.field ? [f.field] : [])},
    guided: {name: actor.display, key: d.self + " " + d.action,
             note: `${actor.display} invited ${subject} to this step`}});
  const g = $("dialog[open][data-guided]");
  if (!g) return;
  if (replay !== r) { closeGuided(); return; }
  g.setAttribute("data-replay-invite", "");
  g.guidedSet(f.suggest || {});
}
/* the form is gone: the frames that waited behind it (replay.held) are
   drawn on the screen it was over, in the order they were recorded,
   and that screen wears the arrival outline when a write is among
   them. A form the recording leaves open at its end (`open`) stays, as
   it always did, and what waited is drawn under it. → the line of a
   step with no click among them (replayNotice), or null. */
function replayLand(r, open) {
  const held = r.held || [];
  r.held = null;
  if (!held.length) return null;
  if (!open) closeGuided();
  let wrote = null, note = null;
  for (const f of held) {
    applyReplayFrame(f, true);
    if (f.type !== "transition") continue;
    wrote = f.self || wrote;
    note = r.notice || note;
  }
  if (wrote) {
    r.gaze = null;
    replayGaze("row", wrote);
  }
  return note;
}
/* the tracker a replay draws (questTracker, 120-nav-home.js): the
   latest quest document at or before the playhead. A pinned active
   quest is shown. When the one shown finishes, the tracker says so
   until another is pinned; unpinned, paused or abandoned, it hides. */
function replayQuest(r, self, doc) {
  if (doc.kind !== "quest") return;
  const shown = !!r.quest && r.quest.self === self;
  if (doc.state === "active" && (doc.data || {}).pinned) {
    r.quest = {...doc, self};
    r.questDone = null;
  } else if (shown) {
    r.quest = null;
    r.questDone = doc.state === "finished" ? questTitle(doc) : null;
  } else return;
  questTracker();
}
function applyReplayFrame(f, landed) {
  /* no screen changes behind an open form: a `transition` or a `doc`
     recorded while the form is open waits (replay.held), so the
     submit's press and the close play first. The frame that ends the
     form draws what waited, and so does the next frame that is not the
     form's own when the recording never closes it; a caption ends no
     form. `landed` is a frame that waited, drawn now. */
  const form = f.type === "ui" && !!(f.ui || {}).dialog;
  if (!landed && replay.held && (f.type === "transition" || f.type === "doc")) {
    replay.held.push(f);
    replayChip();
    return;
  }
  const note = landed || replayKeepsForm(f) ? null : replayLand(replay);
  if (form && !replay.held) replay.held = [];
  /* a `doc` frame is a screen and nobody's act: it is kept for
     renderReplay, and its screen is drawn again when it is the one
     showing */
  if (f.type === "doc") {
    const self = String(f.self || "").split("?")[0];
    if (self && f.doc) {
      /* a document equal to the one held is on screen already */
      const same = JSON.stringify(replay.docs.get(self)) === JSON.stringify(f.doc);
      replay.docs.set(self, f.doc);
      if (!same) replayQuest(replay, self, f.doc);
      if (!same && self === String(hereHref() || "").split("?")[0]) render();
    }
    replayChip();
    return;
  }
  /* a `refusal` frame is the answer the open form got and moves no
     screen: its box is drawn in that form (replayRefuse), or its line
     in the caption band when no form was open for it (a bulk write's
     refused row) */
  if (f.type === "refusal") {
    replayRefuse(f);
    replayChip();
    return;
  }
  const actor = replayActor(f);
  replay.who = actor;
  /* a `ui` beat equal to the act before it is on screen already: it is
     not drawn again */
  const beat = f.type === "ui" ? replayBeat(f) : null;
  const again = !!beat && beat === replay.beat;
  replay.beat = beat;
  replay.notice = replayNotice(replay, f);
  /* the recorder's next step takes the band's refusals with it */
  if (f.type === "move" || f.type === "ui") replay.refusals = [];
  if (note && !replay.notice) replay.notice = note;
  if (f.type === "move") {
    if (f.self) { applyFollowMove(f.self); replayGaze("row", f.self); }
  } else if (f.type === "ui" && !again) {
    applyUiFrame({self: f.self, ui: f.ui || {}, principal: actor});
    /* whether the recording has a form open: its dialog is drawn a
       moment after this, and no gesture is made under it */
    replay.door = !!(f.ui || {}).dialog;
    /* a form that closed takes its refusal with it */
    if (!replay.door) replay.refused = null;
    const c = (f.ui || {}).collection;
    if (c && c.self && !(f.ui || {}).dialog)
      replayGaze("list", collectionHrefOf(c));
  } else if (f.type === "transition" && f.self) {
    const row = replay.rows.get(f.self) || {log: []};
    row.kind = f.kind;
    row.state = f.to;
    row.summary = f.summary || row.summary;
    row.log.push({...f, actor});
    replay.rows.set(f.self, row);
    /* the write an open invitation asked for: its dialog closes */
    const inv = $("dialog[open][data-replay-invite]");
    if (inv && inv.getAttribute("data-guided") === f.self + " " + f.action)
      closeGuided();
    /* as the firehose steers: go where they wrote, unless a dialog is
       open; a row already on screen is drawn again from the frame, and
       one that was not was walked to before this (replayWalkOf), so
       the hash is set here only for a row with no link to press. A
       step with no click behind it moves no screen: its notice says it */
    if (f.self === hereHref()) render();
    /* a write that waited behind a form is drawn on the screen the form
       was over, and goes nowhere */
    else if (landed) { /* the screen stays */ }
    else if (!$("dialog[open]") && !replay.notice) location.hash = "#" + f.self;
  } else if (f.type === "invitation" && f.self && f.action) {
    openReplayInvitation(f, actor);
  } else if (f.type === "caption") {
    /* one line about the step that follows; an empty one clears it */
    replay.caption = f.text ? f : null;
  }
  replayCaption();
  replayChip();
}
function replaySchedule() {
  const r = replay;
  clearTimeout(r.timer);
  replayGestureRest(r);
  /* a form the recording never closes, and no frame after it: what
     waited behind it is drawn when the last frame has played */
  if (r.at >= r.frames.length && r.held) {
    r.notice = replayLand(r, true) || r.notice;
    replayCaption();
  }
  if (film && r.at >= r.frames.length) filmEnd();
  if (r.at >= r.frames.length) { r.playing = false; replayChip(); return; }
  /* the screen is still for its floor first and the gesture comes after
     it: the frame waits for both when its recorded gap is shorter. The
     stillness is counted from the moment the screen was drawn, so what
     the screen has had of it is not waited for again */
  const still = replayHoldTime(r.frames, r.at);
  r.still = still;
  const left = Math.max(0, still - (performance.now() - r.drawn) * r.speed);
  const gap = Math.max(replayGap(r),
    still && left + REPLAY_GLIDE_MS + REPLAY_PRESS_MS);
  r.timer = setTimeout(replayStep, gap / r.speed);
  /* the gesture ends as the wait does, and never starts inside the
     stillness */
  r.lead = setTimeout(() => replayGesture(r),
    Math.max(left, gap - REPLAY_GLIDE_MS - REPLAY_PRESS_MS) / r.speed);
}
/* the wait before the frame at `r.at`, as it was recorded: a burst is
   spread, a long silence is cut, and a caption is given its reading
   time on top */
function replayGap(r) {
  const prev = r.at ? (r.frames[r.at - 1].t || 0) : 0;
  const dt = Math.max(0, (r.frames[r.at].t || 0) - prev);
  const read = replayReadingTime(r.at ? r.frames[r.at - 1] : null);
  /* a frame that changes nothing on screen folds into the one before it */
  if (!read && replayStill(r.frames, r.at)) return 0;
  const gap = read + Math.min(REPLAY_MAX_GAP,
                       r.at && dt < REPLAY_BURST_MS ? REPLAY_BURST_GAP : dt);
  return gap;
}
/* a `ui` beat as one string: who reported it, where, and what. Two
   beats with one string draw one screen. */
function replayBeat(f) {
  return JSON.stringify([f.who || "", f.self || "", f.ui || {}]);
}
/* whether the frame `f` ends no form: the form's own beats, a caption,
   the refusal the form got, and the frames that wait behind the form. */
function replayKeepsForm(f) {
  if (f.type === "refusal") return true;
  return f.type === "transition" || f.type === "doc" || f.type === "caption" ||
    (f.type === "ui" && !!(f.ui || {}).dialog);
}
/* whether the frame at `at` waits behind an open form, as
   applyReplayFrame holds it: a `transition` or a `doc` recorded after
   a `ui` beat with a dialog, with nothing between them but captions
   and other frames that wait. */
function replayHeld(frames, at) {
  const f = frames[at];
  if (!f || (f.type !== "transition" && f.type !== "doc")) return false;
  for (let i = at - 1; i >= 0; i--) {
    const t = frames[i].type;
    if (t === "ui") return !!(frames[i].ui || {}).dialog;
    if (t === "refusal") continue;
    if (t !== "transition" && t !== "doc" && t !== "caption") return false;
  }
  return false;
}
/* whether the frame at `i` draws a write that waited behind a form: it
   ends the form (applyReplayFrame), and the last transition before it
   waits behind that form. */
function replayLands(frames, i) {
  if (replayKeepsForm(frames[i])) return false;
  for (let j = i - 1; j >= 0; j--) {
    if (frames[j].type === "transition") return replayHeld(frames, j);
    if (!replayKeepsForm(frames[j])) return false;
  }
  return false;
}
/* whether the frame at `at` changes nothing a viewer sees, and so
   plays with no wait: a `doc` in the burst of the frame before it is
   that frame's own screen, a `doc` equal to the last one recorded for
   its screen draws nothing new, and neither does a `ui` beat equal to
   the act before it. */
function replayStill(frames, at) {
  const f = frames[at];
  if (!f || !at) return false;
  /* a frame that waits behind a form is drawn when the form is gone;
     with no frame after it to end the form, it is drawn in its own
     time, at the end */
  if (replayHeld(frames, at) &&
      frames.some((g, k) => k > at && !replayKeepsForm(g))) return true;
  if (f.type === "doc") {
    if ((f.t || 0) - (frames[at - 1].t || 0) < REPLAY_BURST_MS) return true;
    for (let i = at - 1; i >= 0; i--)
      if (frames[i].type === "doc" && frames[i].self === f.self)
        return JSON.stringify(frames[i].doc) === JSON.stringify(f.doc);
    return false;
  }
  if (f.type !== "ui") return false;
  let i = at - 1;
  while (i >= 0 && frames[i].type === "doc") i--;
  return !!frames[i] && frames[i].type === "ui" &&
    replayBeat(frames[i]) === replayBeat(f);
}
/* whether the frame at `at` changes what a viewer sees. Every frame
   does, unless it changes nothing (replayStill); a `doc` does only when
   it is for the row or the list on screen, which is then drawn again.
   One for another address is kept and draws nothing. */
function replayShows(frames, at) {
  const f = frames[at];
  if (!f || replayStill(frames, at)) return false;
  if (f.type !== "doc") return true;
  /* a quest's document draws the tracker, which is on every screen */
  if (f.doc && f.doc.kind === "quest") return true;
  const row = s => String(s || "").split("?")[0];
  return !!f.doc && !!row(f.self) && row(f.self) === row(hereHref());
}
/* how long the screen is still before the gesture toward the frame at
   `at` may start: the stillness owed to the last change of the screen
   before it. There is one rule: after any frame that changes the
   screen (replayShows), the next one that does waits at least
   REPLAY_MIN_STILL, and REPLAY_WRITE_HOLD after a transition. A frame
   that changes nothing is never held back and owes nothing; the frame
   before it is the one that counts. */
function replayHoldTime(frames, at) {
  if (!replayShows(frames, at)) return 0;
  let i = at - 1;
  while (i >= 0 && !replayShows(frames, i)) i--;
  const f = frames[i];
  if (!f) return 0;
  /* the frame that ends a form shows the write that waited behind it:
     the write's own hold is owed from there */
  if (replayLands(frames, i)) return REPLAY_WRITE_HOLD;
  if (f.type === "transition") return REPLAY_WRITE_HOLD;
  return REPLAY_MIN_STILL;
}
/* a screen of the replay is being drawn (render): the stillness it is
   owed is counted from the moment the draw is done, and not from the
   frame that asked for it. */
function replayDrawn(r, draw) {
  if (!r.drawing++) r.asked = performance.now();
  const done = () => { r.drawing--; r.drawn = performance.now(); };
  return Promise.resolve(draw).then(done, done);
}
/* how long the screen is yet to be left alone before the gesture toward
   the frame at the playhead, in ms after speed: what is left of its
   stillness (r.still), counted from the moment the last change was
   drawn. A draw that is not done has not begun it, and is waited for
   no longer than a long silence. */
function replayStillLeft(r) {
  const now = performance.now();
  if (!r.still) return 0;
  if (r.drawing && now - r.asked < REPLAY_MAX_GAP) return REPLAY_BURST_MS;
  return r.drawn + r.still / r.speed - now;
}
/* how long the frame after `f` waits for `f` to be read: nothing,
   unless `f` is a caption with a line in it */
function replayReadingTime(f) {
  /* a refusal drawn in the band is a line to read as well */
  if (f && f.type === "refusal" && replay && replayFormless(replay, f))
    f = {type: "caption", text: replayRefusedLine(replay, f)};
  if (!f || f.type !== "caption" || !f.text) return 0;
  return Math.min(REPLAY_READ_MAX,
                  Math.max(REPLAY_READ_MIN, REPLAY_READ_MS * f.text.length));
}
/* the caption on screen (docs/spec-agent-demo-walks.md §3): a band
   holds the line until the next caption replaces it or an empty one
   clears it. While the form the caption is about is open, the line is
   drawn in it as well, by the code that draws an invitation's note:
   beside its field, with the field lit, when the caption names one. */
function replayCaption() {
  const c = replay && replay.caption;
  /* a step with no click behind it (replayNotice) says so in the band,
     in the caption's place, until the next frame is applied */
  /* and so does a refusal no form was open for (replayRefuse): one line
     for each refused row, until the recorder's next step */
  const n = replay &&
    [...replay.refusals, replay.notice].filter(Boolean).join("\n");
  let band = $("#replaycaption");
  if (!band && (c || n))
    document.body.append(band = el("div", {id: "replaycaption", role: "status"}));
  if (band) {
    band.textContent = c ? c.text : "";
    band.style.display = c && !filmBeside(c) ? "block" : "none";
    if (n) {
      band.textContent = n;
      band.style.display = "block";
    }
    band.toggleAttribute("data-replay-notice", !!n);
  }
  const g = $("dialog[open][data-guided]:not([data-replay-invite])");
  if (!g || !g.guidedMark) return;
  const here = !!c && !!c.action &&
    String(c.self).split("?")[0] + " " + c.action === g.getAttribute("data-guided");
  const old = g.querySelector("[data-caption-note]");
  if (old && here && old.textContent === c.text) return;
  if (old) {
    old.remove();
    for (const s of g.querySelectorAll(".invited")) s.classList.remove("invited");
  }
  if (here)
    g.guidedMark(c.field ? [c.field] : [], c.text)
      .setAttribute("data-caption-note", "");
}
/* a look: the gaze arrived at a row (a `move`) or a list (a
   `collection` ui). The root element carries the mark for
   REPLAY_GAZE_MS, so a screen drawn after the move is outlined as well
   (030-screens.css). A look at the address last outlined is no
   arrival, and under an open dialog the screen did not move. */
function replayGaze(what, target) {
  const r = replay;
  if (!r || r.gaze === target || $("dialog[open]")) return;
  r.gaze = target;
  const root = document.documentElement;
  clearTimeout(replayGazeTimer);
  root.setAttribute("data-replay-gaze", what);
  replayGazeTimer = setTimeout(() => root.removeAttribute("data-replay-gaze"),
                               REPLAY_GAZE_MS / r.speed);
}
/* whether the form `key` names wrote before the frame at the playhead
   closes it: a transition of the same actor's since the form opened,
   or in the same burst as the close. A form closed with none was
   cancelled. */
function replayWrote(r, key) {
  const who = r.frames[r.at].who, t = r.frames[r.at].t || 0;
  for (let i = r.at + 1; i < r.frames.length &&
                         (r.frames[i].t || 0) - t < REPLAY_BURST_MS; i++)
    if (r.frames[i].type === "transition" && r.frames[i].who === who) return true;
  for (let i = r.at - 1; i >= 0; i--) {
    const f = r.frames[i];
    if (f.who !== who) continue;
    if (f.type === "transition") return true;
    const d = f.type === "ui" && (f.ui || {}).dialog;
    if (f.type === "ui" && (!d || d.self + " " + d.action !== key)) return false;
  }
  return false;
}
/* whether the frame `f` is a quest's `create` by `who` whose goal is
   the door `key` names. The goal is read from the quest's own document,
   which the walk holds from the quest's first plan on. */
function replayKeeps(r, f, who, key) {
  if (f.type !== "transition" || f.who !== who || f.kind !== "quest" ||
      f.action !== "create") return false;
  const held = r.frames.find(g => g.type === "doc" && g.self === f.self && g.doc);
  const d = (held && held.doc.data) || {};
  return String(d.self || "").split("?")[0] + " " + d.action === key;
}
/* the door a `refusal` frame is about, as a guided dialog is keyed */
function replayRefusedKey(f) {
  return String(f.self || "").split("?")[0] + " " + f.action;
}
/* whether the recording had no form open for the refusal `f`: the same
   hand's last `ui` beat before it shows no dialog, or another door's,
   or a `move` came after that beat. A bulk write is so: it is staged
   as a move to the collection and then the writes, and each row it
   refused has a frame of its own (docs/spec-agent-demo-walks.md §2). */
function replayFormless(r, f) {
  const key = replayRefusedKey(f);
  for (let i = r.frames.indexOf(f) - 1; i >= 0; i--) {
    const n = r.frames[i];
    if (n.type === "move") return true;
    if (n.type !== "ui" || n.who !== f.who) continue;
    const d = (n.ui || {}).dialog;
    return !d || String(d.self || "").split("?")[0] + " " + d.action !== key;
  }
  return true;
}
/* the line the caption band shows for a refusal no form was open for:
   the row, by the name the recording has for it, and its problem. */
function replayRefusedLine(r, f) {
  const self = String(f.self || "").split("?")[0];
  const name = (r.rows.get(self) || {}).summary ||
    (r.docs.get(self) || {}).summary || self;
  return "Refused: " + [name, f.detail || f.title].filter(Boolean).join(": ");
}
/* whether the refusal `f` was kept as a goal: the same hand's quest for
   that door comes after it, before their next refusal. */
function replayKept(r, f) {
  const key = replayRefusedKey(f);
  for (let i = r.frames.indexOf(f) + 1; i > 0 && i < r.frames.length; i++) {
    const n = r.frames[i];
    if (replayKeeps(r, n, f.who, key)) return true;
    if (n.type === "refusal" && n.who === f.who) return false;
  }
  return false;
}
/* a `refusal` frame: the answer the recorder's write door gave. It is
   kept (replay.refused) until its form closes, and drawn in that form
   by the live code (dlg.guidedRefuse, 180-action-dialog.js): the
   problem box, or each field's message under its field for a schema
   refusal (`errors`), and "Accept as quest" when the recording keeps it
   as a goal, so the pointer presses it where the person saw it. A form
   not drawn yet draws it when it opens (openGuidedDialog). A refusal no
   form was open for (replayFormless) is drawn in the caption band
   (replayCaption), one line for each, and the form that is open keeps
   its own. */
function replayRefuse(f) {
  if (!replay) return;
  if (f && replayFormless(replay, f)) {
    replay.refusals.push(replayRefusedLine(replay, f));
    replayCaption();
    return;
  }
  replay.refused = f || null;
  const g = $("dialog[open][data-guided]:not([data-replay-invite])");
  if (!f || !g || !g.guidedRefuse ||
      g.getAttribute("data-guided") !== replayRefusedKey(f)) return;
  g.guidedRefuse({title: f.title, detail: f.detail, remedies: f.remedies || [],
                  errors: f.errors || {}},
                 replayKept(replay, f));
}
/* whether the form `key` ended as a quest and not as a write: a quest's
   `create` by the same hand, found as replayWrote finds a write, whose
   goal is that form's door (replayKeeps). */
function replayAccepted(r, key) {
  const who = r.frames[r.at].who, t = r.frames[r.at].t || 0;
  const kept = f => replayKeeps(r, f, who, key);
  for (let i = r.at + 1; i < r.frames.length &&
                         (r.frames[i].t || 0) - t < REPLAY_BURST_MS; i++)
    if (kept(r.frames[i])) return true;
  for (let i = r.at - 1; i >= 0; i--) {
    const f = r.frames[i];
    if (kept(f)) return true;
    if (f.type !== "ui" || f.who !== who) continue;
    const d = (f.ui || {}).dialog;
    if (!d || d.self + " " + d.action !== key) return false;
  }
  return false;
}
/* a step with no click behind it: a transition by a principal other
   than the recorder (a scheduled action firing, a seat, another
   person), a `clock_shift`, and an invitation the recorder did not
   write. It makes no gesture and moves no pointer and no screen; only
   an invitation's row is walked to before it (replayWalkOf). → the
   line the caption band shows for it, or null for a step the pointer
   makes. */
function replayNotice(r, f) {
  const other = !!r.recorder && !!f.who && f.who !== r.recorder;
  const actor = replayActor(f);
  if (f.type === "transition") {
    const what = f.summary || `${pretty(f.action || "")} · ${pretty(f.kind || "")}`;
    if (f.kind === "clock_shift") return "Later: " + what;
    if (other)
      return (actor.type === "system" ? "Scheduled" : actor.display) + ": " + what;
  }
  if (f.type === "invitation" && other)
    return "Invited: " + (f.note || pretty(f.action || ""));
  return null;
}
/* the field the typing beat `f` types into: the one its `focus` names
   (a staged call, docs/spec-agent-demo-walks.md §2), or the first whose
   value differs from the beat before it (a person's form). Null when
   the beat types nothing. */
function replayTypingOf(f) {
  const ui = (f.type === "ui" && f.ui) || {};
  if (!ui.dialog) return null;
  if (typeof ui.focus === "string" && !ui.focus.startsWith("/")) return ui.focus;
  const now = ui.fields || {};
  return Object.keys(now).find(k =>
    JSON.stringify(now[k]) !== JSON.stringify(guidedLastFields[k])) || null;
}
/* the element a person would press, on the screen now shown, to cause
   the frame `f`: for a move, the link to that row on the page (a
   collection row, a ref link, a breadcrumb), or the navigation bar's
   entry for it or for its kind; for a dialog, the action button of that
   door on that row; for a query, the filter control of the list shown,
   or the link or the navigation entry of another; in an open form, the
   field a typing beat types into, or the submit button for the beat
   that closes the form after its write. A move whose row has no link
   on the page goes to the navigation entry of its kind, unless that
   list is the screen shown: replayHop draws the list, and the row's
   link is pressed there. Null when the screen
   has none, or when the frame changes no screen: the frame is then
   applied with no gesture. */
function replayGestureTarget(f) {
  if (!f || !replay) return null;
  const row = s => String(s || "").split("?")[0];
  const seen = e => e.getClientRects().length > 0;
  const ui = (f.type === "ui" && f.ui) || {}, d = ui.dialog, c = ui.collection;
  /* the pointer fills the form; an invitation's dialog has no submit. A
     `move` ends a form as its closing beat does (replayKeepsForm), and a
     recorded walk may hold one before that beat: the press is made there */
  const g = $("dialog[open][data-guided]:not([data-replay-invite])");
  if (g && g.guidedField && (f.type === "ui" || f.type === "move")) {
    const key = g.getAttribute("data-guided");
    if (d) return d.self + " " + d.action === key
      ? g.guidedField(replayTypingOf(f)) : null;
    if (!replayWrote(replay, key)) return null;
    /* a refusal kept as a goal: the press is on "Accept as quest", under
       the refusal's own box; the footer's is for a walk recorded before
       a walk held its refusals */
    if (replayAccepted(replay, key))
      return g.querySelector("[data-quest-accept]") || g.guidedAccept();
    /* the button that writes, drawn unlit until the pointer presses it */
    let write = g.querySelector("[data-replay-write]");
    if (!write) {
      write = g.guidedWrite();
      write.classList.remove("invited");
      write.setAttribute("data-replay-write", "");
    }
    return write;
  }
  if (replay.door || $("dialog[open]")) return null;
  const link = (box, hit) =>
    [...document.querySelectorAll(box + " a[href^=\"#/\"]")]
      .find(a => seen(a) && hit(a.getAttribute("href").slice(1)));
  const nav = path => link("#kinds", h => row(h) === path);
  const here = hereHref();
  if (f.type === "move" && f.self) {
    const to = row(f.self), list = to.replace(/\/[^/]+$/, "");
    if (to === here) return null;
    return link("#view", h => row(h) === to) || nav(to) ||
      (list !== row(here) && nav(list)) || null;
  }
  if (d) {
    if (d.self + " " + d.action === guidedDismissed) return null;
    /* the tracker's head step is taken from the tracker: its Go */
    const head = replay.quest && questHead(replay.quest);
    const go = $("#questbar [data-tracker-go]");
    if (go && seen(go) && head && !questWaits(head) &&
        row(head.self) === d.self && head.door === d.action) return go;
    const doors = [...document.querySelectorAll("#view button[data-action]")]
      .filter(b => seen(b) && b.dataset.action === d.action);
    const rowOf = b => (b.closest("tr[data-self]") || {dataset: {}}).dataset.self;
    return doors.find(b => rowOf(b) === d.self) ||
      (d.self === here && doors.find(b => !rowOf(b))) || null;
  }
  if (c && c.self) {
    const target = collectionHrefOf(c);
    const shown = collectionHrefOf(
      collectionShareOf(location.hash.slice(1)) || {self: ""});
    if (target === shown) return null;
    return (row(c.self) === here &&
            ($("#view .filterwrap > button") || $("#view .filterbar"))) ||
      link("#view", h => h === target) || nav(row(c.self)) || null;
  }
  return null;
}
/* the replay pointer: a small arrow drawn over the page (030-screens.css),
   in film mode as well. It glides from where it last stood, and from
   the middle of the screen the first time. */
function replayPointerTo(target, speed) {
  target.scrollIntoView({block: "nearest", inline: "nearest"});
  /* a modal dialog is drawn over the whole page, so the pointer for a
     form is drawn inside its dialog */
  const host = target.closest("dialog[open]") || document.body;
  let p = $("#replaypointer");
  if (!p) {
    document.body.append(p = el("div", {id: "replaypointer", "aria-hidden": "true"}));
    /* one drawn again, after its dialog was closed under it, starts
       where the last one stood */
    const [x, y] = replayPointerAt || [innerWidth / 2, innerHeight / 2];
    p.style.transform = `translate(${x}px, ${y}px)`;
  }
  if (p.parentElement !== host) host.append(p);
  p.getBoundingClientRect();             // the glide starts from here
  const b = target.getBoundingClientRect();
  replayPointerAt = [Math.round(b.left + Math.min(b.width / 2, 28)),
                     Math.round(b.top + b.height / 2)];
  p.style.transitionDuration = REPLAY_GLIDE_MS / speed + "ms";
  p.style.transform = `translate(${replayPointerAt[0]}px, ${replayPointerAt[1]}px)`;
}
/* where the pointer last stood, or null before its first gesture */
let replayPointerAt = null;
/* the gesture for the frame at the playhead, begun one time: the
   pointer glides to its target, and the target is then lit in the
   invitation's lit style until the frame is applied. A form's field is
   clicked and not lit: it wears the ring a focused field does. With no
   target there is no gesture. */
function replayGesture(r) {
  if (replay !== r || !r.playing || r.gesture) return;
  /* never inside the stillness: a screen drawn late is still for its
     floor from its draw, and the gesture waits for the rest of it */
  const left = replayStillLeft(r);
  if (left > 0) {
    clearTimeout(r.lead);
    r.lead = setTimeout(() => replayGesture(r), left);
    return;
  }
  /* a frame on a row that is not on screen makes the gesture of a move
     to that row first */
  const walk = replayWalkOf(r, r.frames[r.at]);
  const f = walk || r.frames[r.at];
  /* a beat equal to the one before it is on screen already: no click */
  const to = replayStill(r.frames, r.at) ? null : replayGestureTarget(f);
  if (!to) return;
  r.gesture = {at: r.at, until: performance.now()
                 + (REPLAY_GLIDE_MS + REPLAY_PRESS_MS) / r.speed,
               walk,
               field: to.closest("dialog[data-guided]") ? replayTypingOf(f) : null,
               hop: r.hopped === r.at ? null : replayHopOf(f, to)};
  replayPointerTo(to, r.speed);
  r.lead = setTimeout(() => {
    /* a screen drawn again during the glide has a new element for the
       same act */
    const lit = to.isConnected ? to : replayGestureTarget(f);
    if (replay !== r || !lit) return;
    if (lit !== to) replayPointerTo(lit, r.speed);
    const form = lit.closest("dialog[data-guided]");
    if (form && r.gesture && r.gesture.field) { form.guidedClick(r.gesture.field); return; }
    lit.classList.add("invited");
    lit.setAttribute("data-replay-press", "");
  }, REPLAY_GLIDE_MS / r.speed);
}
/* how long the frame at the playhead still waits for its gesture. A
   screen drawn too late for the gap has its gesture begun here. */
function replayGestureWait(r) {
  if (!r.gesture) replayGesture(r);
  return r.gesture ? r.gesture.until - performance.now() : 0;
}
/* the gesture is over: nothing is lit, and the pointer stays where it
   is, or goes with the replay when `gone` */
function replayGestureRest(r, gone) {
  clearTimeout(r.lead);
  r.gesture = null;
  for (const e of document.querySelectorAll("[data-replay-press]")) {
    e.classList.remove("invited");
    e.removeAttribute("data-replay-press");
  }
  const p = $("#replaypointer");
  if (gone) replayPointerAt = null;
  if (p && gone) p.remove();
  /* a form closed under the pointer: it stays where it was, on the page */
  else if (p && p.parentElement !== document.body && !p.closest("dialog[open]"))
    document.body.append(p);
}
/* the move a frame waits for: a dialog beat on another screen with no
   action button for its door here, a write of the recorder's on a row
   that is not shown, and an invitation's row. The pointer makes it as
   it makes a recorded move (replayGestureTarget, replayHop), and the
   frame is applied on the row it leads to. Null for every other frame,
   for the screen shown, under an open dialog, and after the one walk a
   frame makes. A row with no link to press has no walk: its frame is
   applied as a move with no link is. */
function replayWalkOf(r, f) {
  if (!f || r.walked === r.at || r.door || $("dialog[open]")) return null;
  const row = s => String(s || "").split("?")[0];
  const ui = (f.type === "ui" && f.ui) || {}, c = ui.collection;
  const list = !!ui.dialog && !!c && !!c.self;
  const to = ui.dialog ? !replayGestureTarget(f) && (list ? collectionHrefOf(c) : f.self)
    : f.type === "transition" ? !replayNotice(r, f) && f.self
    : f.type === "invitation" && f.action ? row(f.self) : null;
  return to && row(to) !== hereHref() ? {type: "move", who: f.who, self: to, list} : null;
}
/* the list a move passes through: when its gesture presses the
   navigation entry of its kind, and not of the row itself, that entry's
   address. Null for every other gesture. */
function replayHopOf(f, to) {
  if (f.type !== "move" || !to.closest("#kinds")) return null;
  const list = to.getAttribute("href").slice(1);
  return list.split("?")[0] === String(f.self).split("?")[0] ? null : list;
}
/* a hop: the navigation entry was pressed, so its list is drawn, a
   screen of its own with the arrival outline and the screen floor, and
   the frame waits there for its next gesture, the row's link in that
   list. One hop is made for a frame: a list that does not show the row
   leaves the move to be applied with the outline alone. */
function replayHop(r) {
  const list = r.gesture.hop;
  r.hopped = r.at;
  replayGestureRest(r);
  location.hash = "#" + list;
  replayGaze("list", list);
  replayLinger(r);
}
/* a walk: the link to the frame's row was pressed, so that row is
   drawn, a screen of its own as a hop's list is, and the frame waits
   there for its own gesture: the door's button, for a dialog beat. One
   walk is made for a frame. */
function replayArrive(r) {
  const walk = r.gesture.walk;
  r.walked = r.at;
  replayGestureRest(r);
  applyFollowMove(walk.self);
  replayGaze(walk.list ? "list" : "row", walk.self);
  replayLinger(r);
}
/* a screen the pointer drew on its way to a frame (a hop's list, a
   walk's row) is still for the screen floor; then the frame's next
   gesture is made, and the frame waits for both. The floor is counted
   from the draw the hash just set asks for (replayStillLeft) */
function replayLinger(r) {
  r.drawn = performance.now();
  r.still = REPLAY_MIN_STILL;
  const wait = REPLAY_MIN_STILL + REPLAY_GLIDE_MS + REPLAY_PRESS_MS;
  r.timer = setTimeout(replayStep, wait / r.speed);
  r.lead = setTimeout(() => replayGesture(r), REPLAY_MIN_STILL / r.speed);
}
function replayStep() {
  const r = replay;
  if (!r || !r.playing || r.at >= r.frames.length) return;
  /* a screen drawn late is still for its floor from its draw: the
     gesture has not begun, and the frame waits for the rest of it */
  const left = r.gesture ? 0 : replayStillLeft(r);
  if (left > 0) {
    r.timer = setTimeout(replayStep, left);
    return;
  }
  /* the gesture comes before the act: the frame waits for it */
  const wait = replayGestureWait(r);
  if (wait > 0) {
    r.timer = setTimeout(replayStep, wait);
    return;
  }
  /* no jump: the pressed navigation entry draws its list first */
  if (r.gesture && r.gesture.hop) { replayHop(r); return; }
  /* nor for a frame on another row: the pressed link draws its row first */
  if (r.gesture && r.gesture.walk) { replayArrive(r); return; }
  const shows = replayShows(r.frames, r.at);
  applyReplayFrame(r.frames[r.at++]);
  /* the stillness a change is owed is counted from here, or from the
     end of its draw when that is later (replayDrawn) */
  if (shows) r.drawn = performance.now();
  if (replay === r) replaySchedule();
}
function playReplay() {
  const r = replay;
  if (!r) return;
  if (r.at >= r.frames.length) {       // again, from the start
    r.at = 0;
    r.who = null;
    r.caption = null;
    r.gaze = null;
    r.door = false;
    r.beat = null;
    r.notice = null;
    r.refusals = [];
    r.hopped = null;
    r.walked = null;
    r.held = null;
    r.rows.clear();
    r.docs.clear();
    closeGuided();
    guidedFocus = null;
    guidedDismissed = null;
  }
  r.playing = true;
  replayCaption();
  replayChip();
  replaySchedule();
}
function pauseReplay() {
  if (!replay) return;
  replay.playing = false;
  clearTimeout(replay.timer);
  replayChip();
}
function setReplaySpeed(x) {
  if (!replay) return;
  replay.speed = x;
  if (replay.playing) replaySchedule();
  replayChip();
}
/* stop: the live screen comes back where the person was. `quiet` is
   one replay giving way to the next. */
function stopReplay(quiet) {
  const r = replay;
  if (!r) return;
  clearTimeout(r.timer);
  replayGestureRest(r, true);
  closeGuided();
  replay = null;
  apiHeld = false;
  guidedFocus = null;
  guidedLastFields = {};
  guidedLastLabels = {};
  guidedDismissed = null;
  guidedSeq = -1;
  clearTimeout(replayGazeTimer);
  document.documentElement.removeAttribute("data-replay-gaze");
  replayCaption();
  replayChip();
  if (quiet) return;
  /* the tracker is the live quest's again, read now for what moved */
  questTracker();
  wellKnown().then(w => (w.resources || {}).quest && refreshQuest()).catch(() => {});
  /* a hashchange renders and beats on its own */
  if (location.hash !== r.back) location.hash = r.back;
  else { render(); presenceBeat(); }
}
function replayChip() {
  const chip = $("#replaychip");
  if (!chip) return;
  chip.textContent = "";
  chip.style.display = replay ? "inline-block" : "none";
  chip.removeAttribute("data-replay-state");
  if (!replay) return;
  const r = replay, ended = r.at >= r.frames.length;
  chip.setAttribute("data-replay-state",
    r.playing ? "playing" : ended ? "ended" : "paused");
  chip.title = `replaying “${r.title}”`
    + (r.engine ? `, recorded on the engine ${r.engine}` : "")
    + " — read-only: nothing is read from the engine or written to it";
  chip.append(`replay · ${r.title} · `,
    el("span", {"data-replay-who": "",
                title: "who is acting in the recording"},
      r.who ? r.who.display : "—"),
    ` · ${r.at}/${r.frames.length}`,
    el("button", {"data-replay-toggle": "",
        title: r.playing ? "pause" : ended ? "play again from the start" : "play",
        onclick: () => (r.playing ? pauseReplay() : playReplay())},
      r.playing ? "⏸" : ended ? "↻" : "▶"));
  const speed = el("select", {"data-replay-speed": "", title: "speed"},
    REPLAY_SPEEDS.map(x => el("option", {value: String(x)}, x + "x")));
  speed.value = String(r.speed);
  speed.addEventListener("change", () => setReplaySpeed(Number(speed.value)));
  chip.append(speed,
    el("button", {"data-replay-stop": "",
        title: "stop the replay and go back to the live screen",
        onclick: () => stopReplay()}, "✕"));
}
/* a screen during a replay (render() hands every /api/ address here):
   what the recording itself says about this address, and no read. A
   row shows its transitions so far, each from its own frame; a
   collection lists the rows the recording names under it, so the
   focused one can be lit. */
function renderReplay(view, href) {
  const r = replay;
  const [path, query] = href.split("?");
  const self = decodeURIComponent(path);
  const row = r.rows.get(self);
  /* the latest document at or before the playhead draws the product's
     own screen; with none, the panel below stands */
  const doc = r.docs.get(self);
  if (doc && doc.kind) return renderReplayDoc(view, doc);
  const panel = el("div", {class: "panel", "data-replay-screen": self});
  panel.append(el("div", {class: "crumbs"}, "Replay / ",
    el("span", {class: "id", title: self}, self)));
  panel.append(el("h2", {class: "prose"}, (row && row.summary) || self));
  if (row && row.state)
    panel.append(el("div", {},
      el("span", {class: "statechip", title: row.kind || ""}, row.state)));
  panel.append(el("p", {class: "muted", "data-replay-note": ""},
    `From the recording “${r.title}”. Nothing on this screen is read `
    + `from the engine, and nothing is written.`));
  if (query) panel.append(el("p", {class: "muted mono"}, "?" + query));
  for (const ev of (row ? row.log : []))
    panel.append(el("div", {class: "ev", "data-replay-transition": ev.action || ""},
      el("div", {class: "ev-head"}, evTime(ev.at), " ", ev.actor.display),
      el("div", {class: "ev-body"},
        `${pretty(ev.action || "")} · ${pretty(ev.kind || "")}: `
        + `${ev.from ? pretty(ev.from) : "·"} → ${pretty(ev.to || "")}`)));
  /* the doors the recording opens on this row, as the buttons its
     pointer presses; in a hand they do nothing */
  const doors = [...r.fields.keys()].filter(k => k.startsWith(self + " "))
    .map(k => k.slice(self.length + 1));
  if (doors.length)
    panel.append(el("div", {"data-replay-doors": ""}, doors.map(a =>
      el("button", {type: "button", "data-action": a}, pretty(a)))));
  const under = [...r.known].filter(s => s.startsWith(self + "/")).sort();
  if (under.length)
    panel.append(el("table", {}, el("tbody", {}, under.map(s => {
      const known = r.rows.get(s) || {};
      return el("tr", {"data-self": s},
        el("td", {}, el("a", {href: "#" + s},
          known.summary || s.split("/").pop().slice(0, 8))),
        el("td", {}, known.state || ""));
    }))));
  view.append(panel);
  paintGuidedFocus();
}
/* a screen the walk carries (docs/spec-agent-demo-walks.md §8a): the
   document recorded for this address, handed to the code that draws a
   live row or collection. The screen is inert, so no action on it can
   be taken, and apiHeld answers every read that code makes with
   nothing. The hints are the ones this page already holds. */
function renderReplayDoc(view, doc) {
  const screen = el("div", {"data-replay-screen": doc.self || "",
                            "data-replay-doc": "", inert: ""});
  view.append(screen);
  const hints = dataHintsCache[String(doc.kind).replace(/_collection$/, "")] || {};
  /* a row's draw is done when its promise is: render() waits for it */
  let drawn = null;
  if (String(doc.kind).endsWith("_collection")) renderCollection(screen, doc, hints);
  else drawn = renderResource(screen, doc, hints).catch(() => {});
  paintGuidedFocus();
  return drawn;
}
/* ── film mode (docs/spec-agent-demo-walks.md §8b): a sealed walk played
   for a camera, at /#/api/walks/<id>?film=1. The root element's
   data-film hides the chrome a film must not show (030-screens.css)
   and says where the page is: `ready` while the title card holds for
   FILM_TITLE_MS, `playing` once play starts by itself at 1×, and
   `ended` when the last screen has held for FILM_HOLD_MS. The camera
   reads that and nothing else. The export GET is the one read, as it
   is for ▶ Replay. ──────────────────────────────────────────────── */
const FILM_TITLE_MS = 2000, FILM_HOLD_MS = 1500;
/* the walk being filmed, by its self, or null */
let film = null;
function filmWalkOf(raw) {
  const [path, query] = String(raw || "").split("?");
  return /^\/api\/walks\/[^/]+$/.test(path) &&
    new URLSearchParams(query || "").get("film") === "1" ? path : null;
}
function filmState(s) { document.documentElement.setAttribute("data-film", s); }
/* the last frame is applied: the screen holds, then the page says so */
function filmEnd() {
  setTimeout(() => filmState("ended"), FILM_HOLD_MS);
}
/* a caption that names a field is drawn beside that field and not in
   the band, as an invitation's note is, once its form is open */
function filmBeside(c) {
  const g = film && c.field && c.action &&
    $("dialog[open][data-guided]:not([data-replay-invite])");
  return !!g && !!g.guidedMark &&
    String(c.self).split("?")[0] + " " + c.action === g.getAttribute("data-guided");
}
async function filmBoot() {
  const self = filmWalkOf(location.hash.slice(1));
  if (!self || film) return;
  film = self;
  /* the chrome is gone before the walk is read; a walk that cannot be
     read never says `ready` */
  filmState("");
  let text = null;
  try {
    const res = await fetch(self + "/export", {headers: principalHeaders()});
    if (res.ok) text = await res.text();
  } catch (_e) { /* never ready */ }
  const walk = parseWalk(text);
  if (!walk) return;
  const card = el("div", {id: "filmcard"},
    el("h1", {}, walk.header.title || "a walk"));
  document.body.append(card);
  filmState("ready");
  setTimeout(() => {
    card.remove();
    filmState("playing");
    startReplay(text);
  }, FILM_TITLE_MS);
}
window.addEventListener("hashchange", filmBoot);
filmBoot();
/* the two ways in: a sealed walk's row page (160-resource-surface.js),
   whose export is the one read a replay makes, and a .ndjson file the
   person picks, which makes none */
async function replayWalk(self) {
  let text = null;
  try {
    const res = await fetch(self + "/export", {headers: principalHeaders()});
    if (res.ok) text = await res.text();
  } catch (_e) { /* told below */ }
  if (text === null) { toast("This walk's export could not be read"); return; }
  startReplay(text);
}
async function replayFile(file) {
  if (!file) return;
  let text = null;
  try { text = await file.text(); } catch (_e) { /* told below */ }
  if (text === null) { toast("That file could not be read"); return; }
  startReplay(text);
}
const $replaybtn = $("#replaybtn"), $replayfile = $("#replayfile");
if ($replaybtn && $replayfile) {
  $replaybtn.addEventListener("click", () => $replayfile.click());
  $replayfile.addEventListener("change", () => {
    replayFile($replayfile.files[0]);
    $replayfile.value = "";
  });
}

