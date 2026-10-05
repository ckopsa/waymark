(ns waymark10.ui-remedy-prefill-test
  "A remedy that bound an :input opens its door's form prefilled with
  it: remedyChips (ui/140-links-access.js) reads the refusal's
  resolved_remedies beside its tokens.

  Two halves, as in ui-hidden-sse-test: the source invariant runs
  everywhere; the behavioural half runs remedyChips under node against
  fakes, and SKIPS when `node` is not on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private fragment "waymark10/ui/140-links-access.js")

(defn- source [] (slurp (io/resource fragment)))

;; ── the source invariant ────────────────────────────────────────────

(deftest the-unavailable-block-hands-its-bindings-to-the-chips
  (let [src (source)]
    (testing "both not-now renderings pass resolved_remedies"
      (is (str/includes? src "remedyChips(entry.remedies, doc, null, entry.resolved_remedies)"))
      (is (str/includes? src "g[0][1].resolved_remedies")))
    (testing "a bound input becomes the dialog's prefill"
      (is (str/includes? src "function remedyChips(remedies, doc, onAct, resolved)"))
      (is (str/includes? src "async function remedyCreate(col, prefill)")))))

;; ── the behavioural half ────────────────────────────────────────────

(defn- chips-fn
  "remedyChips and remedyCreate: from the remedies banner to pulseAction."
  []
  (let [src (source)
        from (str/index-of src "function remedyChips")
        to (str/index-of src "function pulseAction")]
    (assert (and from to) "markers moved in 140-links-access.js")
    (subs src from to)))

(def ^:private harness "
const opened = [], pulsed = [];
let hash = '';
globalThis.location = {set hash(v) { hash = v; }, get hash() { return hash; }};
globalThis.el = (tag, attrs, ...kids) => {
  const n = {tag, attrs: attrs || {}, kids: [], replaced: null,
             append(...xs) { this.kids.push(...xs); },
             replaceWith(x) { this.replaced = x; }};
  n.append(...kids);
  return n;
};
globalThis.label = (a) => a;
globalThis.pretty = (k) => k;
globalThis.wellKnown = () => Promise.resolve({});
globalThis.collectionHref = (_w, kind) => '/api/' + kind + 's';
globalThis.api = (href) => Promise.resolve(
  {ok: true, body: {kind: 'tag_collection', self: href,
                    actions: {create: {href}}}});
globalThis.actionDialog = (o) => { opened.push(o); };
globalThis.pulseAction = (a) => { pulsed.push(a); };
const tick = () => new Promise(r => setTimeout(r, 0));
let failures = 0;
const check = (ok, msg) => { if (!ok) { failures++; console.error('FAIL: ' + msg); } };
(async () => {
  /* a create elsewhere, its input bound */
  const box = remedyChips(['tag.create'], {kind: 'crate', actions: {}}, null,
                          [{door: 'tag.create', input: {label: 'L1'}}]);
  await tick();
  const a = box.kids[0].replaced;
  check(a && a.attrs.href === '#/api/tags', 'the chip links to the collection');
  let prevented = false;
  a.attrs.onclick({preventDefault() { prevented = true; }});
  await tick();
  check(prevented, 'the click opens the modal, not the page');
  check(opened.length === 1 && opened[0].name === 'create'
        && opened[0].prefill.label === 'L1', 'the create modal opens prefilled');
  /* a create with nothing bound keeps its plain link */
  const bare = remedyChips(['tag.create'], {kind: 'crate', actions: {}});
  await tick();
  let p2 = false;
  bare.kids[0].replaced.attrs.onclick({preventDefault() { p2 = true; }});
  check(!p2 && opened.length === 1, 'an unbound create still navigates');
  /* a door on this page, its input bound */
  const here = remedyChips(['crate.ship'], {kind: 'crate', actions: {ship: {}}},
                           null, [{door: 'crate.ship', input: {to: 'x'}}]);
  here.kids[0].attrs.onclick();
  check(opened.length === 2 && opened[1].name === 'ship'
        && opened[1].prefill.to === 'x', 'the page door opens prefilled');
  const plain = remedyChips(['crate.ship'], {kind: 'crate', actions: {ship: {}}});
  plain.kids[0].attrs.onclick();
  check(pulsed.length === 1 && opened.length === 2, 'an unbound one only pulses');
  process.exit(failures ? 1 : 0);
})();
")

(defn- node? []
  (try (zero? (:exit (sh "node" "--version"))) (catch Exception _ false)))

(deftest a-bound-remedy-opens-its-form-prefilled
  (if-not (node?)
    (println "waymark10.ui-remedy-prefill-test: no `node` on PATH —"
             "the behavioural half is SKIPPED (the source invariant above"
             "still ran).")
    (let [f (java.io.File/createTempFile "wm10-remedy-" ".mjs")]
      (try
        (spit f (str (chips-fn) "\n" harness))
        (let [{:keys [exit out err]} (sh "node" (.getPath f))]
          (is (zero? exit) (str "node harness failed\n" out err)))
        (finally (.delete f))))))
