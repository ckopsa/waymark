(ns waymark10.ui-collection-doors-test
  "A safe collection door (:collection-doors, ticket 80c6c9e2) rides the
  collection document's `actions` with safety.safe and no effect. The
  generic UI sorts it away from the bulk moves and the create on
  purpose, and offers no button for it: collectionDoors
  (ui/130-collection.js).

  TWO TESTS, as ui-hidden-sse-test has them and for its reasons: the
  first reads the shipped source and runs everywhere; the second RUNS
  collectionDoors under node, and SKIPS when `node` is not on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private fragment "waymark10/ui/130-collection.js")

(defn- source [] (slurp (io/resource fragment)))

;; ── the source invariant ────────────────────────────────────────────

(deftest the-bar-reads-its-doors-through-collection-doors
  (let [src (source)]
    (testing "a safe entry is judged first, before its name or its effect"
      (is (str/includes? src "if (entry.safety?.safe) out.safe.push([name, entry]);")))
    (testing "the bar takes its create and its bulk moves from that one place"
      (is (str/includes? src "const doors = collectionDoors(doc.actions);"))
      (is (str/includes? src "const bulkActions = doors.bulk;"))
      (is (str/includes? src "const create = doors.create;"))
      (is (not (str/includes? src "const create = doc.actions?.create;"))))))

;; ── the behavioural half ────────────────────────────────────────────

(def ^:private fn-start "function collectionDoors(actions) {")
(def ^:private fn-end "/* end collectionDoors */")

(defn- collection-doors-js []
  (let [src (source)
        from (str/index-of src fn-start)
        to (str/index-of src fn-end)]
    (assert (and from to (< from to)) "marker moved: collectionDoors")
    (subs src from to)))

(def ^:private cases-js "
let failures = 0;
function ok(cond, what) {
  if (cond) console.log('  ok   ' + what);
  else { failures += 1; console.log('  FAIL ' + what); }
}
const safety = {safe: true, idempotent: true, reversible: true, confirm: false};
const d = collectionDoors({
  create: {method: 'POST', href: '/api/film_rules', effect: {to: 'draft'},
           safety: {idempotent: false, reversible: false, confirm: false}},
  query: {method: 'GET', href: '/api/film_rules'},
  retire: {method: 'POST', href: '/api/film_rules/-/retire',
           effect: {to: 'retired', bulk: true},
           safety: {idempotent: true, reversible: false, confirm: false}},
  judge: {method: 'POST', href: '/api/film_rules/-/judge',
          input: {type: 'object', properties: {}}, safety}
});
ok(d.safe.length === 1 && d.safe[0][0] === 'judge', 'judge is a safe door');
ok(d.bulk.length === 1 && d.bulk[0][0] === 'retire',
   'the one bulk move is retire: judge is not among them');
ok(d.create && d.create.effect.to === 'draft', 'create is still the create');

const e = collectionDoors({
  create: {method: 'POST', safety},
  sweep: {method: 'POST', effect: {bulk: true}, safety}
});
ok(e.create === null, 'a safe door named create is no create');
ok(e.bulk.length === 0, 'a safe door is no bulk move, whatever it carries');
ok(e.safe.length === 2, 'both are safe doors');

const none = collectionDoors(undefined);
ok(none.create === null && !none.bulk.length && !none.safe.length,
   'a document with no actions has no doors');

console.log(failures ? ('FAILURES: ' + failures) : 'ALL OK');
process.exit(failures ? 1 : 0);
")

(defn- node? []
  (try (zero? (:exit (sh "node" "--version"))) (catch Exception _ false)))

(deftest a-safe-door-is-no-bulk-move-and-no-create
  (if-not (node?)
    (println "waymark10.ui-collection-doors-test: no `node` on PATH —"
             "the behavioural half is SKIPPED (the source invariant above"
             "still ran).")
    (let [f (java.io.File/createTempFile "wm10-doors-" ".mjs")]
      (try
        (spit f (str (collection-doors-js) "\n" cases-js))
        (let [{:keys [exit out err]} (sh "node" (.getPath f))]
          (is (zero? exit) (str "node harness failed\n" out err)))
        (finally (.delete f))))))
