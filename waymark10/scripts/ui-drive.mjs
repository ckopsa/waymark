/* ui-drive.mjs — the phase-10 UI verification, reproducible:
   drive the generic UI (GET /api/-/ui) through the family-week story
   in headless chromium over CDP. Zero deps beyond node >= 22.

   1. make dev10 (a fresh mealplan10_dev; seed a blocking event if you
      want the finalize warning: see docs/waymark10-design.md §10)
   2. chromium --headless=new --remote-debugging-port=9223 \
        --no-sandbox --user-data-dir=/tmp/wm10-chrome about:blank &
   3. node waymark10/scripts/ui-drive.mjs

   The drive is self-normalizing: a re-run against mutated state
   brings the plan back to `planned` before its checks. */
const DEBUG_PORT = process.env.CDP_PORT || "9223";
const BASE = "http://localhost:8010";

const list = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json`)).json();
const page = list.find(t => t.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(res => ws.onopen = res);

let msgId = 0;
const pending = new Map();
const consoleErrors = [];
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  if (m.method === "Runtime.exceptionThrown")
    consoleErrors.push(JSON.stringify(m.params.exceptionDetails.exception?.description
                                      || m.params.exceptionDetails.text));
  if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error")
    consoleErrors.push(m.params.args.map(a => a.value || a.description).join(" "));
};
function send(method, params) {
  return new Promise(res => {
    const id = ++msgId;
    pending.set(id, res);
    ws.send(JSON.stringify({id, method, params: params || {}}));
  });
}
async function evaljs(expr) {
  const r = await send("Runtime.evaluate",
    {expression: expr, awaitPromise: true, returnByValue: true});
  if (r.result.exceptionDetails)
    throw new Error("eval failed: " + JSON.stringify(r.result.exceptionDetails));
  return r.result.result.value;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function waitFor(pred, what, ms = 6000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await evaljs(pred)) return true;
    await sleep(150);
  }
  throw new Error("timed out waiting for " + what);
}
let passed = 0;
function ok(name, cond) {
  if (!cond) throw new Error("FAILED: " + name);
  passed++;
  console.log("  ok " + name);
}

await send("Runtime.enable");
await send("Page.enable");

/* ── boot: principal + home ─────────────────────────────────────────── */
console.log("· home");
await send("Page.navigate", {url: BASE + "/api/-/ui"});
await sleep(1200);
await evaljs(`localStorage.setItem("wm10.principal", "priya"); location.reload(); true`);
await sleep(1200);
await waitFor(`document.querySelectorAll("nav a").length > 5`, "nav from well-known");
ok("nav lists app kinds from well-known",
   await evaljs(`[...document.querySelectorAll("nav a")].some(a => a.textContent === "Plans")`));
ok("home shows engine kinds group",
   await evaljs(`document.body.textContent.includes("Engine kinds")`));
ok("home lists the declared surface",
   await evaljs(`document.body.innerText.includes("week-board")`));

/* ── meals collection ───────────────────────────────────────────────── */
console.log("· meals collection");
await evaljs(`location.hash = "/api/meals"; true`);
await waitFor(`document.querySelectorAll("tbody tr").length >= 6`, "meal rows");
ok("collection rows carry state + summary",
   await evaljs(`document.body.innerText.includes("on_list") &&
                 document.body.innerText.includes("Carnitas tacos")`));
ok("filter bar built from the query grammar (state enum + sort)",
   await evaljs(`[...document.querySelectorAll("select option")].map(o => o.value)
                 .filter(v => ["suggested","on_list","retired","name","-name"].includes(v)).length >= 4`));

/* state filter drives a real filtered fetch */
await evaljs(`{ const sel = [...document.querySelectorAll("select")]
    .find(s => [...s.options].some(o => o.value === "retired"));
  sel.value = "retired"; sel.dispatchEvent(new Event("change")); true }`);
await waitFor(`decodeURIComponent(location.hash).includes("state=retired")`, "state filter in href");
await waitFor(`document.body.innerText.includes("filtered: state=retired")`, "filtered summary");
ok("state filter round-trips through the collection self href", true);

/* ── create a meal through the generated form ───────────────────────── */
console.log("· meal create dialog");
await evaljs(`location.hash = "/api/meals"; true`);
await waitFor(`[...document.querySelectorAll("button")].some(b => b.textContent.startsWith("New meal"))`, "create button");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.startsWith("New meal")).click(); true`);
await waitFor(`document.querySelector("dialog[open] input[name=name]")`, "create form");
ok("form generated from the create schema (name, themes, prose notes)",
   await evaljs(`!!document.querySelector("dialog[open] input[name=name]") &&
                 !!document.querySelector("dialog[open] input[name=themes]") &&
                 document.querySelector("dialog[open] textarea[name=notes]") !== null`));
ok("required fields are marked",
   await evaljs(`document.querySelector("dialog[open] .field label").innerText.includes("*")`));
await evaljs(`document.querySelector("dialog[open] input[name=name]").value = "Liver and onions";
  document.querySelector("dialog[open] input[name=themes]").value = "retro";
  [...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);
await waitFor(`document.body.innerText.includes("Liver and onions") &&
               decodeURIComponent(location.hash).match(/\\/api\\/meals\\/[0-9a-f-]+/)`,
              "navigated to the new meal");
ok("create landed and navigated to the new envelope", true);

/* ── the confirm gate: accept, then the confirm-gated retire ────────── */
console.log("· confirm gate");
await waitFor(`[...document.querySelectorAll("button")].some(b => b.textContent.includes("Add to meal list"))`,
              "meal action buttons");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.includes("Add to meal list")).click(); true`);
await waitFor(`document.querySelector("dialog[open]")`, "accept dialog");
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);
await waitFor(`document.querySelector(".statechip")?.textContent === "on_list"`, "on_list");
ok("a plain action invokes through its dialog (accept → on_list)", true);
await waitFor(`[...document.querySelectorAll("button")].some(b => b.textContent.includes("Retire"))`,
              "retire button");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.includes("Retire")).click(); true`);
await waitFor(`document.querySelector("dialog[open] .consequence")`, "consequence box");
ok("confirm dialog shows the declared consequence",
   await evaljs(`document.querySelector("dialog[open] .consequence").innerText
                 .includes("leaves the family list")`));
ok("the submit button is the explicit confirm",
   await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1)
                 .textContent.startsWith("Confirm &")`));
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);
await waitFor(`document.querySelector(".statechip") &&
               document.querySelector(".statechip").textContent === "retired"`, "retired");
ok("confirmed retire landed (state=retired)", true);

/* ── the plan: unavailable narration + reopen ───────────────────────── */
console.log("· plan envelope");
/* setup, not verification: whatever state a prior partial run left the
   plan in, bring it to planned before the checks */
const planHash = await evaljs(`(async () => {
  const h = {"x-waymark-principal": "priya", "Content-Type": "application/json"};
  const b = await (await fetch("/api/plans", {headers: h})).json();
  const item = b.data.items[0];
  if (item.state === "draft")
    await fetch(item.self + "/-/finalize", {method: "POST",
      headers: Object.assign({}, h, {"Waymark-Acknowledge": "calendar-clear"})});
  return item.self; })()`);
await evaljs(`location.hash = ${JSON.stringify(planHash)}; true`);
await waitFor(`document.querySelector(".statechip")?.textContent === "planned"`, "plan page");
ok("unavailable actions narrate with becomes_available",
   await evaljs(`document.body.textContent.includes("Begin") &&
                 document.body.textContent.includes("The plan starts 2026-07-14") &&
                 document.body.textContent.includes("available at 2026-07-14")`));
ok("unavailable renders as disabled buttons with tooltip reasons",
   await evaljs(`[...document.querySelectorAll("button:disabled")]
                 .some(b => b.title.includes("The plan starts"))`));
ok("days render as a nested table (dates + labels)",
   await evaljs(`document.body.innerText.includes("2026-07-18") &&
                 document.body.innerText.includes("Traeger brisket")`));

/* the surface chip earned by probe */
await waitFor(`[...document.querySelectorAll("a.chip")].some(a => a.textContent.includes("week-board"))`,
              "surface chip");
ok("the declared surface offers itself from its anchor", true);

/* reopen → draft */
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.trim() === "Reopen").click(); true`);
await waitFor(`document.querySelector("dialog[open]")`, "reopen dialog");
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);
await waitFor(`document.querySelector(".statechip")?.textContent === "draft"`, "draft again");
ok("reopen landed (state=draft)", true);

/* ── assign_meal: folded enum + ref picker + guard refusal ──────────── */
console.log("· assign_meal form");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.includes("Assign meal")).click(); true`);
await waitFor(`document.querySelector("dialog[open] select[name=date]")`, "assign form");
ok("the date field is the folded acceptance enum (7 plan days)",
   await evaljs(`[...document.querySelectorAll("dialog[open] select[name=date] option")]
                 .filter(o => o.value.startsWith("2026-07")).length === 7`));
await waitFor(`[...document.querySelectorAll("dialog[open] select[name=meal_id] option")].length > 3`,
              "ref options loaded");
ok("the meal_id ref field offers rows labeled by summary",
   await evaljs(`[...document.querySelectorAll("dialog[open] select[name=meal_id] option")]
                 .some(o => o.textContent.includes("Smash burgers"))`));
/* wrong theme → the guard's own sentence in the dialog */
await evaljs(`{ const d = document.querySelector("dialog[open]");
  d.querySelector("select[name=date]").value = "2026-07-15";
  const meals = d.querySelector("select[name=meal_id]");
  meals.value = [...meals.options].find(o => o.textContent.includes("Carnitas")).value;
  [...d.querySelectorAll(".dlgfoot button")].at(-1).click(); true }`);
await waitFor(`document.querySelector("dialog[open] .problem")`, "guard refusal in dialog");
ok("a guard refusal narrates in the form",
   await evaljs(`document.querySelector("dialog[open] .problem").innerText
                 .includes("theme night")`));
/* fix it: burgers on Tuesday the 15th? no — burgers are american = the 15th */
await evaljs(`{ const d = document.querySelector("dialog[open]");
  const meals = d.querySelector("select[name=meal_id]");
  meals.value = [...meals.options].find(o => o.textContent.includes("Smash burgers")).value;
  [...d.querySelectorAll(".dlgfoot button")].at(-1).click(); true }`);
await waitFor(`!document.querySelector("dialog[open]")`, "dialog closed");
ok("the corrected assign landed", true);

/* ── dry-run check button ───────────────────────────────────────────── */
console.log("· dry-run");
await waitFor(`[...document.querySelectorAll("button")].some(b => b.textContent.includes("Assign meal"))`, "plan page back");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.includes("Assign meal")).click(); true`);
await waitFor(`document.querySelector("dialog[open] select[name=date]")`, "assign form again");
await waitFor(`[...document.querySelectorAll("dialog[open] select[name=meal_id] option")]
               .some(o => o.textContent.includes("Carnitas"))`, "ref options again");
await evaljs(`{ const d = document.querySelector("dialog[open]");
  d.querySelector("select[name=date]").value = "2026-07-14";
  const meals = d.querySelector("select[name=meal_id]");
  meals.value = [...meals.options].find(o => o.textContent.includes("Carnitas")).value;
  [...d.querySelectorAll(".dlgfoot button")].find(b => b.textContent === "Check").click(); true }`);
await waitFor(`document.querySelector("dialog[open] .validok")`, "dry-run verdict");
ok("dry_run=1 pre-validates in the form",
   await evaljs(`document.querySelector("dialog[open] .validok").innerText.includes("accept")`));
await evaljs(`{ const d = document.querySelector("dialog[open]");
  [...d.querySelectorAll(".dlgfoot button")].find(b => b.textContent === "Cancel").click(); true }`);

/* ── finalize: the acknowledge dialog ───────────────────────────────── */
console.log("· finalize + acknowledge");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.includes("Finalize")).click(); true`);
await waitFor(`document.querySelector("dialog[open]")`, "finalize dialog");
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);
await waitFor(`document.querySelector("dialog[open] .warnbox")`, "warnings box");
ok("warning 409 surfaces every warning before anything is acknowledged",
   await evaljs(`document.querySelector("dialog[open] .warnbox").innerText
                 .includes("1 calendar conflict(s)")`));
await evaljs(`[...document.querySelectorAll("dialog[open] .warnbox button")]
  .find(b => b.textContent.includes("Acknowledge")).click(); true`);
await waitFor(`document.querySelector(".statechip")?.textContent === "planned"`, "planned");
ok("acknowledge-and-retry landed (state=planned)", true);

/* ── the surface screen ─────────────────────────────────────────────── */
console.log("· week-board surface");
await evaljs(`[...document.querySelectorAll("a.chip")]
  .find(a => a.textContent.includes("week-board")).click(); true`);
await waitFor(`document.body.innerText.includes("Week board") ||
               document.body.innerText.includes("Week-board")`, "surface screen");
ok("attention flag raised for the conflicted week",
   await evaljs(`[...document.querySelectorAll(".flag.up")]
                 .some(f => f.textContent.includes("has_conflicts"))`));
ok("the calendar member rides the declared edge",
   await evaljs(`document.body.innerText.includes("Piano recital")`));

/* ── drafts saved on blur (update_recipe on a meal) ─────────────────── */
console.log("· draft on blur");
const mealSelf = await evaljs(`fetch("/api/meals?state=on_list", {headers: {"x-waymark-principal": "priya"}})
  .then(r => r.json()).then(b => b.data.items[0].self)`);
await evaljs(`location.hash = ${JSON.stringify(mealSelf)}; true`);
await waitFor(`[...document.querySelectorAll("button")].some(b => b.textContent.includes("Update recipe") || b.textContent.includes("Update_recipe") || b.textContent.toLowerCase().includes("recipe"))`, "meal page");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.toLowerCase().includes("recipe")).click(); true`);
await waitFor(`document.querySelector("dialog[open] textarea[name=recipe]")`, "recipe form (prose widget)");
ok("x-display prose renders a textarea", true);
await evaljs(`{ const ta = document.querySelector("dialog[open] textarea[name=recipe]");
  ta.value = "# Draft recipe\\nhalf-written by the UI drive";
  ta.dispatchEvent(new Event("focusout", {bubbles: true})); true }`);
await waitFor(`document.querySelector("dialog[open] [data-draftnote]")?.textContent.includes("draft saved")`,
              "draft saved note");
ok("the draft saved on blur through the draft sub-resource", true);
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")]
  .find(b => b.textContent === "Cancel").click(); true`);
/* reopen: the half-written effort comes back from the server */
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.toLowerCase().includes("recipe")).click(); true`);
await waitFor(`document.querySelector("dialog[open] textarea[name=recipe]")`, "recipe form again");
ok("reopening prefills from the stored draft",
   await evaljs(`document.querySelector("dialog[open] textarea[name=recipe]").value
                 .includes("half-written")`));
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);
await waitFor(`!document.querySelector("dialog[open]")`, "recipe committed");
ok("the act consumed the draft", true);

/* ── SSE: a transition made elsewhere updates the screen ────────────── */
console.log("· live updates");
await evaljs(`location.hash = ${JSON.stringify(mealSelf)}; true`);
await waitFor(`document.querySelector(".statechip")`, "meal page again");
const before = await evaljs(`document.body.innerText.includes("# Draft recipe")`);
await fetch(BASE + mealSelf + "/-/retire",
            {method: "POST", headers: {"x-waymark-principal": "colton"}});
await waitFor(`document.querySelector(".statechip")?.textContent === "retired"`, "SSE refetch", 8000);
ok("the firehose refetched the open envelope after a foreign write", true);
ok("the ticker narrates the transition",
   await evaljs(`document.getElementById("ticker").innerText.includes("retire")`));

console.log(`\nUI drive: ${passed} checks passed` +
            (consoleErrors.length ? `\nCONSOLE ERRORS:\n` + consoleErrors.join("\n") : ", no console errors"));
ws.close();
process.exit(consoleErrors.length ? 1 : 0);
