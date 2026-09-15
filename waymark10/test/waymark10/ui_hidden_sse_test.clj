(ns waymark10.ui-hidden-sse-test
  "waymark-dxnp: a hidden tab releases its SSE connections.

  Three long-lived streams per tab against an HTTP/1.1 server and a
  6-connection browser cap means the SECOND tab's plain GETs queue
  forever. sse() (ui/200-events-follow.js) therefore aborts every
  in-flight stream on document.hidden, parks its retry loop with no
  timer pending, and on visibilitychange back reopens each one —
  carrying Last-Event-ID where the route has ever handed out an id
  (the firehose), and paying one screen refetch where it has not
  (presence and intents carry no ids: see server/presence.clj).

  TWO TESTS, DELIBERATELY. The first reads the shipped source — it is
  the one that runs everywhere, including a CI runner with no node,
  and it is what catches the fragment being reverted or rewritten past
  the invariant. The second actually RUNS sse() under node against a
  fake fetch and a fake document, because \"holds no connections while
  hidden\" is a claim about behaviour and no amount of grepping proves
  it. There is no JS runtime in this project's dependency tree and
  adding one (GraalJS) to assert one function would cost more than it
  buys, so the behavioural half SKIPS when `node` is not on PATH
  rather than failing. Read the skip line in CI output as: unproven
  here, proven on a developer machine."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private fragment "waymark10/ui/200-events-follow.js")

(defn- source [] (slurp (io/resource fragment)))

;; ── the source invariant ────────────────────────────────────────────

(deftest sse-releases-its-connection-while-hidden
  (let [src (source)]
    (testing "one registry of live streams, and one listener for the page"
      (is (str/includes? src "const SSE_STREAMS = new Set()"))
      (is (str/includes? src "\"visibilitychange\"")))
    (testing "hiding aborts the in-flight body"
      (is (str/includes? src "s.ctl.abort()"))
      (is (str/includes? src "new AbortController()"))
      (is (str/includes? src "signal: ctl.signal")))
    (testing "…and parks the retry loop instead of backing off into it"
      (is (str/includes? src "while (ssePaused) await new Promise"))
      (is (str/includes? src "if (ssePaused) continue;")))
    (testing "returning resumes by id where the route hands ids out"
      (is (str/includes? src "headers[\"Last-Event-ID\"]"))
      (is (str/includes? src "if (f.id) stream.lastId = f.id;")))
    (testing "…and refetches the screen once where it does not"
      (is (str/includes? src "if (s.lastId == null) blind = true;"))
      (is (str/includes? src "setTimeout(render, 0)")))))

;; ── the behavioural half ────────────────────────────────────────────

;; The fragment's SELF-CONTAINED PRELUDE — parseFrame, the pause
;; machinery, sse() — ends where the one-shot replay helper begins.
;; Everything after that line reaches into the DOM and the follow
;; chip, which is a page, not a function.
(def ^:private prelude-end "/* a one-shot replay:")

(defn- prelude []
  (let [src (source)
        cut (str/index-of src prelude-end)]
    (assert cut (str "marker moved: " prelude-end))
    (subs src 0 cut)))

(def ^:private fakes "
/* ── the fakes ─────────────────────────────────────────────────── */
const enc = new TextEncoder();
let hidden = false, renders = 0, live = 0;
const listeners = [], opens = [];
globalThis.principalHeaders = () => ({});
globalThis.localStorage = {getItem: () => null, setItem() {}, removeItem() {}};
globalThis.$ = () => ({replaceChildren() {}, set textContent(_v) {}});
globalThis.el = () => ({});
globalThis.render = () => { renders += 1; };
globalThis.document = {
  get hidden() { return hidden; },
  addEventListener: (_t, f) => listeners.push(f)
};
globalThis.fetch = (href, opts) => {
  let queue = [], waiter = null, aborted = false;
  const conn = {href, headers: opts.headers, push(text) {
    queue.push(enc.encode(text));
    if (waiter) { const w = waiter; waiter = null; w(); }
  }};
  opens.push(conn); live += 1;
  if (opts.signal) opts.signal.addEventListener('abort', () => {
    aborted = true; live -= 1;
    if (waiter) { const w = waiter; waiter = null; w(); }
  });
  return Promise.resolve({ok: true, body: {getReader: () => ({
    async read() {
      for (;;) {
        if (aborted) throw new Error('aborted');
        if (queue.length) return {done: false, value: queue.shift()};
        await new Promise(r => { waiter = r; });
      }
    }
  })}});
};
const settle = (ms = 25) => new Promise(r => setTimeout(r, ms));
const flip = v => { hidden = v; for (const f of listeners) f(); };
let failures = 0;
function ok(cond, what) {
  if (cond) console.log('  ok   ' + what);
  else { failures += 1; console.log('  FAIL ' + what); }
}
")

(def ^:private cycle-js "
/* ── the cycle ─────────────────────────────────────────────────── */
(async () => {
  const seen = [];
  sse('/api/-/events', f => seen.push(f));      // the firehose: ids
  sse('/api/-/presence', f => seen.push(f));    // ephemeral: no ids
  await settle();
  ok(live === 2, 'a visible tab holds both streams');

  opens[0].push('event: transition\\nid: 7\\ndata: {\"self\":\"/api/x/1\"}\\n\\n');
  opens[1].push('event: presence\\ndata: {\"event\":\"snapshot\"}\\n\\n');
  await settle();
  ok(seen.length === 2, 'frames arrive on both streams');

  flip(true);
  await settle();
  ok(live === 0, 'a hidden tab holds NO connections');
  const atHide = opens.length;
  await settle(120);
  ok(opens.length === atHide, 'the retry loop does not reconnect while hidden');

  flip(false);
  await settle();
  ok(live === 2, 'returning reopens every stream');
  const reopened = opens.slice(atHide);
  const fire = reopened.find(c => c.href === '/api/-/events');
  const pres = reopened.find(c => c.href === '/api/-/presence');
  ok(fire && fire.headers['Last-Event-ID'] === '7',
     'the firehose resumes from the last id it saw');
  ok(pres && !('Last-Event-ID' in pres.headers),
     'a stream that never handed out an id asks for no resume');
  ok(renders === 1, 'the screen is refetched exactly once instead');

  fire.push('event: transition\\nid: 8\\ndata: {\"self\":\"/api/x/1\"}\\n\\n');
  await settle();
  ok(seen.length === 3, 'frames flow again after the cycle');

  console.log(failures ? ('FAILURES: ' + failures) : 'ALL OK');
  process.exit(failures ? 1 : 0);
})();
")

(defn- node? []
  (try (zero? (:exit (sh "node" "--version"))) (catch Exception _ false)))

(deftest the-hidden-visible-cycle-runs
  (if-not (node?)
    (println "waymark10.ui-hidden-sse-test: no `node` on PATH —"
             "the behavioural half is SKIPPED (the source invariant above"
             "still ran).")
    (let [f (java.io.File/createTempFile "wm10-sse-" ".mjs")]
      (try
        (spit f (str fakes "\n" (prelude) "\n" cycle-js))
        (let [{:keys [exit out err]} (sh "node" (.getPath f))]
          (is (zero? exit) (str "node harness failed\n" out err)))
        (finally (.delete f))))))
