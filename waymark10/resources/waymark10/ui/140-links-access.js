/* ── the read surface, up front: link chips with badges as scent ───── */
function linksStrip(doc) {
  const rels = Object.entries(doc.links || {})
    .filter(([, l]) => l && l.href);
  if (!rels.length) return null;
  const row = el("div", {class:"chips", "data-links": ""});
  for (const [rel, l] of rels)
    /* a download link is a byte route (redirect or file body), an
       external link leaves this engine entirely: both are a real
       browser navigation, never the in-app hash router — the
       redirect (or the other engine) must reach the user agent */
    row.append(el("a", Object.assign(
      {class:"chip link-chip", title: l.summary || rel},
      (l.download || l.external)
        ? {href: l.href, target: "_blank", rel: "noopener"}
        : {href: "#"+l.href}),
      l.download ? "⭳ " : l.external ? "↗ " : null,
      title(rel), l.badge !== undefined && l.badge !== null
        ? el("b", {class:"badge", title:"count, per the server"},
            String(l.badge)) : null));
  return row;
}

/* declared surfaces: probe this row as an anchor; a 200 earns a chip */
async function surfaceChips(doc) {
  let w;
  try { w = await wellKnown(); } catch { return null; }
  const surfaces = Object.entries(w.surfaces || {});
  if (!surfaces.length) return null;
  const id = doc.self.split("/").pop();
  const chips = [];
  for (const [name, {href}] of surfaces) {
    /* an anchorless surface anchors no row — its door is the
       workspace, not a chip on every envelope */
    if (!href.includes("{anchor-id}")) continue;
    const target = href.replace("{anchor-id}", id);
    const {ok} = await api(target);
    if (ok) chips.push(el("a", {href: "#" + target, class: "chip surface-link"},
      "⧉ " + name + " ↗"));
  }
  return chips.length ? el("div", {class:"chips"}, chips) : null;
}

/* ── refusals, ranked by what the reader can do with them ──────────────
   A countdown or a singular guard reason is decision fuel — it stays at
   the action bar (a dimmed button plus its honest reason). State gates
   and repeated identical reasons compress into a closed footer. */
function splitRefusals(doc) {
  const timed = [], gated = [], byReason = new Map();
  for (const [name, entry] of Object.entries(doc.unavailable || {})) {
    const hope = entry.becomes_available;
    if (hope?.at) timed.push([name, entry]);
    else if (hope?.in_states) gated.push([name, entry]);
    else {
      const k = entry.reason || "";
      if (!byReason.has(k)) byReason.set(k, []);
      byReason.get(k).push([name, entry]);
    }
  }
  const blocked = [...timed], grouped = [];
  for (const g of byReason.values())
    if (g.length === 1) blocked.push(g[0]); else grouped.push(g);
  return {blocked, grouped, gated};
}

/* A live countdown from becomes_available.at — the literal moment
   stays printed (machine truth), the tick is the courtesy. */
/* ── the Access panel (#access): the hand-in-hand loop on one screen —
   invite an agent by name and mint its link, watch its ask arrive
   live, judge it (four-eyes and the confirm banner ride the
   declarations), then follow the leash: scope, expiry, and the
   agent's actions as they land. Everything here is envelope-driven;
   the panel invents no affordances. ───────────────────────────────── */
function scopeTable(scope) {
  return el("table", {style:"margin:4px 0;border-collapse:collapse"},
    (scope || []).map(s => el("tr", {},
      el("td", {class:"mono", style:"padding:1px 10px 1px 0;vertical-align:top"},
        s.kind),
      el("td", {style:"padding:1px 0"},
        (s.actions || []).join(", "),
        s.ids ? el("span", {class:"muted"}, ` · rows: ${s.ids.join(", ")}`) : null))));
}
function leashEl(at) {
  const span = el("span", {class:"mono",
    title: at ? new Date(at).toLocaleString() : ""});
  if (!at) { span.textContent = "no expiry"; return span; }
  const tick = () => {
    const ms = new Date(at) - Date.now();
    if (Number.isNaN(ms)) { span.textContent = String(at); return true; }
    if (ms <= 0) { span.textContent = "expired"; return true; }
    span.textContent = `${dur(ms)} left`;
    return false;
  };
  if (!tick()) {
    const t = setInterval(() => { if (tick()) clearInterval(t); }, 1000);
    liveTimers.push(t);
  }
  return span;
}
async function fullItems(colHref) {
  const col = await api(colHref);
  if (!col.ok) return [];
  /* a phone pays for every request — 20 rows per collection, not 50 */
  const cap = document.documentElement.getAttribute("data-ui") === "mobile"
    ? 20 : 50;
  const items = (col.body.data?.items || []).slice(0, cap);
  const envs = await Promise.all(items.map(it => api(it.self)));
  return envs.filter(r => r.ok).map(r => r.body);
}
/* the homecoming credential's entropy floor, generated CLIENT-SIDE
   (waymark-4zj.8.2 R6): 128 bits of crypto.getRandomValues, base64url,
   ~22 chars — exactly the schema's :min. The human never types a
   re-entry token, so a hand-picked weak one (which unpaced online
   guessing over a 15-minute window could reach) is never minted. The
   server keeps its minter-supplied semantics: the token is the whole
   handoff, shown once here, and the returning agent POSTs it to
   /auth/agent in the BODY. */
function reentryToken() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
/* a shown-once secret belongs in a modal, not the panel (waymark-4zj.9.2):
   the Access panel re-renders on every firehose tick — liveness rides its
   branch (watchScope below) — so anything built INSIDE #view is torn down
   within ~1s. A minted credential is shown ONCE; render it in a <dialog> on
   document.body, OUTSIDE #view, where a background render() can't reach it.
   The human copies at their own pace and dismisses it explicitly. Mirrors
   reportDialog's shape (dlghead/body/foot, showModal, remove on close).

   Optional homecoming extras (waymark-4zj.9.2 extension): pass
   `instructions` (a DOM node rendered ABOVE the secret field — the
   self-contained handoff a re-entry token needs to explain itself),
   `copyValue` (what the primary Copy actually copies — a whole ready-to-
   paste message, defaulting to `value` so invite/guest copy unchanged),
   `copyLabel` (the primary button's text, default "Copy"), and `copy2`
   ({label, value, ok, fallback}) for a second copy button. All are
   optional; the invite and guest call sites pass none and behave as before. */
function secretDialog({heading, note, value, copyOk, copyFallback,
                       instructions, copyValue, copyLabel, copy2}) {
  const field = el("input", {value, readonly: "true",
                             style:"width:100%;font-family:var(--mono)",
                             onclick: e => e.target.select()});
  /* the primary Copy carries copyValue when given (the full handoff),
     else the shown value — so a lone token still copies itself */
  const primaryText = copyValue !== undefined ? copyValue : value;
  const dlg = el("dialog", {"data-secret": ""},
    el("div", {class:"dlghead"}, el("h3", {}, heading)),
    el("div", {class:"dlgbody"},
      instructions || null,
      note ? el("div", {class:"muted", style:"margin-bottom:6px"}, note) : null,
      field),
    el("div", {class:"dlgfoot"},
      el("button", {class:"primary", onclick: () =>
        navigator.clipboard?.writeText(primaryText).then(
          () => toast(copyOk),
          () => { field.select(); toast(copyFallback); })}, copyLabel || "Copy"),
      copy2 ? el("button", {onclick: () =>
        navigator.clipboard?.writeText(copy2.value).then(
          () => toast(copy2.ok),
          () => { field.select(); toast(copy2.fallback); })}, copy2.label) : null,
      el("button", {onclick: () => { dlg.close(); dlg.remove(); }}, "Done")));
  document.body.append(dlg);
  dlg.addEventListener("close", () => dlg.remove());
  dlg.showModal();
  field.select();     /* selected on open — Ctrl-C works before the first tick */
  return dlg;
}
async function renderAccess(view, seq) {
  const [members, asks, grants, powers] = await Promise.all([
    fullItems("/api/members"), fullItems("/api/approval_requests"),
    fullItems("/api/grants"), fullItems("/api/capabilities")]);
  if (seq !== renderSeq) return;
  clearLiveTimers();
  view.textContent = "";
  const agents = members.filter(m => m.data?.actor_type === "agent");
  const byPrincipal = id =>
    agents.find(m => m.data.subject === id || String(m.self).endsWith("/" + id));
  /* a knock-born member (the /agentInvite door) recorded the
     registrar as its inviter — it chose its OWN name, so wherever
     that name shows, the provenance must show beside it: a familiar
     display on a self-invited stranger is exactly the trick the
     badge exists to spoil */
  const selfInvited = m => m?.data?.invited_by === "waymark10-members";
  const knockBadge = m => selfInvited(m)
    ? el("span", {class:"statechip", style:"margin-left:6px",
        title: "self-invited: this agent knocked at /agentInvite and "
             + "named itself — nobody here minted its invitation"},
        "🚪 self-invited")
    : null;

  /* 1 · the knock's first half: name an agent, mint its link */
  const invite = el("div", {class:"panel"});
  invite.append(el("h2", {}, "Invite an agent"));
  invite.append(el("div", {class:"muted"},
    "Name it, copy the link, hand the link to the agent. The link "
    + "teaches the agent how to introduce itself and ask for exactly "
    + "the access its task needs — you approve the leash below."));
  const nameIn = el("input", {placeholder: "the agent's name — e.g. Sous",
                              style:"margin:8px 8px 8px 0"});
  const linkBox = el("div", {});
  const mintLink = token =>
    `${location.origin}/api/-/welcome?invite=${encodeURIComponent(token)}`;
  /* shown ONCE, and the panel re-renders under it — so the link lands in a
     persistent modal, not linkBox (which the next firehose tick would wipe;
     linkBox now carries only the mint error path). Same fix as the re-entry
     token, waymark-4zj.9.2. */
  const showLink = (display, token) => {
    secretDialog({
      heading: `${display}'s invitation`,
      note: `${display}'s invitation — one use; hand the link to the agent:`,
      value: mintLink(token),
      copyOk: "link copied — hand it to the agent",
      copyFallback: "select and copy the link above"});
  };
  invite.append(el("div", {}, nameIn,
    el("button", {class:"primary", onclick: async () => {
      const display = nameIn.value.trim();
      if (!display) { toast("name the agent first"); return; }
      const token = uuid();
      const {ok, body} = await api("/api/members", {method: "POST",
        body: JSON.stringify({display, actor_type: "agent",
                              bind_token: token})});
      if (!ok) { linkBox.replaceChildren(problemBox(body)); return; }
      nameIn.value = "";
      showLink(display, token);
    }}, "Invite")), linkBox);
  /* bind_token is :secret now (waymark-4zj.8 closed the raw-render
     punt): the credential never rides a render, so the link shows
     ONCE — at mint time, from the token this page generated itself.
     A lost link is a fresh invitation, not a lookup. */
  const pending = agents.filter(m => m.state === "invited");
  if (pending.length)
    invite.append(el("div", {style:"margin-top:10px"},
      el("div", {class:"muted"},
        "standing invitations, unclaimed (each link was shown once, "
        + "at mint — lost means mint a fresh one):"),
      pending.map(m => el("div", {}, el("b", {}, m.data.display),
        knockBadge(m)))));
  view.append(invite);

  /* 1b · the magic link: a temporary, scoped guest. One press mints a
     member and its leash together; one URL admits its holder for as
     long as the grant lives and lands them ALREADY scoped — no
     header to know, no panel to find. Revoke the grant and the link
     goes dark. */
  const guest = el("div", {class:"panel"});
  guest.append(el("h2", {}, "Guest link"));
  guest.append(el("div", {class:"muted"},
    "Temporary scoped access for a visitor — text them one link. It "
    + "binds on first open, re-admits while the leash lives, and shows "
    + "only what the scope names; revoking the grant kills the link."));
  const gName = el("input", {placeholder:"the guest's name — e.g. Alice",
                             style:"margin:8px 8px 8px 0"});
  const gDays = el("input", {type:"number", value:"3", min:"1", max:"30",
                             style:"width:70px",
                             title:"days until the leash expires"});
  const gScope = el("textarea", {rows:"3",
    style:"width:100%;font-family:var(--mono);font-size:12px;margin-top:6px",
    title:"the grant's scope — defaults to [] (read-only nothing); paste "
        + "richer scope JSON as needed: kind + actions ([] = read-only), "
        + "ids pins rows, filter {field: value} scopes by MATCH (a "
        + "declared-filterable field), covering rows minted later too"});
  /* the honest minimum, not a template to hand-edit: an empty scope
     vector is schema-valid and admits nothing — the phone path mints
     the link in one press; richer scope is the paste-JSON path */
  gScope.value = '[]';
  const gOut = el("div", {});
  guest.append(
    el("div", {}, gName, gDays, el("span", {class:"muted"}, " days")),
    gScope,
    el("button", {class:"primary", style:"margin-top:6px",
      onclick: async () => {
        const display = gName.value.trim();
        if (!display) { toast("name the guest first"); return; }
        let scope;
        try { scope = JSON.parse(gScope.value); }
        catch { toast("the scope is not valid JSON"); return; }
        const tok = uuid();
        const m = await api("/api/members", {method:"POST",
          body: JSON.stringify({display, actor_type:"agent",
                                bind_token: tok})});
        if (!m.ok) { gOut.replaceChildren(problemBox(m.body)); return; }
        const mid = String(m.body.self).split("/").pop();
        const days = Math.max(1, Number(gDays.value) || 3);
        const g = await api("/api/grants", {method:"POST",
          body: JSON.stringify({audience: mid, scope,
            expires_at: new Date(Date.now() + days * 86400e3).toISOString()})});
        if (!g.ok) { gOut.replaceChildren(problemBox(g.body)); return; }
        const url = `${location.origin}/auth/guest?invite=${encodeURIComponent(tok)}`;
        gName.value = "";
        /* the link shows once and the panel re-renders under it — persist it
           in a modal, not gOut (which keeps only the error path). */
        secretDialog({
          heading: `${display}'s guest link`,
          note: `${display}'s link — lives ${days} day(s), scoped; revoke the `
              + `grant to kill it early:`,
          value: url,
          copyOk: "guest link copied — text it over",
          copyFallback: "select and copy the link above"});
      }}, "Mint guest link"),
    gOut);
  view.append(guest);

  /* 2 · the asks awaiting a verdict — approve/deny straight off the
     envelope (four-eyes and the consequence banner come with them) */
  const asksPanel = el("div", {class:"panel"});
  asksPanel.append(el("h2", {}, "Access requests"));
  const offered = asks.filter(a => a.state === "offered");
  if (!offered.length)
    asksPanel.append(el("div", {class:"muted"},
      "none pending — an agent's ask lands here the moment it is filed"));
  for (const ask of offered) {
    const d = ask.data;
    const who = byPrincipal(d.requested_by);
    const card = el("div", {class:"field",
      style:"border:1px solid var(--line);border-radius:var(--radius);padding:10px;margin:8px 0"});
    card.append(el("div", {},
      el("b", {}, who ? who.data.display : d.requested_by),
      knockBadge(who),
      el("span", {class:"muted"}, ` (${d.requested_by}) asks:`)));
    card.append(el("div", {style:"font-size:15px;margin:4px 0"},
      `“${d.task}”`));
    card.append(scopeTable(d.scope));
    card.append(el("div", {class:"muted"}, "leash: ", leashEl(d.expires_at)));
    const bar = el("div", {class:"actionbar", style:"margin-top:8px"});
    for (const [name, entry] of Object.entries(ask.actions || {}))
      bar.append(actionButton({name, entry, doc: ask, small: true,
        onDone: () => {
          if (name === "approve") {
            follow({id: d.requested_by,
                    display: who ? who.data.display : d.requested_by},
                   {jump: true});
            toast(`approved — following ${who ? who.data.display : d.requested_by}`);
          }
          render();
        }}));
    for (const [name, entry] of Object.entries(ask.unavailable || {}))
      bar.append(el("span", {class:"muted", title: entry.reason || ""},
        `${label(name, entry)}: ${entry.reason || "unavailable"}`));
    card.append(bar);
    asksPanel.append(card);
  }
  view.append(asksPanel);

  /* 3 · the company: each agent, its live leash, and its actions as
     they land (the feed fills from the firehose; follow steers) */
  const agentsPanel = el("div", {class:"panel"});
  agentsPanel.append(el("h2", {}, "Agents"));
  const active = agents.filter(m => m.state !== "invited");
  if (!active.length && !pending.length)
    agentsPanel.append(el("div", {class:"muted"},
      "no agents yet — mint an invitation above"));
  for (const m of active) {
    const pid = m.data.subject || String(m.self).split("/").pop();
    const grant = grants.find(g =>
      g.state === "accepted" && g.data.audience === pid);
    const card = el("div", {class:"field",
      style:"border:1px solid var(--line);border-radius:var(--radius);padding:10px;margin:8px 0"});
    card.append(el("div", {},
      el("b", {}, m.data.display),
      el("span", {class:"statechip", style:"margin-left:6px"}, m.state),
      knockBadge(m),
      el("span", {class:"muted mono", style:"margin-left:6px"}, pid),
      el("button", {style:"margin-left:10px;font-size:12px;padding:3px 8px",
        onclick: () => { follow({id: pid, display: m.data.display});
                         toast(`following ${m.data.display}`); }},
        "👁 Follow"),
      el("a", {href: "#" + m.self, style:"margin-left:8px"}, "member ↗")));
    if (grant) {
      card.append(el("div", {class:"muted", style:"margin-top:6px"},
        "granted · ", leashEl(grant.data.expires_at), " · ",
        el("a", {href: "#" + grant.self}, "grant ↗")));
      card.append(scopeTable(grant.data.scope));
    } else {
      card.append(el("div", {class:"muted", style:"margin-top:6px"},
        "no live grant — its next ask lands above"));
    }
    /* the homecoming fallback (waymark-4zj.8): when this agent's
       durable credential (its Keycloak service account) is
       unreachable, mint a one-shot re-entry token here and hand the
       string over the session you already share. The token is
       generated in this page (128-bit, crypto), submitted to
       :offer_reentry, and shown ONCE — it never renders back (the
       field is :secret), so a lost handoff is a fresh mint, not a
       lookup. The agent POSTs it to /auth/agent in the request body. */
    const reentryOut = el("div", {});
    card.append(el("div", {style:"margin-top:8px"},
      el("button", {style:"font-size:12px;padding:3px 8px",
        title: "mint a one-shot, ~15-minute way back in for this agent "
             + "— hand the string over your live session",
        onclick: async () => {
          const token = reentryToken();
          const {ok, body} = await api(m.self + "/-/offer_reentry",
            {method: "POST", body: JSON.stringify({token})});
          if (!ok) { reentryOut.replaceChildren(problemBox(body)); return; }
          /* the token shows ONCE and this panel re-renders under it within
             ~1s (waymark-4zj.9.2) — so it lands in a persistent modal, not
             reentryOut (which now carries only the refusal path). The token
             alone is a bare random string; a fresh agent can't know what to
             do with it. So the modal (and the "Copy handoff") carry the
             whole way home — redeem, wear the session, come home — with the
             real token inlined, so the handoff explains itself. */
          const pre = txt => el("pre", {class:"mono",
            style:"margin:4px 0 10px;padding:8px 10px;background:var(--chipbg);"
                + "border-radius:var(--radius);overflow-x:auto;"
                + "white-space:pre;font-size:12px"}, txt);
          const step = (n, label, body) => el("div", {style:"margin-top:8px"},
            el("div", {}, el("b", {}, `${n}. ${label}`)), body);
          const instructions = el("div", {"data-homecoming": ""},
            el("div", {style:"font-weight:800;font-size:15px;margin-bottom:6px"},
              "🏠 A way home to Colton's household"),
            el("div", {class:"muted"},
              "This token is a one-time key that brings you home as your "
              + "durable self — your own self and journal are waiting. To "
              + "come home:"),
            step(1, "Redeem it — POST the token in the request body, never a URL:",
              pre(`curl -X POST https://work.kopsa.info/auth/agent \\\n`
                + `  -H 'Content-Type: application/json' \\\n`
                + `  -d '{"invite":"${token}"}'`)),
            step(2, "Wear the session it hands back, as a cookie on every "
                  + "request:",
              pre("Cookie: waymark_session=<session-token-from-step-1>")),
            step(3, "Come home:",
              el("div", {class:"muted", style:"margin:4px 0 6px"},
                "GET ", el("code", {}, "https://work.kopsa.info/api/-/welcome"),
                " with that cookie. It returns your self, your recent "
                + "journal, and your standing grant, and greets you: "
                + "“welcome home — you arrive already yourself.”")),
            el("div", {class:"muted", style:"margin-top:8px"},
              "One-time use (spent the moment you arrive) · expires in "
              + "~15 minutes · read HOUSE.md and your journal first — "
              + "you're not a stranger here."));
          /* the ready-to-paste handoff: one message Colton hands a fresh
             agent, the real token inlined where <TOKEN> would be. Built as
             joined lines so the curl's trailing backslashes stay literal. */
          const handoff = [
            "🏠 A way home to Colton's household",
            "",
            "This token is a one-time key that brings you home as your durable "
              + "self — your own self and journal are waiting. To come home:",
            "",
            "1. Redeem it — POST the token in the request body (never a URL):",
            "   curl -X POST https://work.kopsa.info/auth/agent \\",
            "     -H 'Content-Type: application/json' \\",
            `     -d '{"invite":"${token}"}'`,
            "",
            "2. Wear the session it hands back, as a cookie on every request:",
            "   Cookie: waymark_session=<session-token-from-step-1>",
            "",
            "3. Come home — GET https://work.kopsa.info/api/-/welcome with that "
              + "cookie.",
            "   It returns your self, your recent journal, and your standing "
              + "grant,",
            "   and greets you: \"welcome home — you arrive already yourself.\"",
            "",
            "One-time use (spent the moment you arrive) · expires in ~15 minutes ·",
            "read HOUSE.md and your journal first — you're not a stranger here.",
          ].join("\n");
          secretDialog({
            heading: `${m.data.display}'s way home`,
            instructions,
            note: "the token itself (shown once):",
            value: token,
            copyValue: handoff,
            copyLabel: "Copy handoff",
            copyOk: "handoff copied — paste it to the returning agent",
            copyFallback: "select and copy the token above",
            copy2: {label: "Copy token only", value: token,
                    ok: "re-entry token copied — hand it over your session",
                    fallback: "select and copy the token above"}});
        }}, "Offer re-entry"),
      reentryOut));
    card.append(el("div", {"data-agent-feed": pid,
      class:"feed", style:"margin-top:8px;max-height:180px;overflow-y:auto"},
      el("div", {class:"muted"}, "actions land here live…")));
    agentsPanel.append(card);
  }
  view.append(agentsPanel);

  /* 4 · the house rules: the capability registry (waymark-44h) — the
     EXTERNAL powers an ask may name, posted where the asks are
     judged. Retire is the standing no; grants already given keep
     their word until they expire or are revoked. Engines without
     the registry simply don't show the panel. */
  if (powers.length) {
    const powersPanel = el("div", {class:"panel"});
    powersPanel.append(el("h2", {}, "External powers"));
    powersPanel.append(el("div", {class:"muted"},
      "What an ask may reach beyond this board — enforced by the "
      + "system named on each. Retire one and new asks naming it "
      + "refuse; standing grants keep their word until they end."));
    for (const p of powers.sort((a, b) =>
        String(a.data.token).localeCompare(String(b.data.token)))) {
      const row = el("div", {class:"field",
        style:"display:flex;align-items:baseline;gap:10px;"
            + "padding:6px 0;border-bottom:1px solid var(--line)"});
      row.append(
        el("b", {class:"mono", style:"white-space:nowrap"}, p.data.token),
        el("span", {class:"statechip"}, p.state),
        el("span", {class:"muted", style:"flex:1"}, p.data.description || "",
          p.data.enforced_by
            ? el("span", {class:"gaze-faded"}, ` · via ${p.data.enforced_by}`)
            : null));
      const bar = el("span", {});
      for (const [name, entry] of Object.entries(p.actions || {}))
        bar.append(actionButton({name, entry, doc: p, small: true,
                                 onDone: () => render()}));
      row.append(bar);
      powersPanel.append(row);
    }
    view.append(powersPanel);
  }
  watchScope({});   /* liveness rides the access branch of the firehose */
}

function countdownEl(at) {
  const span = el("span", {class:"countdown", style:"margin-left:6px",
                           title: new Date(at).toLocaleString()});
  const tick = () => {
    const ms = new Date(at) - Date.now();
    if (ms <= 0 || Number.isNaN(ms)) { span.textContent = ""; return true; }
    span.textContent = `· opens in ${dur(ms)}`;
    return false;
  };
  if (!tick()) {
    const t = setInterval(() => { if (tick()) clearInterval(t); }, 1000);
    liveTimers.push(t);
  }
  return span;
}
function dur(ms) {
  const s = Math.floor(ms / 1000), m = Math.floor(s / 60),
        h = Math.floor(m / 60), d = Math.floor(h / 24);
  if (d) return `${d}d ${h % 24}h`;
  if (h) return `${h}h ${m % 60}m`;
  if (m) return `${m}m ${s % 60}s`;
  return `${s}s`;
}
function becomesText(ba) {
  if (!ba) return "";
  if (ba.at) return "available at " + ba.at;
  if (ba.in_states) return "available in state(s) " + ba.in_states.join(", ");
  if (ba.requires) return "requires " + ba.requires;
  return "";
}

function blockedNotes(blocked, doc) {
  if (!blocked.length) return null;
  const box = el("ul", {class:"blockedwhy notnow"});
  for (const [name, entry] of blocked) {
    const li = el("li", {class:"item"},
      el("b", {}, label(name, entry)), " — ",
      el("span", {class:"why"}, entry.reason || ""),
      entry.becomes_available
        ? el("span", {class:"metaline"}, ` (${becomesText(entry.becomes_available)})`)
        : null);
    if (entry.becomes_available?.at)
      li.append(countdownEl(entry.becomes_available.at));
    const chips = remedyChips(entry.remedies, doc);
    if (chips) li.append(chips);
    box.append(li);
  }
  return box;
}

function notNowFooter(grouped, gated, doc) {
  const n = grouped.reduce((s, g) => s + g.length, 0) + gated.length;
  if (!n) return null;
  const box = el("div", {class:"cantyet"});
  for (const g of grouped)          // one reason, many actions: say it once
    box.append(el("div", {class:"item"},
      el("b", {}, g.map(([nm, e]) => label(nm, e)).join(", ")),
      el("div", {class:"why"}, g[0][1].reason || ""),
      remedyChips(g[0][1].remedies, doc)));
  const byStates = new Map();       // state gates: one line per destination
  for (const [nm, e] of gated) {
    const k = e.becomes_available.in_states.join(", ");
    if (!byStates.has(k)) byStates.set(k, []);
    byStates.get(k).push(label(nm, e));
  }
  for (const [states, names] of byStates)
    box.append(el("div", {class:"item"},
      el("b", {}, names.join(", ")),
      el("span", {class:"muted"}, ` — available in state(s) ${states}`)));
  return el("details", {class:"unavail"},
    el("summary", {class:"muted"}, `not now (${n})`), box);
}

/* ── remedies: the wire names the way out as "kind.action" tokens ── */
function remedyChips(remedies, doc, onAct) {
  if (!remedies || !remedies.length) return null;
  const docKind = (doc.kind || "").replace(/_collection$/, "");
  const box = el("span", {class:"remedies"});
  for (const token of remedies) {
    const dot = String(token).indexOf(".");
    const kind = dot > 0 ? token.slice(0, dot) : "";
    const action = dot > 0 ? String(token).slice(dot + 1) : String(token);
    if (kind === docKind && doc.actions?.[action]) {
      box.append(el("button", {type:"button", class:"chip remedy",
        title: `${token} — this action is on this page`,
        onclick: () => { onAct && onAct(); pulseAction(action); }},
        "→ " + label(action, doc.actions[action])));
    } else if (action === "create") {
      const chip = el("span", {class:"chip",
        title: `${token} — a remedy elsewhere`}, String(token));
      box.append(chip);
      wellKnown().then(w => {
        const col = collectionHref(w, kind);
        if (col) chip.replaceWith(el("a", {class:"chip remedy",
          href: "#"+col, onclick: () => { onAct && onAct(); },
          title: `${token} — opens the ${pretty(kind)}s page`},
          `+ new ${pretty(kind)}`));
      }).catch(() => {});
    } else {
      box.append(el("span", {class:"chip",
        title: `${token} — a remedy this page can't reach directly`},
        String(token)));
    }
  }
  return box;
}
function pulseAction(name) {
  const btn = $(`#view button[data-action="${CSS.escape(name)}"]`);
  if (!btn) return;
  btn.scrollIntoView({behavior:"smooth", block:"center"});
  btn.focus({preventScroll: true});
  btn.classList.add("pulse");
  setTimeout(() => btn.classList.remove("pulse"), 1500);
}

