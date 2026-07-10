/* ui-drive.mjs — the generic-UI verification, reproducible: drive
   GET /api/-/ui (the waymark9 client ported to wire 10; the original
   phase-10 page is preserved at /api/-/ui-lite) in headless chromium
   over CDP. Zero deps beyond node >= 22. Two drives share one
   harness:

   DEFAULT (the family-week story, against a mealplan10 dev engine):
   1. a FRESH dev database + seeded world per run — the story retires
      meals it later relies on, so re-runs need fresh bytes. The seed
      is the family-week test's, § by §: boot with the Piano recital
      on the FakeEvents feed and discovery run, then over the API as
      priya — rotation create+activate; eight meals (Carnitas tacos
      mexican / Elote corn mexican / Smash burgers american / Chicken
      stir fry asian / Traeger brisket bbq / Pancake supper breakfast
      for dinner / Sheet-pan pizza pizza / Spaghetti and meatballs
      italian), all accepted; a plan {start_date 2026-07-14, weeks 1};
      assign tacos→14 burgers→15 stir-fry→16 brisket→18 pancakes→19
      spaghetti→20; mark_eating_out 17 "Blaze Pizza". Boot shape:

        cd mealplan10 && MEALPLAN10_PORT=8011 \
          MEALPLAN10_DSN=jdbc:postgresql://localhost:5433/<fresh-db>?user=ckopsa \
          WAYMARK10_AUTO_MIGRATE=1 clojure -M -e "
          (require 'mealplan10.main 'mealplan10.event-source
                   'waymark10.server.mirror)
          (let [eng (mealplan10.main/start!)]
            (mealplan10.event-source/seed! mealplan10.main/events
              \"uid-recital@2026-07-16\"
              {:title \"Piano recital\" :date \"2026-07-16\"
               :kind \"blocking\"})
            (waymark10.server.mirror/discover! eng :event))
          @(promise)"

   2. chromium --headless=new --remote-debugging-port=9223 \
        --no-sandbox --user-data-dir=/tmp/wm10-chrome about:blank &
   3. BASE=http://localhost:8011 node waymark10/scripts/ui-drive.mjs

   BATCH-A (parts/links/validation/vocab/effort, against the
   FRAMEWORK fixtures — never mealplan10):
   1. WAYMARK10_TEST_DSN=... clojure -Sdeps \
        '{:aliases {:fx {:extra-paths ["test"]}}}' -M:fx -e \
        "(do ((requiring-resolve 'waymark10.batch-a-dev/start!) 8123) nil) @(promise)"
      (boot fresh per drive run — the drive seeds through the API;
       the (do … nil) keeps the REPL from printing the engine map,
       whose recursive registry overflows the printer)
   2. the same chromium
   3. node waymark10/scripts/ui-drive.mjs batch-a

   The story's plan checks stay self-normalizing (a partial re-run
   brings the plan back to planned before them), and the ported-page
   additions below seed uniquely-named rows per run — but the meal
   sections assume the fresh world of step 1. */
const MODE = process.argv[2] === "batch-a" ? "batch-a" : "story";
const DEBUG_PORT = process.env.CDP_PORT || "9223";
const BASE = process.env.BASE ||
  (MODE === "batch-a" ? "http://localhost:8123" : "http://localhost:8010");

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

async function mealplanStory() {
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
  ta.value = "# Draft recipe\\nhalf-written by the UI drive " + Date.now();
  ta.dispatchEvent(new Event("input", {bubbles: true}));
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

/* ════ ported-page additions: the waymark9 chrome on the 10 wire ═════ */
const H = {"x-waymark-principal": "colton", "Content-Type": "application/json"};

/* ── the activity ledger: the drawer renders the firehose ───────────── */
console.log("· activity ledger");
await evaljs(`document.getElementById("ledgertoggle").click(); true`);
await waitFor(`document.getElementById("ledger").classList.contains("open")`, "ledger open");
const feedMeal = await (await fetch(BASE + "/api/meals",
  {method: "POST", headers: H,
   body: JSON.stringify({name: "Feed check meal", themes: ["bbq"]})})).json();
await fetch(BASE + feedMeal.self + "/-/accept", {method: "POST", headers: H});
await waitFor(`[...document.querySelectorAll("#feed .ev")]
               .some(e => e.innerText.includes("accept") && e.innerText.includes("meal"))`,
              "accept transition in the feed", 8000);
ok("the activity feed renders firehose transitions (action · kind · from → to)",
   await evaljs(`[...document.querySelectorAll("#feed .ev")]
                 .some(e => e.innerText.includes("colton") &&
                            e.innerText.includes("suggested → on list"))`));
await evaljs(`document.getElementById("ledgerclose").click(); true`);

/* ── the undo affordance: an inverse action in the post-action doc ──── */
console.log("· undo affordance");
await evaljs(`location.hash = ${JSON.stringify(planHash)}; true`);
await waitFor(`[...document.querySelectorAll("button")].some(b => b.textContent.trim() === "Reopen")`,
              "plan page (planned)");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.trim() === "Reopen").click(); true`);
await waitFor(`document.querySelector("dialog[open]")`, "reopen dialog");
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);
await waitFor(`document.querySelector("#toast button[data-undo]")`, "undo button in the toast");
ok("a reverse action in the post-action envelope becomes the undo affordance",
   await evaljs(`document.querySelector("#toast button[data-undo]").textContent
                 .includes("undo") &&
                 document.querySelector("#toast").textContent.includes("reopen ✓")`));
await waitFor(`document.querySelector(".statechip")?.textContent === "draft"`, "plan draft again");
/* restore: the later runs expect a planned plan is not required — leave draft;
   the story is self-normalizing at its head */

/* ── bulk: the collection form of the act, honestly partial ─────────── */
console.log("· bulk partial report");
const runTag = String(Date.now());
await (await fetch(BASE + "/api/meals",
  {method: "POST", headers: H,
   body: JSON.stringify({name: `Bulk ok ${runTag}`, themes: ["bbq"]})})).json();
const bulkNo = await (await fetch(BASE + "/api/meals",
  {method: "POST", headers: H,
   body: JSON.stringify({name: `Bulk no ${runTag}`, themes: ["bbq"]})})).json();
await fetch(BASE + bulkNo.self + "/-/accept", {method: "POST", headers: H});
await fetch(BASE + bulkNo.self + "/-/retire", {method: "POST", headers: H});
await evaljs(`location.hash = "/api/meals?page[size]=100"; true`);
await waitFor(`document.body.innerText.includes(${JSON.stringify(runTag)})`,
              "bulk fixture rows on the collection");
await evaljs(`{ for (const tr of document.querySelectorAll("tbody tr")) {
    if (tr.innerText.includes(${JSON.stringify(runTag)})) {
      const box = tr.querySelector("[data-bulk-check]");
      if (box) { box.checked = true;
                 box.dispatchEvent(new Event("change", {bubbles: true})); }
    }
  } true }`);
await evaljs(`[...document.querySelectorAll("button")]
  .find(b => b.textContent.includes("Add selected to meal list")).click(); true`);
await waitFor(`document.querySelector("dialog[open] .consequence")`, "bulk confirm dialog");
ok("the bulk dialog states the selection and demands the confirm",
   await evaljs(`document.querySelector("dialog[open] .metaline").textContent
                   .includes("2 selected row(s)") &&
                 [...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1)
                   .textContent.startsWith("Confirm &")`));
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);
await waitFor(`document.querySelector("dialog[open][data-report]")`, "bulk report dialog");
ok("the bulk report is honestly partial (1 succeeded, 1 refused, with the reason)",
   await evaljs(`document.querySelector("dialog[open] .verdict-totals").textContent
                   .includes("succeeded 1 · refused 1") &&
                 document.querySelector("dialog[open] .verdict-refused") !== null &&
                 document.querySelector("dialog[open] td.reason").textContent.length > 0`));
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")].at(-1).click(); true`);

/* ── the draft chrome: relay/2 stale rejection, recovered ───────────── */
console.log("· draft stale-rejection recovery (relay/2)");
const staleMeal = await (await fetch(BASE + "/api/meals",
  {method: "POST", headers: H,
   body: JSON.stringify({name: "Stale draft meal", themes: ["bbq"]})})).json();
await fetch(BASE + staleMeal.self + "/-/accept", {method: "POST", headers: H});
await evaljs(`location.hash = ${JSON.stringify(staleMeal.self)}; true`);
await waitFor(`[...document.querySelectorAll("button")]
               .some(b => b.textContent.toLowerCase().includes("recipe"))`, "stale meal page");
await evaljs(`[...document.querySelectorAll("button")]
  .find(b => b.textContent.toLowerCase().includes("recipe")).click(); true`);
await waitFor(`document.querySelector("dialog[open] textarea[name=recipe]")`, "recipe form");
/* the relay must be LIVE before we type — the state frame's presence
   roster is its observable arrival (without the socket, saves fall
   back to the plain PUT, which has no revision discipline to reject) */
await waitFor(`(document.querySelector("dialog[open] [data-draftnote]")?.textContent || "")
               .includes("editing with")`, "relay/2 socket joined", 8000);
await evaljs(`{ const ta = document.querySelector("dialog[open] textarea[name=recipe]");
  ta.value = "v1 typed in this ui";
  ta.dispatchEvent(new Event("input", {bubbles: true}));
  ta.dispatchEvent(new Event("focusout", {bubbles: true})); true }`);
await waitFor(`document.querySelector("dialog[open] [data-draftnote]")?.textContent.includes("draft saved")`,
              "first save acked over the socket");
/* a second writer moves the field's revision underneath us (a plain
   draft PUT bumps revs and broadcasts nothing — relay/2's recorded
   convergence point is our next frame) */
await fetch(BASE + staleMeal.self + "/-/update_recipe/draft",
  {method: "PUT", headers: H,
   body: JSON.stringify({recipe: "the external truth"})});
await evaljs(`{ const ta = document.querySelector("dialog[open] textarea[name=recipe]");
  ta.value = "v2 typed against a stale rev";
  ta.dispatchEvent(new Event("input", {bubbles: true}));
  ta.dispatchEvent(new Event("focusout", {bubbles: true})); true }`);
await waitFor(`document.querySelector("dialog[open] [data-draftnote]")?.textContent.includes("edit overtaken")`,
              "stale frame recovered", 8000);
ok("a stale edit is rejected with the field's truth and the form recovers",
   await evaljs(`document.querySelector("dialog[open] textarea[name=recipe]").value
                 === "the external truth"`));
await evaljs(`[...document.querySelectorAll("dialog[open] .dlgfoot button")]
  .find(b => b.textContent === "Discard draft").click(); true`);
await waitFor(`!document.querySelector("dialog[open]")`, "draft discarded");
ok("discard closes the composition", true);

/* ── the preserved lite page still serves ───────────────────────────── */
ok("ui-lite (the preserved phase-10 page) serves beside the port",
   await evaljs(`fetch("/api/-/ui-lite").then(r => r.status === 200)`));
}

/* ════ batch A: parts, links, validation, vocab, effort ═══════════════
   Against the framework-fixture engine (waymark10.batch-a-dev) — the
   page knows nothing; every check reads what batch A put on the wire. */
async function batchAStory() {
  const h = {"x-waymark-principal": "priya", "Content-Type": "application/json"};
  const post = async (path, body) => {
    const res = await fetch(BASE + path,
      {method: "POST", headers: h, body: body ? JSON.stringify(body) : null});
    return {status: res.status, body: await res.json().catch(() => null)};
  };

  /* seed through the API, like any client */
  console.log("· seeding fixtures through the API");
  await post("/api/meals", {name: "Carnitas tacos", themes: ["mexican"]});
  await post("/api/meals", {name: "Smash burgers", themes: ["american"]});
  await post("/api/meals", {name: "Traeger brisket", themes: ["bbq", "american"]});
  const plan = await post("/api/plans", {start_date: "2026-07-14", weeks: 1,
    days: [{date: "2026-07-14", theme: "mexican"}, {date: "2026-07-15"}]});
  const proj = await post("/api/ba_projects", {name: "Kitchen"});
  const pid = proj.body.self.split("/").pop();
  await post("/api/ba_tickets", {title: "Sand the top", project_id: pid,
                                 due_date: "2026-07-20", points: 3});
  const t2 = await post("/api/ba_tickets", {title: "Oil the top", project_id: pid,
                                            due_date: "2026-07-20", points: 1});
  await post("/api/ba_days", {date: "2026-07-20", label: "Sanding day"});

  console.log("· boot + principal");
  await send("Page.navigate", {url: BASE + "/api/-/ui"});
  await sleep(1200);
  await evaljs(`localStorage.setItem("wm10.principal", "priya"); location.reload(); true`);
  await sleep(1200);

  /* ── parts: the day row gets its Assign meal button back ─────────── */
  console.log("· parts: per-item buttons with the pre-bound key");
  await evaljs(`location.hash = ${JSON.stringify(plan.body.self)}; true`);
  await waitFor(`document.querySelector('[data-part="days"]')`, "parts section");
  ok("the day rows carry their per-item Assign meal buttons",
     await evaljs(`[...document.querySelectorAll('[data-part="days"] tbody tr')].length === 2 &&
                   [...document.querySelectorAll('[data-part="days"] .partactions button')]
                     .filter(b => b.textContent.includes("Assign meal")).length === 2`));
  ok("the parts-covered array left the data table",
     await evaljs(`![...document.querySelectorAll(".kv td:first-child")]
                     .some(td => td.textContent === "days")`));
  await evaljs(`[...document.querySelectorAll('[data-part="days"] tr[data-part-key="2026-07-15"] button')]
                .find(b => b.textContent.includes("Assign meal")).click(); true`);
  await waitFor(`document.querySelector('dialog[open] [data-const="date"]')`, "bound key in form");
  ok("the part dialog binds the key const — shown, never re-picked",
     await evaljs(`document.querySelector('dialog[open] [data-const="date"]').textContent === "2026-07-15" &&
                   !document.querySelector("dialog[open] select[name=date]")`));

  /* ── blur dry-run: the server's field errors, inline ─────────────── */
  console.log("· blur dry-run 422 inline");
  await evaljs(`{ const d = document.querySelector("dialog[open]");
    d.querySelector("select[name=meal_id]")
      .dispatchEvent(new FocusEvent("focusout", {bubbles: true})); true }`);
  await waitFor(`(document.querySelector('dialog[open] [data-srverr="meal_id"]')?.textContent || "").length > 0`,
                "server field error inline");
  ok("a blur dry-run 422 renders the server's field error inline", true);

  await waitFor(`[...document.querySelectorAll("dialog[open] select[name=meal_id] option")].length > 2`,
                "meal ref options");
  await evaljs(`{ const d = document.querySelector("dialog[open]");
    const sel = d.querySelector("select[name=meal_id]");
    sel.value = [...sel.options].find(o => o.textContent.includes("Smash burgers")).value;
    [...d.querySelectorAll(".dlgfoot button")].at(-1).click(); true }`);
  await waitFor(`!document.querySelector("dialog[open]")`, "assign landed");
  ok("the part-item invoke landed with the pre-bound key",
     await evaljs(`fetch(${JSON.stringify(plan.body.self)},
                         {headers: {"x-waymark-principal": "priya"}})
                   .then(r => r.json())
                   .then(b => b.data.days[1].meal_id != null)`));

  /* ── keystroke validation from the JSON schema ────────────────────── */
  console.log("· keystroke validation");
  await evaljs(`location.hash = "/api/meals"; true`);
  await waitFor(`[...document.querySelectorAll("button")].some(b => b.textContent.startsWith("New meal"))`,
                "create button");
  await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.startsWith("New meal")).click(); true`);
  await waitFor(`document.querySelector("dialog[open] input[name=name]")`, "create form");
  await evaljs(`{ const inp = document.querySelector("dialog[open] input[name=name]");
    inp.value = "x".repeat(130); inp.dispatchEvent(new Event("input", {bubbles: true})); true }`);
  ok("a keystroke validation message appears from the schema",
     await evaljs(`document.querySelector('dialog[open] [data-err="name"]')
                   .textContent.includes("at most 120")`));
  await evaljs(`{ const inp = document.querySelector("dialog[open] input[name=name]");
    inp.value = ""; inp.dispatchEvent(new Event("input", {bubbles: true})); true }`);
  ok("a required field says so as you erase it",
     await evaljs(`document.querySelector('dialog[open] [data-err="name"]')
                   .textContent === "required"`));

  /* ── vocab combobox with facet counts ─────────────────────────────── */
  console.log("· vocab combobox");
  ok("the themes field is a combobox fed by the collection's x-facets",
     await evaljs(`(async () => {
       const inp = document.querySelector('dialog[open] input[data-vocab="themes"]');
       if (!inp) return false;
       const list = document.getElementById(inp.getAttribute("list"));
       for (let i = 0; i < 40 && !list.children.length; i++)
         await new Promise(r => setTimeout(r, 100));
       return [...list.children].some(o => o.value === "american" &&
                                           (o.label || "").includes("2"));
     })()`));
  await evaljs(`{ const d = document.querySelector("dialog[open]");
    d.querySelector("input[name=name]").value = "Elote night";
    const inp = d.querySelector('input[data-vocab="themes"]');
    const list = document.getElementById(inp.getAttribute("list"));
    inp.value = [...list.children].find(o => o.value === "mexican").value;
    inp.dispatchEvent(new Event("input", {bubbles: true}));
    [...d.querySelectorAll(".dlgfoot button")].at(-1).click(); true }`);
  await waitFor(`decodeURIComponent(location.hash).match(/\\/api\\/meals\\/[0-9a-f-]+/)`,
                "meal created");
  ok("the combobox pick submitted through the create", true);

  /* ── effort-aware emphasis ────────────────────────────────────────── */
  console.log("· effort emphasis");
  await waitFor(`document.querySelector('button[data-effort="assent"]')`, "effort-stamped buttons");
  ok("an assent action's button is prominent (one click, offered as one)",
     await evaljs(`[...document.querySelectorAll('button[data-effort="assent"]')]
                   .some(b => b.className.includes("primary"))`));

  /* ── links: navigation strip with badges ──────────────────────────── */
  console.log("· links navigation");
  await evaljs(`location.hash = ${JSON.stringify(t2.body.self)}; true`);
  await waitFor(`document.querySelector("[data-links]")`, "links strip");
  ok("the links strip renders the declared rels with badges",
     await evaljs(`[...document.querySelectorAll("[data-links] a.chip")].some(a =>
                     a.textContent.includes("Agenda") &&
                     a.querySelector(".badge")?.textContent === "1")`));
  await evaljs(`[...document.querySelectorAll("[data-links] a.chip")]
                .find(a => a.textContent.includes("Agenda")).click(); true`);
  await waitFor(`document.body.innerText.includes("filtered: date=2026-07-20")`,
                "agenda target collection");
  ok("a link navigates to the filtered target collection",
     await evaljs(`decodeURIComponent(location.hash).includes("/api/ba_days?date=2026-07-20") &&
                   document.body.innerText.includes("2026-07-20 · Scheduled")`));
}

if (MODE === "batch-a") await batchAStory();
else await mealplanStory();

console.log(`\nUI drive (${MODE}): ${passed} checks passed` +
            (consoleErrors.length ? `\nCONSOLE ERRORS:\n` + consoleErrors.join("\n") : ", no console errors"));
ws.close();
process.exit(consoleErrors.length ? 1 : 0);
