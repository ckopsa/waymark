/* ── the undo stack (waymark-qmo6, docs/spec-undo.md) ───────────────
   A person triages cards in RUNS — thumb, thumb, thumb — and the
   failure mode of a run is a chip touched by mistake. The engine's
   answer is a way back that lasts a few minutes; this is the client's
   half of it: the last few taps THIS PAGE made, each with the door
   that takes it back, in a small stack out of the way.

   IT HOLDS NO OPINION ABOUT THE LAW, and that is the whole design.
   The window, whose hand may undo, whether another row now stands in
   the way — every one of those is the engine's, and this page learns
   them the way it learns everything else: an inverse door that is in
   `actions` is offerable, one that has moved to `unavailable` is not.
   So the stack polls the rows it is holding and drops the entries
   whose door has gone. A second copy of the window here would be a
   second opinion about the law, and it would be wrong the first time
   the household changed the number.

   AN EXPIRY IS NOT NEWS; A REFUSAL IS. An entry the person never
   touched simply leaves when its door does. An entry they TAPPED and
   the house refused keeps its place and shows the wall's own sentence,
   because a refused undo is the most important thing on the screen: it
   is the house saying another row now stands in the way, and naming
   it.

   IN-PAGE MEMORY ONLY. A reload clears the stack, which is honest —
   it is a memory of what this hand just did, not a record of
   anything. Nothing here is stored and nothing is sent. */

const UNDO_DEPTH = 4;
const UNDO_POLL_MS = 20000;

let undoStack = [];
let undoPoll = null;

/* The door in a post-action document that would put the row back where
   it came from: an action whose landing IS the state we just left.
   Read off the envelope rather than declared here — the engine's
   `:undo` pointer is verified against the graph at declaration time and
   stripped, so what reaches the wire is the edge itself. */
function undoDoorFor(before, after) {
  const backTo = before.state;
  if (!backTo || !after || after.state === backTo) return null;
  const hit = Object.entries(after.actions || {})
    .find(([, a]) => a.effect?.to === backTo);
  return hit ? {name: hit[0], entry: hit[1]} : null;
}

/* One tap, remembered. Called for every action this page performs, from
   the one place they all funnel through (`maybeUndoToast`), so the card
   surface, the deck's swipe and the feed's chips are all covered
   without any of them knowing this exists. */
function recordUndoable(name, before, after) {
  const back = undoDoorFor(before, after);
  if (!back || !after.self) return;
  undoStack = undoStack.filter(e => e.self !== after.self);
  undoStack.unshift({self: after.self, did: name, door: back.name,
                     entry: back.entry, doc: after,
                     summary: after.summary || after.self, problem: null});
  undoStack = undoStack.slice(0, UNDO_DEPTH);
  renderUndoStack();
  startUndoPoll();
}

function forgetUndo(self) {
  undoStack = undoStack.filter(e => e.self !== self);
  renderUndoStack();
}

async function takeItBack(item) {
  const res = await invokeBare(item.entry, item.doc);
  if (res.ok) {
    forgetUndo(item.self);
    render();
    return;
  }
  /* the engine's own sentence, on the entry that asked for it — never a
     toast that scrolls away from the thing it is about (the feed card's
     own rule, one surface over) */
  item.problem = res.body || {};
  renderUndoStack();
}

function undoItemNode(item) {
  const node = el("div", {class: "undoitem"});
  node.append(el("div", {class: "undodid"},
                 el("b", {}, pretty(item.did)),
                 el("button", {class: "undodrop", type: "button",
                               "aria-label": "dismiss",
                               onclick: () => forgetUndo(item.self)}, "✕")));
  node.append(el("div", {class: "undowhat"},
                 el("a", {href: "#" + item.self}, item.summary)));
  if (item.problem) node.append(problemBox(item.problem));
  else node.append(el("button", {"data-undo": item.door,
                                 onclick: () => takeItBack(item)},
                      label(item.door, item.entry)));
  return node;
}

function renderUndoStack() {
  const box = $("#undostack");
  if (!box) return;
  box.textContent = "";
  if (!undoStack.length) {
    box.style.display = "none";
    stopUndoPoll();
    return;
  }
  box.append(el("div", {class: "undohead"}, "just now"));
  box.append(undoStack.map(undoItemNode));
  box.style.display = "flex";
}

/* THE STACK ASKS THE ENGINE, IT DOES NOT COUNT. Each held row is
   re-read; an entry whose way-back door is no longer among the row's
   `actions` has expired, or somebody else has written to the row since,
   or a wall now stands in the way — and this page does not need to know
   which, because in every one of those cases the honest thing to show
   is nothing. An entry showing a refusal is left alone: the person
   asked for it and is reading it. */
async function refreshUndoStack() {
  const held = undoStack.slice();
  for (const item of held) {
    if (item.problem) continue;
    const res = await api(item.self);
    if (!res.ok) { forgetUndo(item.self); continue; }
    const doc = res.body || {};
    if ((doc.actions || {})[item.door]) item.doc = doc;
    else forgetUndo(item.self);
  }
  renderUndoStack();
}

/* its own interval, deliberately NOT on liveTimers: those die with the
   screen that owns them, and the whole point of the stack is that it
   outlives the card you were looking at when you tapped */
function startUndoPoll() {
  if (undoPoll) return;
  undoPoll = setInterval(refreshUndoStack, UNDO_POLL_MS);
}
function stopUndoPoll() {
  if (!undoPoll) return;
  clearInterval(undoPoll);
  undoPoll = null;
}
