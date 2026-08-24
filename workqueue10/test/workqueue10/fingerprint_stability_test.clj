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
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
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
  #"^(?:workqueue10|mealplan10|choreplan10|eveningplan10|calendar10)\..*\.resources\.|^(?:mealplan10|choreplan10|eveningplan10|calendar10|workqueue10)\.resources\.")

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
      ;; 32 since waymark-iqa.6 added :insight (31 since .4's
      ;; :tickler). The count is a census, not a law: it moves when the
      ;; house gains a kind and never otherwise, which is exactly the
      ;; change it is here to notice.
      (is (= 32 (count before)) "the whole household is under the lens")
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
    (is (= #{"grocery_list" "meal_line" "plan_day" "product" "rotation"}
           (set warned))
        (str "the formless residue is this census and no wider; when a kind "
             "leaves it, delete it from here — when one JOINS it, ask why"))))
