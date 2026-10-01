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

   ACCESS (the held seat call's names, against waymark10.access-dev,
   which seeds its own member, seat and held seat-restate; CI runs it
   in the ui-access job of .github/workflows/tests.yml):
   1. the same boot, naming waymark10.access-dev/start! on 8124
   2. the same chromium
   3. node waymark10/scripts/ui-drive.mjs access

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

   (The FEED and RECIPE drives — the day's scroll-first face and the
   recipe editor — retired with the feed, 2026-09, and so did
   feed-smoke.sh.)

   The story's plan checks stay self-normalizing (a partial re-run
   brings the plan back to planned before them), and the ported-page
   additions below seed uniquely-named rows per run — but the meal
   sections assume the fresh world of step 1. */
const MODE = ["batch-a", "access", "invitation", "guided"].includes(process.argv[2])
  ? process.argv[2] : "story";
const DEBUG_PORT = process.env.CDP_PORT || "9223";
const BASE = process.env.BASE ||
  (["batch-a", "guided"].includes(MODE) ? "http://localhost:8123"
   : ["access", "invitation"].includes(MODE) ? "http://localhost:8124"
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
  await waitFor(`${replayState} === "ended"`, "the replay to reach its last frame");
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
     scope: [{kind: "invitation", actions: []}, {kind: "seat", actions: []}],
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
   replaced, and an invitation to bo opens in bo's own hand. Each tab
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
    const until = async (pred, what, ms = 8000) => {
      const t0 = Date.now();
      while (Date.now() - t0 < ms) {
        if (await js(pred)) return true;
        await sleep(150);
      }
      throw new Error(`${label}: timed out waiting for ${what}`);
    };
    return {call: c.call, js, until, close: c.close};
  };
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
      `dialog[open][data-guided="${recipeKey}"]`)})`, "the guided dialog", 15000);
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

  console.log("· stopping");
  await press(B, "dialog[open] .dlgfoot", "Cancel");
  await press(A, "dialog[open] .dlgfoot", "Cancel");
  await A.js(`document.querySelector("#sharebtn").click(); true`);
  ok("the toggle turns sharing off", await A.js(`sessionStorage.getItem("wm10.share.ui")`) === null);
  await B.js(`document.querySelector("#followchip [data-guided-mark]").click(); true`);
  await B.until(`localStorage.getItem("wm10.follow.ui") === null &&
                 !document.querySelector("#followchip [data-guided-mark]")`, "guided mode off");
  ok("the guided mark turns guided mode off, and the follow stands",
     await B.js(`followId`) === "ada");
  A.close(); B.close();
  for (const ctx of contexts)
    await browser.call("Target.disposeBrowserContext", {browserContextId: ctx});
  browser.close();
}

if (MODE === "batch-a") await batchAStory();
else if (MODE === "access") await accessStory();
else if (MODE === "invitation") await invitationStory();
else if (MODE === "guided") await guidedStory();
else await mealplanStory();

console.log(`\nUI drive (${MODE}): ${passed} checks passed` +
            (consoleErrors.length ? `\nCONSOLE ERRORS:\n` + consoleErrors.join("\n") : ", no console errors"));
ws.close();
process.exit(consoleErrors.length ? 1 : 0);
