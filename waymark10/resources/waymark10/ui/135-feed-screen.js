/* ── the feed screen: the day's mixed read, under a thumb ───────────
   waymark-iqa.7, docs/spec-feed.md fork (c). GET /api/-/feed answers a
   DOCUMENT — kind "feed", not an envelope — and render()
   (110-discovery-routing.js) forks on that kind the way it forks on
   "dashboard": the deploy-history tradition. Not a saved_view feed
   probe, for the four reasons the spec gives, of which two are visible
   right here: a card census has no single :target, and the seam has no
   row to ride an item list with.

   134-feed.js is the harvest and this file is deliberately its
   sibling: per-card panels built from the card's OWN projected
   envelope (so grants and state keep gating and the feed invents no
   affordance), an IntersectionObserver on links.next,
   the honest terminal panel, and a fresh read after a verb lands —
   the envelope answers, the feed never guesses an outcome. Three
   things differ, and each is the document rather than a preference:

   1. IT KEYS ON card_id, not on self. The seam has no row; two
      populations may name one row on two days; card_id is the card's
      identity within the day and the only key that exists for every
      element of this document.
   2. IT RENDERS A SEAM. "That's the house, caught up." is a
      structural element and prose, not a projection — its own quiet
      rule across the feed, and everything under it is history.
   3. THE VERBS ARE TAP CHIPS AND NOTHING SWIPES. checks.clj:744
      already refuses a gesture side on a :feed view — "a sequential
      read takes no {side} gesture" — and the epic asked for one tap.
      The gesture-duties policy's three DUTIES cross over anyway: a
      short label (the action's own :display :label), effort ≤
      selection (already partitioned server-side; `heavier` is a LINK
      because its href is a screen and a POST to it would land on the
      UI's index), and a way back (the undo affordance off the
      post-action envelope, exactly as the deck's commits take it).

   AND IT IS A LIST, NOT A STACK OF FULL-SCREEN PANELS. 134-feed.js
   snaps one item per viewport and leaves on Escape; both belong to an
   overlay over a table, and this is a screen the hash names. Escape
   would have nowhere honest to go (Back is the way out), and one card
   per screen would put a two-line memory alone on a phone and make
   the seam a page you must dismiss rather than one you scroll past.
   Cards are as tall as what they have to say.

   EVERY VERB FIRED HERE CARRIES THE ORIGIN KEY —
   `Idempotency-Key: feed/<day>/<card_id>/<nonce>`, feed/origin-key's
   own spelling — so actions-from-the-feed is one prefix away in the
   audit trail, per day, per section, per kind, forever. That is the
   epic's success measure and it is wired from the first tap rather
   than added when somebody asks.

   NO EXTERNAL BYTE IS FETCHED. A media card's origin rides as a link
   chip and never as an <img src>: this page is self-contained by
   declaration (020-base.css), and an image element pointed at a third
   party is a beacon wearing a picture's clothes. The link is honest
   and the row's own screen is one tap away.

   AND THE ONE BEACON THIS PAGE DOES SEND IS THE READER'S OWN
   (waymark-8um.1). The feed GET writes nothing and never will. What
   the SCREEN may do — only when doc.views.recording is true, which is
   false for every member who has not turned their own on and false on
   every preview — is post a `feed_view` per card it actually showed:
   a card must hold half the viewport for a full second, is reported
   at most once in this page's life, and the posts go out on one
   debounce rather than one per card per frame. Nothing is sent from a
   preview, because a preview of somebody else's feed is your looking
   and not theirs.

   AND A NEW DRAW IS A NEW PAGE LIFE (waymark-8um.2). Dealing again
   navigates, so `renderFeedScreen` runs afresh and the dedupe set is
   rebuilt — a card that comes up in two draws on one day is reported
   in both, which is what actually happened. The day-level truth is
   the door's, not this page's: `feed_view`'s :unique [[:card_id :day
   :member]] means the second report is refused by name and the house
   still holds exactly one row for that card that day. The dedupe here
   is a politeness about network traffic; the counting law is storage's
   and was never this page's to keep. */

/* the census, for the section rules and their headings — the recipe's
   own order, read off the CARDS rather than off doc.sections: an
   empty population contributes nothing and the seam moves up, so a
   heading painted from the census would announce a section that never
   arrives. */
const FEED_SECTION_LABEL = {
  outcomes: "This week could hold", do_now: "Do now", decide: "Decide",
  fuel: "Fuel", archive: "Archive"};
const FEED_SECTION_HINT = {
  outcomes: "composed, with the friction already paid — a thumb each way",
  do_now: "one physical next step, under the thumb",
  decide: "things waiting on somebody's answer",
  fuel: "what the house already finished",
  archive: "further back — a year ago this week, and what moved"};

/* the origin convention, client side (feed/origin-key). The card id
   is percent-encoded because a card_id carries slashes of its own and
   a metric that could not tell them from the key's would be a metric
   that guessed. The nonce is load-bearing: idempotency-lookup is
   scoped (key, kind) and two taps of one verb on one card on one day
   must not collide. */
function feedOriginKey(day, cardId) {
  return "feed/" + day + "/" + encodeURIComponent(cardId) + "/" +
    uuid().replace(/-/g, "").slice(0, 12);
}

/* a screen href, as this page navigates it: the server hands
   `/#/api/tasks/…` (feed/screen-of — the canonical address, the root
   serves this same page), and dropping the leading slash makes it the
   same destination without a reload. The address is unchanged; only
   the trip is cheaper. */
function feedScreenHref(href) {
  const h = String(href || "");
  return h.startsWith("/#") ? h.slice(1) : h;
}

/* is the feed's door mounted for this reader? The feed cannot
   advertise itself on .well-known — a module has no way to contribute
   a line to a core document and the contribution table is closed at
   four on purpose (spec-mcp-surface, inherited) — so the UI knows the
   address and asks. It asks with a cursor this engine could not have
   minted: the route refuses it before a single row is read (422), an
   engine assembled without the module answers 404, and so does an
   anonymous reader, who has no feed either. One probe per page load,
   the promise cached the way wellKnown() caches its own. */
let feedDoorCache = null;
function feedDoor() {
  if (!feedDoorCache)
    feedDoorCache = api("/api/-/feed?cursor=probe")
      .then(r => r.status !== 404, () => false);
  return feedDoorCache;
}

async function renderFeedScreen(view, doc) {
  const day = doc.day || "";
  const seen = new Set();          // card_id — one card per id, ever
  let nextHref = (doc.links || {}).next?.href || null;
  let lastSection = null;
  let loading = false;
  let count = 0;

  /* ── DEAL AGAIN (waymark-8um.2, law 6) ──────────────────────────────
     The person spins; the system never spins for them.

     THE NONCE IS MINTED HERE, on the tap, and it goes into the
     ADDRESS. That is the whole decision and it has three consequences
     worth wanting: a draw is bookmarkable, shareable and replayable
     (the same address always answers the same order — a draw is not a
     secret); the server stays a pure function of (member, day, draw)
     and stores nothing, exactly as it does for the daily order; and
     the document never has to carry a suggested nonce, which would
     have made every read of the feed different from every other read
     of the same feed.

     ↻ RE-READ IS NOT A SPIN. The two chips stand side by side so the
     difference is legible: re-read asks THE SAME ADDRESS again — same
     draw, fresh rows — and deal again asks a new one. Nothing else on
     this page mints a draw: no timer, no poll, no pull-to-refresh, and
     the ledger deliberately watches nothing (see watchScope below), so
     a household write cannot re-roll the order under a reader's thumb.
     Leaving the screen and coming back reads the day's own order,
     because the draw lives in the address and nowhere else. */
  function feedDrawNonce() {
    return uuid().replace(/-/g, "").slice(0, 10);
  }
  /* built from doc.self rather than from location.hash, because self
     is the SERVER's address for this read and already carries the
     preview a previewer is inside of */
  function feedAddress(draw) {
    const [path, q] = String(doc.self || "/api/-/feed").split("?");
    const p = new URLSearchParams(q || "");
    p.delete("draw");
    if (draw) p.set("draw", draw);
    return path + (p.toString() ? "?" + p : "");
  }
  function goToFeed(href) {
    if (location.hash === "#" + href) render();
    else location.hash = "#" + href;
  }

  /* ── chrome ─────────────────────────────────────────────────────── */
  /* the day rides the head as data, not as a second copy of the
     sentence: doc.summary already says "Feed · <day> · N cards", and
     this page does not rewrite the server's own prose */
  const head = el("div", {class: "panel feed-head", "data-day": day},
    el("h2", {class: "prose"}, "The feed"),
    el("div", {class: "metaline"},
      el("span", {class: "muted", title: "the day this order was seeded for"
                                       + " — it rolls at midnight"},
        doc.summary || day),
      el("button", {class: "chip",
                    title: "read this same order again from the top — the same"
                         + " draw, with whatever the house has done since",
                    onclick: () => render()}, "↻ Re-read"),
      el("button", {class: "chip",
                    title: "a fresh order over the same house. You spin it;"
                         + " nothing here ever spins it for you.",
                    onclick: () => goToFeed(feedAddress(feedDrawNonce()))},
        "🂠 Deal again"),
      doc.draw
        ? el("button", {class: "chip",
                        title: "back to the house's usual order for today",
                        onclick: () => goToFeed(feedAddress(null))},
            "↩ Today's order")
        : null));
  /* WHY THIS ORDER opens into the RECIPE (waymark-iqa.29). The order
     used to be a hard-coded vector in an application's main.clj that no
     reader would ever see; the server now narrates it line by line, in
     the household's own words where the household wrote them, and this
     disclosure is where a person reads it. Viewing only — editing a
     recipe is its own bead and wants the composition scaffolding a
     saved_view has. The notes ride underneath it, because a note is
     about this READ and a line is about the order itself. */
  if ((doc.notes || []).length || (doc.recipe || {}).lines) {
    const why = el("details", {class: "feed-why"},
      el("summary", {}, "Why this order"));
    const recipe = doc.recipe || {};
    if ((recipe.lines || []).length) {
      const ol = el("ol", {class: "feed-recipe"});
      for (const line of recipe.lines) {
        const counts = [];
        if (typeof line.offered === "number")
          counts.push(line.offered + " offered");
        if (line.claimed_above > 0)
          counts.push(line.claimed_above + " already claimed above");
        if (typeof line.showed === "number")
          counts.push(line.showed + " shown");
        ol.append(el("li", {class: line.seam ? "feed-recipe-seam" : "",
                            value: (line.line ?? 0) + 1},
          el("span", {class: "prose"}, line.says || ""),
          counts.length
            ? el("span", {class: "muted feed-recipe-counts"},
                " — " + counts.join(" · "))
            : null));
      }
      why.append(ol);
      if (recipe.guarantees)
        why.append(el("p", {class: "prose muted"}, recipe.guarantees));
    }
    /* the contest, in the household's own words with its own two
       numbers (waymark-8um.3, law 5). It rides EVERY answer, whether or
       not it is doing anything for this reader, because the point of a
       readable formula is that a person can read it before they have to
       care — and the page authors none of it: the sentence is the
       server's, the numbers are the recipe's. */
    if (recipe.formula_says)
      why.append(el("p", {class: "prose muted"}, recipe.formula_says));
    /* …and the crown's rank, the same way (waymark-1uv.2): four
       numbers, narrated by the server with the recipe's own numbers
       in the sentence, on every answer. */
    if (recipe.crown_rank_says)
      why.append(el("p", {class: "prose muted"}, recipe.crown_rank_says));
    for (const n of doc.notes || [])
      why.append(el("p", {class: "prose muted"}, n));
    /* the view door, when it is SHUT (waymark-8um.1). The open case
       already rides doc.notes above, said once by the server. This is
       the closed one, and it lives inside the collapsed disclosure
       rather than on the page: a screen that asked every morning for a
       permission it was right not to have would be nagging, and a
       switch nobody can find is not a choice. */
    if ((doc.views || {}).says && !(doc.views || {}).recording)
      why.append(el("p", {class: "prose muted"}, doc.views.says + " ",
        doc.views.switch
          ? el("a", {href: "#" + doc.views.switch}, "the switch ↗")
          : null));
    head.append(why);
  }
  view.append(head);

  /* ── COMPOSE ME ANOTHER (waymark-jfv.20) ─────────────────────────────
     The person pulls, and the rank puts the answer first (since
     waymark-1uv.3 the chip rides whether or not a bundle is on offer;
     asking means 'rank mine first'). The server says whether the
     crown carded nothing (`doc.crown.empty`), where the ask goes
     (`doc.crown.ask`), and which requests this reader already has
     standing — this page paints exactly that and authors none of it. The chip is ONE TAP under the feed's own origin key
     (the card_id is the server's, `outcomes/composition_request/ask`),
     so the pull is counted as an action-from-the-feed like any card
     verb; a standing request is a sentence with a link and no verb,
     because your own request is not a decision to make. The answer
     comes at the composer's next sitting, and the note says so rather
     than letting a button imply a vending machine. */
  const crown = doc.crown || null;
  if (crown && (crown.ask || (crown.standing || []).length)) {
    const panel = el("div", {class: "feed-sect feed-crown",
                             "data-crown-empty": crown.empty ? "1" : "0"},
      el("b", {}, FEED_SECTION_LABEL.outcomes),
      el("span", {class: "muted"}, crown.says || ""));
    const bar = el("div", {class: "actions feed-verbs"});
    for (const s of crown.standing || [])
      bar.append(el("a", {class: "chip", href: "#" + s.self,
                          title: "your standing request — the composer"
                               + " reads it at its next sitting"},
        "asked " + String(s.asked_at || "").slice(0, 10)
          + (s.value_name ? " · for " + s.value_name : "") + " ↗"));
    if (crown.ask) {
      const problem = el("div", {"data-feed-problem": ""});
      const chip = el("button", {class: "chip primary",
                                 title: crown.ask.note || ""},
        "✦ " + (crown.ask.label || "Compose me another"));
      chip.onclick = async () => {
        chip.disabled = true;
        const res = await api(crown.ask.href, {
          method: crown.ask.method || "POST",
          body: JSON.stringify({}),
          headers: {"Idempotency-Key": feedOriginKey(day, crown.card_id)}});
        if (!res.ok) {
          chip.disabled = false;
          problem.replaceChildren(problemBox(res.body || {}));
          return;
        }
        problem.replaceChildren();
        chip.replaceWith(el("span", {class: "feed-settled"},
          el("span", {class: "ok"}, "✓ "), "Asked",
          el("span", {class: "muted"},
             " — the composer answers at its next sitting")));
      };
      bar.append(chip);
      panel.append(bar, problem);
    } else panel.append(bar);
    view.append(panel);
  }

  const list = el("div", {class: "feedcards", role: "feed",
                          "aria-label": "the day's feed"});
  view.append(list);
  const endBox = el("div", {class: "feed-endbox"});
  view.append(endBox);
  /* the ledger watches nothing in particular here: a feed that
     refetched on every household write would re-roll under the
     reader's thumb, and the day's order is supposed to hold still */
  watchScope({});

  /* ── the view door (waymark-8um.1) ──────────────────────────────────
     The server says whether this reader's screen may report what it
     showed. It is FALSE unless the member turned their own on, and it
     is false on every preview — so this page never has to decide who
     the beacon would be about, and it never sends one for somebody
     else's feed. `doc.preview` is checked as well, and the redundancy
     is deliberate: two independent reasons to stay quiet, because the
     one thing this must never do is file a preview under the member
     being previewed.

     The door refuses anyway, by name, and that is not a reason for
     this page to be careless — it is why being careless here would
     only ever produce a refusal nobody reads. */
  const viewDoor = doc.views || {};
  const recordingViews = viewDoor.recording === true && !doc.preview;
  const viewPostTo = viewDoor.post_to || "/api/feed_views";
  const reported = new Set();      // card_id — reported once, ever
  let pendingViews = new Map();    // card_id → population
  let viewFlush = null;

  /* one POST per card, and the batching the epic asked for is bought
     with DWELL and DEDUPE rather than with a bulk door: a row is per
     card because the ranking formula aggregates by card, and a
     screenful carrying a vector of ids would put the thing being
     counted inside a JSON array with no index on it. */
  function flushViews() {
    viewFlush = null;
    const batch = pendingViews;
    pendingViews = new Map();
    for (const [cardId, population] of batch)
      api(viewPostTo, {method: "POST",
                       body: JSON.stringify({card_id: cardId,
                                             population: population,
                                             day: day})});
  }

  function reportShown(cardId, population) {
    if (!recordingViews || !cardId || cardId === "seam") return;
    if (reported.has(cardId)) return;
    reported.add(cardId);
    pendingViews.set(cardId, population || "");
    if (!viewFlush) viewFlush = setTimeout(flushViews, 1500);
  }

  /* half the card, for a full second. A card flicked past on the way
     to the archive was not shown to anybody, and counting it would be
     the manufactured engagement the third law forbids, measured
     instead of manufactured. */
  const dwelling = new Map();      // element → timer
  const viewIo = recordingViews && "IntersectionObserver" in window
    ? new IntersectionObserver(entries => {
        for (const e of entries) {
          const card = e.target;
          if (e.isIntersecting) {
            if (dwelling.has(card)) continue;
            dwelling.set(card, setTimeout(() => {
              dwelling.delete(card);
              viewIo.unobserve(card);
              reportShown(card.getAttribute("data-card-id"),
                          card.getAttribute("data-population"));
            }, 1000));
          } else {
            clearTimeout(dwelling.get(card));
            dwelling.delete(card);
          }
        }
      }, {threshold: 0.5})
    : null;

  /* ── the card renderers ─────────────────────────────────────────── */
  /* the seam: prose, not a projection, and the one element of this
     document with no row behind it */
  function seamPanel(card) {
    return el("div", {class: "feed-seam", "data-card-id": "seam"},
      el("div", {class: "feed-seam-rule"}),
      el("div", {class: "feed-seam-say prose"},
        card.sentence || "That's the house, caught up."),
      el("div", {class: "muted feed-seam-sub"},
        (card.above ?? 0) + " above · everything below is history"));
  }

  /* the byline: a principal id and it must stay one. :by is :raw by
     the decision sugar's own rule, and a display layer that dressed an
     agent up as a person would be hiding the one fact the reader most
     needs (spec-feed.md, .6's note). The badge posture says WHOSE
     without pretending to know WHO. */
  function bylineChip(pid) {
    return el("span", {class: "statechip fcard-by mono",
      title: "published by principal " + pid + " — the id as the wire "
           + "gives it. This house does not dress a principal up as a "
           + "person; an agent's findings are offered, never accepted, "
           + "by the agent that found them."}, "◆ " + pid);
  }

  function verbChip(card, article, name, entry) {
    const primary = (entry.display || {}).style === "primary";
    const chip = el("button",
      {class: "chip verb" + (primary ? " primary" : ""),
       "data-action": name, "data-effort": entry.effort || "",
       title: (entry.display || {}).description ||
              (entry.safety || {}).one_way || ""},
      label(name, entry), (entry.safety || {}).confirm ? " …" : "");
    chip.addEventListener("click", () => fireVerb({
      card, article, chip, name, entry,
      lbl: label(name, entry), doc: card,
      href: entry.href, method: entry.method || "POST"}));
    return chip;
  }

  /* one tap, one origin key, one honest answer. A verb that wants a
     form or a confirmation is not a tap and goes through the ordinary
     dialog — carrying the same key, so a card verb counts as one
     whichever door it opened. */
  async function fireVerb(v) {
    const {card, article, chip, name, entry, lbl} = v;
    /* the origin names the card the thumb was actually on. A PIECE of
       a bundle is its own card_id (feed/piece-card mints it), so
       actions-from-the-feed counts the piece rather than filing the
       tap under the bundle that happened to contain it. */
    const key = feedOriginKey(day, v.cardId || card.card_id);
    if (entry && (entry.input || (entry.safety || {}).confirm)) {
      actionDialog({name, entry, doc: v.doc, idemKey: key,
                    onDone: out => settle(card, article, lbl, out)});
      return;
    }
    chip.disabled = true;
    const h = {"Idempotency-Key": key};
    if ((entry || {}).safety?.fence && (v.doc.meta || {}).etag)
      h["If-Match"] = v.doc.meta.etag;
    const res = await api(v.href, {method: v.method, body: JSON.stringify({}),
                                   headers: h});
    if (!res.ok) {
      chip.disabled = false;
      /* the engine's own refusal sentence, on the card that asked for
         it — never a toast that scrolls away from the thing it is
         about. A piece keeps its refusal on the piece: the target's
         own create guards judge at the tap, and the household needs
         to read which LINE was refused. */
      const box = (v.problemBox && v.problemBox.isConnected)
        ? v.problemBox : article.querySelector("[data-feed-problem]");
      box.replaceChildren(problemBox(res.body || {}));
      return;
    }
    (v.problemBox || article.querySelector("[data-feed-problem]"))
      .replaceChildren();
    maybeUndoToast(name, v.doc, res.body || {});
    /* a card verb answers the CARD; the offered step answers a
       different row and leaves the finding still unanswered, so it
       settles only its own chip */
    if (v.scope === "chip") {
      chip.replaceWith(el("span", {class: "feed-settled"},
        el("span", {class: "ok"}, "✓ "), lbl));
      return;
    }
    /* a PIECE answers itself and leaves the rest of the bundle
       standing — that IS the partial accept, and the card has to show
       it happening one line at a time */
    if (v.scope === "piece") {
      settleBar(v.bar, lbl, res.body);
      reasonChips(v.bar, v);
      return;
    }
    /* AWAITED, and it has to be: the settle may finish by re-reading
       the row, and a chip row appended before that read landed would
       be wiped by the bar it was appended to. */
    await settle(card, article, lbl, res.body);
    reasonChips(article.querySelector(".feed-verbs"), v);
  }

  /* ── the quick reasons (waymark-jfv.16) ─────────────────────────────
     THE DECLINE STAYS ONE TAP. Everything above this line fires an
     input-free verb; what happens here is a SECOND, OPTIONAL tap on a
     card that has already settled — which is the only shape the card
     grammar leaves. A verdict that collected anything would read
     `recall` at `demand/effort`, and `split-verbs` would have moved it
     off the card into a link before this page ever saw it.

     Three facts arrive rather than being derived, and that is the
     whole of this page's manners:

     - WHETHER to ask is the ACTION's own advertisement
       (`display.reasons`), so it rides the ordinary grant projection.
       A decline a reader does not hold carries no chips for the same
       structural reason it carries no verb.
     - WHERE to send it is the DOCUMENT's own door (`reasons.post_to`),
       read off the reason kind's `:plural` — the `views` precedent.
     - WHICH WORDS to offer is the same door's `choices`, which is the
       declaration's own enum wearing its own `:x-display` prose. A
       fifth word declared server-side is a fifth chip here with
       nothing changed.

     Tapping none is a complete answer and nothing is written. */
  function reasonChips(bar, v) {
    const door = doc.reasons || null;
    if (!bar || !door || !door.post_to) return;
    const choices = door.choices || [];
    if (!choices.length) return;
    if (!(((v.entry || {}).display || {}).reasons)) return;
    const subject = v.doc || {};
    const self = String(subject.self || "");
    if (!self) return;
    const row = el("div", {class: "feed-reasons", "data-reasons": ""});
    row.append(el("span", {class: "muted feed-reasons-ask"}, "Why?"));
    for (const c of choices) {
      const chip = el("button",
        {class: "chip reason", "data-reason": c.value,
         title: door.says || ""}, c.label);
      chip.addEventListener("click", async () => {
        for (const b of row.querySelectorAll("button")) b.disabled = true;
        /* the same origin convention the verdict rode, with a fresh
           nonce: a reason tapped from a card IS an action from the
           feed, and actions-from-the-feed should be able to count it
           against the card the thumb was actually on. */
        const key = feedOriginKey(day, v.cardId || v.card.card_id);
        const res = await api(door.post_to, {
          method: "POST",
          headers: {"Idempotency-Key": key},
          body: JSON.stringify({
            subject_kind: subject.kind || v.card.kind || "",
            subject_id: self.split("/").pop(),
            subject_href: self,
            /* `says` FIRST: a piece's own sentence is what it IS,
               while its `display.title` is the kind's static heading
               ("Piece of an outcome"), which would make every reason
               row read the same. A bundle carries no `says` and its
               title is its goal, so the fallback is right there. */
            about: subject.says || (subject.display || {}).title ||
                   subject.summary || "",
            verdict: v.name,
            [door.field || "reason"]: c.value})});
        if (!res.ok) {
          for (const b of row.querySelectorAll("button")) b.disabled = false;
          const box = (v.problemBox && v.problemBox.isConnected)
            ? v.problemBox : v.article.querySelector("[data-feed-problem]");
          if (box) box.replaceChildren(problemBox(res.body || {}));
          return;
        }
        /* THE CHIPS COLLAPSE TO THE WORD THAT WAS CHOSEN — the others
           do not come back, the way no settled verb does. Beside it,
           the way to the second layer: the sentence a quick word could
           not carry lives on the reason row's own screen, and it is a
           LINK because a prose box is `composition` and composition is
           never a thumb. */
        const reasonSelf = ((res.body || {}).self) || "";
        row.replaceChildren(
          el("span", {class: "feed-settled"},
            el("span", {class: "ok"}, "✓ "), c.label),
          reasonSelf
            ? el("a", {class: "chip link-chip", href: "#" + reasonSelf,
                       title: "add what the quick word could not carry"},
                "Say more ↗")
            : null);
      });
      row.append(chip);
    }
    bar.append(row);
  }

  /* one verb bar, settled: the chips do not come back. A fresh
     envelope carries every effort, and re-deriving the ≤-selection
     partition here would be a second opinion about what fits under a
     thumb (feed/card-ceiling is the server's one spelling). */
  function settleBar(bar, lbl, after) {
    if (!bar) return;
    bar.replaceChildren(el("span", {class: "feed-settled"},
      el("span", {class: "ok"}, "✓ "), lbl,
      (after && after.state)
        ? el("span", {class: "muted"}, " · now " + pretty(after.state))
        : null));
  }

  /* after a verb lands: the ROW's own fresh envelope decides what the
     card now says. The verbs do not come back — a fresh envelope
     carries every effort and re-deriving the ≤-selection partition
     here would be a second opinion about what fits under a thumb
     (feed/card-ceiling is the one spelling, and it is the server's).
     The card that is answered is answered; the next read of the day
     simply will not carry it. */
  async function settle(card, article, lbl, after) {
    article.classList.add("done");
    /* a bundle's own verdict answers every piece still on offer — in
       one transaction, server-side — so the pieces' chips go with it
       rather than sitting there offering a door that is closed */
    for (const pb of article.querySelectorAll("[data-piece-verbs]"))
      if (pb.querySelector("button"))
        pb.replaceChildren(el("span", {class: "feed-settled muted"},
          "answered with the bundle"));
    const bar = article.querySelector(".feed-verbs");
    const say = state => el("div", {class: "feed-settled"},
      el("span", {class: "ok"}, "✓ "), lbl,
      state ? el("span", {class: "muted"}, " · now " + pretty(state)) : null);
    if (after && after.self && after.state) {
      if (bar) bar.replaceChildren(say(after.state));
      const line = article.querySelector("[data-feed-summary]");
      if (line && after.summary) line.textContent = after.summary;
      return;
    }
    const {ok, body} = await api(card.self);
    if (!article.isConnected) return;
    if (bar) bar.replaceChildren(say(ok && body ? body.state : null));
    const line = article.querySelector("[data-feed-summary]");
    if (line && ok && body && body.summary) line.textContent = body.summary;
  }

  /* an insight's evidence, read late and quietly. `evidence` is a
     vector, and a vector does not ride :fields (render/grid-fields —
     a flat cell cannot hold one), so the count is not on the card and
     the card's own href is where it lives. One read per insight card,
     at most two a day by the recipe, and a card that answers nothing
     simply keeps the line it already had. */
  async function fillEvidence(slot, card) {
    const {ok, body} = await api(card.self);
    if (!ok || !slot.isConnected) return;
    const ev = ((body || {}).data || {}).evidence || [];
    if (!ev.length) return;
    slot.replaceChildren(el("span", {},
      el("span", {class: "muted"}, `read ${ev.length} `
        + (ev.length === 1 ? "row" : "rows") + ": "),
      ev.slice(0, 6).map((addr, i) => [
        i ? ", " : "",
        el("a", {class: "mono", href: "#" + addr, title: addr},
          String(addr).split("/").pop().slice(0, 8))]),
      ev.length > 6 ? el("span", {class: "muted"}, ` +${ev.length - 6}`) : null));
  }

  /* ── why this card is here (waymark-iqa.29) ─────────────────────────
     The citation is the SERVER's, always, and this page never derives
     one: a why assembled here from predicates would be a second
     opinion about what admitted a card, which is exactly the mistake
     `heavier` exists to avoid on the verb side. What the page does is
     ask.

     Two halves, and the split is the wire's own cost decision. Every
     card carries `why` — the recipe line that admitted it and where
     the seed drew it — so the disclosure can open with something true
     before any network happens, joined against the narrated recipe the
     document already carries. The SENTENCES cost prose per card, so
     they ride ?explain=1 and are fetched once per page, the first time
     anybody actually asks. That late read is sound because the feed's
     own law says so: two reads of one DRAW answer the same cards in
     the same order (:feed/day-stable, :feed/draw-stable), so the
     answer lines up by card_id and cannot be a different day's feed —
     or, since waymark-8um.2, a different draw's. `srcHref` is
     `doc.self`, which carries the draw, so the late read is the same
     spin the page is showing. */
  const explainCache = new Map();          // page href → Promise<Map>
  function explainOf(srcHref) {
    if (!explainCache.has(srcHref)) {
      const href = srcHref + (srcHref.includes("?") ? "&" : "?") + "explain=1";
      explainCache.set(srcHref, api(href).then(({ok, body}) => {
        const m = new Map();
        if (ok) for (const c of (body || {}).cards || [])
          if (c && c.card_id && (c.why || {}).says) m.set(c.card_id, c.why.says);
        return m;
      }, () => new Map()));
    }
    return explainCache.get(srcHref);
  }

  function whyDisclosure(card, srcHref) {
    const why = card.why;
    if (!why) return null;
    const line = ((doc.recipe || {}).lines || [])[why.line];
    const box = el("div", {class: "fcard-why-body"});
    /* the honest opening line, from parts the card and the document
       already carry: the recipe's own sentence for the line that
       admitted this card, and the size of the draw it came out of */
    if (line && line.says)
      box.append(el("p", {class: "prose"},
        "Recipe line " + (why.line + 1) + " — " + line.says));
    if (typeof why.rank === "number")
      box.append(el("p", {class: "muted"},
        "Drawn #" + why.rank + " of " + why.of + " this line offered today."));
    /* the contest's own two numbers, from the card's own always-on why
       (waymark-8um.3). Present only when the reader is recording and
       this section is one the contest may weight — absent everywhere
       else, which is itself the honest answer. The full sentence
       arrives with the server's citation when anybody actually asks. */
    if (typeof why.seen === "number")
      box.append(el("p", {class: "muted"},
        why.seen === 0
          ? "Fresh — you have not been shown this one."
          : ("Shown " + why.seen + " day" + (why.seen === 1 ? "" : "s")
             + " with nothing done"
             + (why.cooled > 0
                ? (", so it steps back " + why.cooled
                   + " place" + (why.cooled === 1 ? "" : "s") + " in its line.")
                : ", which has not cooled it yet."))));
    /* the crown's own numbers, from the card's always-on why.crown
       (waymark-1uv.2): the lift and the inputs that made it, before
       any network happens. Present only on a crown card; the server's
       full arithmetic replaces it when anybody asks. */
    if (why.crown && typeof why.crown.lift === "number") {
      const c = why.crown;
      const parts = [
        c.asked ? "answers your own request, so it stands first" : "nobody asked for it",
        c.value === "declared" ? "serves a declared value" : "serves a value only observed so far",
        (typeof c.seen === "number")
          ? ("shown " + c.seen + " day" + (c.seen === 1 ? "" : "s")
             + (c.cooled > 0 ? (", cooled " + c.cooled) : ""))
          : null,
        c.declined ? ("the house said " + String(c.declined).replace(/_/g, " ")
                      + " about this line of thinking") : null,
        c.days_left + " day" + (c.days_left === 1 ? "" : "s") + " left on its week"];
      box.append(el("p", {class: "muted"},
        "Ranked in the crown — lift " + c.lift + ": "
        + parts.filter(Boolean).join("; ") + "."));
    }
    const details = el("details", {class: "fcard-why"},
      el("summary", {}, "Why this card?"), box);
    let asked = false;
    details.addEventListener("toggle", async () => {
      if (!details.open || asked) return;
      asked = true;
      const says = (await explainOf(srcHref)).get(card.card_id);
      if (!says || !says.length || !box.isConnected) return;
      /* the server's own sentences replace the opening two: same
         citation, spelled out, and every trait word in it is the
         declaration's own */
      box.replaceChildren(...says.map(s => el("p", {class: "prose"}, s)));
    });
    return details;
  }

  /* ── a bundle's pieces (waymark-jfv.4) ──────────────────────────────
     A card carrying `pieces` is a BUNDLE, and the dispatch is on that
     shape and never on a kind name — `outcome` is an application's
     word and this is the framework's page.

     Each piece is a line with its own state, its own sentence and its
     OWN chips, because the epic's unit of consent is a thumb per part:
     tap Not this on the ones that are wrong, then Make it so on the
     bundle, which takes the ones still standing. The chips are the
     piece row's own projected verbs — the server already ran them
     through the same grant projection and the same ≤-selection
     partition the card's went through — so this page invents no
     affordance and hides none it was handed. */
  function pieceLine(card, article, piece) {
    const bar = el("div", {class: "fpiece-verbs", "data-piece-verbs": ""});
    const box = el("div", {class: "fpiece-problem"});
    const orderOf = e => (e.display || {}).order ?? 99;
    for (const [name, entry] of Object.entries(piece.actions || {})
           .sort(([a, ea], [b, eb]) =>
             orderOf(ea) - orderOf(eb) || a.localeCompare(b))) {
      const primary = (entry.display || {}).style === "primary";
      const chip = el("button",
        {class: "chip verb" + (primary ? " primary" : ""),
         "data-action": name, "data-effort": entry.effort || "",
         title: (entry.display || {}).description ||
                (entry.safety || {}).one_way || ""},
        label(name, entry), (entry.safety || {}).confirm ? " …" : "");
      chip.addEventListener("click", () => fireVerb({
        card, article, chip, name, entry, bar, problemBox: box,
        cardId: piece.card_id, scope: "piece",
        lbl: label(name, entry), doc: piece,
        href: entry.href, method: entry.method || "POST"}));
      bar.append(chip);
    }
    /* a piece whose only surviving verb wants a screen says so, the
       same way a card does — a link, never a button */
    for (const h of piece.heavier || [])
      bar.append(el("a", {class: "chip link-chip",
        href: feedScreenHref(h.href),
        title: `${h.label} asks for a screen — effort ${h.effort}`},
        h.label + " ↗"));
    /* THE IMPACT LINE (waymark-jfv.17), and it is VISIBLE rather than
       disclosed. The owner's discomfort was not knowing what the tap
       would do, and a sentence behind a toggle is a sentence nobody
       read — so it sits under the composer's prose and above the
       chips, quiet, in the server's own words. Two voices, one line
       apart: `says` is what the piece IS and whoever composed it
       wrote that; `impact` is what saying yes DOES and only the
       engine writes that. */
    const impact = piece.impact
      ? el("div", {class: "fpiece-impact", "data-impact": ""}, piece.impact)
      : null;
    return el("li", {class: "fpiece", "data-card-id": piece.card_id,
                     "data-kind": piece.kind || ""},
      el("div", {class: "fpiece-top"},
        piece.state ? el("span", {class: "statechip"}, piece.state) : null,
        el("a", {class: "fpiece-say prose", href: "#" + piece.self,
                 title: piece.self},
          piece.says || piece.summary || "this piece")),
      impact,
      bar.childElementCount ? bar : null,
      box);
  }

  /* the card, by the shape the WIRE gives it rather than by any kind
     name this generic page could not know: a card carrying `pieces`
     is a composed bundle whose parts each answer for themselves; a
     card carrying an `offer` link is a finding with a next step
     attached; a card with a sentence and no verb is fuel or a memory,
     something to read; and everything else is a row speaking for
     itself. An unknown card degrades into that last shape rather than
     into a blank — and a card that throws is a problem panel wearing
     its own refusal, never its neighbours' problem (the dashboard's
     posture). */
  function cardArticle(card, hints, srcHref) {
    const kind = card.kind || "";
    const article = el("article",
      /* section and population ride as DATA — the two names the recipe
         declared, here for styling and for nothing else: no client
         may reorder on them */
      {class: "fcard", "data-card-id": card.card_id,
       "data-section": card.section || "", "data-kind": kind,
       "data-population": card.population || ""});
    /* something to READ rather than to answer: no verb of any weight,
       below the seam or in the fuel section. A fuel card is USUALLY
       verb-less (a done row has no verbs left) and that is the one
       place in the census where it is the point — do-now drops a card
       with no verb and fuel is made of them. */
    const quiet = !Object.keys(card.actions || {}).length &&
                  !(card.heavier || []).length &&
                  (card.sentence || card.section === "fuel" ||
                   card.section === "archive");
    const offer = (card.links || {}).offer;
    const bundle = (card.pieces || []).length ? card.pieces : null;
    const heading = (card.display || {}).title || card.summary || title(kind);
    /* the say-line: the server's own sentence wherever there is one —
       the seam has one, a cleared queue has one, a memory from a year
       ago has one — and otherwise the summary, unless the summary is
       just the heading wearing its state ("Call the dentist · open"),
       which the state chip above already said. */
    const say = card.sentence ||
      (card.summary && !card.summary.startsWith(heading) ? card.summary : null);
    /* the top line: what state the row is in, what kind it is, when it
       last moved (or, for an archive card, when the moment WAS) */
    const when = card.at || (card.meta || {}).updated_at || "";
    article.append(el("div", {class: "fcard-top"},
      card.state ? el("span", {class: "statechip"}, card.state) : null,
      el("span", {class: "fcard-kind",
                  title: `${pretty(card.population || card.section || "")}`
                       + ` · ${kind}`}, pretty(kind)),
      /* the byline, on the two cards that are somebody's WORK rather
         than the house's own rows: a finding was authored, a bundle
         was composed, and either way the reader is owed the principal
         id rather than a name this page invented for it */
      (offer && (card.fields || {}).authored_by)
        ? bylineChip(card.fields.authored_by)
        : (bundle && (card.fields || {}).composed_by
             ? bylineChip(card.fields.composed_by) : null),
      when ? el("span", {class: "version",
                         title: card.at ? "when this happened" : "last moved"},
        String(when).slice(0, 16).replace("T", " ")) : null));

    if (quiet) {
      /* the read-only shape: the sentence IS the card (a cleared
         queue, a streak, a year ago this week) and the row's own
         screen is one tap away underneath it. No table, no chips —
         a finished thing is something to read. */
      article.append(
        el("div", {class: "fcard-say prose", "data-feed-summary": ""},
          say || heading),
        el("div", {class: "fcard-sub"},
          el("a", {href: "#" + card.self, title: card.self},
            card.sentence ? (card.summary || heading) : "open it ↗")));
    } else {
      article.append(el("h3", {class: "fcard-title prose"},
        el("a", {href: "#" + card.self, title: card.self}, heading)));
      if (say)
        article.append(el("div", {class: "fcard-say prose",
                                  "data-feed-summary": ""}, say));
      for (const line of feedTeaserLines(card, hints))
        article.append(line);
    }

    /* THE PIECES, each one its own line with its own thumb. They go
       between what the bundle SAYS and what the bundle's own verdicts
       are, because that is the order a person reads it in: this is
       what the week could hold, here is what it is made of, and here
       is the answer to the whole of it. */
    if (bundle)
      article.append(el("ul", {class: "fcard-pieces"},
        bundle.map(p => pieceLine(card, article, p))));

    /* …and the BUNDLE's own impact line under them: the union its own
       verb would take, said once, directly above the chip that takes
       it. Same key as a piece's, same voice, one level up. */
    if (card.impact)
      article.append(el("div", {class: "fcard-impact", "data-impact": ""},
        card.impact));

    /* the evidence, on the two cards that CLAIM something: a finding
       cites what it read, and so does a bundle — an outcome sits on
       top of the household's own ledger, and the ledger is one tap
       down each of these links */
    if (offer || bundle) {
      const slot = el("div", {class: "fcard-evidence"});
      article.append(slot);
      fillEvidence(slot, card);
    }

    const bar = el("div", {class: "feed-verbs"});
    /* THE OFFER IS THE PRIMARY AFFORDANCE, and it is two things,
       deliberately: the chip DOES the offered step through the row's
       own action door — the reader's own grant gating it at the
       router, which is better than any handler could manage — and the
       link GOES to the row's screen. Accepting the finding is a
       separate answer and rides the verdict chips beside them. */
    if (offer && offer.href) {
      const oaction = (card.fields || {}).offer_action;
      const target = feedScreenHref(offer.href);      // "#/api/…"
      if (oaction && target.startsWith("#/api/")) {
        const doorHref = target.slice(1) + "/-/" + oaction;
        const chip = el("button", {class: "chip verb primary",
          "data-offer": oaction,
          title: "do the offered step now — " + doorHref
               + " · your own grant gates it, the same as on its own screen"},
          title(oaction));
        chip.addEventListener("click", () => fireVerb({
          card, article, chip, name: oaction, entry: null,
          lbl: title(oaction), doc: {self: target.slice(1)},
          href: doorHref, method: "POST", scope: "chip"}));
        bar.append(chip);
      }
      bar.append(el("a", {class: "chip link-chip", href: target,
        title: offer.summary || "the row this finding is about"},
        "Open the row ↗"));
    }
    const orderOf = e => (e.display || {}).order ?? 99;
    for (const [name, entry] of Object.entries(card.actions || {})
           .sort(([a, ea], [b, eb]) =>
             orderOf(ea) - orderOf(eb) || a.localeCompare(b)))
      bar.append(verbChip(card, article, name, entry));
    for (const h of card.heavier || [])
      bar.append(el("a", {class: "chip link-chip",
        href: feedScreenHref(h.href),
        title: `${h.label} asks for a screen — effort ${h.effort}, which is `
             + "heavier than a tap"}, h.label + " ↗"));
    /* the row's other declared doors, and only the ones that are a
       PLACE TO GO: a screen the declaration spelled with "/#" (the
       tickler's subject, an insight's offer), a download, or a door
       out of this house (a media card's origin — a link, never an
       <img>: see the header). A link naming a collection is a query
       rather than a next step, and a card has no room for a query. */
    for (const [rel, l] of Object.entries(card.links || {}))
      if (l && l.href && rel !== "offer" && rel !== "self" &&
          (l.external || l.download || String(l.href).startsWith("/#")))
        bar.append(el("a", {class: "chip link-chip",
          href: l.external || l.download ? l.href : feedScreenHref(l.href),
          target: l.external ? "_blank" : null,
          rel: l.external ? "noopener" : null,
          title: l.summary || rel},
          (l.download ? "⭳ " : l.external ? "↗ " : "") + title(rel)
          + (l.external || l.download ? "" : " ↗")));
    if (bar.childElementCount) article.append(bar);
    const cite = whyDisclosure(card, srcHref);
    if (cite) article.append(cite);
    article.append(el("div", {"data-feed-problem": ""}));
    return article;
  }

  /* ── appending, section by section ──────────────────────────────── */
  /* `srcHref` is the door THIS batch came through, and it travels with
     the cards because a citation is fetched from the same page that
     served them: an archive card on page four is explained by page
     four's own read, never by the top of the feed. */
  function appendCards(cards, srcHref) {
    for (const card of cards || []) {
      if (!card || !card.card_id || seen.has(card.card_id)) continue;
      seen.add(card.card_id);
      const section = card.section || "";
      if (section === "seam") { list.append(seamPanel(card)); lastSection = null;
                                continue; }
      if (section !== lastSection) {
        lastSection = section;
        list.append(el("div", {class: "feed-sect"},
          el("b", {}, FEED_SECTION_LABEL[section] || title(section)),
          el("span", {class: "muted"}, FEED_SECTION_HINT[section] || "")));
      }
      count++;
      try {
        const article = cardArticle(card, feedHints[card.kind] || {}, srcHref);
        list.append(article);
        if (viewIo) viewIo.observe(article);
      } catch (e) {
        /* degrade alone: one card that cannot render is a problem
           panel wearing its own refusal, never a broken page */
        list.append(el("article", {class: "fcard",
          "data-card-id": card.card_id},
          el("div", {class: "problem"},
            "This card could not be drawn — " + ((e && e.message) || e), " ",
            card.self ? el("a", {href: "#" + card.self}, "open the row ↗")
                      : null)));
      }
    }
  }

  /* the x-display hints, per kind, once: a card names its kind and the
     published schema names its labels. Fetched before a page paints so
     a field never flashes its raw wire name (kindSchema caches, so a
     second page pays only for kinds it has not met). */
  const feedHints = {};
  async function learnKinds(cards) {
    const kinds = [...new Set((cards || []).map(c => c && c.kind)
                                           .filter(Boolean))];
    await Promise.all(kinds.map(async k => {
      if (!(k in feedHints)) feedHints[k] = await kindSchema(k);
    }));
  }

  /* ── the terminal panel: a sentinel while pages remain, the honest
     end when none do — and the day's own refusal when a cursor has
     outlived its day ─────────────────────────────────────────────── */
  function paintEnd(problem) {
    endBox.textContent = "";
    if (problem) {
      endBox.append(problemBox(problem),
        el("div", {class: "actions"},
          el("button", {onclick: () => loadMore()}, "Try again"),
          el("button", {class: "primary", onclick: () => render()},
            "Read from the top")));
      return;
    }
    if (loading) return endBox.append(el("div", {class: "muted"},
      "reading further back…"));
    if (nextHref)
      return endBox.append(el("button",
        {onclick: () => loadMore()}, "Further back ↓"));
    endBox.append(el("div", {class: "feed-fin prose"},
      count ? "— that's the whole archive —"
            : "Nothing to read today. The house is quiet."));
  }

  async function loadMore() {
    if (!nextHref || loading) return;
    loading = true;
    paintEnd();
    let problem = null;
    try {
      const came = nextHref;
      const {ok, status, body} = await api(nextHref);
      if (!list.isConnected) return;
      if (!ok) {
        /* a cursor whose day has rolled is a 409 with a sentence
           saying so; re-reading from the top is the only answer and
           the button below says exactly that */
        if (status === 409) nextHref = null;
        problem = body || {};
      } else {
        await learnKinds(body.cards);
        if (!list.isConnected) return;
        appendCards(body.cards, came);
        nextHref = (body.links || {}).next?.href || null;
      }
    } finally { loading = false; }
    /* the tail is painted AFTER the flag drops — painting it while
       `loading` still stood would leave "reading further back…" on a
       page that had finished reading */
    paintEnd(problem);
    /* re-arm: a short page can leave the sentinel inside the margin
       with no new intersection to report */
    io.unobserve(endBox);
    io.observe(endBox);
  }

  /* one screenful of lookahead — 134-feed.js watches from 200% out
     because its sentinel is measured against a panel-sized scroller
     and one panel IS a screen; here the sentinel rides the page, so
     200% would swallow ten cards at a time and the archive would
     stop being something you walk. Below the margin the button
     above is the same door, tapped rather than scrolled into. */
  const io = new IntersectionObserver(entries => {
    if (entries.some(x => x.isIntersecting)) loadMore();
  }, {rootMargin: "100% 0px"});
  io.observe(endBox);

  await learnKinds(doc.cards);
  if (!list.isConnected) return;     // a newer render superseded us
  appendCards(doc.cards, doc.self || "/api/-/feed");
  paintEnd();
}

/* WHAT A CARD SHOWS BESIDE ITS SENTENCE, and what it deliberately
   does not. No field table: a saved view names a deck's card fields
   and the table has the query grammar's grid columns, but a feed card
   has neither — its kinds are mixed and nothing declared what three
   fields matter here, so any three this page picked would be three it
   GUESSED. What it does show is the one line the declaration already
   marked for exactly this: a prose field with :x-display {:teaser
   true}, which the projection site truncates and the collection table
   shows as its quiet second line. One rule, two surfaces. The rest of
   the row is a tap away on the row's own screen. */
function feedTeaserLines(card, hints) {
  const out = [];
  for (const [f, v] of Object.entries(card.fields || {})) {
    if (!v || typeof v !== "string") continue;
    if (xdisplay(hints, f).widget !== "prose") continue;
    out.push(el("div", {class: "fcard-teaser prose",
                        title: fieldLabel(hints, f)}, v));
  }
  return out.slice(0, 2);
}
