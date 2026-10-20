(ns workqueue10.fingerprint-stability-test
  "The law is a form, and a form must print the same twice.

  waymark-j82: task rows at the deployed engine carried law_revision
  73, and almost every one of those revisions was minted by nothing.
  A bare fn hashed by `pr-str` of the resident object —
  #object[…$fn__12873 0x7698a3d9 …], a compiler-assigned class number
  and a JVM identity hash — so nine kinds' fingerprints differed on
  every boot with no code change at all; a tenth moved whenever any
  namespace happened to load earlier, because a `#(…)` inside a
  handler body rode a load-global reader gensym into the stored form.
  The engine dutifully minted a revision for each, and time travel's
  as-of basis, the decision record and the propose hold all cited
  numbers that were boot noise.

  This suite is the bug's own shape, run inside one JVM: compute every
  registered kind's fingerprint, then RE-EVALUATE the declarations —
  which mints new fn objects, new class numbers and new reader
  gensyms, exactly what a fresh boot does — and demand all thirty come
  back byte-identical. The load-order half is provoked deliberately:
  before the second pass we load unrelated namespaces and burn the
  gensym counter forward, so the counter cannot accidentally land
  where it started."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.expr :as e]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.scenario :as sc]
            [workqueue10.main :as main]))

(defn- hashes
  "kind → fingerprint hash, over the whole registered household."
  []
  (into (sorted-map)
        (map (fn [res] [(name (:kind res)) (fp/fingerprint-hash (r/fingerprint res))]))
        (main/check-resources)))

(def ^:private declaring-namespaces
  "Every loaded namespace that declares a kind of this household —
  the ones a boot evaluates and this test re-evaluates."
  #"^(?:workqueue10|mealplan10|choreplan10|calendar10)\..*\.resources\.|^(?:mealplan10|choreplan10|calendar10|workqueue10)\.resources\.")

(defn- declaring-nses []
  (->> (all-ns)
       (map ns-name)
       (filter #(re-find declaring-namespaces (str %)))
       sort))

(defn- roots
  "Every var root these namespaces currently hold — the world as the
  rest of the suite already captured it."
  [nses]
  (into {} (for [n nses
                 [_ v] (ns-interns (the-ns n))
                 :when (.hasRoot ^clojure.lang.Var v)]
             [v (deref v)])))

(defn- reboot!
  "Simulate the next boot without leaving this one: perturb everything
  the old hash was secretly a function of, then re-evaluate every
  declaring namespace. Reloading re-runs each `def`, so every bare fn
  becomes a new object with a new class number and every `#(…)` in a
  guard or handler body is re-read with fresh gensyms."
  [nses]
  ;; load order: namespaces the household never mentions, loaded in
  ;; the middle of its life — the bead's probe, made a fixture
  (require 'clojure.zip 'clojure.data 'clojure.instant)
  ;; and the reader's gensym counter, walked somewhere it has never
  ;; been, so a match below cannot be coincidence
  (dotimes [_ 250] (read-string "#(vector % %2 %&)"))
  (doseq [n nses] (require n :reload)))

(deftest every-kinds-fingerprint-survives-a-reboot
  ;; The probe is destructive by nature — it replaces live declaration
  ;; objects — and sibling suites (mealplan10.style-invariance-test)
  ;; pin their law by SHARING those very objects. So the world is
  ;; photographed before the reboot and put back after, whatever the
  ;; verdict: this test may not decide what its neighbours see.
  (let [nses (declaring-nses)
        world (roots nses)
        before (hashes)]
    (try
      ;; 37 since waymark-jfv.20 added :composition_request — the
      ;; person's own pull past the weekly cap, one tap, never an
      ;; agent's (36 since waymark-jfv.11 added :person — the roster,
      ;; so a composed plan stops inventing who somebody is; 35 since
      ;; waymark-jfv.3's :outcome and :outcome_piece, TWO kinds because
      ;; consent is per piece and a verdict carrying a selection is
      ;; inexpressible in the card grammar; 33 since waymark-jfv.2's
      ;; :value, 32 since waymark-iqa.6's :insight, 31 since .4's
      ;; :tickler). The count is a census, not a law: it moves when the
      ;; house gains or loses a kind and never otherwise, which is exactly the
      ;; change it is here to notice.
      ;; …and 39 since waymark-bug's :hypothesis, the house's first row
      ;; that carries a NUMBER about the people in it: a claim, a shape,
      ;; the rows it is about, and a posterior no door can set.
      ;; …and 43 since waymark-i89n.2/.3's day plan: context, day_plan,
      ;; block and span — the day as rows, so the feed can read the
      ;; block a person is in; 44 since .4's decision — the unit of
      ;; intention on a block, whose start is the verdict.
      ;; …and 40 since the 2026-09 lean-down retired the evening fold
      ;; (activity, evening_plan, evening_session) and weather — the
      ;; census moves down as well as up, and this line says so.
      ;; …and 41 since docs/spec-seat.md § 13.8's :inbox_item — the
      ;; household's unanswered mail as rows, and the decision tree over
      ;; them. The census moves because the house gained a kind, which
      ;; is exactly the change this line is here to notice.
      ;; …and 40 since the 2026-09 retirement of :permission_slip
      ;; (waymark-442.6), which the owner did not use. The :decision
      ;; key it first rode stays; only the kind goes.
      ;; …and 36 since the 2026-09 retirement of the feed took the
      ;; kinds that only existed to be carded on it: :tickler,
      ;; :outcome, :outcome_piece and :composition_request.
      (is (= 36 (count before)) "the whole household is under the lens")
      (is (seq nses) "…and the declarations are re-evaluable in place")
      (reboot! nses)
      (let [after (hashes)
            moved (into (sorted-map)
                        (remove (fn [[k h]] (= h (get before k))))
                        after)]
        (is (= before after)
            (str "these kinds' fingerprints moved with no change of law: "
                 (str/join ", " (keys moved))
                 " — the law is a form, and a form must print the same twice"))
        (testing "no kind quietly vanished or arrived across the reboot"
          (is (= (keys before) (keys after)))))
      (finally
        (doseq [[v val] world] (alter-var-root v (constantly val)))))
    (testing "and the world is handed back exactly as it was borrowed"
      (is (= before (hashes))))))

(deftest the-residue-that-still-hashes-by-address-says-so
  ;; The trade waymark-j82 struck, held where a reader can see it: a
  ;; guard whose :check is a bare fn has no content to hash, so its
  ;; leaf is the ADDRESS the declaration carries it at. Stable, and
  ;; honest about being blind — the checks warn on every one of them
  ;; ([opaque-residue]), and that warning is the census below.
  (let [warned (into (sorted-set)
                     (comp (mapcat (fn [res]
                                     (map (fn [w] [(name (:kind res)) w])
                                          (:waymark10/warnings (meta res)))))
                           (filter (fn [[_ w]] (str/starts-with? w "[opaque-residue]")))
                           (map first))
                     (main/check-resources))]
    ;; empty since waymark ticket 5937bb50: the meal-plan :accepts
    ;; guards now capture their form through defacceptsfn
    (is (= #{}
           (set warned))
        (str "the formless residue is this census and no wider; when a kind "
             "leaves it, delete it from here — when one JOINS it, ask why"))))

(deftest how-a-kinds-work-ends-is-law-and-mints-a-revision
  ;; waymark-iqa.24/.25: `:over` says which of a kind's endings the
  ;; house STANDS BEHIND and which it let go. It began read-side, off
  ;; fingerprint-of's projection, but the ending wall came to read it
  ;; (its ended states and :ways-back) to shut a row's doors — so it is
  ;; law. waymark-fp62.4.1.2 (owner's ruling 2026-09-28) put it in the
  ;; fingerprint: re-spelling a kind's endings changes which doors
  ;; open, and must mint a revision, a hold and a sweep. Kinds that
  ;; declare :over took a one-time re-mint for it.
  (let [declaring (filter :over (main/check-resources))]
    (is (seq declaring) "no kind declares :over — this test has gone stale")
    (doseq [res declaring]
      (testing (name (:kind res))
        (is (not= (fp/fingerprint-hash (r/fingerprint (dissoc res :over)))
                  (fp/fingerprint-hash (r/fingerprint res)))
            (str (name (:kind res))
                 ": declaring how its work ENDS left its fingerprint"
                 " unmoved — :over is law the ending wall reads, and"
                 " fingerprint-of must project it"))))))

(defn- when-trees
  "Every declarative :when tree a kind carries, wherever it sits."
  [res]
  (->> (tree-seq (some-fn map? vector? set?)
                 #(if (map? %) (vals %) (seq %))
                 (into {} res))
       (keep #(when (map? %) (:when %)))
       (filter #(and (seq? %) (e/well-formed? %)))
       distinct))

(defn- when-guards
  "Every map a kind carries that declares a :when tree — the guard
  itself, so its :pending posture rides beside the tree."
  [res]
  (->> (tree-seq (some-fn map? vector? set?)
                 #(if (map? %) (vals %) (seq %))
                 (into {} res))
       (filter #(and (map? %) (seq? (:when %)) (e/well-formed? (:when %))))
       distinct))

(def ^:private principals
  "Who acts now, each judged: an agent and a person."
  [{:principal "seat:fingerprint-pin" :faces #{"seat:fingerprint-pin"}}
   {:principal "person:fingerprint-pin" :faces #{"person:fingerprint-pin"}}])

(defn- history-actors
  "A scenario's :history as the scope's :actors — every face of every
  actor of a door, as the guard scope's log answers it."
  [history]
  (fn [door]
    (into #{}
          (comp (filter #(= (name door) (name (:action %))))
                (mapcat (juxt :as :acted-by))
                (remove nil?)
                (map str))
          history)))

(defn- judged-scopes
  "One scope per principal per scenario of the kind — an empty one when
  the kind declares no scenario. Each lends the scenario's :history as
  :actors, its row's id as :self, and :rows-matching and
  :count-matching served from its :given rows, as offline-ctx does at
  check tier — so a related-row tree is judged two-valued."
  [res]
  (for [p principals
        s (or (seq (:scenarios res)) [{:name :fingerprint-pin}])
        :let [reads (@#'sc/scenario-reads s)]]
    (assoc p :data {} :input {} :vars {}
           :actors (history-actors (:history s))
           :self (str "scenario:" (name (:name s)))
           :rows-matching (:rows-matching reads)
           :count-matching (:count-matching reads))))

(deftest the-unknown-verdict-moves-no-existing-law
  ;; docs/spec-related-row-law.md §9 step 1: the third verdict is new
  ;; language, and nothing the household already declares may change
  ;; by it. The fingerprint hashes the normalized tree and neither
  ;; normalize nor fingerprint-of learned the sentinel, so no hash
  ;; moves; the verdict is pinned here: given who acts and the log a
  ;; tree reads, no :when answers unknown — the two-valued verdict it
  ;; always gave. (me) and did read the principal and :actors, so each
  ;; tree is judged by an agent and a person over each scenario's
  ;; :history (step 9); (self), exists, none and count-rows read :self
  ;; and the row hooks, lent from each scenario's row and :given.
  (let [resources (main/check-resources)
        trees (mapcat (fn [res] (map #(vector (name (:kind res)) % res) (when-trees res)))
                      resources)]
    (is (seq trees) "no kind declares a :when — this test has gone stale")
    (doseq [[kind w res] trees
            scope (judged-scopes res)]
      (testing (str kind " " (:principal scope) " " (pr-str w))
        (is (= (e/evaluate w scope) (e/evaluate (e/normalize w) scope)))
        (is (not (e/unknown? (e/evaluate w scope))))))
    (testing "with an empty scope, an unknown verdict is never a refusal (§5)"
      (doseq [res resources
              g (when-guards res)
              :let [w (:when g)]
              scope [{} {:data {} :input {} :vars {}}]]
        (testing (str (name (:kind res)) " " (pr-str w))
          (is (= (e/evaluate w scope) (e/evaluate (e/normalize w) scope)))
          (when (e/unknown? (e/evaluate w scope))
            (is (not= :deny (:pending g))
                "an unknown verdict reads pending, not a refusal")))))))
