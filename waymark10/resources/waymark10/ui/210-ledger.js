/* ── the ledger rows: time in mono, the actor marked by type, machine
   facts quiet, and the law loud — exactly once, when it changes ────── */
const ACTOR_MARKS = {human: "●", agent: "◆", system: "○"};
function evTime(at) {
  return el("span", {class:"ev-time"},
    at ? new Date(at).toLocaleTimeString([], {hour12: false}) : "");
}
function actorChip(actor) {
  actor = actor || {};
  return el("span", {class:"actor-chip",
      title: `follow ${actor.display || actor.id || "?"}'s actions`,
      onclick: () => { follow(actor); toast(`following ${actor.display || actor.id}`); }},
    el("span", {class:"actor-mark " + (actor.type || "human")},
      ACTOR_MARKS[actor.type] || "●"),
    " ", actor.display || actor.id || "?");
}
function lawStampChip(ev) {
  return ev.law_revision != null
    ? el("span", {class:"anchor law-chip",
        title: `written under law revision ${ev.law_revision}`},
        "⚖ r" + ev.law_revision)
    : null;
}
function ledgerRow(ev, isFollowed) {
  return el("div", {class: "ev" + (isFollowed ? " followed" : "")},
    el("div", {class:"ev-head"},
      evTime(ev.at), " ", actorChip(ev.actor), lawStampChip(ev)),
    el("div", {class:"ev-body"},
      `${pretty(ev.action || "")} · `,
      el("a", {href:"#"+ev.self, title: `${ev.from ?? "·"} → ${ev.to}`},
        `${pretty(ev.kind || "")}: ${ev.from ? pretty(ev.from) : "·"} → ${pretty(ev.to || "")}`)));
}
function derivRow(ev) {
  /* a derivation-class event: maintenance changed a fact no transition
     announces — quiet, mono. self is null when the whole kind restamped. */
  const what = Array.isArray(ev.changed) ? ev.changed.join(", ")
    : typeof ev.changed === "object" && ev.changed ? Object.keys(ev.changed).join(", ")
    : String(ev.changed ?? "");
  return el("div", {class:"ev deriv"},
    evTime(ev.at), " ",
    ev.self ? el("a", {href:"#"+ev.self}, `ƒ ${ev.class || "derivation"} · ${what}`)
            : el("span", {}, `ƒ ${ev.class || "derivation"} · ${pretty(ev.kind || "")} · ${what}`));
}
/* The law line — the signature: a full-width violet rule when the
   definition kind itself transitions. Degrades to the stored summary;
   enriched from the definition row itself. */
function lawLineRow(ev) {
  const detail = el("span", {class:"law-detail"},
    ev.summary || (ev.self || "").split("/").pop().slice(0, 8));
  const row = el("div", {class:"ev law-line"},
    el("div", {},
      el("span", {class:"law-stamp"},
        "⚖ ", ev.action === "create" ? "law recorded" : "law revised"),
      " · ", el("a", {href:"#"+ev.self, class:"law-link"}, detail)),
    evTime(ev.at));
  api(ev.self).then(({ok, body}) => {
    if (!ok || !body.data) return;
    const d = body.data;
    const bits = [`${pretty(d.target_kind || "")} · rev ${d.revision}`];
    if (d.diff_class && d.diff_class !== "initial") bits.push(d.diff_class);
    if (d.change_summary) bits.push(d.change_summary);
    detail.textContent = bits.join(" · ");
  }).catch(() => {});
  return row;
}

/* ── the per-resource history panel: this row's transition log,
   replayed from its events stream (wire 10 frames carry no recorded
   inputs and no version — the replay's own order is the order) ────── */
let historyOpen = false;
function historySection(streamHref) {
  const count = el("span", {class:"muted mono", style:"font-size:11px"});
  const body = el("div", {class:"history-body"});
  const sec = el("details", historyOpen ? {class:"history", open:""}
                                        : {class:"history"},
    el("summary", {class:"muted"}, "History ", count), body);
  let loaded = false;
  const load = () => {
    if (loaded) return;
    loaded = true;
    body.append(el("span", {class:"muted"}, "…"));
    fetchReplay(streamHref).then(frames => {
      if (!document.contains(body)) return;   // superseded by a re-render
      const trans = frames.filter(f => f.event === "transition").map(f => f.data);
      trans.reverse();                        // replay is id order: newest first
      body.textContent = "";
      count.textContent = trans.length ? `(${trans.length})` : "";
      if (!trans.length)
        return body.append(el("span", {class:"muted"}, "no transitions yet"));
      for (const ev of trans)
        body.append(el("div", {class:"ev"},
          el("div", {class:"ev-head"},
            evTime(ev.at), " ", actorChip(ev.actor), lawStampChip(ev)),
          el("div", {class:"ev-body"},
            `${pretty(ev.action || "")} · `,
            `${ev.from ? pretty(ev.from) : "·"} → ${pretty(ev.to || "")}`)));
    }).catch(() => {
      if (!document.contains(body)) return;
      body.textContent = "";
      body.append(el("div", {class:"muted"}, "couldn't load history"));
    });
  };
  sec.addEventListener("toggle", () => {
    historyOpen = sec.open;
    if (sec.open) load();
  });
  if (historyOpen) load();
  return sec;
}

/* ── the activity drawer: the ledger's one global home. Closed ≠ torn
   down — arrivals count into the pip (violet the moment the law
   moves); opening clears it. ───────────────────────────────────────── */
const ledgerEl = $("#ledger");
const ledgerToggle = $("#ledgertoggle");
const ledgerPip = $("#ledgerpip");
let ledgerUnread = 0, ledgerUnreadLaw = false;
function paintPip() {
  if (!ledgerUnread) { ledgerPip.style.display = "none"; return; }
  ledgerPip.textContent = ledgerUnread > 99 ? "99+" : String(ledgerUnread);
  ledgerPip.className = ledgerUnreadLaw ? "law" : "";
  ledgerPip.style.display = "inline-block";
}
const ledgerIsOpen = () => ledgerEl.classList.contains("open");
function setLedger(open, opts) {
  const moveFocus = !opts || opts.focus !== false;
  ledgerEl.classList.toggle("open", open);
  ledgerToggle.setAttribute("aria-expanded", String(open));
  localStorage.setItem("wm10.ledger.open", open ? "1" : "0");
  if (open) {
    ledgerUnread = 0; ledgerUnreadLaw = false; paintPip();
    if (moveFocus) ledgerEl.focus();
  } else if (moveFocus) ledgerToggle.focus();
}
ledgerToggle.addEventListener("click", () => setLedger(!ledgerIsOpen()));
$("#ledgerclose").addEventListener("click", () => setLedger(false));
ledgerEl.addEventListener("keydown", ev => {
  if (ev.key === "Escape") { ev.stopPropagation(); setLedger(false); }
});
if (localStorage.getItem("wm10.ledger.open") === "1")
  setLedger(true, {focus: false});

/* what the current screen is watching: a transition (or derivation)
   touching it refetches, debounced — a burst becomes one read */
let SCOPE = {};
let refetchTimer = null;
function watchScope(scope) { SCOPE = scope || {}; }
function scopeHit(t) {
  return (SCOPE.self && (t.self === SCOPE.self ||
                         (t.self || "").startsWith(SCOPE.self + "/"))) ||
         (SCOPE.kind && t.kind === SCOPE.kind);
}
function tickerLine(t) {
  $("#ticker").replaceChildren(
    el("span", {}, (t.at || "").slice(11, 19) + " · ",
      el("b", {}, ((t.actor || {}).id || "?")), " ", t.action || "", " · ",
      t.kind || "", " ", (t.from || "·") + " → " + (t.to || "·"), " · ",
      el("a", {href: "#" + t.self, class: "mono"}, t.self || "")));
}

const seen = new Set();  // dedupe — at-least-once delivery
sse("/api/-/events", ({event, id, data: ev}) => {
  const feed = $("#feed");
  if (event === "derivation") {
    const key = `d:${ev.kind}#${ev.self}#${ev.at}`;
    if (seen.has(key)) return;
    seen.add(key);
    feed.prepend(derivRow(ev));
    if (scopeHit(ev)) {
      clearTimeout(refetchTimer);
      refetchTimer = setTimeout(render, 350);
    }
    return;
  }
  if (event !== "transition") return;
  const key = id ? `t:${id}` : `t:${ev.self}#${ev.at}`;
  if (seen.has(key)) return;
  seen.add(key);
  tickerLine(ev);
  const isFollowed = !!(followId && ev.actor && ev.actor.id === followId);
  let lawLine = false;
  if (ev.kind === "definition") {
    lawLine = true;                    // the law moved: the one loud line
    feed.prepend(lawLineRow(ev));
  } else feed.prepend(ledgerRow(ev, isFollowed));
  if (!ledgerIsOpen()) {
    ledgerUnread += 1;
    if (lawLine) ledgerUnreadLaw = true;
    paintPip();
  }
  const here = decodeURIComponent(location.hash.slice(1).split("?")[0]);
  /* the Access panel is itself a following view: agent feeds fill in
     place, access-kind transitions re-render the panel, and the
     follow-navigate stays parked — watching from the balcony is the
     point of standing on it */
  if (here === "access") {
    const feedBox = document.querySelector(
      `[data-agent-feed="${CSS.escape((ev.actor || {}).id || "")}"]`);
    if (feedBox) {
      const placeholder = feedBox.querySelector(".muted");
      if (placeholder && feedBox.children.length === 1) placeholder.remove();
      feedBox.prepend(ledgerRow(ev, isFollowed));
      while (feedBox.children.length > 20) feedBox.lastChild.remove();
    }
    if (["member", "approval_request", "grant"].includes(ev.kind)) {
      clearTimeout(refetchTimer);
      refetchTimer = setTimeout(render, 350);
    }
    return;
  }
  /* following: go where they went — unless a dialog is open (never yank
     a human out of a form they're typing in) */
  if (isFollowed && ev.self && ev.self !== here && !$("dialog[open]")) {
    location.hash = "#" + ev.self;
    return;
  }
  if (scopeHit(ev) ||
      (here && ev.self && here === ev.self.replace(/\/[^/]+$/, ""))) {
    clearTimeout(refetchTimer);
    refetchTimer = setTimeout(render, 350);
  }
});

