"use strict";
/* The generic human client (spec Part IV), waymark9's ui.html ported to
   wire 10: route = self · screen = envelope · buttons = actions ·
   disabled-with-tooltip = unavailable · forms = input schema · filter
   bar = the collection query schema · live updates + activity feed =
   the events stream. It consumes only the envelope — it invents no
   affordances.

   Wire-10 deltas this port absorbs (docs/waymark10-design.md, closing
   table): "waymark": "10"; dev identity = x-waymark-principal /
   x-waymark-roles / x-waymark-actor-type; grants scope by
   X-Waymark-Grant (a selector, not a credential); guard names on the
   wire are kebab; drafts 404 until saved and answer {values,
   base_version, prefill, revs, authors}; live collab speaks
   waymark-relay/2 (set/edit/ack/stale/update/presence/regate/sync);
   the SSE surface frames `event: transition` (with ids) and
   `event: derivation`; collection items are envelope-minus-data;
   embeds arrive server-spliced as links.<rel>.embedded.

   Named gaps, so nobody hunts for silent stubs:
   - presence/follow-navigation chrome: CLOSED — wire 10 grew
     GET/POST /api/-/presence back (ephemeral, never law). Following
     below steers on presence moves (where they LOOK) as well as
     transitions (where they WRITE); resource screens show viewing
     dots; this page reports its own gaze by explicit heartbeat (it
     holds only the firehose, never per-resource streams).
   - provenance cells (x-source/x-authority/x-derived): the v10
     published schema does not emit them; the costumes stay retired.
   - the batch textarea (9's design §7): v10 serves the batch route but
     advertises no entry.batch — the page invents no affordance.
   - x-display.relation bounds and if/then conditional arms: not
     emitted by the v10 schema publisher.
   - recomputing honesty banners: v10 meta carries no `recomputing`.
   - the agent-token brief: v10 grants mint no opaque bearer token; the
     grant screen instead scopes THIS session via X-Waymark-Grant. */

/* the server's shell verdict: a mobile User-Agent got this page
   stamped data-ui="mobile" (?ui= overrides). The CSS keys the whole
   mobile chrome off the stamp; the JS below only branches where
   layout alone can't carry it (the Home tab, the sort select). */
const MOBILE = document.documentElement.dataset.ui === "mobile";

const $ = (s, el=document) => el.querySelector(s);
const uuid = () => {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = new Uint8Array(16);
  (crypto.getRandomValues ? crypto.getRandomValues(b)
    : b.forEach((_, i) => b[i] = Math.floor(Math.random() * 256)));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map(x => x.toString(16).padStart(2, "0"));
  return `${h.slice(0,4).join("")}-${h.slice(4,6).join("")}-${h.slice(6,8).join("")}`
       + `-${h.slice(8,10).join("")}-${h.slice(10,16).join("")}`;
};
const pretty = s => String(s).replace(/_/g, " ");
const title = s => { const t = pretty(s); return t.charAt(0).toUpperCase() + t.slice(1); };
const el = (tag, attrs={}, ...kids) => {
  const n = document.createElement(tag);
  for (const [k,v] of Object.entries(attrs)) {
    if (k === "onclick") n.addEventListener("click", v);
    else if (v !== undefined && v !== null) n.setAttribute(k, v);
  }
  for (const kid of kids.flat(Infinity)) if (kid != null)
    n.append(kid.nodeType ? kid : document.createTextNode(kid));
  return n;
};

/* ── identity: the dev principal + the session's grant scope ───────── */
const $who = $("#who");
$who.value = localStorage.getItem("wm10.principal") || "";
$who.addEventListener("change", () => {
  localStorage.setItem("wm10.principal", $who.value.trim());
  render();
});
function principalId() { return $who.value.trim(); }
function principalHeaders() {
  const h = {};
  const who = principalId();
  if (who) h["x-waymark-principal"] = who;
  /* the v10 grant scope: a grant id is a scope SELECTOR the audience
     principal presents — not a credential (waymark9 minted opaque
     bearer tokens instead; wire 10 does not) */
  const grant = localStorage.getItem("wm10.grant");
  if (grant) h["X-Waymark-Grant"] = grant;
  return h;
}
function grantChip() {
  const chip = $("#grantchip");
  const grant = localStorage.getItem("wm10.grant");
  chip.textContent = "";
  chip.style.display = grant ? "inline-block" : "none";
  if (grant) chip.append(`grant ${grant.slice(0, 8)}`,
    el("button", {title: "leave the grant scope", onclick: () => {
      localStorage.removeItem("wm10.grant"); grantChip(); render();
    }}, "✕"));
}
grantChip();

async function api(href, opts={}) {
  const res = await fetch(href, {method: opts.method || "GET",
    headers: {...principalHeaders(),
              ...(opts.body !== undefined ? {"Content-Type": "application/json"} : {}),
              ...(opts.headers || {})},
    body: opts.body});
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (_e) { body = {unparsed: text}; }
  return {res, status: res.status, ok: res.status < 400, body,
          etag: res.headers.get("ETag")};
}

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.style.display = "block";
  setTimeout(() => t.style.display = "none", 4000);
}

/* live countdown timers die with the screen that owns them */
const liveTimers = [];
function clearLiveTimers() {
  while (liveTimers.length) clearInterval(liveTimers.pop());
}

