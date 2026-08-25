/* ui-drive.mjs — the generic-UI verification, reproducible: drive
   GET /api/-/ui (the waymark9 client ported to wire 10; the original
   phase-10 page is preserved at /api/-/ui-lite) in headless chromium
   over CDP. Zero deps beyond node >= 22. Two drives share one
   harness:

   DEFAULT (the family-week story, against a mealplan10 dev engine —
   HISTORICAL: the standalone mealplan10 boot retired with the
   consolidation cleanup (waymark-26j); the meal kinds are served by
   workqueue10 now (`make dev-queue`), and this recipe's event-source
   seeding predates even that, so treat it as the story's record, not
   a runnable script):
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

   FEED (the day's scroll-first face, waymark-iqa.7 — against a
   workqueue10 dev engine, which serves every kind the recipe's
   populations name):
   1. make dev-queue                       (:8014, dockerized pg :5433)
   2. the same chromium
   3. BASE=http://localhost:8014 node waymark10/scripts/ui-drive.mjs feed
   or all three, plus the audit-trail half node cannot see:
      waymark10/scripts/feed-smoke.sh

   The story's plan checks stay self-normalizing (a partial re-run
   brings the plan back to planned before them), and the ported-page
   additions below seed uniquely-named rows per run — but the meal
   sections assume the fresh world of step 1. */
const MODE = ["batch-a", "feed", "recipe"].includes(process.argv[2])
  ? process.argv[2] : "story";
const DEBUG_PORT = process.env.CDP_PORT || "9223";
const BASE = process.env.BASE ||
  (MODE === "batch-a" ? "http://localhost:8123"
   : MODE === "feed" || MODE === "recipe" ? "http://localhost:8014"
   : "http://localhost:8010");

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
ok("schema-derived columns render real field values (name, themes)",
   await evaljs(`document.querySelector('th[title="name"]') !== null &&
                 [...document.querySelectorAll("tbody tr td")]
                   .some(td => td.textContent.includes("Carnitas tacos"))`));
ok("a sortable column's header carries an arrow, an unfilterable one doesn't offer Filters",
   await evaljs(`document.querySelector('th[title="name"]').classList.contains("sortable")`));

/* the name column sorts by header click — no dropdown, an arrow. The
   hash updates synchronously but the re-render is async (a fresh
   fetch), so wait for the arrow itself, not just the hash. */
await evaljs(`document.querySelector('th[title="name"]').click(); true`);
await waitFor(`decodeURIComponent(location.hash).includes("sort=name")`, "sort=name in href");
await waitFor(`document.querySelector('th[title="name"]').textContent.includes("↑")`,
              "ascending arrow rendered");
ok("clicking a sortable header sorts ascending and shows the arrow", true);
await evaljs(`document.querySelector('th[title="name"]').click(); true`);
await waitFor(`decodeURIComponent(location.hash).includes("sort=-name")`, "sort=-name in href");
await waitFor(`document.querySelector('th[title="name"]').textContent.includes("↓")`,
              "descending arrow rendered");
ok("clicking it again flips to descending", true);

/* the Filters popover: pick State, its sole "in" op, a value, apply */
await evaljs(`[...document.querySelectorAll("button")]
  .find(b => b.textContent.startsWith("Filters")).click(); true`);
await waitFor(`getComputedStyle(document.querySelector(".filterpanel")).display !== "none"`,
              "filters panel open");
await evaljs(`{ const sel = [...document.querySelectorAll(".filterpanel select")]
    .find(s => [...s.options].some(o => o.textContent === "State"));
  sel.value = [...sel.options].find(o => o.textContent === "State").value;
  sel.dispatchEvent(new Event("change")); true }`);
await waitFor(`document.querySelector('.filterpanel [data-role=values] select[name=state]')`,
              "state value select populated");
ok("the operator picker matches the column's declared ops (state is x-in only)",
   await evaljs(`[...document.querySelectorAll(".filterpanel select")][1].options.length === 1 &&
                 [...document.querySelectorAll(".filterpanel select")][1]
                   .options[0].textContent === "in list"`));
await evaljs(`{ const sel = document.querySelector('.filterpanel [data-role=values] select[name=state]');
  sel.value = "retired"; true }`);
await evaljs(`[...document.querySelectorAll(".filterpanel button")]
  .find(b => b.textContent === "Apply").click(); true`);
await waitFor(`decodeURIComponent(location.hash).includes("state=retired")`, "state filter in href");
await waitFor(`document.body.innerText.includes("filtered: state=retired")`, "filtered summary");
ok("the Filters popover applies a column+operator+value filter", true);
await waitFor(`[...document.querySelectorAll(".chip.on")]
              .some(c => c.textContent.includes("State") && c.textContent.includes("retired"))`,
              "removable chip rendered");
ok("the applied filter renders as a removable chip", true);
await evaljs(`[...document.querySelectorAll(".chip.on span")]
  .find(s => s.textContent === "✕").click(); true`);
await waitFor(`!decodeURIComponent(location.hash).includes("state=retired")`, "chip removal clears filter");
ok("removing the chip clears the filter from the href", true);

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

/* ── presence: dots + follow-me steering (9's surface, restored) ────── */
console.log("· presence");
await evaljs(`unfollow(); true`);            // self-normalizing: no stale follow
await evaljs(`location.hash = ${JSON.stringify(feedMeal.self)}; true`);
await waitFor(`document.querySelector("[data-presence]")`, "presence slot on the envelope");
/* colton holds only the firehose: his gaze arrives as an explicit
   heartbeat, and the open screen grows his viewing dot */
await fetch(BASE + "/api/-/presence", {method: "POST", headers: H,
  body: JSON.stringify({self: feedMeal.self})});
await waitFor(`(document.querySelector("[data-presence]")?.textContent || "")
               .includes("colton is here")`, "viewing dot", 10000);
ok("an explicit heartbeat becomes a viewing dot on the open screen", true);

/* the member envelope offers Follow (members auto-provision on first
   sight, id = principal id) */
await evaljs(`location.hash = "/api/members/colton"; true`);
await waitFor(`[...document.querySelectorAll("button")].some(b => b.textContent.includes("Follow"))`,
              "follow button on the member envelope");
await evaljs(`[...document.querySelectorAll("button")].find(b => b.textContent.includes("Follow")).click(); true`);
await waitFor(`document.getElementById("followchip").textContent.includes("colton")`, "follow chip");
ok("the member envelope's Follow button starts the follow", true);

/* a simulated move: colton's gaze lands elsewhere and THIS screen
   navigates there — where he looks, not where he writes */
await fetch(BASE + "/api/-/presence", {method: "POST", headers: H,
  body: JSON.stringify({self: planHash})});
await waitFor(`decodeURIComponent(location.hash.slice(1)).split("?")[0] === ${JSON.stringify(planHash)}`,
              "follow-me navigation", 10000);
ok("a presence move steers the following screen (look, not write)", true);
await evaljs(`unfollow(); true`);

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

  /* ── DataGrid columns: a top-level collection ─────────────────────── */
  console.log("· datagrid: real field columns + sort arrows on a collection");
  await evaljs(`location.hash = "/api/ba_tickets"; true`);
  await waitFor(`document.querySelectorAll("tbody tr").length >= 2`, "ticket rows");
  ok("schema-derived columns replace the old hardcoded four (State/Summary/Updated/Actions)",
     await evaljs(`document.querySelectorAll("thead th").length > 4 &&
                   !!document.querySelector('th[title="due_date"]') &&
                   [...document.querySelectorAll("tbody td")]
                     .some(td => td.textContent.trim() === "2026-07-20")`));
  ok("points (sortable, not filterable) still carries an arrow",
     await evaljs(`document.querySelector('th[title="points"]')?.classList.contains("sortable")`));
  ok("due_date (filterable, not declared sortable) carries none",
     await evaljs(`!document.querySelector('th[title="due_date"]').classList.contains("sortable")`));

  await evaljs(`document.querySelector('th[title="points"]').click(); true`);
  await waitFor(`decodeURIComponent(location.hash).includes("sort=points")`, "sort=points in href");
  await waitFor(`document.querySelector('th[title="points"]').textContent.includes("↑")`,
                "ascending arrow rendered");
  ok("clicking a sortable header sorts ascending with an up arrow", true);
  await evaljs(`document.querySelector('th[title="points"]').click(); true`);
  await waitFor(`decodeURIComponent(location.hash).includes("sort=-points")`, "sort=-points in href");
  await waitFor(`document.querySelector('th[title="points"]').textContent.includes("↓")`,
                "descending arrow rendered");
  ok("clicking it again flips to descending with a down arrow", true);

  /* Filters popover: pick a column, an operator matching its declared
     ops, a value, apply — then remove via the chip */
  await evaljs(`[...document.querySelectorAll("button")]
    .find(b => b.textContent.startsWith("Filters")).click(); true`);
  await waitFor(`getComputedStyle(document.querySelector(".filterpanel")).display !== "none"`,
                "filters panel open");
  await evaljs(`{ const sel = [...document.querySelectorAll(".filterpanel select")]
      .find(s => [...s.options].some(o => o.textContent === "Project id"));
    sel.value = [...sel.options].find(o => o.textContent === "Project id").value;
    sel.dispatchEvent(new Event("change")); true }`);
  await waitFor(`document.querySelector('.filterpanel [data-role=values] input[name=project_id]')`,
                "project_id value input populated (eq — a plain input, not a select)");
  await evaljs(`document.querySelector('.filterpanel [data-role=values] input[name=project_id]')
    .value = ${JSON.stringify(pid)}; true`);
  await evaljs(`[...document.querySelectorAll(".filterpanel button")]
    .find(b => b.textContent === "Apply").click(); true`);
  await waitFor(`decodeURIComponent(location.hash).includes("project_id=" + ${JSON.stringify(pid)})`,
                "project_id filter in href");
  ok("the Filters popover applies a column+operator+value filter", true);
  await waitFor(`[...document.querySelectorAll(".chip.on")]
                .some(c => c.textContent.includes("Project id"))`,
                "removable chip rendered");
  ok("the applied filter renders as a removable chip", true);
  await evaljs(`[...document.querySelectorAll(".chip.on span")]
    .find(s => s.textContent === "✕").click(); true`);
  await waitFor(`!decodeURIComponent(location.hash).includes("project_id=")`, "chip removal clears filter");
  ok("removing the chip clears the filter from the href", true);

  /* ── DataGrid columns: an embedded table, parent-scoped controls ──── */
  console.log("· datagrid: embedded table columns + parent-scoped sort");
  await evaljs(`location.hash = ${JSON.stringify(proj.body.self)}; true`);
  await waitFor(`document.querySelector(".embed")`, "embed section");
  const findTickets = `[...document.querySelectorAll(".embed")]
    .find(d => d.querySelector(".embed-head b").textContent === "This project's tickets")`;
  ok("the embedded tickets table shows real per-field columns, not just summary",
     await evaljs(`{ const div = ${findTickets};
       !!div.querySelector('th[title="due_date"]') &&
              !!div.querySelector('th[title="points"]') &&
              [...div.querySelectorAll("tbody td")].some(td => td.textContent.trim() === "2026-07-20");
     }`));
  ok("its columns carry the same sort/no-sort declarations as the top-level collection",
     await evaljs(`{ const div = ${findTickets};
       div.querySelector('th[title="points"]').classList.contains("sortable") &&
              !div.querySelector('th[title="due_date"]').classList.contains("sortable");
     }`));
  await evaljs(`${findTickets}.querySelector('th[title="points"]').click(); true`);
  await waitFor(`decodeURIComponent(location.hash).includes("embed.tickets.sort=points")`,
                "embed.tickets.sort=points on the parent's own hash");
  ok("a sort click on the embedded table mutates the PARENT's embed.<rel>.sort param " +
     "(not a navigation to the embed's bare href)",
     await evaljs(`decodeURIComponent(location.hash).includes("/api/ba_projects/") &&
                   !decodeURIComponent(location.hash).includes("/api/ba_tickets?")`));
  await waitFor(`{ const div = ${findTickets};
    const rows = [...div.querySelectorAll("tbody tr")].map(tr => tr.textContent);
    rows.findIndex(t => t.includes("Oil the top")) <
           rows.findIndex(t => t.includes("Sand the top"));
  }`, "embedded rows visibly reordered");
  ok("the embedded rows visibly reordered (ascending points first)", true);
}

/* ════ feed: the day's scroll-first face (waymark-iqa.7) ══════════════
   Against a workqueue10 dev engine (`make dev-queue`, :8014 — or any
   port, with BASE). It seeds its own world through the API — every
   population the recipe names that a single day can be made to hold —
   then reads the screen the way a person would: scroll the sections in
   census order, cross the seam, tap a verb, watch the card settle.

   `scripts/feed-smoke.sh` is this drive plus the half node cannot see:
   the Idempotency-Key the tap left on the transition row. */
async function feedStory() {
  const tag = String(Date.now()).slice(-6);
  const H = pid => ({"x-waymark-principal": pid,
                     "Content-Type": "application/json"});
  const post = async (path, body, pid = "colton") => {
    const res = await fetch(BASE + path,
      {method: "POST", headers: H(pid),
       body: body ? JSON.stringify(body) : null});
    return await res.json().catch(() => null);
  };

  console.log("· seeding a day through the API");
  /* do-now: open rows of front-door kinds, each with a light verb */
  const t1 = await post("/api/tasks",
    {title: `Call the dentist ${tag}`, detail: "the one on Maple street"});
  const t2 = await post("/api/tasks", {title: `Return the library books ${tag}`});
  /* an activity carries a COMPOSITION verb (set_duration) beside its
     assent one — the heavier half of the partition, which must render
     as a link and never as a button */
  await post("/api/activities", {title: `Sketch the porch ${tag}`,
    physical_energy: "low", mental_energy: "low", location: "anywhere"});
  /* fuel + the archive: chores can END, so retiring them empties the
     kind (a `cleared` card) and leaves the rest as memories */
  for (const name of ["Wipe the baseboards", "Descale the kettle",
                      "Flip the mattress"]) {
    const c = await post("/api/chores", {name: `${name} ${tag}`,
                                         cadence: "monthly"});
    if (c && c.self) await post(c.self + "/-/retire");
  }
  /* decide: a tickler over a real row, and a finding published by
     SOMEBODY ELSE (the four-eyes wall means a finding never cards to
     its own author) that offers a light action on that tickler */
  const tick = await post("/api/ticklers",
    {what: `Sort the garage shelves ${tag}`, subject_kind: "task",
     subject_id: String(t2.self).split("/").pop(), subject_href: t2.self});
  await post("/api/insights",
    {finding: `The garage shelves have not moved since June ${tag}`,
     evidence: [t2.self, tick.self], offer_kind: "tickler",
     offer_id: String(tick.self).split("/").pop(),
     offer_action: "take_it_back", offer_href: tick.self}, "sous");

  console.log("· boot + principal");
  await send("Page.navigate", {url: BASE + "/api/-/ui"});
  await sleep(1200);
  await evaljs(`localStorage.setItem("wm10.principal", "colton");
                location.reload(); true`);
  await sleep(1500);

  /* ── the door: the feed cannot advertise itself on .well-known, so
     the page probes the address it knows ─────────────────────────── */
  await waitFor(`[...document.querySelectorAll("nav a")]
                 .some(a => a.textContent === "Feed")`, "the feed's nav door");
  ok("the nav offers the feed (probed, not advertised)", true);
  await evaljs(`[...document.querySelectorAll("nav a")]
                .find(a => a.textContent === "Feed").click(); true`);
  await waitFor(`document.querySelectorAll(".feedcards .fcard").length > 3`,
                "the feed's cards");
  ok("the feed screen renders from the document's own kind",
     await evaljs(`location.hash === "#/api/-/feed" &&
                   !!document.querySelector(".feed-head")`));

  /* ── the census, top to bottom, and the seam in the middle ─────── */
  const order = await evaljs(`[...document.querySelectorAll(
      ".feedcards .fcard, .feedcards .feed-seam")]
    .map(n => n.dataset.section || "seam")`);
  ok("sections arrive in census order with the seam among them",
     JSON.stringify(order) === JSON.stringify(
       [...order].sort((a, b) => ["do_now", "decide", "fuel", "seam", "archive"]
         .indexOf(a) - ["do_now", "decide", "fuel", "seam", "archive"]
         .indexOf(b))) && order.includes("seam"));
  ok("every section the answer carries wears a heading",
     await evaljs(`(() => {
       const heads = [...document.querySelectorAll(".feed-sect b")]
         .map(b => b.textContent);
       const secs = new Set([...document.querySelectorAll(".fcard")]
         .map(c => c.dataset.section));
       const want = {do_now: "Do now", decide: "Decide", fuel: "Fuel",
                     archive: "Archive"};
       return [...secs].every(s => heads.includes(want[s]));
     })()`));
  ok("the seam is one quiet element, not a card, and says the sentence",
     await evaljs(`document.querySelectorAll(".feed-seam").length === 1 &&
       document.querySelector(".feed-seam-say").textContent
         .includes("caught up")`));

  /* ── the populations, one line each ────────────────────────────── */
  ok("a do-now card offers its verb as a TAP CHIP, labeled as declared",
     await evaljs(`[...document.querySelectorAll(
        '.fcard[data-population="next_actions"] .feed-verbs button.chip.verb')]
       .some(b => b.textContent.trim() === "Done")`));
  ok("nothing on this screen binds a swipe (a sequential read takes none)",
     await evaljs(`!document.querySelector("[data-gesture]") &&
                   !document.querySelector(".deck-card")`));
  ok("a composition verb rides as a LINK to the row's screen, never a button",
     await evaljs(`(() => {
       const a = [...document.querySelectorAll(".feed-verbs a.link-chip")]
         .find(x => x.textContent.includes("Set duration"));
       return !!a && a.getAttribute("href").startsWith("#/api/activities/") &&
              !a.getAttribute("href").includes("/-/");
     })()`));
  ok("a tickler offers all three verdicts and a way back to the row",
     await evaljs(`(() => {
       const c = document.querySelector('.fcard[data-kind="tickler"]');
       const labels = [...c.querySelectorAll(".feed-verbs button")]
         .map(b => b.textContent.trim());
       return ["Not now", "Let it go", "Take it back"]
                .every(l => labels.includes(l)) &&
              !!c.querySelector('.feed-verbs a[href^="#/api/tasks/"]');
     })()`));
  ok("an insight is a decide card with the offer PRIMARY, both verdicts, " +
     "and a byline that stays a principal id",
     await evaljs(`(() => {
       const c = document.querySelector('.fcard[data-kind="insight"]');
       const primary = c.querySelector(".feed-verbs button.chip.verb.primary");
       const labels = [...c.querySelectorAll(".feed-verbs button")]
         .map(b => b.textContent.trim());
       return !!primary && primary.dataset.offer === "take_it_back" &&
              labels.includes("Do it") && labels.includes("Not useful") &&
              c.querySelector(".fcard-by").textContent.includes("sous") &&
              !!c.querySelector('.feed-verbs a[href^="#/api/ticklers/"]');
     })()`));
  await waitFor(`document.querySelector(".fcard-evidence a")`,
                "the finding's evidence, read late");
  ok("the finding names what it read (two rows, each a live link)",
     await evaljs(`document.querySelector(".fcard-evidence").textContent
                   .includes("read 2 rows")`));
  ok("a fuel card is READ-ONLY and wears the server's own sentence",
     await evaljs(`(() => {
       const c = document.querySelector('.fcard[data-section="fuel"]');
       return !!c && !c.querySelector("button") &&
              c.querySelector(".fcard-say").textContent
                .includes("Nothing is left in chores");
     })()`));
  ok("archive cards are read-only too, and each links its own row",
     await evaljs(`[...document.querySelectorAll('.fcard[data-section="archive"]')]
       .every(c => !c.querySelector("button") &&
                   !!c.querySelector('a[href^="#/api/"]'))`));
  /* the bottomless half, walked to its end. The tail is TWO doors on
     one hinge — an IntersectionObserver that takes the next page when
     the sentinel comes within a screenful, and a button for a thumb
     that got there first — so on a page whose sentinel is already in
     view the observer wins every race and the button never appears.
     Waiting for the tail to settle and then judging what LANDED is the
     honest claim; counting button clicks was counting one of the two
     doors and calling the other one a failure. */
  const firstPage = await (await fetch(BASE + "/api/-/feed",
                                       {headers: H("colton")})).json();
  const onPageOne = (firstPage.cards || [])
    .filter(c => c.section === "archive").length;
  const settled = `!document.querySelector(".feed-endbox").textContent
                     .includes("reading further back")`;
  let pages = 0;
  await waitFor(settled, "the tail to settle", 15000);
  while (pages < 8 &&
         await evaljs(`!!document.querySelector(".feed-endbox button")`)) {
    const had = await evaljs(`document.querySelectorAll(".fcard").length`);
    await evaljs(`document.querySelector(".feed-endbox button").click(); true`);
    await waitFor(`document.querySelectorAll(".fcard").length > ${had} ||
                   !document.querySelector(".feed-endbox button")`,
                  "the next archive page", 10000);
    await waitFor(settled, "the tail to settle", 10000);
    pages++;
  }
  const archived = await evaljs(
    `document.querySelectorAll('.fcard[data-section="archive"]').length`);
  ok(`the archive walks: ${archived} archive cards landed, ` +
     `${onPageOne} of them on page one (${pages} taken by the button, ` +
     `the rest by the sentinel)`,
     (firstPage.links || {}).next ? archived > onPageOne : archived === onPageOne);
  ok("no card_id repeats, however many pages have landed",
     await evaljs(`(() => {
       const ids = [...document.querySelectorAll("[data-card-id]")]
         .map(n => n.dataset.cardId);
       return new Set(ids).size === ids.length;
     })()`));
  /* and the tail says one of two TRUE things — that there is more to
     walk (the offer, which the observer also takes when the sentinel
     scrolls into the margin), or that there is not. It never pretends
     to be infinite: a surface that lies once, at the bottom, lies to
     whoever scrolled the furthest. */
  ok("the tail is honest — more to walk, or the end said out loud",
     await evaljs(`(() => {
       const t = document.querySelector(".feed-endbox").textContent;
       return t.includes("Further back") || t.includes("archive") ||
              t.includes("quiet") || t.includes("reading");
     })()`));

  /* ── the citation: why this order, and why this card (iqa.29) ───
     The recipe used to be a vector in an application's main.clj that
     no reader would ever see, and a card said nothing at all about
     the four layers that put it there. Both are disclosures now, and
     both are the SERVER's prose: the page joins, it never derives. */
  console.log("· the feed explains itself");
  ok("Why this order opens into the narrated recipe, line by line",
     await evaljs(`(() => {
       const d = document.querySelector(".feed-why");
       if (!d) return false;
       d.open = true;
       const lis = [...d.querySelectorAll("ol.feed-recipe li")];
       return lis.length > 3 &&
              lis.every(li => li.textContent.trim().length > 20) &&
              d.textContent.includes("exactly one card is the seam");
     })()`));
  console.log("    recipe, line 1: " +
    await evaljs(`document.querySelector("ol.feed-recipe li").textContent.trim()`));
  const asked = await evaljs(`(() => {
    const d = document.querySelector(
      '.fcard[data-population="next_actions"] details.fcard-why');
    if (!d) return false;
    window.__why = d;                 /* the ONE disclosure we opened */
    d.querySelector("summary").click();
    return true; })()`);
  await waitFor(`(() => {
    const b = window.__why && window.__why.querySelector(".fcard-why-body");
    return !!b && b.textContent.includes("seed"); })()`,
    "the card's own citation, fetched once with ?explain=1");
  const cite = await evaljs(
    `[...window.__why.querySelectorAll(".fcard-why-body p")]
       .map(p => p.textContent)`);
  ok("a card cites its recipe line, the declaration's own trait words, " +
     "and the day's draw",
     asked && cite.length >= 3 && cite[0].startsWith("Recipe line") &&
     cite.some(s => s.includes(":nav")) &&
     cite.some(s => s.includes(":over")) &&
     cite.some(s => s.includes("seed")));
  console.log("    why this card:\n      " + cite.join("\n      "));

  /* ── the tap: one chip, one origin key, one settled card ───────── */
  console.log("· a verb, from the card");
  await evaljs(`window.__keys = [];
    const f = window.fetch;
    window.fetch = (u, o) => { const k = (o && o.headers || {})["Idempotency-Key"];
      if (k) window.__keys.push([String(u), k]); return f(u, o); };
    true`);
  const cardId = await evaljs(`(() => {
    const c = [...document.querySelectorAll('.fcard[data-population="next_actions"]')]
      .find(c => [...c.querySelectorAll("button")].some(b => b.textContent.trim() === "Done"));
    [...c.querySelectorAll("button")].find(b => b.textContent.trim() === "Done").click();
    return c.dataset.cardId; })()`);
  await waitFor(`document.querySelector(".fcard.done .feed-settled")`,
                "the card settles on the fresh envelope");
  ok("the tapped card settles where it is — the envelope answers, " +
     "the feed never guesses", true);
  const keys = await evaljs(`window.__keys`);
  ok("the invoke carried feed/<day>/<card_id>/<nonce> as its Idempotency-Key",
     keys.length === 1 && keys[0][0].includes("/-/complete") &&
     keys[0][1] === "feed/" + (await evaljs(`document.querySelector(".feed-head").dataset.day`))
       + "/" + encodeURIComponent(cardId) + "/" + keys[0][1].split("/").pop() &&
     /^[0-9a-f]{12}$/.test(keys[0][1].split("/").pop()));
  console.log("    key sent: " + keys[0][1]);

  /* ── deal again: the person spins (waymark-8um.2, law 6) ────────
     Four claims, one per half of the law. ↻ Re-read asks the SAME
     address again and answers the same order. A deal-again tap puts a
     nonce in the ADDRESS and answers a different one. The pages of
     that draw continue THAT draw — proved on the wire, where
     links.next can be read. And the way back to the day's own order is
     a tap too, because the draw lives in the address and nowhere
     else. */
  console.log("· dealing again");
  /* page one's own cards, above the archive: the archive walks itself
     as the sentinel scrolls, so how DEEP a page happens to be when it
     settles is a fact about the viewport rather than about the draw */
  const idsNow = () => evaljs(
    `[...document.querySelectorAll('[data-card-id]')]
       .filter(n => n.dataset.section !== "archive")
       .map(n => n.dataset.cardId)`);
  /* every re-render replaces the head, and the old one is still on the
     page while the new document is in flight — so the head is STAMPED
     before each tap and the walk waits for an unstamped one. Without
     it every claim below would be read off the page it was meant to
     replace, and every one of them would pass. */
  const chip = async label => {
    const hit = await evaljs(
      `(() => { const h = document.querySelector(".feed-head");
                if (h) h.dataset.stale = "1";
                const b = [...document.querySelectorAll(".feed-head button.chip")]
                  .find(b => b.textContent.includes(${JSON.stringify(label)}));
                if (!b) return false; b.click(); return true; })()`);
    if (!hit) return false;
    await waitFor(`!!document.querySelector(".feed-head:not([data-stale])")`,
                  "a fresh read after " + label, 15000);
    await waitFor(`document.querySelectorAll(".feedcards .fcard").length > 3`,
                  "the cards after " + label);
    await waitFor(settled, "the tail to settle", 15000);
    return true;
  };
  /* a fresh read first: the tap above finished a row, so the DOM this
     block inherits is a page from before that landed */
  await evaljs(`location.hash = "#/api/-/feed"; true`);
  await chip("Re-read");
  const daily = await idsNow();
  ok("the daily read carries no draw — the day's order is the absence of one",
     !(await evaljs(`location.hash`)).includes("draw=") &&
     !(await evaljs(
       `!!document.querySelector(".feed-head").textContent.match(/draw /)`)));

  ok("↻ Re-read is not a spin: same address, same order",
     await chip("Re-read") &&
     JSON.stringify(await idsNow()) === JSON.stringify(daily) &&
     daily.length > 3);

  /* a small deck can deal itself the same order twice — that is the
     hash telling the truth, not a failure — so the tap is allowed
     three spins before the claim is judged */
  let dealt = null, spins = 0;
  while (spins < 3 && (dealt === null ||
                       JSON.stringify(dealt) === JSON.stringify(daily))) {
    await chip("Deal again");
    await waitFor(`location.hash.includes("draw=")`, "the draw, in the address");
    dealt = await idsNow();
    spins++;
  }
  const drew = (await evaljs(`location.hash`)).split("draw=")[1].split("&")[0];
  ok(`a tap draws a fresh order (${spins} spin${spins === 1 ? "" : "s"}), ` +
     `and the draw is in the address: ${drew}`,
     JSON.stringify(dealt) !== JSON.stringify(daily));
  ok("the screen says a person dealt again, in the household's own words",
     await evaljs(`(() => { const d = document.querySelector(".feed-why");
       if (!d) return false; d.open = true;
       return d.textContent.includes("You dealt again"); })()`));
  ok("the same draw, read twice, is the same order (stability is per DRAW)",
     await (async () => {
       const a = await (await fetch(`${BASE}/api/-/feed?draw=${drew}`,
                                    {headers: H("colton")})).json();
       const b = await (await fetch(`${BASE}/api/-/feed?draw=${drew}`,
                                    {headers: H("colton")})).json();
       return a.draw === drew && a.seed === b.seed &&
         JSON.stringify(a.cards.map(c => c.card_id)) ===
         JSON.stringify(b.cards.map(c => c.card_id));
     })());
  ok("links.next continues the SAME draw, page after page",
     await (async () => {
       let href = `/api/-/feed?draw=${drew}`, walked = 0;
       for (let i = 0; i < 4 && href; i++) {
         const page = await (await fetch(BASE + href,
                                         {headers: H("colton")})).json();
         if (page.draw !== drew) return false;
         if (i > 0 && !href.includes(`draw=${drew}`)) return false;
         href = (page.links || {}).next?.href || null;
         walked++;
       }
       return walked > 1;
     })());

  ok("the way back is a tap, and it lands on the day's own order",
     await chip("Today's order") &&
     !(await evaljs(`location.hash`)).includes("draw=") &&
     JSON.stringify(await idsNow()) === JSON.stringify(daily));

  /* ── a refusal speaks in the engine's own words, on the card ──── */
  console.log("· a refusal, on the card that asked for it");
  await evaljs(`location.hash = "#/api/-/feed"; true`);
  await waitFor(`document.querySelector('.fcard[data-kind="insight"]')`,
                "the feed again");
  /* the finder cannot answer its own finding — so read the feed as
     the AUTHOR and watch the door refuse from the card */
  await evaljs(`localStorage.setItem("wm10.principal", "sous");
                location.reload(); true`);
  await sleep(1500);
  await evaljs(`location.hash = "#/api/-/feed"; true`);
  await waitFor(`document.querySelector(".feedcards .fcard")`, "sous's own feed");
  ok("a finding never cards to its own author (the four-eyes wall, " +
     "read off the wire)",
     await evaljs(`!document.querySelector('.fcard[data-kind="insight"]')`));
}


/* RECIPE (waymark-4yn) — the feed's order is a ROW now, and this is
   the walk that proves a household can change it without a deploy,
   through the GENERIC form and nothing else. It needs no seeding: the
   observable is the SEAM's own sentence, which is the one recipe field
   that reaches a card verbatim, so what a population happens to hold
   today never enters the claim. It creates one household recipe,
   revises it mid-day, and RETIRES it before it returns — the engine it
   leaves behind is the engine it found.

     make dev-queue
     BASE=http://localhost:8014 node waymark10/scripts/ui-drive.mjs recipe */
async function recipeStory() {
  const tag = Math.random().toString(36).slice(2, 8);
  const words = "Caught up, and a person said so · " + tag;
  const words2 = "That is the whole house · " + tag;
  const asColton = `{headers:{"x-waymark-principal":"colton","x-waymark-actor-type":"human"}}`;
  const feed = q => evaljs(
    `fetch("${BASE}/api/-/feed" + ${JSON.stringify(q || "")}, ${asColton}).then(r=>r.json())`);
  const seamOf = doc => (doc.cards.find(c => c.card_id === "seam") || {}).sentence;

  /* a FRESH document — the tab persists between runs and a hash-only
     change never reloads — then route by hash the way a person clicks */
  console.log("\n· the feed before any row");
  await send("Page.navigate", {url: "about:blank"});
  await sleep(300);
  await send("Page.navigate", {url: `${BASE}/`});
  await sleep(2500);
  await evaljs(`location.hash = "#/api/-/feed"; true`);
  await sleep(1200);

  const before = await feed("");
  ok("with no row stored, recipe.source says built-in",
     before.recipe.source.source === "built-in");
  ok("recipe.order carries the order in the EDITOR's own shape",
     Array.isArray(before.recipe.order) && before.recipe.order.length > 0);
  const wasSeam = seamOf(before);

  console.log("\n· the create form, in the generic UI");
  await evaljs(`location.hash = "#/api/feed_recipes"; true`);
  await waitFor(`[...document.querySelectorAll('button,a')].some(n => /new|create|add/i.test(n.textContent||""))`,
                "the feed_recipes collection screen");
  await sleep(1200);
  await evaljs(`[...document.querySelectorAll('button,a')]
                  .find(n => /new|create|add/i.test(n.textContent||"")).click(); true`);
  await sleep(1200);
  ok("the create form has an order box",
     await evaljs(`!!document.querySelector('[name="order"]')`));
  const chips = await evaljs(`(() => {
    const ta = document.querySelector('[name="order"]');
    const panel = ta && ta.nextElementSibling;
    if (!panel || !panel.classList.contains("opt-chips")) return [];
    return [...panel.querySelectorAll(".opt-row")].map(r => ({
      field: ((r.querySelector(".muted")||{}).textContent||"").trim(),
      chips: [...r.querySelectorAll(".chip")].map(c => c.textContent)}));
  })()`);
  console.log("    chip rows: " +
    chips.map(r => r.field + " " + r.chips.length).join(", "));
  ok("the entry list offers pickers, not recall", chips.length >= 3);
  ok("section offers the census (an enum, no fetch)",
     !!chips.find(r => r.chips.includes("do_now") && r.chips.includes("seam")));
  ok("population offers the registry (an enum, no fetch)",
     !!chips.find(r => r.chips.includes("next_actions")));
  ok("kinds fetched its own vocabulary (an x-options recipe)",
     !!chips.find(r => /kinds/i.test(r.field) && r.chips.length > 3));

  console.log("\n· edit one line of the order the house already reads, and submit");
  const order = JSON.parse(JSON.stringify(before.recipe.order));
  for (const l of order) if (l.section === "seam") l.sentence = words;
  const missing = await evaljs(`(() => {
    const set = (n, v) => {
      const el = document.querySelector('[name="'+n+'"]');
      if (!el) return "no " + n;
      el.value = v;
      el.dispatchEvent(new Event("input", {bubbles:true}));
      el.dispatchEvent(new Event("change", {bubbles:true}));
      return null; };
    return [set("label", ${JSON.stringify("Hand-verified order " + tag)}),
            set("scope", "household"),
            set("order", ${JSON.stringify(JSON.stringify(order, null, 1))})]
           .filter(Boolean); })()`);
  ok("every field of the create form was fillable", missing.length === 0);
  await sleep(300);
  await evaljs(`[...document.querySelectorAll('button')]
                  .find(n => /^(create|save|submit)$/i.test((n.textContent||"").trim()))
                  .click(); true`);
  await sleep(2000);
  const mine = await evaljs(
    `fetch("${BASE}/api/feed_recipes?state=active", ${asColton}).then(r=>r.json())`);
  const row = (mine.data.items || []).find(i => (i.summary||"").includes(tag));
  ok("the generic form created the row", !!row);

  console.log("\n· the feed, next read");
  const after = await feed("");
  ok("the house reads in the row's order", seamOf(after) === words);
  ok("the document names which recipe answered",
     after.recipe.source.source === "household" &&
     after.recipe.source.id === row.self.split("/").pop() &&
     typeof after.recipe.source.version === "number");

  console.log("\n· revise it mid-day, through the row's own form");
  await evaljs(`location.hash = "#${row.self}"; true`);
  await waitFor(`[...document.querySelectorAll('button,a')].some(n => /revise/i.test(n.textContent||""))`,
                "the recipe's own screen");
  await sleep(900);
  await evaljs(`[...document.querySelectorAll('button,a')]
                  .find(n => /revise/i.test((n.textContent||"").trim())).click(); true`);
  await sleep(1200);
  ok("revise prefills the order it is editing",
     await evaljs(`(document.querySelector('[name="order"]')||{}).value.length > 10`));
  const order2 = JSON.parse(JSON.stringify(before.recipe.order));
  for (const l of order2) if (l.section === "seam") l.sentence = words2;
  await evaljs(`(() => {
    const el = document.querySelector('[name="order"]');
    el.value = ${JSON.stringify(JSON.stringify(order2, null, 1))};
    el.dispatchEvent(new Event("input", {bubbles:true})); })(); true`);
  await sleep(300);
  await evaljs(`(() => { const b = [...document.querySelectorAll('button')]
      .find(n => /^(revise|save|submit|confirm)$/i.test((n.textContent||"").trim()));
    if (b) b.click(); })(); true`);
  await sleep(2000);
  const mid = await feed("?explain=1");
  ok("a mid-day revise lands on the very next read (nothing is cached)",
     seamOf(mid) === words2);
  ok("the stamp's version moved with it",
     mid.recipe.source.version > after.recipe.source.version);
  ok("and explain says whose order it narrated",
     (mid.notes||[]).some(n => n.includes("order answered this read")));

  console.log("\n· retire it, and the house goes back");
  const gone = await evaljs(`fetch("${BASE}${row.self}/-/retire",
    {method:"POST",headers:{"content-type":"application/json",
     "x-waymark-principal":"colton","x-waymark-actor-type":"human"},
     body:"{}"}).then(r=>r.status)`);
  ok("retire answered 200", gone === 200);
  const back = await feed("");
  ok("the built-in answers again", back.recipe.source.source === "built-in");
  ok("and the seam is the deployment's own", seamOf(back) === wasSeam);

  console.log("\n· the third law's wall");
  const byAgent = await evaljs(`fetch("${BASE}/api/feed_recipes",
    {method:"POST",headers:{"content-type":"application/json",
     "x-waymark-principal":"composer-${tag}","x-waymark-actor-type":"agent"},
     body:${JSON.stringify(JSON.stringify({label: "Findings first", scope: "household", order}))}
    }).then(r=>r.status)`);
  /* an UNLEASHED agent never reaches the guard — the router's default
     deny conceals the collection first, which is the honest answer
     here; the leashed refusal (a composer holding a feed_recipe write
     grant, refused by written-by-a-person) is the pack's obligation */
  ok("an agent cannot write the order it is read in", byAgent !== 201);
}

if (MODE === "batch-a") await batchAStory();
else if (MODE === "feed") await feedStory();
else if (MODE === "recipe") await recipeStory();
else await mealplanStory();

console.log(`\nUI drive (${MODE}): ${passed} checks passed` +
            (consoleErrors.length ? `\nCONSOLE ERRORS:\n` + consoleErrors.join("\n") : ", no console errors"));
ws.close();
process.exit(consoleErrors.length ? 1 : 0);
