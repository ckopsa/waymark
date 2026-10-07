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

   ACCESS (the held seat call's names and each x-ref form's label,
   against waymark10.access-dev, which seeds its own member, seat and
   held seat-restate and serves the ref_card fixture; CI runs it
   in the ui-access job of .github/workflows/tests.yml):
   1. the same boot, naming waymark10.access-dev/start! on 8124
   2. the same chromium
   3. node waymark10/scripts/ui-drive.mjs access

   QUEST-PHONE (the quest flow on a phone: 390x844 with touch, in the
   mobile shell, live and then as a film; then the 'not yet' button,
   a shut door tapped into a quest, on the phone and at desktop;
   against the same waymark10.access-dev engine, and CI runs it in the
   ui-access job after the access drive):
   1. the access boot, on 8124
   2. the same chromium
   3. node waymark10/scripts/ui-drive.mjs quest-phone

   INVITATION (an open invitation taken from its collection in one
   tap, and another declined from the dialog; docs/spec-guided-follow.md
   § 3. The invitation kind is core's, so a memory engine — no
   database — serves it beside the meal fixture):
   1. clojure -Sdeps '{:aliases {:fx {:extra-paths ["test"]}}}' -M:fx -e \
        "(do ((requiring-resolve 'waymark10.batch-a-dev/start-held-call!) 8124) nil) @(promise)"
      (boot fresh per drive run — the drive seeds its meal and
       invitations through the API)
   2. the same chromium
   3. node waymark10/scripts/ui-drive.mjs invitation

   GUIDED (guided follow, two people in two browser contexts, against
   the batch-a engine — the recipe door's shared draft needs a real
   store; CI runs it in the ui-guided job of .github/workflows/tests.yml):
   1. the batch-a boot, on 8123
   2. the same chromium
   3. node waymark10/scripts/ui-drive.mjs guided

   LATER ("Do this later" from a row's dialog, and the row's pending
   scheduled actions; docs/spec-scheduled-actions.md R-7.3. The
   scheduled_action kind is core's, so a memory engine — no database —
   serves it beside a ticket fixture):
   1. clojure -Sdeps '{:aliases {:fx {:extra-paths ["test"]}}}' -M:fx -e \
        "(do ((requiring-resolve 'waymark10.batch-a-dev/start-later!) 8125) nil) @(promise)"
      (boot fresh per drive run — the drive writes its ticket through
       the API)
   2. the same chromium
   3. node waymark10/scripts/ui-drive.mjs later

   (The FEED and RECIPE drives — the day's scroll-first face and the
   recipe editor — retired with the feed, 2026-09, and so did
   feed-smoke.sh.)

   The story's plan checks stay self-normalizing (a partial re-run
   brings the plan back to planned before them), and the ported-page
   additions below seed uniquely-named rows per run — but the meal
   sections assume the fresh world of step 1. */
const MODE = ["batch-a", "access", "invitation", "guided", "later", "quest-phone"].includes(process.argv[2])
  ? process.argv[2] : "story";
const DEBUG_PORT = process.env.CDP_PORT || "9223";
const BASE = process.env.BASE ||
  (["batch-a", "guided"].includes(MODE) ? "http://localhost:8123"
   : ["access", "invitation", "quest-phone"].includes(MODE) ? "http://localhost:8124"
   : MODE === "later" ? "http://localhost:8125"
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
/* the page as it stands, as a PNG in the directory SHOTS names (CI's
   ui-access job uploads it); with no SHOTS this does nothing */
async function shot(name) {
  if (!process.env.SHOTS) return;
  const r = await send("Page.captureScreenshot", {format: "png"});
  if (!r.result?.data) return;
  const {mkdir, writeFile} = await import("node:fs/promises");
  await mkdir(process.env.SHOTS, {recursive: true});
  await writeFile(`${process.env.SHOTS}/${name}.png`, Buffer.from(r.result.data, "base64"));
}
/* `why`, when given, is an expression read once at the timeout: what the
   page held, so the message says which half of the wait was false */
async function waitFor(pred, what, ms = 6000, why = null) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await evaljs(pred)) return true;
    await sleep(150);
  }
  let seen = "";
  if (why) {
    try { seen = ": " + JSON.stringify(await evaljs(why)); }
    catch (e) { seen = ": (" + e.message + ")"; }
  }
  throw new Error("timed out waiting for " + what + seen);
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
/* the toast is a receipt; the way back is an ENTRY in the undo stack
   (waymark-qmo6), a real node with the reverse door's own label — not
   the string "[object HTMLDivElement]" the stack rendered until
   waymark-q1an spread the array into append */
await waitFor(`document.querySelector("#undostack .undoitem button[data-undo]")`, "undo entry in the stack");
ok("a reverse action in the post-action envelope becomes an entry in the undo stack",
   await evaljs(`document.querySelector("#undostack .undoitem button[data-undo]").textContent
                 .includes("Finalize") &&
                 !document.querySelector("#undostack").textContent.includes("[object") &&
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

/* ════ access: a held seat call names its people ═══════════════════════
   Against waymark10.access-dev, whose boot seeded one held
   seat-restate: owner is a member, caller and door author are
   `seat:<id>`, and door.id is the seat's own row. Each must render as
   its row's name (principalRef, and door.id's kind-from ref), never
   as the bare uuid. */
async function accessStory() {
  const h = {"x-waymark-principal": "priya"};
  const get = async path => (await fetch(BASE + path, {headers: h})).json();

  console.log("· the seeded held seat-restate");
  const col = await get("/api/held_calls");
  ok("the boot seeded one held call", (col.data?.items || []).length === 1);
  const self = col.data.items[0].self;
  const held = (await get(self)).data;
  const sid = held.door.id;
  ok("it is a seat restate whose caller and author are the seat",
     held.door.kind === "seat" && held.door.action === "restate" &&
     held.caller === "seat:" + sid && held.door.author === "seat:" + sid &&
     !!held.owner);

  console.log("· boot + principal");
  await send("Page.navigate", {url: BASE + "/api/-/ui"});
  await sleep(1200);
  await evaljs(`localStorage.setItem("wm10.principal", "priya"); location.reload(); true`);
  await sleep(1200);

  /* the names, as the page's own reader resolves them */
  const names = await evaljs(`(async () => ({
    member: await rowSummary("member", ${JSON.stringify(held.owner)}),
    seat: await rowSummary("seat", ${JSON.stringify(sid)}) }))()`);
  ok("the member and the seat each have a name to show",
     !!names.member && !!names.seat &&
     names.member !== held.owner && names.seat !== sid);
  /* each name is its row's own summary, read off the API — never
     spelled here */
  const api = {member: (await get("/api/members/" + held.owner)).summary,
               seat: (await get("/api/seats/" + sid)).summary};
  ok("each name is the row's summary as the API reads it",
     names.member === api.member && names.seat === api.seat);

  console.log("· the held call's row page");
  await evaljs(`location.hash = ${JSON.stringify(self)}; true`);
  /* the value cell beside a label holds a link reading `text` */
  const labelled = (label, text) => `[...document.querySelectorAll("td, th, dt")]
    .filter(c => c.textContent.trim() === ${JSON.stringify(label)} ||
                 (c.firstChild?.textContent || "").trim() === ${JSON.stringify(label)})
    .some(c => [...(c.nextElementSibling?.querySelectorAll("a") || [])]
      .some(a => a.textContent.trim() === ${JSON.stringify(text)}))`;
  const checks = [
    ["owner renders as the member's name", "Waits on", names.member],
    ["caller renders as the seat's name", "Who called", names.seat],
    ["door author renders as the seat's name", "Asked by the seat", names.seat],
    ["door.id renders as the target row's summary", "Row", names.seat]];
  for (const [name, label, text] of checks) {
    try { await waitFor(labelled(label, text), name); }
    catch (e) {
      console.log("  what sits beside " + JSON.stringify(label) + ": " + JSON.stringify(await evaljs(
        `[...document.querySelectorAll("td, th, dt")]
           .filter(c => c.textContent.trim() === ${JSON.stringify(label)} ||
                        (c.firstChild?.textContent || "").trim() === ${JSON.stringify(label)})
           .map(c => (c.nextElementSibling?.outerHTML || "(nothing)").slice(0, 400))`)));
      throw e;
    }
    ok(name, true);
  }
  ok("no bare uuid is left where a name belongs",
     await evaljs(`![...document.querySelectorAll("span.mono")].some(s =>
       [${JSON.stringify(held.owner)}, ${JSON.stringify("seat:" + sid)},
        ${JSON.stringify(sid)}].includes(s.title))`));

  /* ref labelling (ticket f0eb54a6). ui_assembly_test pins fieldCell,
     resourceRef and rowSummary as substrings; this runs them. The held
     call on screen carries three x-ref forms: a principal (owner, and
     caller as `seat:<id>`), a nested one (door.author) and a kind-from
     id (door.id). Each name above is also a link to the row it names. */
  console.log("· ref labelling: each form links to the row it names");
  const refLinks = (href, text) => `[...document.querySelectorAll("a")]
    .filter(a => a.getAttribute("href") === ${JSON.stringify("#" + href)} &&
                 a.textContent.trim() === ${JSON.stringify(text)}).length`;
  await waitFor(`${refLinks("/api/members/" + held.owner, names.member)} >= 1`,
                "the owner's link to its member");
  ok("a principal links to its member's row", true);
  await waitFor(`${refLinks("/api/seats/" + sid, names.seat)} >= 3`,
                "the caller's, the door author's and the door row's links to the seat");
  ok("a seat principal, a nested principal and a kind-from id link to the seat's row", true);

  /* a ref_card (access-dev's fixture) carries the other two forms: a
     plain :kind ref and a typed address, both naming one ref_target,
     and a second address naming a row that is not there. */
  const refCreate = async (path, body) => {
    const res = await fetch(BASE + path, {method: "POST",
      headers: {"Content-Type": "application/json", ...h},
      body: JSON.stringify(body)});
    const doc = await res.json().catch(() => null);
    if (res.status !== 201)
      throw new Error("POST " + path + ": " + res.status + " " + JSON.stringify(doc));
    return doc.self;
  };
  const refTarget = await refCreate("/api/ref_targets", {name: "Top shelf"});
  const refTargetId = refTarget.split("/").pop();
  const refLostId = crypto.randomUUID();
  const refCard = await refCreate("/api/ref_cards",
    {name: "Index card", target_id: refTargetId,
     about: "ref_target:" + refTargetId, lost: "ref_target:" + refLostId});
  const refSummary = (await get(refTarget)).summary;
  const refDoc = await get(refCard);
  ok("the card's envelope carries its plain ref's href and summary",
     !!refSummary && refDoc.refs?.target_id?.href === refTarget &&
     refDoc.refs?.target_id?.summary === refSummary);

  /* every request the page starts from here on, by url */
  const refWatch = `(() => {
    window.__refGets = [];
    window.__refFetch = window.fetch;
    window.fetch = (...a) => {
      window.__refGets.push(String(a[0]?.url || a[0]));
      return window.__refFetch.apply(window, a);
    };
    return true; })()`;
  const refUnwatch = `window.fetch = window.__refFetch; delete window.__refFetch; true`;
  /* how many of them named `id`, and whether that count has stopped */
  const refReads = async id => {
    const n = `window.__refGets.filter(u => u.includes(${JSON.stringify(id)})).length`;
    await sleep(1500);
    const count = await evaljs(n);
    await sleep(1500);
    return {count, still: (await evaljs(n)) === count};
  };
  /* the value cell of one field on the row page */
  const refCell = f => `document.querySelector('td.k[title="${f}"]')?.nextElementSibling`;
  const refLinked = (f, href, text) => `(() => {
    const a = ${refCell(f)}?.querySelector("a");
    return !!a && a.getAttribute("href") === ${JSON.stringify("#" + href)} &&
           a.textContent.trim() === ${JSON.stringify(text)}; })()`;

  await evaljs(refWatch);
  await evaljs(`location.hash = ${JSON.stringify(refCard)}; true`);
  await waitFor(refLinked("target_id", refTarget, refSummary), "the plain kind ref's link");
  ok("a plain kind ref reads as its target's summary and links to its row", true);
  await waitFor(refLinked("about", refTarget, refSummary), "the typed address's link");
  ok("a typed address reads as its target's summary and links to its row", true);
  const refLost = await refReads(refLostId);
  const refSeen = await evaljs(
    `window.__refGets.filter(u => u.includes(${JSON.stringify(refTargetId)}))`);
  console.log("  reads of the target: " + JSON.stringify(refSeen) +
              ", of the missing row: " + refLost.count);
  ok("both labels ride the card's envelope: the page starts no read of the target",
     refSeen.length === 0);
  ok("a ref to a row that is not there stays its bare token, unlinked",
     await evaljs(`(() => { const c = ${refCell("lost")};
       return !!c && !c.querySelector("a") &&
              c.textContent.trim() === ${JSON.stringify("ref_target:" + refLostId)}; })()`));
  ok("and the page asks for it and stops: no failing GET loop",
     refLost.count >= 1 && refLost.count <= 2 && refLost.still);
  await evaljs(refUnwatch);

  /* replay (docs/spec-guided-follow.md §4): the boot sealed one walk
     of a move, a ui frame with the restate dialog open, and a
     transition. Replay on its row page plays the export on this
     screen: the one request is the export GET, the screen goes to the
     recorded row, the dialog opens read-only with the recorded
     fields, and the transition is drawn from its own frame. The same
     text from a file makes no request at all. */
  console.log("· replay: the sealed walk, read-only");
  const walks = await get("/api/walks?state=sealed");
  ok("the boot sealed one walk", (walks.data?.items || []).length === 1);
  const walkSelf = walks.data.items[0].self;
  const exportText = await (await fetch(BASE + walkSelf + "/export", {headers: h})).text();
  const [walkHead, ...walkFrames] = exportText.trim().split("\n").map(l => JSON.parse(l));
  ok("its export is a move, a ui frame and a transition",
     walkHead.format === "waymark-walk/1" &&
     walkFrames.map(f => f.type).join() === "move,ui,transition");
  const [, uiFrame, transFrame] = walkFrames;
  const actor = walkHead.cast[uiFrame.who].display;
  const replayState = `document.querySelector("#replaychip")?.getAttribute("data-replay-state")`;
  /* every fetch the page starts from here on, as "METHOD url" */
  const watchFetch = `(() => {
    window.__reqs = [];
    if (!window.__fetch) {
      window.__fetch = window.fetch;
      window.fetch = (...a) => {
        window.__reqs.push(((a[1] || {}).method || a[0]?.method || "GET") + " " +
                           String(a[0]?.url || a[0]));
        return window.__fetch.apply(window, a);
      };
    }
    return true; })()`;
  const replayed = async () => {
    const s = await evaljs(`(() => {
      const d = document.querySelector("dialog[open][data-guided]");
      const n = d && d.querySelector('[name="charter"]');
      return {here: hereHref(),
              screen: !!document.querySelector("#view [data-replay-screen]"),
              dialog: !!d,
              value: n ? n.value : null,
              disabled: !!(n && n.disabled),
              note: d?.querySelector("[data-guided-note]")?.textContent || "",
              buttons: d ? [...d.querySelectorAll(".dlgfoot button")].map(b => b.textContent) : [],
              who: document.querySelector("[data-replay-who]")?.textContent || "",
              heading: document.querySelector("#view [data-replay-screen] h2")?.textContent || "",
              transition: document.querySelector("#view [data-replay-transition]")
                ?.getAttribute("data-replay-transition") || null,
              reqs: window.__reqs}; })()`);
    console.log("  requests during the replay: " + JSON.stringify(s.reqs));
    return s;
  };

  await evaljs(`location.hash = ${JSON.stringify(walkSelf)}; true`);
  await waitFor(`!!document.querySelector("[data-replay-walk]")`,
                "Replay on the sealed walk's row page");
  ok("a sealed walk's row page offers Replay", true);
  await sleep(1500);   /* the row page's own reads settle first */
  await evaljs(watchFetch);
  await evaljs(`document.querySelector("[data-replay-walk]").click(); true`);
  await waitFor(`${replayState} === "ended"`, "the replay to reach its last frame", 15000);
  const first = await replayed();
  ok("the screen navigates to the recorded row, drawn from the recording",
     first.here === uiFrame.self && first.screen);
  ok("the dialog opens read-only with the recorded fields",
     first.dialog && first.disabled && first.value === uiFrame.ui.fields.charter &&
     first.buttons.join() === "Cancel");
  ok("the cast's display names label who is acting",
     first.note.includes(actor) &&
     first.who === walkHead.cast[transFrame.who].display);
  ok("the transition renders from its own frame",
     first.transition === transFrame.action && first.heading === transFrame.summary);
  ok("the replay makes no request but the export GET",
     first.reqs.length === 1 && first.reqs[0] === "GET " + walkSelf + "/export");

  await evaljs(`document.querySelector("[data-replay-stop]").click(); true`);
  await waitFor(`!${replayState} && !document.querySelector("dialog[open]") &&
                 hereHref() === ${JSON.stringify(walkSelf)} &&
                 !!document.querySelector("[data-replay-walk]")`,
                "the live walk page to come back");
  ok("stopping the replay returns the live screen", true);

  /* the file a person picks: paused, sped up to 4x, played to its end */
  await sleep(1500);
  await evaljs(watchFetch);
  const held2 = await evaljs(`(async () => {
    await replayFile(new File([${JSON.stringify(exportText)}], "walk.ndjson"));
    document.querySelector("[data-replay-toggle]").click();
    const paused = ${replayState};
    const speed = document.querySelector("[data-replay-speed]");
    speed.value = "4";
    speed.dispatchEvent(new Event("change"));
    await new Promise(r => setTimeout(r, 1200));
    const at = replay.at;
    document.querySelector("[data-replay-toggle]").click();
    return {paused, at, speed: replay.speed}; })()`);
  ok("pause holds the replay and the speed choice takes",
     held2.paused === "paused" && held2.at === 0 && held2.speed === 4);
  await waitFor(`${replayState} === "ended"`, "the file's replay to reach its last frame");
  const second = await replayed();
  ok("a picked file replays the same way, with no request at all",
     second.here === uiFrame.self && second.dialog &&
     second.value === uiFrame.ui.fields.charter && second.reqs.length === 0);
  await evaljs(`document.querySelector("[data-replay-stop]").click();
                window.fetch = window.__fetch; delete window.__fetch; true`);
  await waitFor(`!${replayState} && !document.querySelector("dialog[open]")`,
                "the second replay to stop");

  /* a walkthrough (docs/spec-walkthrough.md §5 and §7 item 5). The boot
     seeded three steps by the mail-desk sitter for priya: a person
     step naming two fields, an agent step with no action, a person
     step. The page leads her through the person steps, and nothing
     here reloads it: the mark set first is read again at step 3. */
  console.log("· a walkthrough: the page leads through the person steps");
  const wt = ((await get("/api/walkthroughs?state=open&subject=priya")).data?.items || [])[0];
  ok("the boot seeded one walkthrough offered to the viewer", !!wt);
  const wtSteps = (await get(wt.self)).data.steps;
  await evaljs(`window.__led = true; location.hash = ${JSON.stringify(wt.self)}; true`);
  await waitFor(`document.querySelectorAll("[data-walk-steps] li").length === 3 &&
                 !!document.querySelector('[data-action="start"]')`,
                "the walkthrough's row page");
  ok("the row page lists every step before the start", true);
  await evaljs(`document.querySelector('[data-action="start"]').click(); true`);
  /* Start is the row's own door: its dialog, when it shows one, is
     confirmed. A step's own dialog is never touched here. */
  await waitFor(`!!document.querySelector("dialog[open]") || !!walkthroughId`,
                "Start to ask or to land");
  await evaljs(`(() => { const d = document.querySelector("dialog[open]");
    if (d && !d.querySelector("[data-walk-step]"))
      d.querySelector(".dlgfoot button.primary").click();
    return true; })()`);
  const wtDlg = `document.querySelector("dialog[open] [data-walk-step]")`;
  const wtChip = `document.querySelector("#walkchip").textContent`;
  await waitFor(`${wtDlg}?.textContent.startsWith("Step 1 of 3")`,
                "step 1's dialog, opened off the firehose", 15000);
  ok("Start opens step 1's dialog under its step line", true);
  const wtFirst = await evaljs(`({
    lit: document.querySelectorAll("dialog[open] .invited").length,
    skip: document.querySelector("dialog[open] [data-invite-decline]")?.textContent,
    stop: !!document.querySelector("dialog[open] [data-walk-stop]")})`);
  ok("both named fields are lit", wtFirst.lit === 2);
  ok("inside a walkthrough the dialog offers Skip and Stop",
     wtFirst.skip === "Skip" && wtFirst.stop);
  await waitFor(`${wtChip}.includes("Step 1 of 3")`, "the chip on step 1");
  ok("the chip says the same step", true);
  await evaljs(`(() => {
    const set = (name, v) => {
      const i = document.querySelector('dialog[open] [name="' + name + '"]');
      i.value = v;
      i.dispatchEvent(new Event("input", {bubbles: true}));
      i.dispatchEvent(new Event("change", {bubbles: true}));
    };
    set("title", "Sorted mail"); set("room", "Front desk");
    document.querySelector("dialog[open] .dlgfoot button.primary").click();
    return true; })()`);
  await waitFor(`${wtChip}.includes("Step 2 of 3") && ${wtChip}.includes("is working: ")`,
                "the chip's agent line", 15000);
  const wtAgent = await evaljs(`({text: ${wtChip},
    skip: !!document.querySelector("#walkchip [data-walk-skip]"),
    stop: !!document.querySelector("#walkchip [data-walk-stop]")})`);
  ok("after the answer the chip reads the agent's step and its note",
     wtAgent.text.includes(wtSteps[1].note));
  ok("an agent step offers Stop and no Skip", wtAgent.stop && !wtAgent.skip);
  /* the sitter ends its own step through the API; the page hears it */
  const wtAuthor = (await get(wt.self)).data.author;
  const wtAdvanced = await fetch(BASE + wt.self + "/-/advance", {method: "POST",
    headers: {"Content-Type": "application/json", "x-waymark-principal": wtAuthor,
              "Idempotency-Key": "ui-drive-advance-" + Date.now()},
    body: "{}"});
  ok("the sitter advances its own step through the API", wtAdvanced.status < 400);
  await waitFor(`${wtDlg}?.textContent.startsWith("Step 3 of 3")`,
                "step 3's dialog, with no reload", 15000);
  ok("the next person step opens by itself, with no reload",
     await evaljs(`window.__led === true`));
  await evaljs(`document.querySelector("dialog[open] [data-invite-decline]").click(); true`);
  await waitFor(`!document.querySelector("dialog[open]") &&
                 document.querySelector("#walkchip").style.display === "none"`,
                "the walkthrough to finish and leave the hand", 15000);
  const wtEnded = await get(wt.self);
  ok("the walkthrough is finished: answered, done, skipped",
     wtEnded.state === "finished" &&
     (wtEnded.data.outcomes || []).map(o => o.outcome).join() === "answered,done,skipped");

  /* the quest tracker (docs/spec-quests.md, the tracker): priya accepts
     a goal on a note of her own and pins it. The planner answers the
     create with a plan of its own; the story waits for that one, then
     writes its plans through the engine's own `plan` door by a system
     principal, as the planner writes them. */
  console.log("· a pinned quest: the tracker rides every page");
  const sys = {"x-waymark-principal": "admin", "x-waymark-actor-type": "system"};
  let qCalls = 0;
  const qPost = async (path, body, headers) => {
    const res = await fetch(BASE + path, {method: "POST",
      headers: {"Content-Type": "application/json", ...headers,
                "Idempotency-Key": `ui-drive-quest-${Date.now()}-${qCalls++}`},
      body: JSON.stringify(body)});
    return {status: res.status, doc: await res.json().catch(() => null)};
  };
  const qBar = `document.querySelector("#questbar")`;
  const qCount = `${qBar}.querySelector("[data-quest-count]")?.textContent`;
  const qTitle = "Clear the quest pile";
  ok("with no pinned quest the tracker is hidden", await evaljs(`${qBar}.hidden === true`));
  const pile = (await qPost("/api/led_notes", {title: "Quest pile"}, h)).doc;
  const quest = await qPost("/api/quests",
    {self: pile.self, action: "finish", title: qTitle}, h);
  ok("priya accepts the goal as a quest", quest.status === 201);
  const qSelf = quest.doc.self;
  await evaljs(`window.__quest = true; true`);
  ok("priya pins it", (await qPost(qSelf + "/-/pin", {}, h)).status < 400);
  await waitFor(`!${qBar}.hidden &&
                 ${qBar}.querySelector("[data-quest-title]")?.textContent ===
                   ${JSON.stringify(qTitle)}`,
                "the tracker, off the firehose", 15000);
  /* the planner's own plan lands first, so it cannot write over the
     story's */
  let qBorn = null;
  for (let i = 0; i < 60 && !qBorn; i++) {
    if ((await get(qSelf)).data.planned_at) qBorn = true;
    else await sleep(250);
  }
  ok("the planner answers the create with a first plan", qBorn === true);
  /* the planner is quicker than a page, so the row as it was born (no
     plan, no planned_at) is drawn from the quest in hand */
  ok("a quest with no plan yet reads planning… under its title, with no Go",
     await evaljs(`(() => {
       questDoc = {...questDoc, data: {...questDoc.data, plan: [], planned_at: null}};
       questTracker();
       const t = ${qBar}.querySelector("[data-quest-title]");
       return !!${qBar}.querySelector("[data-quest-planning]") &&
         t.textContent === ${JSON.stringify(qTitle)} &&
         t.getAttribute("href") === ${JSON.stringify("#" + qSelf)} &&
         !${qBar}.querySelector("[data-surface='tracker.go']"); })()`));
  const stepOne = {n: 1, door: "rename", self: pile.self, whose: "person",
                   note: "Name the pile, then say which room it is in.",
                   needs: ["title", "room"]};
  const stepTwo = {n: 2, door: "finish", self: pile.self};
  const planned = await qPost(qSelf + "/-/plan",
    {plan: [{...stepOne, state: "next"},
            {...stepTwo, whose: "person", note: "Finish the note.", state: "later"}],
     plan_is_estimate: true}, sys);
  ok("the engine's plan door writes the first plan", planned.status < 400);
  await waitFor(`${qCount} === "0 done, 2 known so far"`,
                "the count, off the plan transition", 15000);
  ok("the count is k done, n known so far, and never k of n",
     await evaljs(`!/\\d+ of \\d+/.test(${qBar}.textContent)`));
  ok("the head step's note stands beside the count",
     await evaljs(`${qBar}.querySelector("[data-quest-note]")?.textContent`) === stepOne.note);
  ok("no step in the tracker reads as a path",
     await evaljs(`!/\\/api\\//.test(${qBar}.textContent)`));
  /* the quest's own page says its goal door by its display label: the
     raw action name is the cell's title, and is not its text. The cell
     is a span: a shut door's button carries data-quest-door too. */
  const qWas = await evaljs(`hereHref()`);
  const qDoor = `document.querySelector("#view .kv span[data-quest-door]")`;
  await evaljs(`location.hash = ${JSON.stringify(qSelf)}; true`);
  await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(qSelf)} && !!${qDoor}`,
                "the quest's row page", 15000);
  const qGoal = ((await get(qSelf)).data.plan || []).find(s =>
    s.self === pile.self && s.door === "finish") || {};
  const qDoorSays = qGoal.door_label || await evaljs(`title("finish")`);
  ok("the quest page's data table says the goal door by its label, not its action name",
     qDoorSays !== "finish" &&
     await evaljs(`${qDoor}.textContent`) === qDoorSays &&
     await evaljs(`${qDoor}.getAttribute("title")`) === "finish");
  await evaljs(`location.hash = ${JSON.stringify(qWas)}; true`);
  await waitFor(`hereHref() === ${JSON.stringify(qWas)}`,
                "the page the story was on", 15000);
  const qPageOne = await evaljs(`hereHref()`);
  await evaljs(`location.hash = "/api/led_notes"; true`);
  await waitFor(`hereHref().split("?")[0] === "/api/led_notes" && !${qBar}.hidden &&
                 ${qCount} === "0 done, 2 known so far"`,
                "the tracker on a second page");
  ok("the tracker is on two different pages", qPageOne.split("?")[0] !== "/api/led_notes");

  const qPlannedAt = (await get(qSelf)).data.planned_at;
  await evaljs(`${qBar}.querySelector("[data-surface='tracker.go']").click(); true`);
  await waitFor(`!!document.querySelector("dialog[open] [data-invite-note]")`,
                "the head step's dialog", 15000);
  const qDlg = await evaljs(`({here: hereHref(),
    lit: document.querySelectorAll("dialog[open] .invited").length,
    note: document.querySelector("dialog[open] [data-invite-note]").textContent,
    decline: !!document.querySelector("dialog[open] [data-invite-decline]")})`);
  ok("Go opens the head step's door on its row", qDlg.here === pile.self);
  ok("the step's needs are lit and its note is shown",
     qDlg.lit === 2 && qDlg.note === stepOne.note);
  ok("a quest's step is no invitation: the dialog offers no Decline", !qDlg.decline);
  await evaljs(`(() => {
    const set = (name, v) => {
      const i = document.querySelector('dialog[open] [name="' + name + '"]');
      i.value = v;
      i.dispatchEvent(new Event("input", {bubbles: true}));
      i.dispatchEvent(new Event("change", {bubbles: true}));
    };
    set("title", "Sorted quest pile"); set("room", "Hall");
    document.querySelector("dialog[open] .dlgfoot button.primary").click();
    return true; })()`);
  await waitFor(`!document.querySelector("dialog[open]")`, "the step's dialog to close", 15000);
  ok("the step is taken through its own door",
     (await get(pile.self)).data.title === "Sorted quest pile");
  /* a move on the row the quest names plans it again: that plan lands
     first, so it cannot write over the story's */
  let qAgain = null;
  for (let i = 0; i < 60 && !qAgain; i++) {
    if ((await get(qSelf)).data.planned_at !== qPlannedAt) qAgain = true;
    else await sleep(250);
  }
  ok("the planner answers the step with a new plan", qAgain === true);

  const replanned = await qPost(qSelf + "/-/plan",
    {plan: [{...stepOne, state: "done"},
            {...stepTwo, whose: "seat", waiting_on: "Planner", state: "waiting"}],
     plan_is_estimate: true, waiting_on: "Planner"}, sys);
  ok("the engine plans again", replanned.status < 400);
  /* the planner's own plan may read the same count: the waiting line is
     the story's alone */
  await waitFor(`${qCount} === "1 done, 2 known so far" &&
                 !!${qBar}.querySelector("[data-quest-waiting]")`,
                "the new count", 15000);
  ok("a new plan transition moves the count with no reload",
     await evaljs(`window.__quest === true`));
  const qSeat = await evaljs(`({
    waiting: ${qBar}.querySelector("[data-quest-waiting]")?.textContent,
    go: ${qBar}.querySelector("[data-surface='tracker.go']")?.disabled,
    doors: ["pause", "unpin", "replan"].every(n =>
      !!${qBar}.querySelector('[data-action="' + n + '"]'))})`);
  ok("a seat's head step reads waiting on, and Go is disabled",
     qSeat.waiting === "waiting on Planner" && qSeat.go === true);
  ok("the menu offers the quest's own Pause, Unpin and Replan", qSeat.doors);

  ok("the engine finishes the quest",
     (await qPost(qSelf + "/-/finish", {}, sys)).status < 400);
  await waitFor(`!!${qBar}.querySelector("[data-quest-complete]") &&
                 ${qBar}.textContent.includes(${JSON.stringify(qTitle)})`,
                "the completion line", 15000);
  ok("finish shows Quest complete with the title", true);
  await waitFor(`${qBar}.hidden === true`, "the tracker to hide", 15000);
  ok("a few seconds later the tracker hides", true);

  /* a step's need may be a path into a nested argument (ticket
     45cae4d6): the fixture's restate door takes
     showcase.evidence.film_url, a map inside a map. The form names that
     input by its path, Go lights that one input, and the submit folds
     the path back into one object. */
  console.log("· a quest step whose need is a path: showcase.evidence.film_url");
  const pNeed = "showcase.evidence.film_url";
  const pFilm = "https://films.example/quest-reel";
  const reel = (await qPost("/api/led_notes", {title: "Quest reel"}, h)).doc;
  const pQuest = await qPost("/api/quests",
    {self: reel.self, action: "finish", title: "Show the reel"}, h);
  ok("priya accepts a second goal as a quest", pQuest.status === 201);
  const pSelf = pQuest.doc.self;
  ok("priya pins the second quest", (await qPost(pSelf + "/-/pin", {}, h)).status < 400);
  let pBorn = null;
  for (let i = 0; i < 60 && !pBorn; i++) {
    if ((await get(pSelf)).data.planned_at) pBorn = true;
    else await sleep(250);
  }
  ok("the planner answers the second create with a first plan", pBorn === true);
  const pStep = {n: 1, door: "restate", self: reel.self, whose: "person",
                 note: "Say where the film plays.", needs: [pNeed]};
  const pPlanned = await qPost(pSelf + "/-/plan",
    {plan: [{...pStep, state: "next"}], plan_is_estimate: true}, sys);
  ok("the engine's plan door takes a path as a need", pPlanned.status < 400);
  await waitFor(`!${qBar}.hidden &&
                 ${qBar}.querySelector("[data-quest-note]")?.textContent ===
                   ${JSON.stringify(pStep.note)}`,
                "the path step's note in the tracker", 15000);
  const pPlannedAt = (await get(pSelf)).data.planned_at;
  await evaljs(`${qBar}.querySelector("[data-surface='tracker.go']").click(); true`);
  await waitFor(`!!document.querySelector("dialog[open] [data-invite-note]")`,
                "the path step's dialog", 15000);
  const pDlg = await evaljs(`(() => {
    const lit = [...document.querySelectorAll("dialog[open] .invited")];
    const input = document.querySelector('dialog[open] [name="${pNeed}"]');
    const inner = input ? input.closest('[data-subform="showcase.evidence"]') : null;
    return {here: hereHref(), lit: lit.length,
            holds: lit.length === 1 && !!input && lit[0].contains(input),
            nested: !!inner && !!inner.parentElement.closest('[data-subform="showcase"]'),
            note: document.querySelector("dialog[open] [data-invite-note]").textContent}; })()`);
  ok("Go opens the restate door on the step's row", pDlg.here === reel.self);
  ok("the input two levels down is named by its path", pDlg.nested);
  ok("the path lights that one input and no other",
     pDlg.lit === 1 && pDlg.holds && pDlg.note === pStep.note);
  await evaljs(`(() => {
    const i = document.querySelector('dialog[open] [name="${pNeed}"]');
    i.value = ${JSON.stringify(pFilm)};
    i.dispatchEvent(new Event("input", {bubbles: true}));
    i.dispatchEvent(new Event("change", {bubbles: true}));
    document.querySelector("dialog[open] .dlgfoot button.primary").click();
    return true; })()`);
  await waitFor(`!document.querySelector("dialog[open]")`,
                "the path step's dialog to close", 15000);
  ok("the submit folds the path back into one nested object",
     JSON.stringify((await get(reel.self)).data.showcase) ===
       JSON.stringify({evidence: {film_url: pFilm}}));
  /* the move plans the quest again: that plan lands before the finish */
  let pAgain = null;
  for (let i = 0; i < 60 && !pAgain; i++) {
    if ((await get(pSelf)).data.planned_at !== pPlannedAt) pAgain = true;
    else await sleep(250);
  }
  ok("the planner answers the path step with a new plan", pAgain === true);
  ok("the engine finishes the second quest",
     (await qPost(pSelf + "/-/finish", {}, sys)).status < 400);
  await waitFor(`!!${qBar}.querySelector("[data-quest-complete]")`,
                "the second completion line", 15000);
  await waitFor(`${qBar}.hidden === true`, "the tracker to hide again", 15000);

  /* "Accept as quest" (docs/spec-quests.md): a refused door that names
     a remedy offers to keep the goal, and one that names none does not.
     The fixture's shelve door refuses the attic with no remedy, and the
     high shelf, for a note with no room, with rename as its remedy. */
  console.log("· a refused door with remedies: Accept as quest");
  const shelfTitle = "Shelf pile";
  const shelfNote = (await qPost("/api/led_notes", {title: shelfTitle}, h)).doc;
  /* priya records this story as a walk of her own, with its screens
     (`docs`, so the walk holds the quest's document), and shares this
     tab so the walk takes its forms. The replay further down plays what
     the engine recorded of these refusals. */
  const rShare = `document.querySelector("#sharebtn")`;
  const rRec = await qPost("/api/walks",
    {followed: "priya", title: "A refusal kept as a quest", docs: true}, h);
  ok("priya starts a walk of her own screen", rRec.status === 201);
  const rWalkSelf = rRec.doc.self;
  const rFrameCount = async () => (await get(rWalkSelf)).data.frame_count;
  /* the walk's frame count, once no more arrive for a second */
  const rSettled = async () => {
    let n = await rFrameCount();
    for (let i = 0; i < 20; i++) {
      await sleep(1000);
      const m = await rFrameCount();
      if (m === n) return n;
      n = m;
    }
    return n;
  };
  await evaljs(`${rShare}.click(); true`);
  await waitFor(`${rShare}.getAttribute("aria-pressed") === "true"`,
                "priya's share toggle on", 15000);
  await evaljs(`location.hash = ${JSON.stringify(shelfNote.self)}; true`);
  /* the hash moves before the page is drawn, and the page it leaves is
     the reel's: a note too, with a shelve door of its own. The wait
     reads the drawn page, so the click is on this note's door. */
  await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(shelfNote.self)} &&
                 document.querySelector("#view").textContent
                   .includes(${JSON.stringify(shelfTitle)}) &&
                 !!document.querySelector('#view [data-action="shelve"]')`,
                "the note's row page", 15000);
  /* the row's own path is the crumb's title, and is not header text */
  ok("a row page's header shows no /api/ path as text",
     await evaljs(`(() => {
       const p = document.querySelector("#view .panel");
       const head = [p.querySelector(".crumbs"), p.querySelector("h2"),
                     ...p.querySelectorAll(".statechip, .version")];
       return p.querySelector(".crumbs .id")?.getAttribute("title") ===
                ${JSON.stringify(shelfNote.self)} &&
              head.every(n => n && !n.textContent.includes("/api/")); })()`));
  const rBefore = await rSettled();
  await evaljs(`document.querySelector('#view [data-action="shelve"]').click(); true`);
  await waitFor(`!!document.querySelector('dialog[open] [name="shelf"]')`,
                "the shelve dialog", 15000);
  /* a shelf the door's schema does not name. The form offers none, so
     this write of priya's goes to the action route beside the form,
     once the walk holds the open form. */
  let rCount = rBefore;
  for (let i = 0; i < 30 && rCount <= rBefore; i++) {
    await sleep(500);
    rCount = await rFrameCount();
  }
  ok("the walk takes priya's shelve form", rCount > rBefore);
  await sleep(1500);
  const rLoft = await qPost(shelfNote.self + "/-/shelve", {shelf: "loft"}, h);
  ok("the shelve door refuses a shelf its schema does not name",
     rLoft.status >= 400 && rLoft.status < 500);
  /* the next refusal takes this one's place in the form: the walk
     keeps them apart, so a replay shows each */
  await sleep(1500);
  /* the enum is a select or a radio group, as the form chose */
  const shelve = async shelf => {
    const before = await rFrameCount();
    await evaljs(`(() => {
      const nodes = [...document.querySelectorAll('dialog[open] [name="shelf"]')];
      const radio = nodes.find(n => n.type === "radio" && n.value === ${JSON.stringify(shelf)});
      const i = radio || nodes[0];
      if (radio) radio.checked = true; else i.value = ${JSON.stringify(shelf)};
      i.dispatchEvent(new Event("input", {bubbles: true}));
      i.dispatchEvent(new Event("change", {bubbles: true}));
      return true; })()`);
    /* the walk takes the choice before the press, as it does of a
       person: the form's beat and the door's answer are two requests */
    for (let i = 0; i < 40 && await rFrameCount() <= before; i++) await sleep(250);
    await evaljs(`document.querySelector("dialog[open] .dlgfoot button.primary").click(); true`);
  };
  const qRefused = `(document.querySelector("dialog[open] .problem")?.innerText || "")`;
  const qAccept = `document.querySelector("dialog[open] [data-surface='dialog.accept']")`;
  /* what the shelve door answered each time, for the timeout's trace: a
     submit closes the dialog on a 2xx and on nothing else */
  await evaljs(`(() => {
    const real = window.fetch;
    window.__shelved = [];
    window.fetch = async (...a) => {
      const res = await real(...a);
      if (String(a[0]).includes("/-/shelve"))
        window.__shelved.push([String(a[0]), res.status, a[1]?.body ?? null]);
      return res; };
    return true; })()`);
  await shelve("attic");
  await waitFor(`${qRefused}.includes("attic")`, "the attic's refusal", 15000);
  /* the offer is drawn after a read of the quests collection: give a
     wrong one the time to land */
  await sleep(1500);
  /* no dialog holds no offer either: the dialog still stands, with the
     attic's refusal in it */
  ok("the shelve dialog still stands with the attic's refusal",
     await evaljs(`${qRefused}.includes("attic")`));
  ok("a refusal without remedies offers no Accept as quest", await evaljs(`!${qAccept}`));
  await shelve("high");
  await waitFor(`${qRefused}.includes("room") && !!${qAccept}`,
                "the offer under the refusal", 15000,
                `({refusal_names_room: ${qRefused}.includes("room"),
                   button: !!${qAccept},
                   offer: document.querySelector("dialog[open] .questoffer")
                            ?.getAttribute("data-quest-offer") ?? null,
                   dialog_open: !!document.querySelector("dialog[open]"),
                   shelved: window.__shelved ?? null,
                   here: location.hash,
                   problem: ${qRefused}})`);
  ok("a refused door with remedies shows Accept as quest", true);
  await shot("accept-as-quest-offer");
  await evaljs(`${qAccept}.click(); true`);
  await waitFor(`!document.querySelector("dialog[open]") && !${qBar}.hidden &&
                 !!${qBar}.querySelector("[data-quest-title]")`,
                "the tracker, off the click", 15000);
  ok("the click closes the dialog and shows the tracker", true);
  await shot("accept-as-quest-tracker");
  const qMe = await evaljs(`viewerId()`);
  const qPinned = (await get("/api/quests?state=active&pinned=true&owner=" +
                             encodeURIComponent(qMe))).data?.items || [];
  ok("the click leaves one pinned quest", qPinned.length === 1);
  const qMade = (await get(qPinned[0].self)).data;
  ok("its self, action and input match the door",
     qMade.self === shelfNote.self && qMade.action === "shelve" &&
     JSON.stringify(qMade.input) === JSON.stringify({shelf: "high"}));
  ok("the tracker's title opens that quest",
     await evaljs(`${qBar}.querySelector("[data-quest-title]").getAttribute("href")`) ===
       "#" + qPinned[0].self);
  /* the finish the stream does not deliver: the owner's own goal write
     can finish the quest before the tracker reads again, so the stream
     is closed here and the read alone must say Quest complete */
  await evaljs(`sseClose(questStream); true`);
  ok("the engine finishes the accepted quest",
     (await qPost(qPinned[0].self + "/-/finish", {}, sys)).status < 400);
  await evaljs(`refreshQuest().catch(() => {}); true`);
  await waitFor(`!!${qBar}.querySelector("[data-quest-complete]")`,
                "the completion line, off the read alone", 15000);
  ok("a quest that finished unheard still says Quest complete", true);
  await waitFor(`${qBar}.hidden === true`, "the tracker to hide again", 15000);

  /* in a block of its own: the story after it names its own rNote, rWalk
     and rSeen */
  {
  /* the guided story's quest walk ("replay: a quest kept from a refusal,
     and its tracker") is a hand-written file. This one the engine
     recorded (walks.clj, `docs: true`): priya keeps the refused shelve as
     a quest and takes two steps from the tracker's Go, a second
     principal takes the first one again between them, when it is
     already done, and the goal door finishes the quest. Every plan is the planner's own, so the counts are read off
     the export's quest documents and none is written here. */
  console.log("· replay: a quest walk the engine recorded, with two principals");
  const rNote = (await qPost("/api/led_notes", {title: "Recorded pile"}, h)).doc;
  const rWalk = await evaljs(`(async () => {
    const r = await api("/api/walks", {method: "POST", body: JSON.stringify(
      {followed: principalId() || viewerId(),
       title: "A quest kept from a refusal, recorded", docs: true})});
    if (!r.ok || !r.body || !r.body.self) return null;
    recording = recordingOf(r.body);
    recordChip();
    /* the walk takes ui frames only while this tab shares, as Record's
       own start has it (startRecording, 200-events-follow.js) */
    if (!uiSharing()) {
      toggleShareUi();
      sessionStorage.setItem("wm10.record.shared", "1");
    }
    return r.body.self; })()`);
  ok("priya starts a self walk that carries its screens",
     !!rWalk && (await get(rWalk)).data.docs === true);
  const rPlan = `JSON.stringify(((questDoc || {}).data || {}).plan || null)`;
  /* the live tracker's head step is `door` on the note, after `done`
     steps, and Go is the owner's to press */
  const rGoFor = (door, done) => `(() => {
    const s = questDoc && questHead(questDoc);
    return !!s && s.door === ${JSON.stringify(door)} &&
      s.self === ${JSON.stringify(rNote.self)} &&
      questDoc.data.plan.filter(p => p.state === "done").length === ${done} &&
      !!${qBar}.querySelector("[data-surface='tracker.go']:not(:disabled)"); })()`;
  /* the planner's next plan of the quest, after the one planned at `was` */
  const rPlanned = async (self, was) => {
    for (let i = 0; i < 60; i++) {
      const at = (await get(self)).data.planned_at;
      if (at && at !== was) return at;
      await sleep(250);
    }
    return null;
  };
  /* a beat carries the form: each press waits for the one before it */
  await sleep(600);
  await evaljs(`location.hash = ${JSON.stringify(rNote.self)}; true`);
  await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(rNote.self)} &&
                 !!document.querySelector('[data-action="shelve"]')`,
                "the recorded note's row page", 15000);
  await sleep(600);
  await evaljs(`document.querySelector('[data-action="shelve"]').click(); true`);
  await waitFor(`!!document.querySelector('dialog[open] [name="shelf"]')`,
                "the shelve dialog, recorded", 15000);
  await sleep(600);
  await shelve("high");
  await waitFor(`${qRefused}.includes("room") && !!${qAccept}`,
                "the offer under the recorded refusal", 15000);
  await sleep(600);
  await evaljs(`${qAccept}.click(); true`);
  await waitFor(`!document.querySelector("dialog[open]") && !${qBar}.hidden &&
                 !!${qBar}.querySelector("[data-quest-title]")`,
                "the recorded quest's tracker", 15000);
  const rSelf = ((await get("/api/quests?state=active&pinned=true&owner=" +
                            encodeURIComponent(qMe))).data?.items || [])[0]?.self;
  ok("Accept as quest leaves the recorded quest pinned", !!rSelf);
  const rFirst = await rPlanned(rSelf, null);
  ok("the planner plans the recorded quest", !!rFirst);
  await waitFor(rGoFor("rename", 0), "the remedy at the tracker's head", 15000, rPlan);
  console.log("  the first plan: " + await evaljs(rPlan));
  await evaljs(`${qBar}.querySelector("[data-surface='tracker.go']").click(); true`);
  await waitFor(`!!document.querySelector('dialog[open] [name="title"]')`,
                "the remedy's dialog, from Go", 15000);
  await sleep(600);
  await evaljs(`(() => {
    const set = (name, v) => {
      const i = document.querySelector('dialog[open] [name="' + name + '"]');
      i.value = v;
      i.dispatchEvent(new Event("input", {bubbles: true}));
      i.dispatchEvent(new Event("change", {bubbles: true}));
    };
    /* a name and a room: the high shelf refuses no more, so the remedy
       is done and the goal door is the next step */
    set("title", "Recorded pile, sorted"); set("room", "Hall");
    return true; })()`);
  await sleep(600);
  await evaljs(`document.querySelector("dialog[open] .dlgfoot button.primary").click(); true`);
  await waitFor(`!document.querySelector("dialog[open]")`, "the remedy's dialog to close", 15000);
  ok("priya takes the first step from the tracker's Go",
     (await get(rNote.self)).data.title === "Recorded pile, sorted");
  /* the plan that answers priya's step lands first: a move committed
     before that plan is written counts as answered by it, and is no
     step of the walk's */
  await waitFor(rGoFor("shelve", 1), "the goal door at the tracker's head", 15000, rPlan);
  const rSecond = (await get(rSelf)).data.planned_at;
  ok("the planner plans again after priya's step", !!rSecond && rSecond !== rFirst);
  /* the remedy is done and its door stays open: the second principal
     takes it again. The plan that answers equals the one held, and it
     is written all the same (quests.clj, plan's `:replay false`), so
     the walk holds the move and the quest's document after it. */
  const rStep = await qPost(rNote.self + "/-/rename",
                            {title: "Recorded pile, filed", room: "Hall"}, sys);
  ok("a second principal takes the quest's done step again", rStep.status < 400);
  if (!(await rPlanned(rSelf, rSecond)))
    throw new Error("FAILED: the planner plans again after the second principal's step: " +
      JSON.stringify({step: rStep.status, note: (await get(rNote.self)).data,
                      plan: ((await get(rSelf)).data.plan || []).map(s => s.door + " " + s.state)}));
  ok("the planner plans again after the second principal's step", true);
  await waitFor(rGoFor("shelve", 1), "the goal door still at the tracker's head", 15000, rPlan);
  console.log("  the last plan: " + await evaljs(rPlan));
  await sleep(600);
  await evaljs(`${qBar}.querySelector("[data-surface='tracker.go']").click(); true`);
  await waitFor(`!!document.querySelector('dialog[open] [name="shelf"]')`,
                "the goal's dialog, from Go", 15000);
  await sleep(600);
  await shelve("high");
  await waitFor(`!document.querySelector("dialog[open]")`, "the goal's dialog to close", 15000,
                qRefused);
  /* the owner's own goal write, end to end: the live tracker says Quest
     complete whether it hears the finish or reads the held quest's row
     first. It is waited for before the row is asked, since the line
     stays only a few seconds. The finish is the consumer's, so the wait
     is as long as the tracker's own reads after Go, and a timeout says
     what the quest's row held: active is a late consumer, finished is a
     tracker that never said so. */
  await waitFor(`!!${qBar}.querySelector("[data-quest-complete]")`,
                "Quest complete, after priya's own goal write", 40000, rPlan)
    .catch(async e => {
      throw new Error(e.message + "; the quest's row is " + (await get(rSelf)).state);
    });
  ok("the tracker says Quest complete after the owner's own goal write", true);
  for (let i = 0; i < 60 && (await get(rSelf)).state !== "finished"; i++) await sleep(250);
  ok("the goal door finishes the recorded quest",
     (await get(rSelf)).state === "finished");
  /* the consumer hands the finished quest to the walk after the move */
  await sleep(1500);
  await evaljs(`stopRecording().then(() => true)`);
  await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(rWalk)} &&
                 !!document.querySelector("[data-replay-walk]")`,
                "the sealed recorded walk's page", 15000);
  ok("stop seals the recorded walk", (await get(rWalk)).state === "sealed");

  const rText = await (await fetch(BASE + rWalk + "/export", {headers: h})).text();
  const [rHeader, ...rFrames] = rText.trim().split("\n").map(l => JSON.parse(l));
  const rQuestDoc = f => f.type === "doc" && (f.doc || {}).kind === "quest";
  console.log("  the frames: " + rFrames.map(f =>
    f.type === "transition" ? `${f.kind}.${f.action}`
      : rQuestDoc(f) ? `doc(quest ${f.doc.state})` : f.type).join(" "));
  const rCreateAt = rFrames.findIndex(f =>
    f.type === "transition" && f.kind === "quest" && f.action === "create");
  ok("the export is the walk of the quest: its create, and its documents",
     rHeader.format === "waymark-walk/1" && rCreateAt >= 0 && rFrames.some(rQuestDoc));
  ok("the quest's first document is recorded after its create",
     rFrames.findIndex(rQuestDoc) > rCreateAt);
  const rOwner = rFrames[rCreateAt].who;
  const rForeignAt = rFrames.findIndex(f =>
    f.type === "transition" && f.who !== rOwner && f.self === rNote.self);
  ok("the second principal's step is a transition frame of the walk", rForeignAt >= 0);
  ok("the second principal's transition is recorded before the quest document it caused",
     rQuestDoc(rFrames.slice(rForeignAt + 1)
       .find(f => f.type === "transition" || f.type === "doc") || {}));
  const rOther = ((rHeader.cast || {})[rFrames[rForeignAt].who] || {}).display || "";
  /* what the tracker says of one quest document (questDraw, replayQuest) */
  const rSays = d => d.state === "finished" ? "complete"
    : !(d.state === "active" && (d.data || {}).pinned) ? null
    : !d.data.planned_at ? "planning"
    : `${(d.data.plan || []).filter(s => s.state === "done").length} done, ` +
      `${(d.data.plan || []).length} known so far`;
  const rWant = rFrames.filter(rQuestDoc).map(f => rSays(f.doc)).filter(Boolean);
  console.log("  the tracker, by the documents: " + rWant.join("; "));
  ok("the recorded documents end on the finished quest",
     rWant[rWant.length - 1] === "complete");
  /* each tracker line the replay drew, in order, is one the documents hold */
  const rFollows = seen => {
    let i = 0;
    for (const s of seen) {
      i = rWant.indexOf(s, i);
      if (i < 0) return false;
    }
    return seen.length > 1 && seen[seen.length - 1] === "complete";
  };
  /* the presses: Accept with no tracker yet, each Go on a count the
     documents hold, and each write on the count its Go had */
  const rPressed = presses => {
    if (presses.map(p => p.what).join() !== "accept,go,write,go,write") return false;
    const [accept, go1, write1, go2, write2] = presses;
    return accept.count === null &&
      [go1, go2].every(p => /known so far$/.test(p.count || "") && rWant.includes(p.count)) &&
      write1.count === go1.count && write2.count === go2.count;
  };
  const rRead = async name => JSON.parse(await evaljs(`JSON.stringify(window.${name})`));
  const rSeen = async () => (await rRead("__questSeen")).filter(s => s !== "hidden");
  const rLine = presses => presses.map(p => `${p.at} ${p.what} ${p.count}`).join("; ");
  const rWhy = `({presses: window.__questPresses, seen: window.__questSeen,
    at: replay && replay.at, film: document.documentElement.getAttribute("data-film"),
    state: document.querySelector("#replaychip")?.getAttribute("data-replay-state") ?? null})`;
  await evaljs(`(() => {
    window.__questPresses = []; window.__questReads = [];
    window.__questSeen = []; window.__questNotices = []; window.__questForms = [];
    const to0 = replayPointerTo, fetch0 = window.fetch;
    /* the tracker of a replay is its walk's: no quest is asked for */
    window.fetch = (u, o) => {
      if (replay && String(u).includes("/api/quests")) window.__questReads.push(String(u));
      return fetch0(u, o);
    };
    window.replayPointerTo = (to, speed) => {
      const what = ["sheet.accept", "dialog.accept"].includes(to.getAttribute("data-surface")) ? "accept"
        : to.getAttribute("data-surface") === "tracker.go" ? "go"
        : to.hasAttribute("data-replay-write") ? "write" : null;
      const last = window.__questPresses[window.__questPresses.length - 1];
      if (replay && what && !(last && last.at === replay.at && last.what === what))
        window.__questPresses.push({at: replay.at, what,
          count: document.querySelector("#questbar [data-quest-count]")?.textContent || null});
      return to0(to, speed);
    };
    /* each tracker line and each notice the replay shows, as it changes */
    setInterval(() => {
      if (!replay) return;
      const bar = document.querySelector("#questbar");
      const now = bar.hidden ? "hidden"
        : bar.querySelector("[data-quest-complete]") ? "complete"
        : bar.querySelector("[data-quest-planning]") ? "planning"
        : bar.querySelector("[data-quest-count]")?.textContent || "";
      const seen = window.__questSeen, notes = window.__questNotices;
      if (seen[seen.length - 1] !== now) seen.push(now);
      const note = String(replay.notice || "");
      if (note && notes[notes.length - 1] !== note) notes.push(note);
      /* the form the replay has open at each frame, for a failed press */
      const form = document.querySelector("dialog[open][data-guided]");
      const forms = window.__questForms;
      const mark = replay.at + " " + (form ? form.getAttribute("data-guided") : "none");
      if (forms[forms.length - 1] !== mark) forms.push(mark);
    }, 40);
    return true; })()`);
  await evaljs(`document.querySelector("[data-replay-walk]").click(); true`);
  await waitFor(`!!replay`, "the recorded walk's replay to start", 15000);
  ok("a replay hides the tracker until its walk has a quest", await evaljs(`${qBar}.hidden`));
  await waitFor(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended"`,
                "the recorded walk to end", 240000, rWhy);
  /* the last frame's tracker line is read by the watch above, 40 ms apart */
  await sleep(200);
  const rPresses = await rRead("__questPresses");
  console.log("  the presses: " + rLine(rPresses));
  console.log("  the tracker, as replayed: " + (await rSeen()).join("; "));
  ok("the replayed tracker follows the recorded documents, plan by plan, to Quest complete",
     rFollows(await rSeen()));
  /* a press the replay did not make: what the walk held up to the first
     Go, and the form the replay had open at each frame */
  if (!rPressed(rPresses)) {
    const tail = s => String(s || "").split("/").pop();
    console.log("  the frames to the first Go: " + rFrames.slice(0, 16).map((f, i) =>
      `${i}@${f.t} ${f.who || "-"} ` +
      (f.type === "transition" ? `${f.kind}.${f.action}`
        : f.type === "ui" ? `ui ${tail(f.self)} ` + ((f.ui || {}).dialog
            ? `form ${tail(f.ui.dialog.self)} ${f.ui.dialog.action}` : `no form`)
        : rQuestDoc(f) ? `doc quest goal ${tail((f.doc.data || {}).self)} ${(f.doc.data || {}).action}`
        : `${f.type} ${tail(f.self)}`)).join(" | "));
    console.log("  the forms, as replayed: " + (await rRead("__questForms")).join("; "));
  }
  ok("the create is pressed on Accept as quest, and each step's form is opened from the tracker's Go",
     rPressed(rPresses));
  ok("the second principal's step is a notice",
     !!rOther && (await rRead("__questNotices")).some(n => n.startsWith(rOther + ": ")));
  ok("the finished quest's document says Quest complete",
     await evaljs(`!${qBar}.hidden && !!${qBar}.querySelector("[data-quest-complete]")`));
  ok("replaying the recorded quest read no quest",
     (await rRead("__questReads")).join(", ") === "");
  await evaljs(`document.querySelector("[data-replay-stop]").click(); true`);
  await waitFor(`!replay && hereHref().split("?")[0] === ${JSON.stringify(rWalk)} &&
                 !!document.querySelector("[data-replay-walk]")`,
                "the replay to stop on the recorded walk's page", 15000);

  /* the same walk filmed: its own export, with no stub in between */
  console.log("· film: the recorded quest walk");
  await evaljs(`(() => {
    window.__questPresses = []; window.__questSeen = [];
    location.hash = "#" + ${JSON.stringify(rWalk)} + "?film=1";
    return true; })()`);
  await waitFor(`document.documentElement.getAttribute("data-film") === "playing" &&
                 getComputedStyle(${qBar}).display !== "none" &&
                 !!${qBar}.querySelector("[data-surface='tracker.go']:disabled")`,
                "the tracker in the recorded film", 240000, rWhy);
  ok("film mode keeps the recorded walk's tracker", true);
  await waitFor(`document.documentElement.getAttribute("data-film") === "ended"`,
                "the recorded film to end", 240000, rWhy);
  ok("the recorded film ends on the finished quest, after the same presses",
     await evaljs(`!!${qBar}.querySelector("[data-quest-complete]") &&
       getComputedStyle(${qBar}).display !== "none"`) &&
     rLine(await rRead("__questPresses")) === rLine(rPresses));
  /* out of film mode, and of the story's fetch and pointer */
  await evaljs(`location.hash = "/api/led_notes"; location.reload(); true`);
  await sleep(1200);
  await waitFor(`typeof hereHref === "function" &&
                 !document.documentElement.hasAttribute("data-film") &&
                 hereHref().split("?")[0] === "/api/led_notes"`,
                "the page out of film mode", 15000);
  }

  /* a recorded refusal, replayed (docs/spec-agent-demo-walks.md §2). The
     walk is the one priya recorded above, as the engine wrote it: the
     shelve form, a schema refusal with its field's message, then the
     guard's refusals, the last one kept as a quest. Replay draws
     each in the form (dlg.guidedRefuse): the message under its field,
     then the problem box with "Accept as quest" under it, which the
     pointer presses. The press is lit for a moment only, so the page is
     watched while the walk plays. */
  console.log("· replay: a recorded refusal, kept as a quest");
  /* the form's closing beat lands before the seal */
  await rSettled();
  ok("priya seals her walk", (await qPost(rWalkSelf + "/-/seal", {}, h)).status < 400);
  await evaljs(`${rShare}.click(); true`);
  await waitFor(`${rShare}.getAttribute("aria-pressed") === "false"`,
                "priya's share toggle off", 15000);
  const rWalk = await (await fetch(BASE + rWalkSelf + "/export", {headers: h})).text();
  const rFrames = rWalk.trim().split("\n").slice(1).map(l => JSON.parse(l));
  console.log("  recorded: " + rFrames.map(f => f.type).join());
  const rRefusals = rFrames.filter(f => f.type === "refusal" &&
    f.self === shelfNote.self && f.action === "shelve");
  ok("the walk holds the three refusals of priya's own writes", rRefusals.length === 3);
  const [rSchema, , rGuard] = rRefusals;
  const rFieldError = ((rSchema?.errors || {}).shelf || [])[0] || "";
  ok("the schema refusal carries its field's message", rFieldError !== "");
  const rDetail = rGuard?.detail || "";
  ok("the high shelf's refusal carries its sentence and its remedy",
     rDetail !== "" && (rGuard?.remedies || []).length > 0);
  ok("a quest's create by the same hand follows it",
     rFrames.slice(rFrames.indexOf(rGuard) + 1).some(f =>
       f.type === "transition" && f.who === rGuard?.who &&
       f.kind === "quest" && f.action === "create"));
  await evaljs(`(() => {
    const seen = window.__refused = {fieldError: "", box: "", offered: false, pressed: false};
    window.__refusedWatch = new MutationObserver(() => {
      const g = document.querySelector("dialog[open][data-guided]");
      if (!g) return;
      const under = g.querySelector('[data-srverr="shelf"]')?.textContent || "";
      if (under) seen.fieldError = under;
      const accept = g.querySelector(".questoffer [data-surface='dialog.accept']");
      if (!accept) return;
      seen.offered = true;
      seen.box = g.querySelector(".problem")?.innerText || seen.box;
      if (accept.hasAttribute("data-replay-press")) seen.pressed = true;
    });
    window.__refusedWatch.observe(document.body,
      {subtree: true, childList: true, attributes: true, characterData: true});
    return true; })()`);
  await evaljs(`(async () => {
    await replayFile(new File([${JSON.stringify(rWalk)}], "refusal.ndjson"));
    return true; })()`);
  await waitFor(`${replayState} === "ended"`, "the refusal's replay to reach its last frame",
                120000, `window.__refused`);
  const rSeen = await evaljs(`(() => {
    window.__refusedWatch.disconnect();
    return {...window.__refused, open: !!document.querySelector("dialog[open]")}; })()`);
  console.log("  seen during the replay: " + JSON.stringify(rSeen));
  /* a replay that drew something else says what the walk held from the
     last refused write on: each frame's time, type, door and form */
  const rOk = (name, cond) => ok(cond ? name : name + " (the walk: " +
    rFrames.slice(Math.max(0, rFrames.indexOf(rGuard) - 1))
      .map(f => f.t + " " + f.type + (f.action ? ":" + f.action : "") +
                (!f.ui ? "" : f.ui.dialog ? ":form" : ":bare")).join(", ") + ")", cond);
  rOk("a replayed schema refusal shows the field's message under its field",
      rFieldError !== "" && rSeen.fieldError.includes(rFieldError));
  rOk("a replayed refusal kept as a quest shows its problem box",
      rDetail !== "" && rSeen.box.includes(rDetail));
  rOk("with Accept as quest under it", rSeen.offered);
  rOk("the pointer presses Accept as quest", rSeen.pressed);
  rOk("the form closes after the press", !rSeen.open);
  await evaljs(`document.querySelector("[data-replay-stop]").click(); true`);
  await waitFor(`!${replayState} && !document.querySelector("dialog[open]")`,
                "the refusal's replay to stop");

  /* the same under a grant that admits quest and nothing else: the one
     live stream carries no row events there, so the tracker hears the
     quest on the quest's own event stream */
  console.log("· a pinned quest under a grant: the tracker hears its own row");
  const gTitle = "Clear the granted pile";
  const gPile = (await qPost("/api/led_notes", {title: "Granted pile"}, h)).doc;
  const gQuest = await qPost("/api/quests",
    {self: gPile.self, action: "finish", title: gTitle}, h);
  ok("priya accepts a second goal as a quest", gQuest.status === 201);
  const gSelf = gQuest.doc.self;
  ok("priya pins it", (await qPost(gSelf + "/-/pin", {}, h)).status < 400);
  let gBorn = null;
  for (let i = 0; i < 60 && !gBorn; i++) {
    if ((await get(gSelf)).data.planned_at) gBorn = true;
    else await sleep(250);
  }
  ok("the planner answers the create with a first plan", gBorn === true);
  const gGrant = await qPost("/api/grants",
    {audience: "priya", scope: [{kind: "quest", actions: []}],
     expires_at: new Date(Date.now() + 86400000).toISOString()}, sys);
  ok("a grant to priya admits quest and nothing else", gGrant.status === 201);
  const gId = gGrant.doc.self.split("/").pop();
  /* an offered grant is the audience's to accept; one born active
     refuses this, and what the page reads below is the proof either way */
  await qPost("/api/grants/" + gId + "/-/accept", {}, h);
  await evaljs(`localStorage.setItem("wm10.grant", ${JSON.stringify(gId)});
                location.reload(); true`);
  await sleep(1200);
  await waitFor(`!${qBar}.hidden &&
                 ${qBar}.querySelector("[data-quest-title]")?.textContent ===
                   ${JSON.stringify(gTitle)}`,
                "the tracker at page load, under the grant", 15000);
  const gScope = await evaljs(`(async () => ({
    quests: (await api("/api/quests")).ok,
    notes: (await api("/api/led_notes")).ok}))()`);
  ok("the page reads through the grant: quests and no notes",
     gScope.quests === true && gScope.notes === false);
  await evaljs(`window.__granted = true; true`);
  const gPlanned = await qPost(gSelf + "/-/plan",
    {plan: [{n: 1, door: "rename", self: gPile.self, whose: "person",
             note: "Name the pile.", state: "done"},
            {n: 2, door: "finish", self: gPile.self, whose: "seat",
             waiting_on: "Planner", state: "waiting"}],
     plan_is_estimate: true, waiting_on: "Planner"}, sys);
  ok("the engine plans the granted quest", gPlanned.status < 400);
  await waitFor(`${qCount} === "1 done, 2 known so far" &&
                 !!${qBar}.querySelector("[data-quest-waiting]")`,
                "the count under the grant", 15000);
  ok("under a grant a plan transition moves the count with no reload",
     await evaljs(`window.__granted === true`));
  ok("the engine finishes the granted quest",
     (await qPost(gSelf + "/-/finish", {}, sys)).status < 400);
  await waitFor(`!!${qBar}.querySelector("[data-quest-complete]") &&
                 ${qBar}.textContent.includes(${JSON.stringify(gTitle)})`,
                "the completion line under the grant", 15000);
  ok("under a grant finish shows Quest complete with the title",
     await evaljs(`window.__granted === true`));
  await evaljs(`localStorage.removeItem("wm10.grant"); location.reload(); true`);
  await sleep(1200);

  /* signed in the way a person is: a session cookie off the magic
     link, the dev box EMPTY. An open invitation addressed to that
     member offers "Take this step" on its row page and in its
     collection — the viewer is the engine's identity (viewerId), not
     the dev box's. */
  console.log("· signed in by session: an invitation addressed to me");
  const admin = {"x-waymark-principal": "admin", "x-waymark-actor-type": "system"};
  const post = async (path, body, headers) => {
    const res = await fetch(BASE + path, {method: "POST",
      headers: {"Content-Type": "application/json", ...headers},
      body: JSON.stringify(body)});
    const doc = await res.json().catch(() => null);
    if (res.status !== 201)
      throw new Error("POST " + path + ": " + res.status + " " + JSON.stringify(doc));
    return doc.self.split("/").pop();
  };
  const tok = "guest-tok-" + Date.now();
  const guest = await post("/api/members",
    {display: "Guest Viewer", actor_type: "agent", bind_token: tok}, admin);
  await post("/api/grants",
    {audience: guest,
     scope: [{kind: "invitation", actions: []}, {kind: "seat", actions: []},
             {kind: "ref_card", actions: []}],
     expires_at: new Date(Date.now() + 86400000).toISOString()}, admin);
  const note = "Restate the desk's charter in your own words.";
  const inv = await post("/api/invitations",
    {subject: guest, self: "/api/seats/" + sid, action: "restate",
     field: "charter", note}, h);

  await evaljs(`localStorage.removeItem("wm10.principal"); true`);
  await send("Page.navigate", {url: BASE + "/auth/guest?invite=" + encodeURIComponent(tok)});
  await sleep(1200);
  await send("Page.navigate", {url: BASE + "/api/-/ui"});
  await sleep(1200);
  await waitFor(`!!window.signedinPrincipal`, "the session's identity on well-known");
  ok("the dev box is empty and the viewer is the session's member",
     await evaljs(`$("#who").value === "" && viewerId() === ${JSON.stringify(guest)}`));

  /* the same card under the guest's grant, which admits ref_card and
     not ref_target: the reader cannot see the target, so the envelope
     names no ref, both values stay bare, and no read loops. */
  console.log("· ref labelling: a target the reader cannot see");
  const refUnseen = await evaljs(`(async () => {
    const card = await api(${JSON.stringify(refCard)});
    const target = await api(${JSON.stringify(refTarget)});
    return {card: card.ok, refs: !!(card.body || {}).refs, target: target.ok}; })()`);
  ok("the guest's grant shows the card and hides its target",
     refUnseen.card && !refUnseen.target);
  ok("so the card's envelope names no ref", !refUnseen.refs);
  await evaljs(refWatch);
  await evaljs(`location.hash = ${JSON.stringify(refCard)}; true`);
  await waitFor(`!!${refCell("target_id")} && !!${refCell("about")}`,
                "the card's row page under the guest's grant");
  const refHidden = await refReads(refTargetId);
  const refBare = await evaljs(`(() => {
    const plain = ${refCell("target_id")}, about = ${refCell("about")};
    const named = c => [...c.querySelectorAll("a")].filter(a =>
      a.getAttribute("href") === ${JSON.stringify("#" + refTarget)}).length;
    return {plain: plain.textContent.trim(), about: about.textContent.trim(),
            links: named(plain) + named(about)}; })()`);
  console.log("  the hidden target reads " + JSON.stringify(refBare) +
              ", reads of it: " + refHidden.count);
  ok("a plain ref to a hidden row keeps its token, not a name",
     !refBare.plain.includes(refSummary) &&
     refBare.plain.includes(refTargetId.slice(0, 8)));
  ok("a typed address to a hidden row stays its bare text",
     refBare.about === "ref_target:" + refTargetId);
  ok("neither links to the row the reader cannot see", refBare.links === 0);
  ok("and the page makes no failing GET loop",
     refHidden.count <= 2 && refHidden.still);
  await evaljs(refUnwatch);

  await evaljs(`location.hash = ${JSON.stringify("/api/invitations/" + inv)}; true`);
  await waitFor(`!!document.querySelector("[data-invite-open]")`,
                "Take this step on the invitation's row page");
  ok("the row page offers the signed-in viewer Take this step", true);

  await evaljs(`location.hash = "/api/invitations"; true`);
  await waitFor(`!![...document.querySelectorAll("tbody tr")]
    .find(r => r.textContent.includes(${JSON.stringify(note)}))
    ?.querySelector("[data-invite-open]")`,
                "Take this step on the invitation's collection row");
  ok("the collection offers the signed-in viewer Take this step", true);

  /* the same session, seen by another. This tab (A) is still the
     guest, the dev box empty; tab B is priya, in her own browser
     context. A's beat is gated on the engine's identity (220-boot.js
     presenceBeat, viewerId), so B's presence holds A, and A's
     share-my-screen `ui` frame reaches B in guided mode. The id is
     read off A's page: presence names the principal as the engine
     resolved it. */
  console.log("· signed in by session: seen by a second viewer");
  await send("Emulation.setFocusEmulationEnabled", {enabled: true});
  const pid = await evaljs(`window.signedinPrincipal.id`);
  ok("the session viewer stands on the invitations, the dev box still empty",
     await evaljs(`$("#who").value === "" && hereHref() === "/api/invitations"`));
  const chrome = await openChrome();
  const B = await chrome.openTab("priya");
  await B.call("Page.navigate", {url: BASE + "/api/-/ui"});
  await sleep(1200);
  await B.js(`localStorage.setItem("wm10.principal", "priya"); true`);
  await B.call("Page.navigate", {url: BASE + "/api/-/ui?follow=" +
    encodeURIComponent(pid) + "&follow_name=Guest"});
  await sleep(1200);
  await B.until(`!!document.querySelector("#sharebtn") && !!document.querySelector("#followchip")`,
                "priya's shell");
  const seen = `PRESENCE.get(${JSON.stringify(pid)})`;
  await B.until(`${seen}?.self === "/api/invitations"`,
                "the session viewer in priya's presence", 15000);
  ok("a session-signed-in viewer appears in a second viewer's presence", true);

  await evaljs(`document.querySelector("#sharebtn").click(); true`);
  await waitFor(`document.querySelector("#sharebtn").getAttribute("aria-pressed") === "true"`,
                "the session viewer's share toggle on");
  const guideMe = `[...document.querySelectorAll("#followchip button")]
    .find(b => b.textContent.startsWith("guide me"))`;
  await B.until(`!!${guideMe}`, "the guide-me offer");
  await B.js(`${guideMe}.click(); true`);
  await B.until(`!!document.querySelector("#followchip [data-guided-mark]")`, "the guided mark");
  await B.until(`${seen}?.ui?.collection?.self === "/api/invitations"`,
                "the session viewer's ui frame on priya's screen", 15000);
  ok("the session viewer's share-my-screen ui frame reaches a guided follower", true);
  await evaljs(`document.querySelector("#sharebtn").click(); true`);
  B.close();
  await chrome.close();
}

/* ════ invitation: one tap from the collection, and a decline ═════════
   Against waymark10.batch-a-dev/start-held-call!. ui_test pins the
   page's strings; this executes them: "Take this step" on an open
   invitation opens the invited row with the door's dialog, the
   suggested value in its field and marked, the field scrolled to, lit
   and focused with the author's note beside it — and the dialog's
   Decline moves another invitation to declined, read off the API. */
async function invitationStory() {
  const person = {"x-waymark-principal": "colton"};
  const call = async (method, path, body, headers) => {
    const res = await fetch(BASE + path,
      {method, headers: {"Content-Type": "application/json", ...headers},
       body: body ? JSON.stringify(body) : null});
    return {status: res.status, body: await res.json().catch(() => null)};
  };
  const idOf = r => r.body.self.split("/").pop();
  const must = (r, status, what) => {
    if (r.status !== status)
      throw new Error(what + ": " + r.status + " " + JSON.stringify(r.body));
    return r;
  };

  console.log("· seeding a meal on the list and two invitations onto it");
  const meal = idOf(must(await call("POST", "/api/meals",
    {name: "Invited soup " + Date.now(), themes: []}, person),
    201, "the person adds a meal"));
  must(await call("POST", "/api/meals/" + meal + "/-/accept", null, person),
       200, "the meal joins the list");
  const suggestion = "Simmer the stock an hour, then season.";
  const invite = async note => idOf(must(await call("POST", "/api/invitations",
    {subject: "colton", self: "/api/meals/" + meal, action: "update_recipe",
     field: "recipe", note, suggest: {recipe: suggestion}}, person),
    201, "the invitation \"" + note + "\""));
  const takeNote = "Write the soup's recipe here.";
  const declineNote = "Or say you would rather not write it.";
  const taken = await invite(takeNote);
  const declined = await invite(declineNote);
  const stateOf = async id => must(await call("GET", "/api/invitations/" + id,
    null, person), 200, "read the invitation").body.state;

  console.log("· boot + principal");
  await send("Page.navigate", {url: BASE + "/api/-/ui"});
  await sleep(1200);
  await evaljs(`localStorage.setItem("wm10.principal", "colton"); location.reload(); true`);
  await sleep(1200);

  /* the row's own "Take this step", found by the invitation's note */
  const takeButton = note => `[...document.querySelectorAll("tbody tr")]
    .find(r => r.textContent.includes(${JSON.stringify(note)}))
    ?.querySelector("[data-invite-open]")`;
  const dialog = `document.querySelector("dialog[open]")`;
  const field = `${dialog}?.querySelector('[name="recipe"]')`;

  console.log("· the invitation collection: take this step");
  await evaljs(`location.hash = "/api/invitations"; true`);
  await waitFor(`!!(${takeButton(takeNote)})`, "Take this step on the invitation's row");
  ok("an open invitation to the viewer offers Take this step", true);
  await evaljs(`${takeButton(takeNote)}.click(); true`);
  await waitFor(`!!(${field})`, "the invited door's dialog");
  ok("the invited row opens",
     await evaljs(`decodeURIComponent(location.hash).includes(${JSON.stringify("/api/meals/" + meal)})`));
  ok("with the invited door's dialog",
     await evaljs(`${dialog}.querySelector("h3").textContent.toLowerCase().includes("recipe")`));
  ok("the suggested value stands in the field",
     await evaljs(`${field}.value === ${JSON.stringify(suggestion)}`));
  ok("and is marked as a suggestion",
     await evaljs(`${field}.classList.contains("suggested-value")`));
  ok("the invited field is lit", await evaljs(`!!${field}.closest(".invited")`));
  ok("the author's note sits beside it",
     await evaljs(`(() => { const n = ${field}.closest(".invited").nextElementSibling;
       return !!n && n.matches("[data-invite-note]") &&
              n.textContent === ${JSON.stringify(takeNote)}; })()`));
  await waitFor(`(() => { const r = ${field}.closest(".invited").getBoundingClientRect();
    return r.top >= 0 && r.bottom <= innerHeight; })()`, "the invited field scrolled into view");
  ok("the invited field is scrolled into view", true);
  await waitFor(`document.activeElement === ${field}`, "the invited field focused");
  ok("and focused", true);
  await evaljs(`[...${dialog}.querySelectorAll(".dlgfoot button")]
    .find(b => b.textContent === "Cancel").click(); true`);
  await waitFor(`!${dialog}`, "the dialog closes on Cancel");
  ok("opening the step and cancelling leaves the invitation open",
     await stateOf(taken) === "open");

  console.log("· decline from the dialog");
  await evaljs(`location.hash = "/api/invitations"; true`);
  await waitFor(`!!(${takeButton(declineNote)})`, "Take this step on the second invitation");
  await evaljs(`${takeButton(declineNote)}.click(); true`);
  await waitFor(`!!${dialog}?.querySelector("[data-invite-decline]")`, "the dialog's Decline");
  ok("the invitation's dialog offers Decline", true);
  await evaljs(`${dialog}.querySelector("[data-invite-decline]").click(); true`);
  await waitFor(`!${dialog}`, "the dialog closes on Decline");
  const t0 = Date.now();
  while (await stateOf(declined) !== "declined" && Date.now() - t0 < 6000) await sleep(150);
  ok("Decline moves the invitation to declined", await stateOf(declined) === "declined");
}

/* ════ later: "Do this later" from a row's dialog ═════════════════════
   Against waymark10.batch-a-dev/start-later!. ui_test pins the page's
   strings; this executes them (docs/spec-scheduled-actions.md R-7.3): a
   ticket's Groom dialog schedules the groom for tomorrow 08:30 under
   the state rule; the ticket's page lists it; Reschedule moves it and
   Cancel removes it; and an *Only if…* row submits conditions the
   engine accepts. Every write is read back off the API. */
async function laterStory() {
  const person = {"x-waymark-principal": "colton"};
  const call = async (method, path, body, headers) => {
    const res = await fetch(BASE + path,
      {method, headers: {"Content-Type": "application/json", ...headers},
       body: body ? JSON.stringify(body) : null});
    return {status: res.status, body: await res.json().catch(() => null)};
  };
  const must = (r, status, what) => {
    if (r.status !== status)
      throw new Error(what + ": " + r.status + " " + JSON.stringify(r.body));
    return r;
  };
  const p = n => String(n).padStart(2, "0");
  /* tomorrow at h:m, as the picker writes it and as the instant it
     names: node and chromium read the one machine's zone */
  const tomorrow = (h, m) => {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    d.setHours(h, m, 0, 0);
    return {local: `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(h)}:${p(m)}`,
            instant: d.getTime()};
  };

  console.log("· seeding a draft ticket");
  const ticket = must(await call("POST", "/api/tickets",
    {title: "Later " + Date.now(), priority: 2}, person),
    201, "the person writes a ticket").body.self.split("/").pop();
  const pending = async () => must(await call("GET",
    "/api/scheduled_actions?state=scheduled", null, person),
    200, "read the scheduled actions").body.items
    .filter(i => ((i.fields || {}).target || {}).id === ticket);
  const settle = async (pred, what) => {
    const t0 = Date.now();
    for (;;) {
      const rows = await pending();
      if (pred(rows)) return rows;
      if (Date.now() - t0 > 6000) throw new Error("timed out waiting for " + what);
      await sleep(150);
    }
  };

  console.log("· boot + principal");
  await send("Page.navigate", {url: BASE + "/api/-/ui"});
  await sleep(1200);
  await evaljs(`localStorage.setItem("wm10.principal", "colton"); location.reload(); true`);
  await sleep(1200);

  const dialog = `document.querySelector("dialog[open]")`;
  const groom = `document.querySelector('button[data-action="groom"]')`;
  const openLater = async () => {
    await waitFor(`!!${groom}`, "the ticket's Groom");
    await evaljs(`${groom}.click(); true`);
    await waitFor(`!!${dialog}?.querySelector("[data-later]")`, "Do this later beside the submit");
    await evaljs(`${dialog}.querySelector("[data-later]").click(); true`);
    await waitFor(`!!${dialog}?.querySelector("[data-later-at]")`, "the time picker");
  };
  const schedule = async at => {
    await evaljs(`${dialog}.querySelector("[data-later-at]").value = ${JSON.stringify(at)}; true`);
    await evaljs(`${dialog}.querySelector("[data-later-go]").click(); true`);
    await waitFor(`!${dialog}`, "the dialog closes once it is scheduled");
  };

  console.log("· schedule a groom for tomorrow 08:30, under state");
  await evaljs(`location.hash = ${JSON.stringify("/api/tickets/" + ticket)}; true`);
  await openLater();
  ok("the dialog offers Do this later beside its submit", true);
  ok("the picker's zone is named",
     await evaljs(`${dialog}.querySelector("[data-later-zone]").textContent ===
                   Intl.DateTimeFormat().resolvedOptions().timeZone`));
  ok("the state rule is chosen, in plain words",
     await evaljs(`(() => { const r = ${dialog}.querySelector('input[name="later_validity"]:checked');
       return r.value === "state" &&
              r.closest("label").textContent.includes("As long as it is still draft"); })()`));
  const first = tomorrow(8, 30);
  await schedule(first.local);
  let rows = await settle(r => r.length === 1, "the scheduled groom");
  ok("the groom is stored under the state rule", rows[0].fields.validity === "state");
  ok("for tomorrow 08:30 in the person's zone",
     Date.parse(rows[0].fields.run_at) === first.instant);
  ok("and the ticket is not groomed yet",
     must(await call("GET", "/api/tickets/" + ticket, null, person),
          200, "read the ticket").body.state === "draft");

  console.log("· the ticket's page lists it; reschedule moves it; cancel removes it");
  const listed = `document.querySelector("[data-scheduled-row]")`;
  await waitFor(`!!${listed}`, "the pending scheduled action on the ticket's page");
  ok("the row's page lists its pending scheduled action",
     await evaljs(`${listed}.textContent.includes("groom")`));
  await evaljs(`${listed}.querySelector("[data-scheduled-reschedule]").click(); true`);
  await waitFor(`!!${dialog}?.querySelector('[name="run_at"]')`, "the reschedule dialog");
  const second = tomorrow(9, 15);
  await evaljs(`${dialog}.querySelector('[name="run_at"]').value = ${JSON.stringify(second.local)}; true`);
  await evaljs(`[...${dialog}.querySelectorAll(".dlgfoot button")].at(-1).click(); true`);
  await waitFor(`!${dialog}`, "the reschedule lands");
  rows = await settle(r => r.length === 1 &&
    Date.parse(r[0].fields.run_at) === second.instant, "the moved time");
  ok("reschedule moves it to tomorrow 09:15", true);
  const moved = rows[0].self;
  /* the page paints again after the move: cancel from the fresh row */
  await waitFor(`!!${listed}?.textContent.includes("09:15")`, "the page shows the moved time");
  await evaljs(`${listed}.querySelector("[data-scheduled-cancel]").click(); true`);
  await settle(r => r.length === 0, "the cancel");
  await waitFor(`!${listed}`, "the cancelled action leaves the page");
  ok("cancel removes it from the row's page", true);
  ok("and the scheduled action is cancelled",
     must(await call("GET", moved, null, person),
          200, "read the scheduled action").body.state === "cancelled");

  console.log("· Only if…: a condition from the collection's filter control");
  await openLater();
  await evaljs(`${dialog}.querySelector('input[name="later_validity"][value="conditions"]').click(); true`);
  const conds = `${dialog}.querySelector("[data-later-conds]")`;
  await waitFor(`!!${conds}.querySelector(".filterpanel")`, "the filter control in the dialog");
  await evaljs(`[...${conds}.querySelectorAll("button")]
    .find(b => b.textContent.startsWith("Filters")).click(); true`);
  await evaljs(`{ const sel = [...${conds}.querySelectorAll(".filterpanel select")]
      .find(s => [...s.options].some(o => o.textContent === "Priority"));
    sel.value = [...sel.options].find(o => o.textContent === "Priority").value;
    sel.dispatchEvent(new Event("change")); true }`);
  await waitFor(`!!${conds}.querySelector('[data-role=values] input[name="priority"]')`,
                "the priority value");
  await evaljs(`${conds}.querySelector('[data-role=values] input[name="priority"]').value = "2"; true`);
  await evaljs(`[...${conds}.querySelectorAll(".filterpanel button")]
    .find(b => b.textContent === "Apply").click(); true`);
  await waitFor(`!!${dialog}.querySelector('[data-later-cond="priority"]')`, "the condition's chip");
  ok("Only if… takes a row from the collection's filter control", true);
  await schedule(first.local);
  rows = await settle(r => r.length === 1, "the conditional groom");
  ok("the conditions are submitted, and the engine accepts them",
     rows[0].fields.validity === "conditions" &&
     (rows[0].fields.conditions || {}).priority === "2");
}

/* ── more tabs: each its own browser context, so each holds its own
   localStorage and cookies — two people in one chromium. → {openTab,
   close}; close disposes every context openTab made. ─────────────── */
async function openChrome() {
  /* one CDP socket per target: the browser's, and each tab's */
  const cdp = async url => {
    const sock = new WebSocket(url);
    await new Promise(res => sock.onopen = res);
    let n = 0;
    const waiting = new Map();
    sock.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
      if (m.method === "Runtime.exceptionThrown")
        consoleErrors.push(JSON.stringify(m.params.exceptionDetails.exception?.description
                                          || m.params.exceptionDetails.text));
      if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error")
        consoleErrors.push(m.params.args.map(a => a.value || a.description).join(" "));
    };
    const call = (method, params) => new Promise((res, rej) => {
      const id = ++n;
      waiting.set(id, m => m.error
        ? rej(new Error(method + ": " + JSON.stringify(m.error))) : res(m));
      sock.send(JSON.stringify({id, method, params: params || {}}));
    });
    return {call, close: () => sock.close()};
  };
  const version = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`)).json();
  const browser = await cdp(version.webSocketDebuggerUrl);
  const contexts = [];
  const openTab = async label => {
    const ctx = (await browser.call("Target.createBrowserContext")).result.browserContextId;
    contexts.push(ctx);
    const target = (await browser.call("Target.createTarget",
      {url: "about:blank", browserContextId: ctx, newWindow: true})).result.targetId;
    const c = await cdp(`ws://127.0.0.1:${DEBUG_PORT}/devtools/page/${target}`);
    await c.call("Runtime.enable");
    await c.call("Page.enable");
    /* a hidden tab parks its live stream (200-events-follow.js) */
    await c.call("Emulation.setFocusEmulationEnabled", {enabled: true});
    const js = async expr => {
      const r = await c.call("Runtime.evaluate",
        {expression: expr, awaitPromise: true, returnByValue: true});
      if (r.result.exceptionDetails)
        throw new Error(`${label}: eval failed: ` + JSON.stringify(r.result.exceptionDetails));
      return r.result.result.value;
    };
    /* `tell`, when given, is read on this tab at the timeout: what the
       page held, so a red run names where it stopped */
    const until = async (pred, what, ms = 8000, tell = null) => {
      const t0 = Date.now();
      while (Date.now() - t0 < ms) {
        if (await js(pred)) return true;
        await sleep(150);
      }
      const held = tell
        ? " — " + await js(tell).catch(e => "its state could not be read: " + e.message)
        : "";
      throw new Error(`${label}: timed out waiting for ${what}${held}`);
    };
    return {call: c.call, js, until, close: c.close};
  };
  const close = async () => {
    for (const ctx of contexts)
      await browser.call("Target.disposeBrowserContext", {browserContextId: ctx});
    browser.close();
  };
  return {openTab, close};
}

/* ════ guided follow: two people, two browser contexts ═══════════════
   Against waymark10.batch-a-dev/start! (the meal fixture on
   Postgres: the recipe door keeps a shared live draft, which a memory
   engine cannot store; the drive seeds through the API). ui-test
   pins the page's strings (ui-follow-offers-guided-mode,
   ui-sharing-is-off-by-default); this executes them, per
   docs/spec-guided-follow.md §2 and §3. Tab A (ada) turns on share my
   screen, filters the meals, focuses a row, pages, opens a dialog and
   types; tab B (bo) follows ada in guided mode and sees each land.
   Then the guards: the Access panel parks, a dialog bo opened is never
   replaced, and an invitation to bo opens in bo's own hand. Then a
   walkthrough by ada for bo (docs/spec-walkthrough.md §7 item 6): its
   agent step's `ui` frame shows on bo's screen under the chip. Each tab
   is its own browser context, so each holds its own localStorage —
   two principals in one chromium. */
async function guidedStory() {
  const tag = Date.now().toString(36);
  const call = async (method, path, body, pid) => {
    const res = await fetch(BASE + path,
      {method, headers: {"Content-Type": "application/json",
                         "x-waymark-principal": pid},
       body: body ? JSON.stringify(body) : null});
    return {status: res.status, body: await res.json().catch(() => null)};
  };
  const must = (r, status, what) => {
    if (r.status !== status)
      throw new Error(what + ": " + r.status + " " + JSON.stringify(r.body));
    return r;
  };

  console.log("· seeding three meals as ada, two of them on the list");
  const meals = [];
  for (const name of ["soup", "stew", "pie"])
    meals.push(must(await call("POST", "/api/meals",
      {name: `Guided ${name} ${tag}`, themes: ["guided"]}, "ada"),
      201, "ada creates a meal").body.self);
  for (const self of meals.slice(0, 2))
    must(await call("POST", self + "/-/accept", null, "ada"), 200, "ada accepts " + self);
  must(await call("GET", "/api/invitations", null, "bo"), 200,
       "this engine serves invitations");

  const chrome = await openChrome();
  const openTab = chrome.openTab;
  const boot = async (tab, pid, query) => {
    await tab.call("Page.navigate", {url: BASE + "/api/-/ui"});
    await sleep(1200);
    await tab.js(`localStorage.setItem("wm10.principal", ${JSON.stringify(pid)}); true`);
    await tab.call("Page.navigate", {url: BASE + "/api/-/ui" + (query || "")});
    await sleep(1200);
    await tab.until(`!!document.querySelector("#sharebtn") &&
                     document.querySelectorAll("nav a").length > 0`, "the shell");
  };
  const button = (scope, label) =>
    `[...document.querySelectorAll(${JSON.stringify(scope + " button")})]
       .find(b => b.textContent.startsWith(${JSON.stringify(label)}))`;
  const press = (tab, scope, label) => tab.js(`${button(scope, label)}.click(); true`);
  /* typing into ada's own dialog: the form shares on its input event */
  const type = (tab, value) => tab.js(`{
    const n = document.querySelector("dialog[open]:not([data-guided]) [name=recipe]");
    n.value = ${JSON.stringify(value)};
    n.dispatchEvent(new Event("input", {bubbles: true})); true }`);
  /* bo has read ada's frame carrying this recipe: the next check is
     about what bo's screen did with it, not whether it arrived. The
     recipe door keeps a shared live draft, and the registry reads the
     fields from that draft, so a beat can carry the draft's older
     values: the 10 s heartbeat is the latest one to carry the new */
  const arrived = (tab, recipe) => tab.until(
    `PRESENCE.get("ada")?.ui?.fields?.recipe === ${JSON.stringify(recipe)}`,
    `ada's frame (${recipe})`, 15000);
  /* the follower's side when a guided dialog never opened: the three
     ways openGuidedDialog and applyGuidedUi return with no retry of
     their own (a seq not past guidedSeq, followUi off when the read
     answers, another open dialog), and ada's last frame as bo holds it */
  const guidedState = `JSON.stringify({guidedSeq, guidedOpening, guidedDismissed,
    followUi, followId, here: hereHref(),
    adaSeq: PRESENCE.get("ada")?.seq ?? null,
    adaDialog: PRESENCE.get("ada")?.ui?.dialog ?? null,
    dialogs: [...document.querySelectorAll("dialog[open]")]
      .map(d => d.getAttribute("data-guided") ?? "not guided")})`;
  /* ada's dialog: update_recipe on the first on-list meal, from its
     row in the collection. A create dialog stands on the collection,
     and the registry keeps only a dialog on a row, so it never crosses */
  const recipeButton = `[...document.querySelectorAll(${JSON.stringify(
      `tr[data-self="${meals[0]}"] button`)})]
    .find(b => /^update recipe/i.test(b.textContent))`;
  const recipeKey = `${meals[0]} update_recipe`;
  const openRecipe = async () => {
    await A.until(`!!${recipeButton}`, "ada's recipe door on the row");
    await A.js(`${recipeButton}.click(); true`);
    await A.until(`!!document.querySelector("dialog[open] [name=recipe]")`, "ada's recipe form");
    await B.until(`!!document.querySelector(${JSON.stringify(
      `dialog[open][data-guided="${recipeKey}"]`)})`, "the guided dialog", 15000,
      guidedState);
  };

  console.log("· two tabs: ada shares, bo follows");
  const A = await openTab("ada"), B = await openTab("bo");
  await boot(A, "ada");
  await boot(B, "bo", "?follow=ada&follow_name=Ada");
  ok("both tabs are visible, so neither parks its live stream",
     await A.js(`document.visibilityState`) === "visible" &&
     await B.js(`document.visibilityState`) === "visible");
  ok("share my screen is off by default",
     await A.js(`document.querySelector("#sharebtn").getAttribute("aria-pressed")`) === "false");
  await A.js(`document.querySelector("#sharebtn").click(); true`);
  await A.until(`document.querySelector("#sharebtn").getAttribute("aria-pressed") === "true"`,
                "the share toggle on");
  ok("the toggle turns sharing on, for this tab only",
     await A.js(`sessionStorage.getItem("wm10.share.ui")`) === "1");
  await B.until(`!!${button("#followchip", "guide me")}`, "the guide-me offer");
  await press(B, "#followchip", "guide me");
  await B.until(`!!document.querySelector("#followchip [data-guided-mark]")`, "the guided mark");
  ok("guide me turns guided follow on and marks the chip",
     await B.js(`localStorage.getItem("wm10.follow.ui")`) === "1");

  console.log("· the collection, its query, the focused row");
  await A.js(`location.hash = "/api/meals"; true`);
  await B.until(`hereHref() === "/api/meals"`, "bo following ada to the meals");
  ok("the follower goes where the sharer goes", true);
  await A.js(`location.hash = "/api/meals?state=on_list"; true`);
  await B.until(`decodeURIComponent(location.hash) === "#/api/meals?state=on_list"`,
                "the shared filter");
  await B.until(`{ const rows = [...document.querySelectorAll("tbody tr[data-self]")]
                     .map(r => r.dataset.self);
                   rows.includes(${JSON.stringify(meals[0])}) &&
                   rows.includes(${JSON.stringify(meals[1])}) &&
                   !rows.includes(${JSON.stringify(meals[2])}) }`, "the on-list rows");
  ok("the collection query crosses: bo's screen filters as ada's does", true);
  const lit = `tr[data-self="${meals[0]}"] td.c-state`;
  await A.until(`!!document.querySelector(${JSON.stringify(lit)})`, "ada's row");
  await A.js(`document.querySelector(${JSON.stringify(lit)}).click(); true`);
  await B.until(`document.querySelector("tr[data-guided-focus]")?.dataset.self === ${JSON.stringify(meals[0])}`,
                "the lit row");
  ok("ada's focused row is lit on bo's screen, and only that row",
     await B.js(`document.querySelectorAll("tr[data-guided-focus]").length`) === 1);

  console.log("· a page of the collection");
  await A.js(`location.hash = "/api/meals?state=on_list&page%5Bsize%5D=1"; true`);
  await A.until(`[...document.querySelectorAll(".pager a")]
                   .some(a => a.textContent === "next →")`, "ada's next-page link");
  await A.js(`[...document.querySelectorAll(".pager a")]
    .find(a => a.textContent === "next →").click(); true`);
  await A.until(`/page\\[number\\]=2/.test(decodeURIComponent(location.hash))`, "ada on page 2");
  await B.until(`/page\\[number\\]=2/.test(decodeURIComponent(location.hash))`, "bo on page 2");
  await B.until(`(document.querySelector(".pager")?.textContent || "").includes("page 2")`,
                "bo's page 2, rendered");
  ok("the page crosses as page[number], the parameter the collection route reads",
     !(await B.js(`/[?&]page=/.test(decodeURIComponent(location.hash))`)));

  console.log("· the Access panel parks");
  await B.js(`location.hash = "access"; true`);
  await B.until(`hereHref() === "access"`, "bo on the Access panel");
  await A.js(`location.hash = "/api/meals"; true`);
  await B.until(`JSON.stringify(PRESENCE.get("ada")?.ui?.collection?.filter) === "{}"`,
                "ada's frame for the unfiltered meals");
  await sleep(500);
  ok("a follower on the Access panel is not moved", await B.js(`hereHref()`) === "access");
  await B.js(`location.hash = "/api/meals"; true`);
  await B.until(`hereHref() === "/api/meals" && !!${button("", "New meal")}`, "bo back on the meals");

  console.log("· ada's dialog, read-only on bo's screen");
  await openRecipe();
  const display = await B.js(`PRESENCE.get("ada")?.principal?.display || "ada"`);
  ok(`the guided dialog is marked "${display} is filling this in"`,
     await B.js(`document.querySelector("dialog[open][data-guided] [data-guided-note]")?.textContent`)
       === `${display} is filling this in`);
  ok("its inputs are disabled and Cancel is its only button",
     await B.js(`{ const g = document.querySelector("dialog[open][data-guided]");
       const inputs = [...g.querySelectorAll("input, select, textarea")];
       inputs.length > 0 && inputs.every(n => n.disabled) &&
       [...g.querySelectorAll(".dlgfoot button")].map(b => b.textContent).join() === "Cancel" }`));
  await type(A, "Guided gumbo");
  await B.until(`document.querySelector("dialog[open][data-guided] [name=recipe]")?.value
                 === "Guided gumbo"`, "the typed recipe", 15000);
  ok("the fields cross as ada types them", true);

  console.log("· a dialog bo opened is not replaced");
  await press(B, "dialog[open][data-guided] .dlgfoot", "Cancel");
  await B.until(`!document.querySelector("dialog[open]")`, "the guided dialog dismissed");
  await press(B, "", "New meal");
  await B.until(`!!document.querySelector("dialog[open]:not([data-guided]) input[name=name]")`,
                "bo's own create form");
  await type(A, "Guided gumbo, again");
  await arrived(B, "Guided gumbo, again");
  await sleep(500);
  ok("bo's own dialog stays open, live and empty, with no guided dialog beside it",
     await B.js(`{ const own = document.querySelector("dialog[open]:not([data-guided])");
       const n = own && own.querySelector("input[name=name]");
       !!n && !n.disabled && n.value === "" && !document.querySelector("dialog[data-guided]") }`));
  await press(B, "dialog[open] .dlgfoot", "Cancel");
  await type(A, "Guided gumbo, once more");
  await arrived(B, "Guided gumbo, once more");
  await sleep(500);
  ok("a guided dialog bo closed by hand is not reopened for the same step",
     await B.js(`!document.querySelector("dialog[open]")`));

  console.log("· an invitation to bo opens in bo's hand");
  await press(A, "dialog[open] .dlgfoot", "Cancel");
  await B.until(`!!PRESENCE.get("ada")?.ui && !PRESENCE.get("ada").ui.dialog`, "ada's dialog closed");
  await openRecipe();
  const note = `Write the recipe here, ${tag}.`;
  const inv = must(await call("POST", "/api/invitations",
    {subject: "bo", self: meals[1], action: "update_recipe", field: "recipe",
     note, suggest: {recipe: "Brown the roux."}}, "ada"),
    201, "ada invites bo to write the recipe").body;
  /* the invitations row's "Take this step" calls openInvitationRow;
     it is called here directly, because bo's screen in guided mode
     stands on ada's meals, not on bo's invitations */
  await B.js(`openInvitationRow({self: ${JSON.stringify(inv.self)}}); true`);
  await B.until(`!!document.querySelector("dialog[open] [data-invite-note]")`, "the invitation dialog");
  ok("the invitation opens in bo's hand, over ada's guided dialog",
     await B.js(`{ const open = document.querySelectorAll("dialog[open]");
       const t = open.length === 1 && open[0].querySelector("textarea[name=recipe]");
       !!t && !t.disabled && !open[0].hasAttribute("data-guided") }`));
  ok("the note stands by the invited field, and the suggestion is marked as one",
     await B.js(`{ const d = document.querySelector("dialog[open]");
       const t = d.querySelector("textarea[name=recipe]");
       d.querySelector("[data-invite-note]").textContent === ${JSON.stringify(note)} &&
       t.value === "Brown the roux." && t.classList.contains("suggested-value") }`));
  ok("bo's screen is on the invited row", await B.js(`hereHref()`) === meals[1]);
  await type(A, "Guided gumbo, while bo writes");
  await arrived(B, "Guided gumbo, while bo writes");
  await sleep(500);
  ok("ada's next frame neither replaces bo's dialog nor moves bo's screen",
     await B.js(`!!document.querySelector("dialog[open] [data-invite-note]") &&
       !document.querySelector("dialog[data-guided]") &&
       hereHref() === ${JSON.stringify(meals[1])}`));

  /* a walkthrough's agent step (docs/spec-walkthrough.md §5, watching
     an agent step): ada leads bo through two steps on the stew, hers
     first. Start follows the author in guided mode, bo's screen goes to
     the step's row, and what ada shares shows there under the chip. */
  console.log("· a walkthrough's agent step: bo watches ada work");
  await press(B, "dialog[open] .dlgfoot", "Cancel");
  await press(A, "dialog[open] .dlgfoot", "Cancel");
  /* bo follows ada's gaze only, so Start is what turns guided mode on */
  await B.js(`document.querySelector("#followchip [data-guided-mark]").click(); true`);
  await B.until(`localStorage.getItem("wm10.follow.ui") === null`,
                "guided mode off before the start");
  const agentNote = "Watch me: writing the recipe.";
  const led = must(await call("POST", "/api/walkthroughs",
    {subject: "bo", title: `Writing the stew ${tag}`,
     steps: [{who: "agent", self: meals[1], note: agentNote},
             {who: "person", self: meals[1], action: "update_recipe",
              fields: ["recipe"], note: "Now you write the rest."}]}, "ada"),
    201, "ada offers bo a walkthrough").body;
  const ledId = led.self.split("/").pop();
  /* each door is taken through the page of the one who holds it */
  const takeDoor = (tab, name) => tab.js(
    `api(${JSON.stringify(led.self)})
       .then(r => invokeBare(r.body.actions[${JSON.stringify(name)}], r.body))
       .then(r => r.ok)`);
  ok("bo starts the walkthrough", await takeDoor(B, "start"));
  await B.until(`walkthroughId === ${JSON.stringify(ledId)} && followId === "ada" &&
                 localStorage.getItem("wm10.follow.ui") === "1"`,
                "the walkthrough in hand and its author followed in guided mode", 15000);
  ok("Start follows the author in guided mode", true);
  await A.js(`location.hash = ${JSON.stringify(meals[1])}; true`);
  /* the stew's own door, not a row's: ada comes from the collection, and
     its rows carry the same door for another meal until the page is drawn */
  const stewButton = `[...document.querySelectorAll("button")]
    .find(b => !b.closest("dialog") && !b.closest("tr[data-self]") &&
               /^update recipe/i.test(b.textContent))`;
  await A.until(`hereHref() === ${JSON.stringify(meals[1])} && !!${stewButton}`,
                "ada's recipe door on the stew's page");
  await A.js(`${stewButton}.click(); true`);
  await A.until(`!!document.querySelector("dialog[open] [name=recipe]")`, "ada's recipe form");
  await B.until(`!!document.querySelector(${JSON.stringify(
    `dialog[open][data-guided="${meals[1]} update_recipe"]`)})`,
                "ada's dialog on bo's screen", 15000, guidedState);
  ok("an agent step's ui frame shows on the subject's screen under the chip",
     await B.js(`{ const chip = document.querySelector("#walkchip");
       chip.style.display !== "none" && chip.textContent.includes("Step 1 of 2") &&
       chip.textContent.includes(${JSON.stringify(" is working: " + agentNote)}) &&
       !chip.querySelector("[data-walk-skip]") && !!chip.querySelector("[data-walk-stop]") &&
       hereHref() === ${JSON.stringify(meals[1])} }`));
  /* ada ends her step with her dialog still open: bo's step opens over it */
  ok("ada advances her own step", await takeDoor(A, "advance"));
  await B.until(`document.querySelector("dialog[open] [data-walk-step]")
                   ?.textContent.startsWith("Step 2 of 2")`,
                "step 2's dialog on bo's screen", 15000);
  ok("the next person step opens in bo's hand, over ada's guided dialog",
     await B.js(`{ const open = document.querySelectorAll("dialog[open]");
       const t = open.length === 1 && open[0].querySelector("textarea[name=recipe]");
       !!t && !t.disabled && !open[0].hasAttribute("data-guided") }`));
  await press(B, "dialog[open] .dlgfoot", "Cancel");
  must(await call("POST", led.self + "/-/withdraw", null, "ada"), 200,
       "ada withdraws the walkthrough");
  await B.until(`!walkthroughId && document.querySelector("#walkchip").style.display === "none"`,
                "the walkthrough out of bo's hand", 15000);
  ok("the follow bo already held stands when the walkthrough leaves his hand",
     await B.js(`followId === "ada" && localStorage.getItem("wm10.follow.ui") === "1"`));

  /* the quest's sheet (docs/spec-guided-follow.md § 2): a plan with an
     uncovered day keeps Finalize shut, and names assign_meal as its way
     out (fixtures.clj, all-days-covered-gate). Ada taps it, and bo's
     screen shows her sheet with the same steps, read-only. */
  console.log("· the quest's sheet of a shut door");
  await press(A, "dialog[open] .dlgfoot", "Cancel");
  await A.until(`!document.querySelector("dialog[open]")`, "ada's recipe form closed");
  await B.until(`!document.querySelector("dialog[open]")`,
                "ada's dialog off bo's screen", 15000, guidedState);
  const plan = must(await call("POST", "/api/plans",
    {start_date: "2026-07-14", weeks: 1,
     days: [{date: "2026-07-14"}, {date: "2026-07-15"}]}, "ada"),
    201, "ada creates a plan with no meal on its days").body.self;
  const shutDoor = '#view [data-surface="door-shut:finalize"]';
  await A.js(`location.hash = ${JSON.stringify(plan)}; true`);
  await A.until(`hereHref() === ${JSON.stringify(plan)} &&
                 !!document.querySelector(${JSON.stringify(shutDoor)})`,
                "the plan's page, with its shut Finalize", 15000,
                `[...document.querySelectorAll("#view button")].map(b => b.outerHTML.slice(0, 160))`);
  await B.until(`hereHref() === ${JSON.stringify(plan)}`,
                "bo following ada to the plan", 15000, guidedState);
  await sleep(600);
  await A.js(`document.querySelector(${JSON.stringify(shutDoor)}).click(); true`);
  const questSheetOpen = `document.querySelector("dialog[open][data-surface='sheet']")`;
  await A.until(`!!${questSheetOpen}`, "ada's quest sheet", 15000,
                `document.body.innerText.slice(-400)`);
  await B.until(`!!document.querySelector("dialog[open][data-surface='sheet'][data-guided-quest]")`,
                "ada's quest sheet on bo's screen", 15000, guidedState);
  const sheetSays = `(() => { const g = ${questSheetOpen};
    return JSON.stringify({
      goal: g.querySelector("[data-quest-sheet-goal]").textContent,
      title: g.querySelector("[data-quest-title-line]").textContent,
      why: g.querySelector("[data-quest-why]").textContent,
      steps: [...g.querySelectorAll("[data-quest-steps] li")].map(li => li.textContent),
      blocked: g.querySelector("[data-quest-blocked]")?.textContent ?? null,
      refused: g.querySelector("[data-quest-refused]").textContent}); })()`;
  const hers = await A.js(sheetSays), his = await B.js(sheetSays);
  console.log("  ada's sheet: " + hers);
  if (hers !== his) console.log("  bo's sheet:  " + his);
  ok("bo's screen shows ada's quest sheet, with the same goal, reason and steps",
     hers === his);
  ok("the engine found steps to the shut door, and both sheets list them",
     JSON.parse(hers).steps.length > 0);
  ok("the sheet is read-only on bo's screen: it says whose it is, and Accept is disabled",
     await B.js(`{ const g = ${questSheetOpen};
       (g.querySelector("[data-guided-note]")?.textContent || "").endsWith(" has this open") &&
       g.querySelector("[data-surface='sheet.accept']").disabled === true }`));
  ok("ada's own Accept stays live",
     await A.js(`${questSheetOpen}.querySelector("[data-surface='sheet.accept']").disabled`) === false);
  /* a second tab of ada's reads another row: her gaze moves and no new
     `ui` beat is made, so the server says the carried frame again under
     its old seq (presence/publish!). That frame takes the move's close
     back on bo's page, and the sheet he holds is the one he held: the
     mark tells a sheet that stayed from one closed and drawn again. */
  const A2 = await openTab("ada's second tab");
  await boot(A2, "ada");
  let a2Reads = 0;
  const heldAcrossMove = async () => {
    const meal = meals[a2Reads++ % meals.length];
    await B.until(`!!document.querySelector("dialog[open][data-quest-sheet][data-guided-quest]")`,
                  "ada's quest sheet on bo's screen, before her move", 15000, guidedState);
    await B.js(`document.querySelector("dialog[open][data-guided-quest]")
      .dataset.heldAcrossMove = "1"; true`);
    await A2.js(`location.hash = ${JSON.stringify(meal)}; true`);
    await A2.until(`hereHref() === ${JSON.stringify(meal)}`,
                   "the meal's page on ada's second tab", 15000);
    await B.until(`PRESENCE.get("ada")?.self === ${JSON.stringify(meal)}`,
                  "ada's move to the meal, on bo's page", 15000, guidedState);
    await sleep(await B.js(`GUIDED_MOVE_MS`) + 350);
    return await B.js(`!!document.querySelector(
      "dialog[open][data-guided-quest][data-held-across-move]")`);
  };
  /* first on a page without the line that takes the close back: the
     case must fail there, or it does not test that line. Bo's page gets
     applyGuidedUi again from its own source with the line cut. Ada's
     10 s heartbeat carries a new seq and takes the close back when it
     lands inside the wait, so the move is given again; the same
     heartbeat draws her sheet on bo's screen again after a close. */
  const takesBack = "if (f.seq === guidedSeq) clearTimeout(guidedMoveTimer);";
  const applySrc = await B.js(`applyGuidedUi.toString()`);
  ok("applyGuidedUi has the line that takes a move's close back",
     applySrc.includes(takesBack));
  await B.js(`window.applyGuidedUiWhole = applyGuidedUi;
    applyGuidedUi = ${applySrc.replace(takesBack, "")}; true`);
  let heldWithout = true;
  for (let i = 0; i < 3 && heldWithout; i++) heldWithout = await heldAcrossMove();
  await B.js(`applyGuidedUi = window.applyGuidedUiWhole; true`);
  ok("without that line, the same move closes her sheet on bo's screen",
     !heldWithout);
  ok("a move of ada's with no new ui beat leaves her sheet open on bo's screen",
     await heldAcrossMove());
  /* the second tab leaves: its beats would move ada's gaze under the
     cases below */
  await A2.call("Page.navigate", {url: "about:blank"});
  A2.close();
  /* a `ui` beat whose every part was redacted for bo reaches his page as
     a plain move (presence/ui-redactor): it closes the guided sheet.
     Ada's own 10 s heartbeat carries her sheet again and takes the close
     back when it lands inside the wait, so the move is given again. */
  let movedShut = false;
  for (let i = 0; i < 3 && !movedShut; i++) {
    await B.js(`onPresenceFrame({event: "presence", data: {event: "move",
      principal: {id: followId, display: followName},
      self: ${JSON.stringify(plan)}, at: new Date().toISOString()}}); true`);
    await sleep(600);
    movedShut = await B.js(`!document.querySelector("dialog[open][data-guided-quest]")`);
  }
  ok("a move of ada's, which a wholly redacted beat becomes, closes her sheet on bo's screen",
     movedShut);
  await A.js(`document.querySelector("dialog[open] [data-quest-decline]").click(); true`);
  await A.until(`!document.querySelector("dialog[open]")`, "ada's sheet closed, off Not now");
  await B.until(`!document.querySelector("[data-surface='sheet']")`,
                "ada's sheet off bo's screen", 15000, guidedState);
  ok("bo's sheet closes with ada's", true);

  console.log("· stopping");
  await A.js(`document.querySelector("#sharebtn").click(); true`);
  ok("the toggle turns sharing off", await A.js(`sessionStorage.getItem("wm10.share.ui")`) === null);
  await B.js(`document.querySelector("#followchip [data-guided-mark]").click(); true`);
  await B.until(`localStorage.getItem("wm10.follow.ui") === null &&
                 !document.querySelector("#followchip [data-guided-mark]")`, "guided mode off");
  ok("the guided mark turns guided mode off, and the follow stands",
     await B.js(`followId`) === "ada");
  B.close();

  console.log("· record: ada's own screen, with nobody following");
  const recordBtn = `document.querySelector("#recordbtn")`;
  const recordingOn = `${recordBtn}.getAttribute("aria-pressed") === "true" &&
    ${recordBtn}.textContent.startsWith("■ Stop")`;
  const frameCount = async () =>
    must(await call("GET", walkSelf, null, "ada"), 200, "ada reads the walk").body.data.frame_count;
  ok("the record button is off before a recording",
     await A.js(`${recordBtn}.getAttribute("aria-pressed")`) === "false");
  await A.js(`window.prompt = () => "Ada writes a recipe"; ${recordBtn}.click(); true`);
  await A.until(recordingOn, "the recording to start");
  const walkSelf = await A.js(`recording.self`);
  ok("record starts a walk of ada by ada, and turns sharing on for this tab",
     await A.js(`sessionStorage.getItem("wm10.share.ui")`) === "1" &&
     await (async () => {
       const d = must(await call("GET", walkSelf, null, "ada"), 200, "ada reads the walk").body;
       return d.state === "recording" && d.data.recorder === "ada" && d.data.followed === "ada";
     })());
  await boot(A, "ada");
  await A.until(`${recordingOn} && recording.self === ${JSON.stringify(walkSelf)}`,
                "the recording to come back after the reload");
  ok("reloading the tab keeps recording", true);
  await A.js(`location.hash = "/api/meals"; true`);
  await A.until(`!!${recipeButton}`, "ada's recipe door on the row");
  const before = await frameCount();
  await A.js(`${recipeButton}.click(); true`);
  await A.until(`!!document.querySelector("dialog[open] [name=recipe]")`, "ada's recipe form");
  let count = before;
  for (let i = 0; i < 30 && count <= before; i++) {
    await sleep(500);
    count = await frameCount();
  }
  ok("the walk takes ada's dialog with no follower stream open", count > before);
  await A.js(`${recordBtn}.click(); true`);
  await A.until(`hereHref() === ${JSON.stringify(walkSelf)} &&
                 ${recordBtn}.getAttribute("aria-pressed") === "false"`, "the sealed walk's page");
  ok("stop seals the walk, opens its page and turns sharing off again",
     must(await call("GET", walkSelf, null, "ada"), 200, "ada reads the walk").body.state === "sealed" &&
     await A.js(`sessionStorage.getItem("wm10.share.ui")`) === null);
  await press(A, "dialog[open] .dlgfoot", "Cancel");
  await A.until(`!document.querySelector("dialog[open]") &&
                 !!document.querySelector("[data-replay-walk]")`, "Replay on the walk's page");
  await A.js(`document.querySelector("[data-replay-walk]").click(); true`);
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended"`,
                "the replay to reach its last frame", 60000);
  ok("the sealed walk replays the dialog ada opened, read-only",
     await A.js(`{ const g = document.querySelector(${JSON.stringify(
         `dialog[open][data-guided="${recipeKey}"]`)});
       const inputs = g ? [...g.querySelectorAll("input, select, textarea")] : [];
       inputs.length > 0 && inputs.every(n => n.disabled) }`));
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· replay: a walk file's invitation frame");
  await A.until(`!replay && !document.querySelector("dialog[open]")`, "the replay to stop");
  const replayNote = `Write the recipe here, ${tag}, in the replay.`;
  const walkFile = [
    {format: "waymark-walk/1", title: "Ada invites Bo",
     cast: {a1: {display: "Ada", type: "human"}, p1: {display: "Bo", type: "human"}}},
    {t: 0, type: "move", who: "a1", self: meals[1]},
    {t: 100, type: "invitation", who: "a1", subject: "p1", self: meals[1],
     action: "update_recipe", field: "recipe", note: replayNote,
     suggest: {recipe: "Brown the roux."}},
    {t: 200, type: "ui", who: "a1", self: "/api/meals", ui: {}},
    {t: 5000, type: "transition", who: "p1", kind: "meal", self: meals[1],
     action: "update_recipe", from: "on_list", to: "on_list",
     at: new Date().toISOString(), summary: `Guided stew ${tag}`},
  ].map(l => JSON.stringify(l)).join("\n");
  /* every request the page makes while the file plays that is not a read */
  await A.js(`{ window.__replayWrites = [];
    const f0 = window.fetch;
    window.fetch = (u, o) => {
      if (o && o.method && o.method !== "GET") window.__replayWrites.push(o.method + " " + u);
      return f0(u, o);
    }; true }`);
  await A.js(`startReplay(${JSON.stringify(walkFile)})`);
  const invite = `document.querySelector("dialog[open][data-replay-invite]")`;
  /* the frame after the invitation is ada's screen elsewhere, 3 s
     before the answer: the replay is paused there for the checks */
  await A.until(`!!${invite} && replay.at === 3`, "the invitation's dialog, and the frame after it", 15000);
  await A.js(`pauseReplay(); true`);
  ok("the replay opens the invited door's dialog on the invited row, read-only, and a later frame leaves both",
     await A.js(`{ const inputs = [...${invite}.querySelectorAll("input, select, textarea")];
       hereHref() === ${JSON.stringify(meals[1])} &&
       inputs.length > 0 && inputs.every(n => n.disabled) }`));
  ok("the invited field is highlighted, the note stands beside it, and the suggestion is marked as one",
     await A.js(`{ const g = ${invite};
       const t = g.querySelector("[name=recipe]");
       const spot = t.closest("label") || t.parentElement;
       const n = g.querySelector("[data-invite-note]");
       spot.classList.contains("invited") && spot.nextElementSibling === n &&
       n.textContent === ${JSON.stringify(replayNote)} &&
       t.value === "Brown the roux." && t.classList.contains("suggested-value") }`));
  ok("the replayed invitation offers neither Decline nor submit",
     await A.js(`{ const b = [...${invite}.querySelectorAll(".dlgfoot button")];
       b.length === 1 && b[0].textContent === "Cancel" }`));
  await A.js(`playReplay(); true`);
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended" &&
                 !!document.querySelector('[data-replay-transition="update_recipe"]')`,
                "the answering transition on the row", 15000);
  ok("the transition that answers the invitation closes its dialog",
     await A.js(`!document.querySelector("dialog[open]")`));
  ok("replaying the file made no write",
     (await A.js(`window.__replayWrites.join(", ")`)) === "");
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· replay: a staged call's write");
  await A.until(`!replay && !document.querySelector("dialog[open]")`, "the replay to stop");
  const stagedDialog = {self: meals[1], action: "update_recipe"};
  const stagedFile = [
    {format: "waymark-walk/1", title: "An agent writes a recipe",
     cast: {a1: {display: "Ada's agent", type: "agent"}}},
    {t: 0, type: "move", who: "a1", self: meals[1]},
    {t: 10, type: "ui", who: "a1", self: meals[1], ui: {dialog: stagedDialog, fields: {}}},
    {t: 20, type: "ui", who: "a1", self: meals[1],
     ui: {dialog: stagedDialog, fields: {recipe: "Brown the roux."}}},
    {t: 30, type: "transition", who: "a1", kind: "meal", self: meals[1],
     action: "update_recipe", from: "on_list", to: "on_list",
     at: new Date().toISOString(), summary: `Guided stew ${tag}`},
    {t: 40, type: "ui", who: "a1", self: meals[1], ui: {dialog: null}},
  ].map(l => JSON.stringify(l)).join("\n");
  /* every glide the pointer starts from here on: how long the screen it
     leaves had been still, and the stillness it owed at that speed. A
     hop's list and a walk's row owe the screen floor. */
  await A.js(`{ window.__replayGlides = []; let changed = 0, drawn = false;
    const apply0 = applyReplayFrame, hop0 = replayHop, arrive0 = replayArrive,
          to0 = replayPointerTo;
    window.applyReplayFrame = f => {
      const i = replay ? replay.frames.indexOf(f) : -1;
      const out = apply0(f);
      if (replay && f.type !== "doc" && !replayStill(replay.frames, i)) {
        changed = performance.now(); drawn = false;
      }
      return out;
    };
    window.replayHop = r => { changed = performance.now(); drawn = true; return hop0(r); };
    window.replayArrive = r => { changed = performance.now(); drawn = true; return arrive0(r); };
    window.replayPointerTo = (to, speed) => {
      if (replay && changed) window.__replayGlides.push({at: replay.at,
        still: Math.round(performance.now() - changed),
        floor: (drawn ? REPLAY_MIN_STILL
                      : replayHoldTime(replay.frames, replay.at)) / replay.speed});
      return to0(to, speed);
    };
    true }`);
  /* at half speed the press lasts 600 ms, which the 150 ms poll cannot miss */
  await A.js(`startReplay(${JSON.stringify(stagedFile)}) && (setReplaySpeed(0.5), true)`);
  /* no jump: the stew's link is not on the walk's page, so the pointer
     takes the path a person would. The replay is paused at each press
     for its check, and makes that gesture again when it plays on. */
  const navPress = `document.querySelector("#kinds a[data-replay-press]")`;
  await A.until(`!!${navPress} && (pauseReplay(), true)`, "the pressed navigation entry", 15000);
  ok("a replayed move to a row that is not on screen presses its kind's navigation entry first",
     await A.js(`${navPress}.getAttribute("href").split("?")[0] === "#/api/meals" &&
       replay.at === 0 && hereHref() !== ${JSON.stringify(meals[1])}`));
  await A.js(`playReplay(); true`);
  const rowPress = `document.querySelector(${JSON.stringify(
    `#view a[href="#${meals[1]}"][data-replay-press]`)})`;
  await A.until(`!!${rowPress} && (pauseReplay(), true)`, "the pressed row link in the list", 15000);
  ok("the list is drawn, and the row's link is pressed there before the move is made",
     await A.js(`hereHref() === "/api/meals" && replay.at === 0`));
  await A.js(`playReplay(); true`);
  const pressed = `document.querySelectorAll("#view button[data-replay-press]")`;
  /* the dialog waits for the gesture: the replay is paused there for the check */
  await A.until(`${pressed}.length === 1 && (pauseReplay(), true)`, "the lit action button", 15000);
  ok("a replayed staged call lights one action button, under the pointer, before its dialog opens",
     await A.js(`{ const b = ${pressed};
       b.length === 1 && b[0].dataset.action === "update_recipe" &&
       b[0].classList.contains("invited") && replay.at === 1 &&
       !!document.querySelector("#replaypointer") && !document.querySelector("dialog[open]") }`));
  await A.js(`playReplay(); true`);
  /* the pointer fills the form: still at half speed, the value waits
     for the click, and the replay is paused there for the check */
  const clicked = `document.querySelector("dialog[open][data-guided] [data-replay-click]")`;
  await A.until(`!!${clicked} && (pauseReplay(), true)`, "the clicked field", 15000);
  ok("the pointer goes into the form and clicks the field before its value shows",
     await A.js(`{ const t = document.querySelector("dialog[open][data-guided] [name=recipe]");
       const c = ${clicked};
       (c === t || c.contains(t)) && t.value === "" && t.disabled && replay.at === 2 &&
       !!document.querySelector("dialog[open][data-guided] #replaypointer") }`));
  await A.js(`setReplaySpeed(1); playReplay(); true`);
  const written = `document.querySelectorAll("dialog[open][data-guided] .dlgfoot [data-replay-write]")`;
  /* the close waits for the press: the replay is paused on its way, and
     the pointer still arrives and presses, for the check */
  await A.until(`${written}.length === 1 && (pauseReplay(), true)`, "the submit under the pointer", 15000);
  await A.until(`${written}[0].hasAttribute("data-replay-press")`, "the pressed submit");
  ok("a replayed staged call presses one submit, lit under the pointer, before the frame that closes its form",
     await A.js(`{ const b = ${written};
       b.length === 1 && b[0].classList.contains("invited") && replay.at === 4 &&
       document.querySelector("dialog[open][data-guided] [name=recipe]")?.value === "Brown the roux." }`));
  await A.js(`playReplay(); true`);
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended" &&
                 !document.querySelector("dialog[open]")`, "the form to close after the mark", 15000);
  ok("the marked form closes", true);
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· replay: a dialog beat on a row that is not on screen");
  /* the walk's page is drawn again before the file plays: the stew's
     page, still on screen, has links the first gesture would take */
  await A.until(`!replay && !document.querySelector("dialog[open]") &&
                 !!document.querySelector("[data-replay-walk]") &&
                 !document.querySelector(${JSON.stringify(`#view a[href="#${meals[1]}"]`)})`,
                "the replay to stop on the walk's page");
  const farFile = [
    {format: "waymark-walk/1", title: "An agent opens a form elsewhere",
     cast: {a1: {display: "Ada's agent", type: "agent"}}},
    {t: 0, type: "ui", who: "a1", self: meals[1], ui: {dialog: stagedDialog, fields: {}}},
    {t: 1000, type: "ui", who: "a1", self: meals[1],
     ui: {dialog: stagedDialog, fields: {recipe: "Brown the roux."}}},
    {t: 3000, type: "ui", who: "a1", self: meals[1], ui: {dialog: null}},
  ].map(l => JSON.stringify(l)).join("\n");
  /* every hash the replay goes to, and whether an element was pressed
     since the hash before it */
  await A.js(`{ window.__replayHops = []; let pressed = false;
    new MutationObserver(ms => {
      if (ms.some(m => m.target.hasAttribute("data-replay-press"))) pressed = true;
    }).observe(document.body, {subtree: true, attributes: true,
                               attributeFilter: ["data-replay-press"]});
    window.addEventListener("hashchange", () => {
      if (replay) window.__replayHops.push((pressed ? "press " : "jump ") + hereHref());
      pressed = false;
    }); true }`);
  await A.js(`startReplay(${JSON.stringify(farFile)})`);
  await A.until(`!!replay && replay.at >= 1`, "the dialog beat, after its walk", 20000);
  console.log("  the path: " + await A.js(`window.__replayHops.join(", ")`));
  await A.until(`!!document.querySelector("dialog[open][data-guided]") && (pauseReplay(), true)`,
                "the form on the row walked to");
  ok("a replayed dialog beat on a row that is not on screen goes there by the list, and no hash changes without a press",
     await A.js(`{ const h = window.__replayHops;
       hereHref() === ${JSON.stringify(meals[1])} && h.length >= 2 &&
       h[0] === "press /api/meals" && h.every(x => x.startsWith("press ")) }`));
  await A.js(`playReplay(); true`);
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended" &&
                 !document.querySelector("dialog[open]")`, "the form to close", 15000);
  const glides = JSON.parse(await A.js(`JSON.stringify(window.__replayGlides)`));
  console.log("  the glides (ms still / ms owed): " +
              glides.map(g => g.still + "/" + g.floor).join(", "));
  ok("the pointer never starts a glide before the screen it leaves has been still for its floor",
     glides.some(g => g.floor > 0) && glides.every(g => g.still >= g.floor - 20));
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· replay: one second between the changes of the screen");
  const onWalk = `!replay && !document.querySelector("dialog[open]") &&
                  !!document.querySelector("[data-replay-walk]") &&
                  !document.querySelector(${JSON.stringify(`#view a[href="#${meals[1]}"]`)})`;
  await A.until(onWalk, "the replay to stop on the walk's page");
  /* a typed field, a caption in the form, a write behind the form, a
     caption, two moves back onto the row shown, a `doc` that draws that
     row again and a `doc` for a row that is not on screen. Frames 1, 5
     and 11 change nothing a viewer sees. */
  const stillFile = [
    {format: "waymark-walk/1", title: "An agent takes its time",
     cast: {a1: {display: "Ada's agent", type: "agent"}}},
    {t: 0, type: "move", who: "a1", self: meals[1]},
    {t: 10, type: "doc", self: meals[1], doc: {note: "as it was"}},
    {t: 20, type: "ui", who: "a1", self: meals[1], ui: {dialog: stagedDialog, fields: {}}},
    {t: 30, type: "ui", who: "a1", self: meals[1],
     ui: {dialog: stagedDialog, fields: {recipe: "Brown the roux."}}},
    {t: 40, type: "caption", who: "a1", text: "The recipe is typed."},
    {t: 50, type: "transition", who: "a1", kind: "meal", self: meals[1],
     action: "update_recipe", from: "on_list", to: "on_list",
     at: new Date().toISOString(), summary: `Guided stew ${tag}`},
    {t: 60, type: "ui", who: "a1", self: meals[1], ui: {dialog: null}},
    {t: 70, type: "caption", who: "a1", text: "The recipe is written."},
    {t: 80, type: "move", who: "a1", self: meals[1]},
    {t: 90, type: "move", who: "a1", self: meals[1]},
    {t: 600, type: "doc", self: meals[1], doc: {note: "as it is now"}},
    {t: 1100, type: "doc", self: meals[0], doc: {note: "another row"}},
    {t: 1110, type: "caption", who: "a1", text: "Done."},
  ].map(l => JSON.stringify(l)).join("\n");
  /* every change of the screen: when its frame was applied (`from`) and
     when its last draw was done (`to`) */
  await A.js(`{ window.__replayDraws = [];
    const apply1 = applyReplayFrame, hop1 = replayHop, drawn1 = replayDrawn;
    const mark = what => {
      const t = Math.round(performance.now());
      window.__replayDraws.push({what, from: t, to: t});
    };
    window.applyReplayFrame = (f, landed) => {
      const i = replay && !landed ? replay.frames.indexOf(f) : -1;
      const shows = i >= 0 && replayShows(replay.frames, i);
      const out = apply1(f, landed);
      if (shows) mark(i + " " + f.type);
      return out;
    };
    window.replayHop = r => { const out = hop1(r); mark("hop"); return out; };
    window.replayDrawn = (r, draw) => drawn1(r, draw).then(() => {
      const m = window.__replayDraws[window.__replayDraws.length - 1];
      if (m) m.to = Math.round(performance.now());
    });
    true }`);
  await A.js(`startReplay(${JSON.stringify(stillFile)})`);
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended" &&
                 !document.querySelector("dialog[open]")`, "the walk to end at 1x", 60000);
  const draws = JSON.parse(await A.js(`JSON.stringify(window.__replayDraws)`));
  console.log("  the changes (ms since the one before was drawn): " +
              draws.map((d, i) => d.what + (i ? " +" + (d.from - draws[i - 1].to) : "")).join(", "));
  ok("a burst's doc, a write behind its form and a doc for a row off screen change no screen",
     draws.map(d => d.what).join(", ") ===
       "hop, 0 move, 2 ui, 3 ui, 4 caption, 6 ui, 7 caption, 8 move, 9 move, 10 doc, 12 caption");
  ok("at 1x no two changes of the screen are drawn less than 1000 ms apart",
     draws.length > 1 && draws.every((d, i) => !i || d.from - draws[i - 1].to >= 1000 - 20));
  const shown = draws.find(d => d.what === "10 doc"), last = draws.find(d => d.what === "12 caption");
  ok("a doc for a row that is not on screen adds no wait",
     await A.js(`replayHoldTime(replay.frames, 11) === 0 &&
                 replayHoldTime(replay.frames, 10) === REPLAY_MIN_STILL`) &&
     !!shown && !!last && last.from - shown.to < 1900 + 300);
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· replay: the floor counts from the draw");
  await A.until(onWalk, "the replay to stop on the walk's page");
  const lateFile = [
    {format: "waymark-walk/1", title: "A list drawn late",
     cast: {a1: {display: "Ada's agent", type: "agent"}}},
    {t: 0, type: "move", who: "a1", self: meals[1]},
  ].map(l => JSON.stringify(l)).join("\n");
  /* the hop's list is drawn 700 ms after its navigation entry is
     pressed: when the hop was made, when its list was drawn, and when
     the pointer next started a glide */
  await A.js(`{ const late = window.__replayLate = {};
    const render2 = renderReplay, hop2 = replayHop, drawn2 = replayDrawn, to2 = replayPointerTo;
    window.renderReplay = (view, href) => href.split("?")[0] !== "/api/meals"
      ? render2(view, href)
      : new Promise(done => setTimeout(done, 700)).then(() => render2(view, href));
    window.replayHop = r => { late.hop = performance.now(); return hop2(r); };
    window.replayDrawn = (r, draw) => drawn2(r, draw).then(() => {
      if (late.hop && !late.drawn && hereHref() === "/api/meals") late.drawn = performance.now();
    });
    window.replayPointerTo = (to, speed) => {
      if (late.drawn && !late.glide) late.glide = performance.now();
      return to2(to, speed);
    };
    true }`);
  await A.js(`startReplay(${JSON.stringify(lateFile)})`);
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended"`,
                "the move, after its list", 20000);
  const late = JSON.parse(await A.js(`JSON.stringify(window.__replayLate)`));
  console.log("  the list: drawn " + Math.round(late.drawn - late.hop) + " ms after the hop, left " +
              Math.round(late.glide - late.drawn) + " ms after it was drawn");
  ok("a list drawn late is still for its whole floor, counted from the moment it is drawn",
     late.drawn - late.hop >= 650 && late.glide - late.drawn >= 1000 - 20);
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· replay: a bulk write's refused rows, in the band");
  await A.until(onWalk, "the replay to stop on the walk's page");
  /* a self walk's bulk write as it is recorded: a move to the
     collection, the write that went through, and a `refusal` frame for
     each row that did not. No form is open, so each is a line in the
     caption band, held until the recorder's next `ui` beat or `move`. */
  const bulkDoc = (self, name) => ({t: 10, type: "doc", self,
    doc: {self, kind: "meal", state: "on_list", summary: name, actions: {}, data: {name}}});
  const bulkRefusal = (t, self, detail) => (
    {t, type: "refusal", who: "a1", self, action: "accept", title: "Not available", detail});
  const bulkNames = [`Guided soup ${tag}`, `Guided stew ${tag}`];
  const bulkDetails = ["The soup is on the list already.", "The stew is on the list already."];
  const bulkLines = bulkNames.map((n, i) => `Refused: ${n}: ${bulkDetails[i]}`);
  const bulkFile = [
    {format: "waymark-walk/1", title: "A bulk write, partly refused",
     cast: {a1: {display: "Ada's agent", type: "agent"}}},
    {t: 0, type: "move", who: "a1", self: "/api/meals"},
    bulkDoc(meals[0], bulkNames[0]),
    bulkDoc(meals[1], bulkNames[1]),
    {t: 20, type: "transition", who: "a1", kind: "meal", self: meals[2],
     action: "accept", from: "draft", to: "on_list",
     at: new Date().toISOString(), summary: `Guided pie ${tag}`},
    bulkRefusal(30, meals[0], bulkDetails[0]),
    bulkRefusal(40, meals[1], bulkDetails[1]),
    {t: 50, type: "ui", who: "a1", self: "/api/meals", ui: {dialog: null}},
    bulkRefusal(60, meals[1], bulkDetails[1]),
    {t: 70, type: "move", who: "a1", self: meals[0]},
  ].map(l => JSON.stringify(l)).join("\n");
  /* what the band shows when each frame has been applied */
  await A.js(`{ window.__bulkBand = [];
    const apply4 = applyReplayFrame;
    window.applyReplayFrame = (f, landed) => {
      const out = apply4(f, landed);
      const band = document.querySelector("#replaycaption");
      if (replay) window.__bulkBand.push({type: f.type, self: f.self || null,
        band: band && getComputedStyle(band).display !== "none" ? band.textContent : "",
        notice: !!band && band.hasAttribute("data-replay-notice")});
      return out;
    };
    true }`);
  await A.js(`startReplay(${JSON.stringify(bulkFile)})`);
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended"`,
                "the bulk write's walk to end", 40000);
  const bulkBand = JSON.parse(await A.js(`JSON.stringify(window.__bulkBand)`));
  const bulkRefused = e => !e ? "no frame"
    : e.band.split("\n").filter(l => l.startsWith("Refused: ")).join(" | ");
  const bulkOf = type => bulkBand.filter(e => e.type === type);
  console.log("  the band: " + bulkBand.map(e => `${e.type} [${bulkRefused(e)}]`).join("; "));
  ok("each refused row of a bulk write is one line in the band: its summary and the problem's detail",
     bulkOf("refusal").length === 3 &&
     bulkRefused(bulkOf("refusal")[0]) === bulkLines[0] &&
     bulkRefused(bulkOf("refusal")[1]) === bulkLines.join(" | ") &&
     bulkOf("refusal").every(e => e.notice));
  ok("the recorder's next ui beat takes the lines away",
     bulkRefused(bulkOf("ui")[0]) === "" && bulkRefused(bulkOf("refusal")[2]) === bulkLines[1]);
  ok("and so does the recorder's next move",
     bulkOf("move").length === 2 && bulkOf("move")[1].self === meals[0] &&
     bulkRefused(bulkOf("move")[1]) === "");
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· replay: a create form with no move to its collection");
  await A.until(onWalk, "the replay to stop on the walk's page");
  /* a self walk of a recorder whose scope names only some rows, and who
     may create there: the create form's `ui` beats and its `refusal`,
     with no `move` to the collection before them and no `doc` behind
     them (docs/spec-agent-demo-walks.md §2). Replay opens the form from
     the beat's own `self`. */
  const ownDoor = {self: "/api/meals", action: "create"};
  const ownName = `Guided broth ${tag}`;
  const ownDetail = "A meal of that name is on the list already.";
  const ownForm = (t, fields) => (
    {t, type: "ui", who: "a1", self: ownDoor.self, ui: {dialog: ownDoor, fields}});
  const ownFile = [
    {format: "waymark-walk/1", title: "A create form and its refusal",
     cast: {a1: {display: "Ada's agent", type: "agent"}}},
    ownForm(0, {}),
    ownForm(10, {name: ownName}),
    {t: 20, type: "refusal", who: "a1", ...ownDoor, title: "Not available", detail: ownDetail},
  ].map(l => JSON.stringify(l)).join("\n");
  await A.js(`startReplay(${JSON.stringify(ownFile)})`);
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended"`,
                "the create form's walk to end", 20000);
  await A.until(`!!document.querySelector("dialog[open][data-guided] .problem")`,
                "the create form's refusal to be drawn in the form");
  const own = JSON.parse(await A.js(`{ const g = document.querySelector("dialog[open][data-guided]");
    JSON.stringify({here: hereHref(),
      screen: !!document.querySelector('#view [data-replay-screen="/api/meals"]'),
      key: g.getAttribute("data-guided"),
      name: g.querySelector("[name=name]")?.value || "",
      box: g.querySelector(".problem")?.innerText || ""}) }`));
  console.log("  seen at the walk's end: " + JSON.stringify(own));
  ok("a create form with no move before it is opened on its own collection",
     own.here === ownDoor.self && own.screen && own.key === ownDoor.self + " " + ownDoor.action);
  ok("with what the recorder typed", own.name === ownName);
  ok("and its refusal is drawn in that form", own.box.includes(ownDetail));
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· replay: a quest kept from a refusal, and its tracker");
  await A.until(onWalk, "the replay to stop on the walk's page");
  /* a walk as `docs: true` records it: the refused door's form with the
     quest's create and pin behind it, the quest's document after each
     plan, and another principal's step just before the plan it caused.
     A refusal, and the engine's own plan and finish, are no frames. */
  const questSelf = "/api/quests/replayed-quest";
  const questName = `Shelve the stew ${tag}`;
  const goalDoor = {self: meals[0], action: "shelve"};
  const questSteps = [[{self: meals[1], action: "update_recipe"}, "Write the recipe"],
                      [{self: meals[1], action: "season"}, "Season it"],
                      [{self: meals[1], action: "taste"}, "Taste it"]];
  const questNow = new Date().toISOString();
  const questAt = (state, ...steps) => ({
    self: questSelf, kind: "quest", state, summary: questName, actions: {},
    data: {title: questName, self: goalDoor.self, action: goalDoor.action,
           pinned: true, planned_at: questNow,
           plan: steps.map((s, i) => ({self: questSteps[i][0].self,
             door: questSteps[i][0].action, note: questSteps[i][1], state: s}))}});
  const questMove = (t, who, kind, self, action) => (
    {t, type: "transition", who, kind, self, action, from: "on_list", to: "on_list",
     at: questNow, summary: kind === "quest" ? questName : `Guided stew ${tag}`});
  const questForm = (t, dialog, fields) => (
    {t, type: "ui", who: "p1", self: meals[0], ui: dialog ? {dialog, fields} : {dialog: null}});
  const questFile = [
    {format: "waymark-walk/1", title: "A quest kept from a refusal",
     cast: {p1: {display: "Ada", type: "human"}, p2: {display: "Planner", type: "agent"}}},
    {t: 0, type: "move", who: "p1", self: meals[0]},
    questForm(10, goalDoor, {reason: "Out of season"}),
    questMove(20, "p1", "quest", questSelf, "create"),
    questMove(30, "p1", "quest", questSelf, "pin"),
    questForm(40, null),
    {t: 50, type: "doc", self: questSelf, doc: questAt("active", "next", "later")},
    questForm(2000, questSteps[0][0], {}),
    questForm(2010, questSteps[0][0], {recipe: "Brown the roux."}),
    questMove(2020, "p1", "meal", meals[1], "update_recipe"),
    questForm(2030, null),
    {t: 2040, type: "doc", self: questSelf, doc: questAt("active", "done", "next", "later")},
    questMove(4000, "p2", "meal", meals[1], "season"),
    {t: 4010, type: "doc", self: questSelf, doc: questAt("active", "done", "done", "next")},
    questForm(6000, questSteps[2][0], {}),
    questForm(6010, questSteps[2][0], {verdict: "Good."}),
    questMove(6020, "p1", "meal", meals[1], "taste"),
    questForm(6030, null),
    {t: 6040, type: "doc", self: questSelf, doc: questAt("finished", "done", "done", "done")},
  ].map(l => JSON.stringify(l)).join("\n");
  /* every press the pointer makes on the quest's own buttons: the frame
     it is for, and what the tracker counted then */
  await A.js(`{ window.__questPresses = []; window.__questReads = [];
    const to3 = replayPointerTo, fetch3 = window.fetch;
    /* the tracker of a replay is its walk's: no quest is asked for */
    window.fetch = (u, o) => {
      if (replay && String(u).includes("/api/quests")) window.__questReads.push(String(u));
      return fetch3(u, o);
    };
    window.replayPointerTo = (to, speed) => {
      const what = ["sheet.accept", "dialog.accept"].includes(to.getAttribute("data-surface")) ? "accept"
        : to.getAttribute("data-surface") === "tracker.go" ? "go"
        : to.hasAttribute("data-replay-write") ? "write" : null;
      const last = window.__questPresses[window.__questPresses.length - 1];
      if (replay && what && !(last && last.at === replay.at && last.what === what))
        window.__questPresses.push({at: replay.at, what,
          count: document.querySelector("#questbar [data-quest-count]")?.textContent || null});
      return to3(to, speed);
    };
    true }`);
  const qBar2 = `document.querySelector("#questbar")`;
  const qCount = n => `!${qBar2}.hidden &&
    ${qBar2}.querySelector("[data-quest-count]")?.textContent === "${n}"`;
  const qPresses = async () => JSON.parse(await A.js(`JSON.stringify(window.__questPresses)`))
    .map(p => `${p.at} ${p.what} ${p.count}`).join("; ");
  const qWant = "4 accept null; 6 go 0 done, 2 known so far; 9 write 0 done, 2 known so far; " +
                "13 go 2 done, 3 known so far; 16 write 2 done, 3 known so far";
  await A.js(`startReplay(${JSON.stringify(questFile)})`);
  ok("a replay hides the live tracker until its walk has a quest", await A.js(`${qBar2}.hidden`));
  await A.until(qCount("0 done, 2 known so far"), "the tracker of the quest's first plan", 40000);
  ok("the quest's first document draws the tracker: its title, its head step, and Go disabled",
     await A.js(`${qBar2}.querySelector("[data-quest-title]").textContent === ${JSON.stringify(questName)} &&
       ${qBar2}.querySelector("[data-quest-note]").textContent === "Write the recipe" &&
       ${qBar2}.querySelector("[data-surface='tracker.go']").disabled && replay.at === 6 &&
       !document.querySelector("dialog[open]")`));
  await A.until(qCount("1 done, 3 known so far"), "the tracker after the first step", 40000);
  ok("the tracker follows the plan made after the recorder's step",
     await A.js(`${qBar2}.querySelector("[data-quest-note]").textContent === "Season it"`));
  await A.until(`String(replay.notice || "").startsWith("Planner: ")`, "the planner's notice", 40000);
  ok("another principal's step is a notice, and moves no screen",
     await A.js(`hereHref() === ${JSON.stringify(meals[0])} &&
       document.querySelector("#replaycaption").textContent.includes("Planner: ")`));
  await A.until(qCount("2 done, 3 known so far"), "the tracker after the planner's step", 40000);
  await A.until(`!!${qBar2}.querySelector("[data-quest-complete]")`, "Quest complete", 40000);
  ok("the finished quest's document says Quest complete",
     await A.js(`!${qBar2}.hidden && ${qBar2}.textContent.includes(${JSON.stringify(questName)})`));
  await A.until(`document.querySelector("#replaychip")?.getAttribute("data-replay-state") === "ended"`,
                "the quest's walk to end", 20000);
  console.log("  the presses: " + await qPresses());
  ok("the create is pressed on Accept as quest, and each step's form is opened from the tracker's Go",
     (await qPresses()) === qWant);
  ok("replaying the quest read no quest",
     (await A.js(`window.__questReads.join(", ")`)) === "");
  await A.js(`document.querySelector("[data-replay-stop]").click(); true`);

  console.log("· film: the tracker is in the film");
  await A.until(onWalk, "the replay to stop on the walk's page");
  ok("stopping gives the tracker back to the live quest",
     await A.js(`!${qBar2}.querySelector("[data-quest-complete]")`));
  /* the sealed walk's page in film mode, with the quest's walk as its
     export: the export GET is the one read a film makes */
  await A.js(`{ const walk = hereHref().split("?")[0], fetch0 = window.fetch;
    window.fetch = (u, o) => String(u) === walk + "/export"
      ? Promise.resolve(new Response(${JSON.stringify(questFile)}))
      : fetch0(u, o);
    window.__questPresses = [];
    location.hash = "#" + walk + "?film=1"; true }`);
  await A.until(`document.documentElement.getAttribute("data-film") === "playing" &&
                 getComputedStyle(${qBar2}).display !== "none" &&
                 !!${qBar2}.querySelector("[data-surface='tracker.go']:disabled")`,
                "the tracker in the film", 60000);
  ok("film mode keeps the tracker", true);
  await A.until(`document.documentElement.getAttribute("data-film") === "ended"`,
                "the film to end", 90000);
  ok("the film ends on the finished quest, after the same presses",
     await A.js(`!!${qBar2}.querySelector("[data-quest-complete]") &&
       getComputedStyle(${qBar2}).display !== "none"`) && (await qPresses()) === qWant);
  A.close();
  await chrome.close();
}

/* the quest flow on a phone (docs/spec-quests.md): a 390x844 screen with
   touch, in the mobile shell. priya keeps a refused shelve as a quest and
   takes its two steps from the tracker's Go, as the access story's
   recorded walk does, and here Accept as quest, Go and the menu are
   pressed by a finger. At each of the four moments (the offer, the
   tracker, the step's door, Quest complete) nothing scrolls sideways, and
   SHOTS keeps a picture. The walk she records of it is then filmed at the
   same size. */
async function questPhoneStory() {
  const h = {"x-waymark-principal": "priya"};
  const sys = {"x-waymark-principal": "admin", "x-waymark-actor-type": "system"};
  const get = async path => (await fetch(BASE + path, {headers: h})).json();
  let calls = 0;
  const post = async (path, body, headers) => {
    const res = await fetch(BASE + path, {method: "POST",
      headers: {"Content-Type": "application/json", ...headers,
                "Idempotency-Key": `ui-drive-phone-${Date.now()}-${calls++}`},
      body: JSON.stringify(body)});
    return {status: res.status, doc: await res.json().catch(() => null)};
  };
  const W = 390, H = 844;

  console.log("· a phone: 390x844 with touch, the mobile shell");
  await send("Emulation.setDeviceMetricsOverride",
             {width: W, height: H, deviceScaleFactor: 2, mobile: true});
  await send("Emulation.setTouchEmulationEnabled", {enabled: true, maxTouchPoints: 5});
  /* the access drive leaves this tab signed in as its guest, sharing:
     the phone is priya's, by the dev box, with no session and no grant */
  await send("Network.clearBrowserCookies");
  await send("Page.navigate", {url: BASE + "/api/-/ui?ui=mobile"});
  await sleep(1200);
  await evaljs(`sessionStorage.clear(); localStorage.removeItem("wm10.grant");
    localStorage.setItem("wm10.principal", "priya"); location.reload(); true`);
  await sleep(1200);
  /* the page knows who reads it a moment after it is drawn */
  await waitFor(`typeof hereHref === "function" && typeof viewerId === "function" &&
                 !!(principalId() || viewerId())`,
                "the page on the phone, as priya", 15000);
  ok("the page is 390 wide, in the mobile shell",
     await evaljs(`innerWidth === ${W} && innerHeight === ${H} &&
       document.documentElement.getAttribute("data-ui") === "mobile"`));
  ok("its pointer is coarse", await evaljs(`matchMedia("(pointer: coarse)").matches`));

  const bar = `document.querySelector("#questbar")`;
  const me = await evaljs(`viewerId()`);
  ok("the phone's viewer is priya, by the dev box",
     !!me && await evaljs(`$("#who").value === "priya"`));
  const pinned = async () => (await get("/api/quests?state=active&pinned=true&owner=" +
                                        encodeURIComponent(me))).data?.items || [];
  /* a quest an earlier drive left pinned would be the tracker's: none is */
  for (const q of await pinned()) await post(q.self + "/-/finish", {}, sys);
  await evaljs(`refreshQuest().catch(() => {}); true`);
  await waitFor(`${bar}.hidden === true`, "a phone with no pinned quest", 15000);

  /* nothing scrolls sideways: the page, and the tracker when it is shown */
  const widths = `(() => {
    const d = document.documentElement, b = ${bar};
    const out = [...b.children].filter(c => {
      const r = c.getBoundingClientRect();
      return r.width > 0 && (r.left < -0.5 || r.right > innerWidth + 0.5);
    }).map(c => c.className || c.tagName);
    return {page: d.scrollWidth <= innerWidth && document.body.scrollWidth <= innerWidth,
            bar: b.hidden || (b.scrollWidth <= b.clientWidth && out.length === 0),
            seen: {doc: d.scrollWidth, body: document.body.scrollWidth,
                   bar: [b.scrollWidth, b.clientWidth], out}}; })()`;
  const noOverflow = async when => {
    const f = await evaljs(widths);
    if (!f.page || !f.bar) console.log(`  the widths ${when}: ` + JSON.stringify(f.seen));
    ok(`the page does not scroll sideways ${when}`, f.page);
    ok(`the tracker does not scroll sideways ${when}`, f.bar);
  };
  /* the open dialog is a sheet inside the screen, and holds no wider line */
  const sheetFits = async name => {
    const s = await evaljs(`(() => {
      const g = document.querySelector("dialog[open]"), r = g.getBoundingClientRect();
      return {left: r.left, right: r.right, wide: g.scrollWidth, room: g.clientWidth}; })()`);
    if (s.left < -0.5 || s.right > W + 0.5 || s.wide > s.room)
      console.log(`  ${name}: ` + JSON.stringify(s));
    ok(`${name} is a sheet inside the screen, with nothing wider than it`,
       s.left >= -0.5 && s.right <= W + 0.5 && s.wide <= s.room);
  };
  /* a touch target: where a finger lands on it, how tall it is, whether
     all of it is on the screen, and whether the finger lands on IT */
  const target = sel => `(() => {
    const t = document.querySelector(${JSON.stringify(sel)});
    if (!t) return null;
    t.scrollIntoView({block: "nearest"});
    const r = t.getBoundingClientRect();
    const x = Math.round(r.left + r.width / 2), y = Math.round(r.top + r.height / 2);
    const hit = document.elementFromPoint(x, y);
    return {x, y, h: r.height,
            on: r.left >= -0.5 && r.top >= -0.5 &&
                r.right <= innerWidth + 0.5 && r.bottom <= innerHeight + 0.5,
            hit: !!hit && (hit === t || t.contains(hit))}; })()`;
  const checkTarget = async (name, sel) => {
    const t = await evaljs(target(sel));
    if (!t || t.h < 44 || !t.on || !t.hit) console.log(`  ${name}: ` + JSON.stringify(t));
    ok(`${name} is at least 44px tall`, !!t && t.h >= 44);
    ok(`${name} is fully on screen, under the finger`, t.on && t.hit);
    return t;
  };
  const tap = async t => {
    await send("Input.dispatchTouchEvent",
               {type: "touchStart", touchPoints: [{x: t.x, y: t.y}]});
    await send("Input.dispatchTouchEvent", {type: "touchEnd", touchPoints: []});
  };

  console.log("· a refused door on a phone: Accept as quest");
  const title = "Phone pile, with a name far too long for one line of a phone's tracker";
  const made = await post("/api/led_notes", {title}, h);
  ok("priya writes a note with a long name", made.status === 201 && !!made.doc?.self);
  const note = made.doc;
  /* her walk of it, with its screens, as the access story records one */
  const walk = await evaljs(`(async () => {
    const r = await api("/api/walks", {method: "POST", body: JSON.stringify(
      {followed: principalId() || viewerId(),
       title: "A quest on a phone", docs: true})});
    if (!r.ok || !r.body || !r.body.self)
      return {refused: r.status, body: r.body || null};
    recording = recordingOf(r.body);
    recordChip();
    if (!uiSharing()) {
      toggleShareUi();
      sessionStorage.setItem("wm10.record.shared", "1");
    }
    return r.body.self; })()`);
  if (typeof walk !== "string") console.log("  the walk's create: " + JSON.stringify(walk));
  ok("priya records the phone's walk", typeof walk === "string");
  /* a beat carries the form: each press waits for the one before it */
  await sleep(600);
  await evaljs(`location.hash = ${JSON.stringify(note.self)}; true`);
  await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(note.self)} &&
                 document.querySelector("#view").textContent.includes(${JSON.stringify(title)}) &&
                 !!document.querySelector('#view [data-action="shelve"]')`,
                "the note's row page", 15000);
  await sleep(600);
  await evaljs(`document.querySelector('#view [data-action="shelve"]').click(); true`);
  await waitFor(`!!document.querySelector('dialog[open] [name="shelf"]')`,
                "the shelve dialog", 15000);
  await sleep(600);
  /* the enum is a select or a radio group, as the form chose */
  const shelve = async shelf => {
    await evaljs(`(() => {
      const nodes = [...document.querySelectorAll('dialog[open] [name="shelf"]')];
      const radio = nodes.find(n => n.type === "radio" && n.value === ${JSON.stringify(shelf)});
      const i = radio || nodes[0];
      if (radio) radio.checked = true; else i.value = ${JSON.stringify(shelf)};
      i.dispatchEvent(new Event("input", {bubbles: true}));
      i.dispatchEvent(new Event("change", {bubbles: true}));
      return true; })()`);
    await sleep(600);
    await evaljs(`document.querySelector("dialog[open] .dlgfoot button.primary").click(); true`);
  };
  const refused = `(document.querySelector("dialog[open] .problem")?.innerText || "")`;
  const accept = "dialog[open] [data-surface='dialog.accept']";
  await shelve("high");
  await waitFor(`${refused}.includes("room") && !!document.querySelector(${JSON.stringify(accept)})`,
                "the offer under the refusal", 15000);
  await sleep(600);
  await sheetFits("the refused door's dialog");
  const offer = await checkTarget("Accept as quest", accept);
  await noOverflow("under the offer");
  await shot("phone-quest-offer");
  await tap(offer);
  await waitFor(`!document.querySelector("dialog[open]") && !${bar}.hidden &&
                 !!${bar}.querySelector("[data-quest-title]")`,
                "the tracker, off the tap", 15000);
  ok("a tap on Accept as quest closes the dialog and shows the tracker", true);
  const quest = (await pinned())[0]?.self;
  ok("the tap leaves the quest pinned", !!quest);

  console.log("· the tracker on a phone");
  const plan = `JSON.stringify(((questDoc || {}).data || {}).plan || null)`;
  const goFor = door => `(() => {
    const s = questDoc && questHead(questDoc);
    return !!s && s.door === ${JSON.stringify(door)} &&
      s.self === ${JSON.stringify(note.self)} &&
      !!${bar}.querySelector("[data-surface='tracker.go']:not(:disabled)"); })()`;
  await waitFor(goFor("rename"), "the remedy at the tracker's head", 30000, plan);
  /* the head step's row is in the tracker by its title, which the page
     reads, and no path is text (ticket 5b3fa3f7) */
  const rowSays = `(${bar}.querySelector("[data-quest-row]")?.textContent || "")`;
  await waitFor(`${rowSays}.includes(${JSON.stringify(title)})`,
                "the note's title in the tracker", 15000, `${bar}.textContent`);
  ok("the tracker names the step's row in words, and no path",
     await evaljs(`!/\\/api\\//.test(${bar}.textContent)`));
  const says = await evaljs(`(() => {
    const b = ${bar}, t = b.querySelector("[data-quest-title]"), cs = getComputedStyle(t);
    const line = b.querySelector("[data-quest-note]");
    return {title: t.textContent, clipped: t.scrollWidth > t.clientWidth,
            ellipsis: cs.textOverflow === "ellipsis" && cs.whiteSpace === "nowrap" &&
                      cs.overflowX === "hidden",
            count: b.querySelector("[data-quest-count]")?.textContent || "",
            note: line ? line.textContent : null,
            noteWide: line ? line.getBoundingClientRect().width : 0,
            needs: questHead(questDoc).needs || []}; })()`);
  console.log("  the tracker: " + JSON.stringify(says));
  ok("the tracker says its title, the count and the head step's note",
     !!says.title && /^\d+ done, \d+ known so far$/.test(says.count) &&
     !!(says.note || "").trim() && says.noteWide >= 100);
  ok("a long title ends in an ellipsis",
     says.ellipsis && (says.title.length < 60 || says.clipped));
  const go = await checkTarget("Go", '[data-surface="tracker.go"]');
  await checkTarget("the tracker's title", "#questbar [data-quest-title]");
  /* the title's 44px box overlaps its neighbours: the tracker is still
     the count's line, the gap and the row of Go, and no taller */
  const tall = await evaljs(`(() => {
    const b = ${bar}, cs = getComputedStyle(b);
    const h = sel => b.querySelector(sel).getBoundingClientRect().height;
    return {bar: b.getBoundingClientRect().height,
            rows: h("[data-quest-count]") + h("[data-surface='tracker.go']") +
                  [cs.rowGap, cs.paddingTop, cs.paddingBottom, cs.borderTopWidth,
                   cs.borderBottomWidth].reduce((n, v) => n + (parseFloat(v) || 0), 0)}; })()`);
  console.log("  the tracker's height: " + JSON.stringify(tall));
  ok("the title's 44px box makes the tracker no taller", tall.bar <= tall.rows + 1);
  await noOverflow("under the tracker");
  await shot("phone-quest-tracker");
  const menu = await checkTarget("the tracker's menu", "#questbar .quest-menu summary");
  await tap(menu);
  await waitFor(`${bar}.querySelector(".quest-menu").open`, "the menu, off the tap", 15000);
  const doors = await evaljs(`[...${bar}.querySelectorAll(".quest-menu-items button")].map(b => {
    const r = b.getBoundingClientRect();
    return {name: b.textContent.trim(), h: r.height,
            on: r.left >= -0.5 && r.right <= innerWidth + 0.5 && r.bottom <= innerHeight + 0.5}; })`);
  console.log("  the menu's doors: " + JSON.stringify(doors));
  ok("each door of the menu is at least 44px tall and on screen",
     doors.length > 0 && doors.every(d => d.h >= 44 && d.on));
  await noOverflow("under the open menu");
  await shot("phone-quest-menu");
  await evaljs(`${bar}.querySelector(".quest-menu").open = false; true`);

  console.log("· Go on a phone: the step's door");
  await sleep(600);
  await tap(go);
  await waitFor(`!!document.querySelector('dialog[open] [name="title"]')`,
                "the remedy's dialog, off a tap on Go", 15000);
  await sleep(600);
  const lit = await evaljs(`document.querySelectorAll("dialog[open] .invited").length`);
  console.log(`  the step needs ${JSON.stringify(says.needs)}: ${lit} lit`);
  ok("a tap on Go opens the step's door, with its needs lit",
     says.needs.length === 0 || lit > 0);
  await sheetFits("the step's door");
  await noOverflow("under the step's door");
  await shot("phone-quest-go");
  await evaljs(`(() => {
    const set = (name, v) => {
      const i = document.querySelector('dialog[open] [name="' + name + '"]');
      i.value = v;
      i.dispatchEvent(new Event("input", {bubbles: true}));
      i.dispatchEvent(new Event("change", {bubbles: true}));
    };
    /* a name and a room: the high shelf refuses no more */
    set("title", ${JSON.stringify(title + ", sorted")}); set("room", "Hall");
    return true; })()`);
  await sleep(600);
  await evaljs(`document.querySelector("dialog[open] .dlgfoot button.primary").click(); true`);
  await waitFor(`!document.querySelector("dialog[open]")`, "the remedy's dialog to close", 15000);
  await waitFor(goFor("shelve"), "the goal door at the tracker's head", 30000, plan);
  await waitFor(`${rowSays}.includes(${JSON.stringify(title + ", sorted")})`,
                "the note's new title in the tracker", 15000, `${bar}.textContent`);
  ok("a rename shows in the tracker: the row's new title", true);
  await sleep(600);
  await tap(await evaljs(target('[data-surface="tracker.go"]')));
  await waitFor(`!!document.querySelector('dialog[open] [name="shelf"]')`,
                "the goal's dialog, off a tap on Go", 15000);
  await sleep(600);
  await shelve("high");
  await waitFor(`!document.querySelector("dialog[open]")`, "the goal's dialog to close", 15000,
                refused);

  console.log("· Quest complete on a phone");
  /* the line stays a few seconds: it is read and pictured at once */
  await waitFor(`!!${bar}.querySelector("[data-quest-complete]")`,
                "Quest complete on the phone", 40000, plan)
    .catch(async e => {
      throw new Error(e.message + "; the quest's row is " + (await get(quest)).state);
    });
  ok("the tracker says Quest complete", true);
  await noOverflow("under Quest complete");
  await shot("phone-quest-complete");
  for (let i = 0; i < 60 && (await get(quest)).state !== "finished"; i++) await sleep(250);
  ok("the goal door finishes the phone's quest", (await get(quest)).state === "finished");
  /* the consumer hands the finished quest to the walk after the move */
  await sleep(1500);
  await evaljs(`stopRecording().then(() => true)`);
  await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(walk)} &&
                 !!document.querySelector("[data-replay-walk]")`,
                "the sealed walk's page", 15000);
  ok("stop seals the phone's walk", (await get(walk)).state === "sealed");

  console.log("· film: the phone's quest walk, at 390x844");
  const why = `({at: replay && replay.at, film: document.documentElement.getAttribute("data-film"),
    state: document.querySelector("#replaychip")?.getAttribute("data-replay-state") ?? null})`;
  await evaljs(`location.hash = "#" + ${JSON.stringify(walk)} + "?film=1"; true`);
  await waitFor(`document.documentElement.getAttribute("data-film") === "playing" &&
                 getComputedStyle(${bar}).display !== "none" &&
                 !!${bar}.querySelector("[data-surface='tracker.go']:disabled")`,
                "the tracker in the phone's film", 240000, why);
  ok("film mode keeps the tracker on a phone", true);
  await noOverflow("in the film");
  await waitFor(`document.documentElement.getAttribute("data-film") === "ended"`,
                "the phone's film to end", 240000, why);
  ok("the film ends on the finished quest",
     await evaljs(`innerWidth === ${W} && !!${bar}.querySelector("[data-quest-complete]") &&
       getComputedStyle(${bar}).display !== "none"`));
  await noOverflow("at the film's end");
  await shot("phone-quest-film");

  /* ── the 'not yet' button (shutDoor, 140-links-access.js) ─────────────
     A led_task ends after its children (access_dev.clj), as a ticket
     does: the parent's Complete is shut and names the child's Complete
     as its way out, and its Discard is shut and names none. One tap on
     Complete makes and pins the quest, Go walks it, and the walk she
     records of it is filmed. Driven on the phone and then at desktop:
     a press is a finger there and a click here. */
  const ready = `typeof hereHref === "function" && typeof viewerId === "function" &&
                 !!(principalId() || viewerId())`;
  /* out of film mode: the page again, live */
  const fresh = async what => {
    await evaljs(`location.hash = "/api/led_tasks"; location.reload(); true`);
    await sleep(1200);
    await waitFor(ready, what, 15000);
  };
  const notYet = async (where, slug, phone) => {
    console.log(`· the 'not yet' button ${where}`);
    const press = async sel => phone
      ? tap(await evaljs(target(sel)))
      : evaljs(`document.querySelector(${JSON.stringify(sel)}).click(); true`);
    /* the sheet a tap on a shut door opens (questSheet), and its doors */
    const sheet = `document.querySelector("dialog[open][data-surface='sheet']")`;
    const notNow = "dialog[open] [data-quest-decline]";
    const acceptIt = '[data-surface="sheet.accept"]';
    await evaljs(`refreshQuest().catch(() => {}); true`);
    await waitFor(`${bar}.hidden === true`, `no pinned quest ${where}`, 15000);
    const parent = await post("/api/led_tasks", {title: `Spring clean ${where}`}, h);
    const epic = parent.doc?.self;
    ok("priya writes a task", parent.status === 201 && !!epic);
    const sub = await post("/api/led_tasks",
      {title: `Sweep the hall ${where}`, parent: epic.split("/").pop()}, h);
    const child = sub.doc?.self;
    ok("and a child of it, not finished", sub.status === 201 && !!child);
    /* a refused tap (ticket e8cb4bcf): one owner holds at most 20 active
       quests (quests.clj, active-cap), so at the cap the tap's preview is
       refused as the create would be. The sentence is said in the sheet
       and Accept is disabled; no quest is pinned and no tracker shows. */
    const spare = await post("/api/led_tasks", {title: `Spare room ${where}`}, h);
    const room = spare.doc?.self;
    const dust = await post("/api/led_tasks",
      {title: `Dust the shelf ${where}`, parent: String(room).split("/").pop()}, h);
    ok("a second task with an open child, for the refused tap",
       spare.status === 201 && !!room && dust.status === 201);
    const held = [];
    let full = null;
    for (let i = 0; i < 21 && !full; i++) {
      const q = await post("/api/quests", {self: room, action: "complete"}, h);
      if (q.status === 201 && q.doc?.self) held.push(q.doc.self);
      else full = q;
    }
    console.log(`  ${held.length} quests held, then: ` + JSON.stringify(full?.doc ?? null));
    ok("priya's active quests reach the cap, and the next create is refused",
       held.length > 0 && !!full && full.status >= 400 && full.status < 500 &&
       /at most 20 active quests/.test(full.doc?.detail || ""));
    await evaljs(`location.hash = ${JSON.stringify(room)}; true`);
    const shutDoor = '[data-surface="door-shut:complete"]';
    await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(room)} &&
                   !!document.querySelector(${JSON.stringify(shutDoor + "[aria-describedby]")})`,
                  "the second task's row page, with its shut Complete", 15000,
                  `[...document.querySelectorAll("#view button")].map(b => b.outerHTML.slice(0, 160))`);
    await sleep(600);
    await press(shutDoor);
    const saidWhy = `(${sheet}?.querySelector("[data-quest-refused]")?.textContent || "")`;
    await waitFor(`!!${saidWhy}`, "the refusal, in the quest's sheet", 15000,
                  `document.body.innerText.slice(-400)`);
    const refusal = await evaljs(saidWhy);
    console.log("  the refused preview: " + JSON.stringify(refusal));
    ok("a refused preview says the engine's sentence in the sheet",
       /at most 20 active quests/.test(refusal));
    ok("and Accept is disabled with that line",
       await evaljs(`${sheet}.querySelector("[data-surface='sheet.accept']").disabled === true`));
    if (phone) await sheetFits("the refused quest's sheet");
    await shot(`${slug}-notyet-refused`);
    await press(notNow);
    await waitFor(`!document.querySelector("dialog[open]")`,
                  "the refused sheet to close, off Not now", 15000);
    ok("the refused tap shows no tracker and pins no quest",
       await evaljs(`${bar}.hidden === true`) && (await pinned()).length === 0);
    ok("the door takes a tap again",
       await evaljs(`!document.querySelector(${JSON.stringify(shutDoor)}).hasAttribute("data-quest-busy")`));
    if (phone) await noOverflow("after the refused quest's sheet");
    for (const q of held) await post(q + "/-/abandon", {}, h);
    ok("priya lets the held quests go",
       (await Promise.all(held.map(q => get(q)))).every(q => q.state === "abandoned"));
    /* one short of the cap again, for the Accept the recording has
       refused (ticket 5b321c0a): the walk's own quest is finished by
       then, and one more made under the open sheet fills the cap */
    const kept = [];
    for (let i = 0; i < held.length - 1; i++) {
      const q = await post("/api/quests", {self: room, action: "complete"}, h);
      if (q.status === 201 && q.doc?.self) kept.push(q.doc.self);
    }
    ok("priya holds one quest short of the cap", kept.length === held.length - 1);
    const walk = await evaljs(`(async () => {
      const r = await api("/api/walks", {method: "POST", body: JSON.stringify(
        {followed: principalId() || viewerId(),
         title: ${JSON.stringify("A shut door, tapped " + where)}, docs: true})});
      if (!r.ok || !r.body || !r.body.self)
        return {refused: r.status, body: r.body || null};
      recording = recordingOf(r.body);
      recordChip();
      if (!uiSharing()) {
        toggleShareUi();
        sessionStorage.setItem("wm10.record.shared", "1");
      }
      return r.body.self; })()`);
    if (typeof walk !== "string") console.log("  the walk's create: " + JSON.stringify(walk));
    ok(`priya records her walk ${where}`, typeof walk === "string");
    await sleep(600);
    await evaljs(`location.hash = ${JSON.stringify(epic)}; true`);
    const door = '[data-surface="door-shut:complete"]';
    await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(epic)} &&
                   !!document.querySelector(${JSON.stringify(door + "[aria-describedby]")})`,
                  "the parent's row page, with its shut Complete", 15000,
                  `[...document.querySelectorAll("#view button")].map(b => b.outerHTML.slice(0, 160))`);
    await sleep(600);
    const seat = await evaljs(`(() => {
      const b = document.querySelector(${JSON.stringify(door)}), cs = getComputedStyle(b);
      const line = document.getElementById(b.getAttribute("aria-describedby"));
      const plain = [...document.querySelectorAll("#view button.blocked")]
        .filter(p => !(p.getAttribute("data-surface") || "").startsWith("door-shut:"));
      return {cls: b.className, text: b.textContent, disabled: b.disabled,
              aria: b.getAttribute("aria-disabled"), self: b.dataset.questSelf,
              border: cs.borderTopStyle, opacity: cs.opacity,
              line: line ? line.textContent : null,
              plain: plain.map(p => ({text: p.textContent, disabled: p.disabled,
                                      flag: !!p.querySelector(".notyet-flag")}))}; })()`);
    console.log("  the shut door: " + JSON.stringify(seat));
    ok("Complete is a reachable button: dashed, flagged, not dimmed",
       /\bblocked\b/.test(seat.cls) && /\bnotyet\b/.test(seat.cls) &&
       seat.text.includes("⚑") && seat.border === "dashed" && seat.opacity === "1");
    ok("it is aria-disabled and not disabled, and names its row",
       seat.aria === "true" && seat.disabled === false && seat.self === epic);
    ok("its reason line ends 'Not yet. Tap to see the quest.'",
       (seat.line || "").trim().endsWith("Not yet. Tap to see the quest."));
    ok("a door with no remedy is the plain disabled button",
       seat.plain.length > 0 && seat.plain.every(p => p.disabled && !p.flag));
    if (phone) {
      await checkTarget("Complete ⚑", door);
      await noOverflow("under the shut door");
    }
    await shot(`${slug}-notyet-door`);
    await press(door);
    /* the tap previews: the sheet says the goal, why its door is shut,
       and the steps with the goal's own form last. Nothing is written. */
    await waitFor(`!!${sheet} && !!${sheet}.querySelector("[data-quest-steps] li")`,
                  "the quest's sheet, off the tap on Complete", 15000,
                  `document.body.innerText.slice(-400)`);
    const seen = await evaljs(`(() => {
      const g = ${sheet};
      return {goal: g.querySelector("[data-quest-sheet-goal]").textContent,
              why: g.querySelector("[data-quest-why]").textContent,
              steps: [...g.querySelectorAll("[data-quest-steps] li")].map(l => l.textContent),
              paths: [...g.querySelectorAll("[data-quest-steps] li")].map(l => l.title),
              turns: [...g.querySelectorAll("[data-quest-turn]")].map(t => t.textContent),
              refused: g.querySelector("[data-quest-refused]").textContent,
              accept: g.querySelector("[data-surface='sheet.accept']").disabled}; })()`);
    console.log("  the quest's sheet: " + JSON.stringify(seen));
    ok("the sheet says the goal and why its door is shut",
       /Complete/.test(seen.goal) && seen.why.replace("Not yet:", "").trim().length > 0);
    ok("it numbers the steps, each with whose turn it is",
       seen.steps.length >= 2 && seen.turns.length === seen.steps.length &&
       seen.turns.every(t => t.trim().length > 0));
    ok("a step names its row in words: the child's title, and the path only as a title",
       seen.steps.some(t => t.includes(`Sweep the hall ${where}`)) &&
       seen.steps.every(t => !t.includes("/api/led_tasks/")) &&
       seen.paths.includes(child) && seen.paths.includes(epic));
    ok("the goal's own form is the last step, with Close reason asked for",
       /Close reason/.test(seen.steps[seen.steps.length - 1] || "") &&
       !/close_reason/.test(seen.steps[seen.steps.length - 1] || ""));
    ok("Accept is offered, with no refusal said",
       seen.accept === false && seen.refused === "");
    if (phone) {
      await sheetFits("the quest's sheet");
      await checkTarget("Not now", notNow);
      await checkTarget("Accept quest", acceptIt);
    }
    await shot(`${slug}-notyet-sheet`);
    await press(notNow);
    await waitFor(`!document.querySelector("dialog[open]")`,
                  "the sheet to close, off Not now", 15000);
    await sleep(600);
    ok("Not now leaves no quest and shows no tracker",
       await evaljs(`${bar}.hidden === true`) && (await pinned()).length === 0);
    await press(door);
    await waitFor(`!!${sheet} && ${sheet}.querySelector("[data-surface='sheet.accept']").disabled === false`,
                  "the quest's sheet again, off a second tap", 15000,
                  `document.body.innerText.slice(-400)`);
    await sleep(600);
    await press(acceptIt);
    /* a refused create or pin is said in the sheet: the page's last
       words say which, when the tracker never shows. The pin's frame
       can show the tracker before the pin's answer closes the sheet,
       so the wait is for both. */
    await waitFor(`!${bar}.hidden && !!${bar}.querySelector("[data-quest-title]") &&
                   !document.querySelector("dialog[open]")`,
                  "the tracker and the closed sheet, off Accept", 15000,
                  `document.body.innerText.slice(-400)`);
    ok("Accept closes the sheet and shows the tracker",
       await evaljs(`!document.querySelector("dialog[open]")`));
    const made = (await pinned())[0]?.self;
    ok("the tap leaves a quest pinned", !!made);
    const goal = (await get(made)).data || {};
    ok("its goal is this row and this door",
       String(goal.self || "").split("?")[0] === epic && goal.action === "complete");

    const headIs = self => `(() => {
      const s = questDoc && questHead(questDoc);
      return !!s && s.door === "complete" && s.self === ${JSON.stringify(self)} &&
        !!${bar}.querySelector("[data-surface='tracker.go']:not(:disabled)"); })()`;
    await waitFor(headIs(child), "the child's Complete at the tracker's head", 30000, plan);
    /* the tracker and the quest's page name a step's row and what its
       door asks for in words, and no path is text (ticket 5b3fa3f7) */
    const childTitle = JSON.stringify(`Sweep the hall ${where}`);
    await waitFor(`(${bar}.querySelector("[data-quest-row]")?.textContent || "")
                     .includes(${childTitle})`,
                  "the child's title in the tracker", 15000, `${bar}.textContent`);
    ok("the tracker names the step's row and its needs in words, and no path",
       await evaljs(`((questHead(questDoc).needs || []).length === 0 ||
                      /Close reason/.test(${bar}.textContent)) &&
                     !/close_reason/.test(${bar}.textContent) &&
                     !/\\/api\\//.test(${bar}.textContent)`));
    const wasAt = await evaljs(`location.hash`);
    const qPlan = `document.querySelector("[data-quest-plan]")`;
    await evaljs(`location.hash = ${JSON.stringify("#" + made)}; true`);
    await waitFor(`!!${qPlan} && [...${qPlan}.querySelectorAll("[data-quest-row]")]
                     .some(a => a.textContent.includes(${childTitle}))`,
                  "the child's title on the quest's page", 15000,
                  `document.body.innerText.slice(-400)`);
    ok("the quest's page names each step's row and its needs in words, and no path",
       await evaljs(`/Close reason/.test(${qPlan}.textContent) &&
                     !/close_reason/.test(${qPlan}.textContent) &&
                     !/\\/api\\//.test(${qPlan}.textContent)`));
    /* the data table under the checklist draws the goal row in words
       and leaves the plan out: no path is visible text anywhere on the
       page (ticket bdd37958) */
    await waitFor(`!!document.querySelector("table.kv [data-quest-row]")`,
                  "the goal row in the quest's data table", 15000,
                  `document.body.innerText.slice(-400)`);
    /* the whole page is the view and the tracker: the shell's ticker
       under them names the last event on any row, on every screen. A
       miss says the text the path was read in */
    const paths = JSON.parse(await evaljs(`JSON.stringify(
      (document.querySelector("#view").innerText + "\\n" + ${bar}.innerText)
        .match(/.{0,60}\\/api\\/.{0,60}/g) || [])`));
    ok("the quest's whole page shows no row path as text" +
       (paths.length ? ": " + JSON.stringify(paths) : ""), !paths.length);
    await evaljs(`location.hash = ${JSON.stringify(wasAt)}; true`);
    await waitFor(`!${qPlan}`, "the page the quest was accepted on", 15000);
    /* the plan's last step, and whether it is `self`'s Complete with
       close_reason still owed: a miss says the plan it read */
    const endsWith = async (name, self) => {
      const steps = JSON.parse(await evaljs(plan)) || [];
      const last = steps[steps.length - 1] || {};
      console.log("  the plan: " + JSON.stringify(steps));
      const ends = last.door === "complete" && last.self === self &&
        (last.needs || []).flat().includes("close_reason");
      ok(name + (ends ? "" : ": " + JSON.stringify(steps)), ends);
    };
    /* a goal the row page draws shut is not rehearsed past its refusal
       (client.clj, the door not afforded), so the planner reads the
       goal's needs from the kind's declaration: the goal's own step,
       with its form, is the last one from the first plan on */
    await endsWith("the first plan ends with the parent's Complete, which needs close_reason",
                   epic);
    /* Go, the form, a reason, and the form's own button: → how many of
       the form's fields were lit */
    const close = async what => {
      await sleep(600);
      await press('[data-surface="tracker.go"]');
      await waitFor(`!!document.querySelector('dialog[open] [name="close_reason"]')`,
                    `${what}, off Go`, 15000);
      await sleep(600);
      const lit = await evaljs(`[...document.querySelectorAll("dialog[open] .invited")]
        .filter(e => !e.closest(".dlgfoot")).length`);
      if (phone) await sheetFits(what);
      await evaljs(`(() => {
        const i = document.querySelector('dialog[open] [name="close_reason"]');
        i.value = "Done.";
        i.dispatchEvent(new Event("input", {bubbles: true}));
        i.dispatchEvent(new Event("change", {bubbles: true}));
        return true; })()`);
      await sleep(600);
      await evaljs(`document.querySelector("dialog[open] .dlgfoot button.primary").click(); true`);
      await waitFor(`!document.querySelector("dialog[open]")`, `${what} to close`, 15000, refused);
      return lit;
    };
    await close("the child's Complete");
    await waitFor(headIs(epic), "the parent's Complete at the tracker's head", 30000, plan);
    await endsWith("the plan then ends with the parent's Complete, which needs close_reason",
                   epic);
    const lit = await close("the parent's Complete");
    console.log(`  the goal's form: ${lit} lit`);
    ok("Go opens the goal's form last, with close_reason lit", lit > 0);
    await waitFor(`!!${bar}.querySelector("[data-quest-complete]")`,
                  `Quest complete ${where}`, 40000, plan);
    ok("Go walks the tapped door's quest to Quest complete", true);
    if (phone) await noOverflow("under the tapped door's Quest complete");
    await shot(`${slug}-notyet-complete`);
    for (let i = 0; i < 60 && (await get(made)).state !== "finished"; i++) await sleep(250);
    ok("the parent's Complete finishes the quest", (await get(made)).state === "finished");
    ok("and the parent is done", (await get(epic)).state === "done");
    /* a door shut by its state alone carries remedies only when a door
       of the row leads to a state it opens from (render.clj,
       out-of-state-entry; machine.clj, roads). Nothing leaves done, so
       a done task's doors name no way out and are never reachable */
    const shut = (await get(epic)).unavailable ?? null;
    console.log("  the done parent's shut doors: " + JSON.stringify(shut));
    ok("the done parent's doors are shut by its state, and carry no remedies",
       ["complete", "discard"].every(n => {
         const e = (shut || {})[n];
         return !!e && (e.becomes_available?.in_states || []).includes("open") &&
           !(e.remedies || []).length; }));
    /* a refused Accept (ticket 5b321c0a): the cap is reached between the
       preview and Accept, so the sheet opens with its steps and the
       create is refused. The sentence is said in the sheet and Accept
       is disabled; the walk holds the `refusal` frame for its film. */
    await evaljs(`location.hash = ${JSON.stringify(room)}; true`);
    await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(room)} &&
                   !!document.querySelector(${JSON.stringify(shutDoor + "[aria-describedby]")})`,
                  "the second task's row page again, with its shut Complete", 15000,
                  `[...document.querySelectorAll("#view button")].map(b => b.outerHTML.slice(0, 160))`);
    await sleep(600);
    await press(shutDoor);
    await waitFor(`!!${sheet} && ${sheet}.querySelector("[data-surface='sheet.accept']").disabled === false`,
                  "the second task's sheet, with Accept offered", 15000,
                  `document.body.innerText.slice(-400)`);
    await sleep(600);
    const last = await post("/api/quests", {self: room, action: "complete"}, h);
    if (last.status === 201 && last.doc?.self) kept.push(last.doc.self);
    ok("a quest made elsewhere fills the cap under the open sheet", last.status === 201);
    await press(acceptIt);
    await waitFor(`!!${saidWhy}`, "the refused Accept, in the quest's sheet", 15000,
                  `document.body.innerText.slice(-400)`);
    const late = await evaljs(saidWhy);
    console.log("  the refused Accept: " + JSON.stringify(late));
    ok("a refused Accept says the engine's sentence in the sheet",
       /at most 20 active quests/.test(late));
    ok("and Accept is disabled, in the sheet still open",
       await evaljs(`${sheet}.querySelector("[data-surface='sheet.accept']").disabled === true`));
    if (phone) await sheetFits("the sheet of the refused Accept");
    await shot(`${slug}-notyet-accept-refused`);
    await sleep(600);
    await press(notNow);
    await waitFor(`!document.querySelector("dialog[open]")`,
                  "the sheet of the refused Accept to close, off Not now", 15000);
    ok("the refused Accept pins no quest", (await pinned()).length === 0);
    await sleep(1500);
    await evaljs(`stopRecording().then(() => true)`);
    await waitFor(`hereHref().split("?")[0] === ${JSON.stringify(walk)} &&
                   !!document.querySelector("[data-replay-walk]")`,
                  "the sealed walk's page", 15000);
    ok(`stop seals the walk ${where}`, (await get(walk)).state === "sealed");
    for (const q of kept) await post(q + "/-/abandon", {}, h);
    ok("priya lets the kept quests go, out of her recording",
       (await Promise.all(kept.map(q => get(q)))).every(q => q.state === "abandoned"));

    console.log(`· film: the tapped door's walk ${where}`);
    /* the press is lit for a moment: the page itself notes it, with
       whether the replay first walked to another row (replayGesture) */
    const active = async () => ((await get("/api/quests?state=active&owner=" +
      encodeURIComponent(me))).data?.items || []).length;
    const before = await active();
    /* and each sheet the replay opens, with its steps, its close and
       the presses the replay draws on its Accept quest */
    await evaljs(`window.__notYet = null;
      window.__sheets = [];
      if (window.__sheetWatch) window.__sheetWatch.disconnect();
      window.__sheetWatch = new MutationObserver(ms => {
        const is = n => n.nodeType === 1 && n.matches("[data-surface='sheet']");
        for (const m of ms) {
          for (const n of m.addedNodes)
            if (is(n)) window.__sheets.push({replay: !!replay, closed: false, accepts: 0,
              steps: [...n.querySelectorAll("[data-quest-steps] li")].map(l => l.textContent)});
          for (const n of m.removedNodes)
            if (is(n)) Object.assign(window.__sheets.find(s => !s.closed) || {}, {closed: true,
              said: n.querySelector("[data-quest-refused]").textContent,
              shut: n.querySelector("[data-surface='sheet.accept']").disabled});
        }
      });
      window.__sheetWatch.observe(document.body, {childList: true});
      new MutationObserver(ms => {
        for (const m of ms) {
          const open = window.__sheets.find(s => !s.closed);
          if (open && m.target.hasAttribute("data-replay-press") &&
              m.target.matches("[data-surface='sheet.accept']"))
            open.accepts++;
        }
        const b = document.querySelector(${JSON.stringify(door + "[data-replay-press]")});
        if (b && !window.__notYet)
          window.__notYet = {self: b.dataset.questSelf,
                             walk: !!(replay && replay.gesture && replay.gesture.walk)};
      }).observe(document.documentElement,
                 {subtree: true, attributes: true, attributeFilter: ["data-replay-press"]});
      window.__beatEvents = [];
      if (!window.__beatWatch) document.addEventListener("waymark:film-beat",
        window.__beatWatch = e => window.__beatEvents.push(e.detail.i));
      location.hash = "#" + ${JSON.stringify(walk)} + "?film=1"; true`);
    await waitFor(`!!window.__notYet`, "the replay's press on Complete", 240000, why);
    const pressed = await evaljs(`window.__notYet`);
    console.log("  the replay's press: " + JSON.stringify(pressed));
    ok("the replay draws the press on Complete, on the parent's own page",
       pressed.self === epic);
    ok("and makes no walk to the quest's row for it", pressed.walk === false);
    await waitFor(`document.documentElement.getAttribute("data-film") === "ended"`,
                  `the film to end ${where}`, 240000, why);
    ok(`film mode reaches ended ${where}`, true);
    /* the beats the film said (docs/spec-agent-demo-walks.md §8b): what
       it pressed, and what the sheet and the tracker said */
    const said = JSON.parse(await evaljs(`JSON.stringify({beats: window.wmFilmBeats || [],
      events: window.__beatEvents, frames: replay ? replay.frames.length : null,
      tracker: readSurface("tracker")})`));
    const beats = said.beats, lastBeat = beats[beats.length - 1] || {};
    const presses = beats.map(b => b.pressed && b.pressed.target);
    const flat = t => String(t).replace(/\s+/g, " ").trim();
    console.log("  the film's beats: " + JSON.stringify(beats.map(b =>
      [b.i, b.type, b.pressed, b.sheet && b.sheet.steps.map(s => s.label),
       b.tracker && b.tracker.next])));
    ok(`the film says a beat for each frame it steps to, in order, by its event ${where}`,
       beats.length > 0 && beats.every((b, n) => !n || b.i > beats[n - 1].i) &&
       JSON.stringify(said.events) === JSON.stringify(beats.map(b => b.i)) &&
       (said.frames == null || lastBeat.i === said.frames - 1));
    ok("the beats hold the press on the shut Complete and the press on Accept quest",
       presses.includes("door:complete") && presses.includes("sheet.accept"));
    ok("a beat's sheet lists the recorded steps, each by its door and its row",
       beats.some(b => b.sheet && b.sheet.steps.length === seen.steps.length &&
         b.sheet.steps.every((s, n) => !!s.label && flat(seen.steps[n]).startsWith(s.label))));
    ok("a beat's tracker names the step at its head",
       beats.some(b => b.tracker && !!b.tracker.next));
    /* what a viewer could read and where it is (§8b): the sheet's beat
       is about the sheet, which is on the screen whole, in type a
       person can read */
    const sheetBeat = beats.find(b => b.sheet && b.sheet.shut_reason &&
                                      b.focus && b.focus.name === "sheet");
    const tight = t => String(t || "").replace(/\s+/g, "");
    const fr = sheetBeat?.focus.rect, vp = sheetBeat?.viewport;
    console.log("  the sheet's beat: " + JSON.stringify(sheetBeat &&
      {focus: [sheetBeat.focus.name, fr], viewport: vp, type_px: sheetBeat.type_px,
       contrast: sheetBeat.contrast, pointer: sheetBeat.pointer,
       boxes: sheetBeat.boxes.map(b => b.name), text: sheetBeat.text.slice(0, 200)}));
    ok(`the sheet's beat has the sheet as its focus, with its rect inside the viewport ${where}`,
       !!sheetBeat && fr.w > 0 && fr.h > 0 && fr.x >= 0 && fr.y >= 0 &&
       fr.x + fr.w <= vp.w + 1 && fr.y + fr.h <= vp.h + 1);
    ok("its text holds the sheet's reason",
       !!sheetBeat && tight(sheetBeat.text).includes(tight(sheetBeat.sheet.shut_reason)));
    ok("its focus text is 12 px or larger, with a contrast ratio and the sheet among its boxes",
       !!sheetBeat && sheetBeat.type_px >= 12 && sheetBeat.contrast >= 1 &&
       sheetBeat.boxes.some(b => b.name === "sheet"));
    ok("the last beat says the tracker as the film leaves it",
       JSON.stringify(lastBeat.tracker) === JSON.stringify(said.tracker));
    const sheets = await evaljs(`window.__sheets`);
    console.log("  the replay's sheets: " + JSON.stringify(sheets));
    ok("the replay opens the quest's sheet for each tap, with the recorded steps",
       sheets.length === 3 && sheets.every(s => s.replay && s.steps.length > 0) &&
       sheets.slice(0, 2).every(s =>
         JSON.stringify(s.steps) === JSON.stringify(seen.steps)));
    /* the third is the second task's: its Accept was refused at the cap,
       and the film says so where the recording did (replaySheetRefused) */
    ok("the replay says the refused Accept in the sheet, with Accept disabled",
       /at most 20 active quests/.test(sheets[2]?.said || "") && sheets[2]?.shut === true);
    /* the quest made over the API under that sheet has the sheet's own
       goal and is not its Accept (replaySheetMade): the one press is
       the refused create's */
    ok("and makes one press on Accept quest for the refused Accept" +
       (sheets[2]?.accepts === 1 ? "" : ": " + JSON.stringify(sheets.map(s => s.accepts))),
       sheets[2]?.accepts === 1);
    ok("and says no refusal in the sheets it was not said in",
       sheets.slice(0, 2).every(s => s.said === ""));
    ok("and closes each: off Not now, and off Accept quest",
       sheets.every(s => s.closed));
    ok("the replay makes no quest", (await active()) === before);
    await shot(`${slug}-notyet-film`);
    await fresh(`the page ${where}, out of its film`);

    /* the same sheet from a walk the connector records (stage-sheet!,
       server/mcp.clj; ticket 5bc2c3e4): a rehearsed quest create on a
       shut goal is the tap, the create after it is Accept quest, and the
       pin puts the quest in the tracker. No page takes part in the
       recording: the film is all the browser sees of it. */
    console.log(`· the connector's quest preview ${where}`);
    const tool = async (name, args) => {
      const res = await fetch(BASE + "/api/-/mcp", {method: "POST",
        headers: {...h, "Content-Type": "application/json", "Accept": "application/json"},
        body: JSON.stringify({jsonrpc: "2.0", id: 1, method: "tools/call",
                              params: {name, arguments: args}})});
      const doc = await res.json().catch(() => null);
      if (res.status !== 200 || !doc?.result || doc.result.isError)
        console.log(`  ${name}: ${res.status} ` + JSON.stringify(doc).slice(0, 400));
      return res.status === 200 && !!doc?.result && !doc.result.isError;
    };
    const top = await post("/api/led_tasks", {title: `Clear the loft ${where}`}, h);
    const loft = top.doc?.self;
    const box = await post("/api/led_tasks",
      {title: `Carry the boxes down ${where}`, parent: String(loft).split("/").pop()}, h);
    ok("a third task with an open child, for the connector's quest",
       top.status === 201 && !!loft && box.status === 201);
    const actives = async () => ((await get("/api/quests?state=active&owner=" +
      encodeURIComponent(me))).data?.items || []).map(q => q.self);
    const had = await actives();
    const rec = await post("/api/walks",
      {followed: await evaljs(`principalId() || viewerId()`),
       title: "A quest previewed by the connector " + where, docs: true}, h);
    const cWalk = rec.doc?.self;
    if (!cWalk) console.log("  the walk's create: " + JSON.stringify(rec));
    ok(`priya records a walk of her own, with no page in it ${where}`,
       rec.status === 201 && !!cWalk);
    const shutGoal = {self: loft, action: "complete"};
    ok("the connector rehearses the quest's create on the shut Complete",
       await tool("waymark_invoke",
                  {kind: "quest", action: "create", dry_run: true, input: shutGoal}));
    await sleep(1500);
    ok("and then makes the quest, with the same input",
       await tool("waymark_invoke", {kind: "quest", action: "create", input: shutGoal}));
    const quest = (await actives()).find(q => !had.includes(q));
    ok("the create made one quest of priya's", !!quest);
    await sleep(1500);
    ok("and the connector pins it",
       await tool("waymark_invoke",
                  {kind: "quest", id: String(quest).split("/").pop(), action: "pin"}));
    ok("the quest is priya's pinned one", (await pinned()).some(q => q.self === quest));
    await sleep(1500);
    ok("priya seals the connector's walk",
       (await post(cWalk + "/-/seal", {}, h)).status < 400 &&
       (await get(cWalk)).state === "sealed");
    /* the live quest is let go, out of the recording: the tracker the
       film shows is then the film's own */
    await post(quest + "/-/abandon", {}, h);
    ok("priya lets the connector's quest go", (await get(quest)).state === "abandoned");
    await evaljs(`refreshQuest().catch(() => {}); true`);
    await waitFor(`${bar}.hidden === true`, `no pinned quest before the film ${where}`, 15000);

    console.log(`· film: the connector's quest preview ${where}`);
    const left = await active();
    await evaljs(`window.__accept = null;
      window.__sheets = [];
      if (window.__sheetWatch) window.__sheetWatch.disconnect();
      window.__sheetWatch = new MutationObserver(ms => {
        const is = n => n.nodeType === 1 && n.matches("[data-surface='sheet']");
        for (const m of ms) {
          for (const n of m.addedNodes)
            if (is(n)) window.__sheets.push({replay: !!replay, closed: false,
              here: hereHref().split("?")[0],
              steps: [...n.querySelectorAll("[data-quest-steps] li")].map(l => l.textContent)});
          for (const n of m.removedNodes)
            if (is(n)) Object.assign(window.__sheets.find(s => !s.closed) || {}, {closed: true,
              said: n.querySelector("[data-quest-refused]")?.textContent || "",
              shut: !!n.querySelector("[data-surface='sheet.accept']")?.disabled});
        }
      });
      window.__sheetWatch.observe(document.body, {childList: true});
      new MutationObserver(() => {
        const b = document.querySelector(
          "[data-surface='sheet.accept'][data-replay-press]");
        if (b && !window.__accept)
          window.__accept = {text: b.textContent, shut: b.disabled,
                             tracker: getComputedStyle(${bar}).display !== "none"};
      }).observe(document.documentElement,
                 {subtree: true, attributes: true, attributeFilter: ["data-replay-press"]});
      location.hash = "#" + ${JSON.stringify(cWalk)} + "?film=1"; true`);
    await waitFor(`!!replay && !!${sheet} &&
                   ${sheet}.querySelectorAll("[data-quest-steps] li").length > 0`,
                  `the quest's sheet in the connector's film ${where}`, 240000, why);
    ok("the replay opens the quest's sheet, with its steps", true);
    if (phone) {
      await sheetFits("the sheet of the connector's film");
      await noOverflow("under the sheet of the connector's film");
    }
    await shot(`${slug}-connector-film-sheet`);
    await waitFor(`!!window.__accept`, "the replay's press on Accept quest", 240000, why);
    const accepted = await evaljs(`window.__accept`);
    console.log("  the replay's Accept: " + JSON.stringify(accepted));
    ok("the pointer presses Accept quest, in the sheet, with Accept offered",
       accepted.shut === false);
    await waitFor(`!document.querySelector("[data-surface='sheet']") &&
                   getComputedStyle(${bar}).display !== "none" &&
                   !!${bar}.querySelector("[data-surface='tracker.go']")`,
                  `the pinned quest in the film's tracker ${where}`, 240000, why);
    ok("the sheet closes off Accept quest, and the quest is pinned in the tracker", true);
    if (phone) await noOverflow("with the connector's quest in the film's tracker");
    await shot(`${slug}-connector-film-tracker`);
    await waitFor(`document.documentElement.getAttribute("data-film") === "ended"`,
                  `the connector's film to end ${where}`, 240000, why);
    ok(`the connector's film reaches ended ${where}`, true);
    const staged = await evaljs(`window.__sheets`);
    console.log("  the connector's film's sheets: " + JSON.stringify(staged));
    ok("the film opens one sheet, on the goal's own page, and closes it",
       staged.length === 1 && staged[0].replay && staged[0].closed &&
       staged[0].here === loft && staged[0].steps.length > 0);
    ok("its steps are said in words, with no /api/ path in them",
       (staged[0]?.steps || []).every(s => !s.includes("/api/")));
    ok("and says no refusal in it", staged[0]?.said === "" && staged[0]?.shut === false);
    ok("the connector's film makes no quest", (await active()) === left);
    await fresh(`the page ${where}, out of the connector's film`);

    /* a rehearsal the create refuses (ticket 03d24d3e): priya holds her
       20 active quests (quests.clj, active-cap) before the walk starts,
       so the connector's rehearsed create is refused as the create would
       be. The walk holds the sheet's beat with that answer (stage-sheet!,
       seen {ok false}); the film says the sentence in the sheet, with
       Accept disabled, and nowhere else. */
    {
      console.log(`· the connector's refused quest preview ${where}`);
      const filled = [];
      let over = null;
      for (let i = 0; i < 21 && !over; i++) {
        const q = await post("/api/quests", shutGoal, h);
        if (q.status === 201 && q.doc?.self) filled.push(q.doc.self);
        else over = q;
      }
      console.log(`  ${filled.length} quests held, then: ` + JSON.stringify(over?.doc ?? null));
      ok("priya's active quests reach the cap, before the walk starts",
         filled.length > 0 && !!over && over.status >= 400 && over.status < 500 &&
         /at most 20 active quests/.test(over.doc?.detail || ""));
      const rec = await post("/api/walks",
        {followed: await evaljs(`principalId() || viewerId()`),
         title: "A quest the connector is refused " + where, docs: true}, h);
      const rWalk = rec.doc?.self;
      if (!rWalk) console.log("  the walk's create: " + JSON.stringify(rec));
      ok(`priya records a second walk of her own, with no page in it ${where}`,
         rec.status === 201 && !!rWalk);
      const res = await fetch(BASE + "/api/-/mcp", {method: "POST",
        headers: {...h, "Content-Type": "application/json", "Accept": "application/json"},
        body: JSON.stringify({jsonrpc: "2.0", id: 1, method: "tools/call",
                              params: {name: "waymark_invoke",
                                       arguments: {kind: "quest", action: "create",
                                                   dry_run: true, input: shutGoal}}})});
      const told = JSON.stringify(await res.json().catch(() => null));
      console.log(`  the refused rehearsal: ${res.status} ` + told.slice(0, 400));
      ok("the connector's rehearsed create is refused, with the engine's sentence",
         /at most 20 active quests/.test(told));
      await sleep(1500);
      ok("priya seals the refused rehearsal's walk",
         (await post(rWalk + "/-/seal", {}, h)).status < 400 &&
         (await get(rWalk)).state === "sealed");
      /* the held quests are let go after the seal, out of the recording */
      for (const q of filled) await post(q + "/-/abandon", {}, h);
      ok("priya lets the held quests go, out of her recording",
         (await Promise.all(filled.map(q => get(q)))).every(q => q.state === "abandoned"));

      console.log(`· film: the connector's refused quest preview ${where}`);
      const idle = await active();
      await evaljs(`window.__sheets = [];
        if (window.__sheetWatch) window.__sheetWatch.disconnect();
        window.__sheetWatch = new MutationObserver(ms => {
          for (const m of ms)
            for (const n of m.addedNodes)
              if (n.nodeType === 1 && n.matches("[data-surface='sheet']"))
                window.__sheets.push({replay: !!replay, here: hereHref().split("?")[0]});
        });
        window.__sheetWatch.observe(document.body, {childList: true});
        location.hash = "#" + ${JSON.stringify(rWalk)} + "?film=1"; true`);
      await waitFor(`!!replay && /at most 20 active quests/.test(${saidWhy})`,
                    `the refusal, in the sheet of the connector's film ${where}`, 240000, why);
      ok("the replay opens the quest's sheet with the refusal's sentence", true);
      ok("and Accept is disabled with that line",
         await evaljs(`${sheet}.querySelector("[data-surface='sheet.accept']").disabled === true`));
      /* the sentence is in the sheet alone, and the caption band is not
         drawn: no refusal line, no notice and no caption beside it */
      const alone = `(() => {
        const n = s => (String(s || "").match(/at most 20 active quests/g) || []).length;
        const s = document.querySelector("[data-surface='sheet']");
        const band = document.querySelector("#replaycaption");
        return n(document.body.textContent) === n(s && s.textContent) &&
          (!band || getComputedStyle(band).display === "none" || !band.textContent);
      })()`;
      ok("no refusal line, notice or caption is drawn outside the sheet", await evaljs(alone));
      if (phone) {
        await sheetFits("the refused sheet of the connector's film");
        await noOverflow("under the refused sheet of the connector's film");
      }
      await shot(`${slug}-connector-film-refused`);
      await waitFor(`document.documentElement.getAttribute("data-film") === "ended"`,
                    `the connector's refused film to end ${where}`, 240000, why);
      ok(`the connector's refused film reaches ended ${where}`, true);
      const shown = await evaljs(`window.__sheets`);
      console.log("  the refused film's sheets: " + JSON.stringify(shown));
      ok("the film opens one sheet, on the goal's own page",
         shown.length === 1 && shown[0].replay && shown[0].here === loft);
      ok("and still draws no refusal outside it at its end", await evaljs(alone));
      ok("the refused film makes no quest", (await active()) === idle);
      await fresh(`the page ${where}, out of the connector's refused film`);
    }
  };

  await fresh("the phone's page, out of its film");
  await notYet("on a phone", "phone", true);
  /* out of the phone: the same story at a desk */
  await send("Emulation.setTouchEmulationEnabled", {enabled: false});
  await send("Emulation.setDeviceMetricsOverride",
             {width: 1280, height: 800, deviceScaleFactor: 1, mobile: false});
  await send("Page.navigate", {url: BASE + "/api/-/ui"});
  await sleep(1200);
  await waitFor(ready, "the page at desktop, as priya", 15000);
  ok("the page is 1280 wide, out of the mobile shell",
     await evaljs(`innerWidth === 1280 &&
       document.documentElement.getAttribute("data-ui") !== "mobile"`));
  await notYet("at desktop", "desktop", false);
  await send("Emulation.clearDeviceMetricsOverride");
}

if (MODE === "batch-a") await batchAStory();
else if (MODE === "access") await accessStory();
else if (MODE === "invitation") await invitationStory();
else if (MODE === "guided") await guidedStory();
else if (MODE === "later") await laterStory();
else if (MODE === "quest-phone") await questPhoneStory();
else await mealplanStory();

console.log(`\nUI drive (${MODE}): ${passed} checks passed` +
            (consoleErrors.length ? `\nCONSOLE ERRORS:\n` + consoleErrors.join("\n") : ", no console errors"));
ws.close();
process.exit(consoleErrors.length ? 1 : 0);
