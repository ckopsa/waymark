(ns waymark10.declaration-test
  "The authored-surface gate: unknown keys refuse with a path where
  they used to silently declare nothing, and the shipped clj-kondo
  hook's key sets cannot drift from the gate's."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [waymark10.declaration :as declaration]
            [waymark10.resource :as r]))

(def ^:private clean
  {:kind :decl_probe
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 50}]]]
   :flow [[:open :close :closed
           {:one-way "Closing records completion; nothing external changes."
            :display {:label "Close"}}]]})

(deftest a-clean-declaration-passes
  (is (nil? (declaration/errors clean)))
  (is (map? (r/resource clean))))

(deftest a-typoed-top-level-key-refuses-with-a-path
  (let [e (try (r/resource (-> clean
                               (dissoc :flow)
                               (assoc :states [:open]
                                      :initial :open
                                      :terminal #{}
                                      :filtrable {:state #{:eq}})))
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :declaration (:check (ex-data e))))
    (is (= [:filtrable] (:path (ex-data e))))
    (is (re-find #"decl_probe \[declaration\] filtrable" (ex-message e)))
    (is (re-find #":filterable" (ex-message e)))))

(deftest a-typoed-action-key-refuses-with-a-path
  (let [e (try (r/normalize-action
                :decl_probe :touch
                {:from #{:open} :to :open
                 :safety {:idempotent true :reversible false :confirm false}
                 :guard []})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :declaration (:check (ex-data e))))
    (is (= [:actions :touch :guard] (:path (ex-data e))))
    (is (re-find #":guards" (ex-message e)))))

(deftest every-declaration-refusal-carries-a-path
  (doseq [bad [(assoc clean :filtrable {})
               (assoc clean :actions {:touch {:frmo #{:open}}})]]
    (let [e (try (declaration/check! bad)
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (vector? (:path (ex-data e)))
          (pr-str (ex-data e))))))

(deftest the-warning-footer-advertises-the-recall-affordance
  ;; a shapeless text field mints a [long-text] warning; the footer
  ;; must ride along, naming dev/explain as the re-reader (probe D5)
  (let [err (with-out-str
              (binding [*err* *out*]
                (r/resource (assoc clean :schema
                                   [:map [:notes :string]]))))]
    (is (re-find #"\[long-text\]" err))
    (is (re-find #"waymark10\.dev/explain decl_probe" err))))

;; ── the kondo drift guard ───────────────────────────────────────────
;; The shipped hook cannot require project code, so it carries copies
;; of the three key sets; this test is what holds them equal.

(def ^:private hook-file
  (io/file "resources/clj-kondo.exports/waymark10/waymark10/hooks/waymark10/resource.clj"))

(defn- hook-defs
  "The hook file's top-level (def name value) forms as {name value}."
  []
  (let [forms (read-string (str "[" (slurp hook-file) "]"))]
    (into {}
          (keep (fn [f] (when (and (seq? f) (= 'def (first f)))
                          [(second f) (nth f 2)])))
          forms)))

(deftest the-shipped-kondo-hook-key-sets-cannot-drift
  (let [defs (hook-defs)]
    (is (= (set declaration/top-level-keys) (get defs 'top-level-keys)))
    (is (= (set declaration/action-keys) (get defs 'action-keys)))
    (is (= r/flow-opt-keys (get defs 'flow-opt-keys)))))
