(ns waymark10.seed-test
  "The seed loader (docs/spec-demo-clones.md § 1, § 5 item 1), over the
  in-memory twin and a toy kind: a seed loads through the doors and
  leaves a history, a refused step ends the boot, a working engine is
  refused, a seeded engine is not seeded twice, and a relative date is
  counted from the boot.

  The demo seed itself is loaded where its own kinds are, in
  workqueue10.demo-seed-test."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store])
  (:import (clojure.lang ExceptionInfo)
           (java.time Instant)))

(def ^:private chore
  "The toy kind: one door out of `open` and one back."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
     [:due {:optional true :x-display {:label "Due"}}
      [:maybe :waymark/date]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

;; A fixture of its own: this cast is the toy seed's, not the demo
;; seed's, so its displays do not follow the demo cast's names.
(def ^:private toy
  {:seed "toy"
   :version 1
   :cast {:ada {:id "ada" :display "Ada Example" :type :human}
          :plan {:id "plan" :display "Planner" :type :agent :acts-for :ada}}
   :steps [{:as :ada :kind :chore :create {:title "Water the ferns"} :ref :c1}
           {:as :plan :kind :chore :on :c1 :action "finish"}]})

(defn- engine
  ([] (engine {}))
  ([opts] (dev/scratch! [chore] (merge {:name "demo-test"} opts))))

(defn- moves [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- refused
  "The refusal a call threw, or nil when it went through."
  [f]
  (try (f) nil
       (catch ExceptionInfo e e)))

(deftest a-seed-loads-through-the-doors-and-leaves-a-history
  (let [eng (engine)
        result (seed/load! eng toy {})
        id (get-in result [:refs :c1 :id])]
    (is (true? (:seeded result)))
    (is (= "done" (name (:state (dev/row eng :chore id)))))
    (testing "each move is in the log, made by the member the step names"
      (let [log (moves eng :chore id)]
        (is (= ["ada" "plan"] (mapv #(get-in % [:actor :id]) log)))
        (is (= "finish" (name (:action (last log)))))))
    (testing "the cast are member rows, born through the member door"
      (is (= "Ada Example" (get-in (dev/row eng :member "ada") [:data :display])))
      (is (= "agent" (get-in (dev/row eng :member "plan") [:data :actor_type])))
      (is (= "ada" (get-in (dev/row eng :member "plan") [:data :acts_for])))
      (is (= 1 (count (moves eng :member "ada")))))))

(deftest a-step-the-law-refuses-ends-the-boot
  (let [eng (engine)
        ;; the schema refuses a chore with no title
        bad (update toy :steps conj
                    {:as :ada :kind :chore :create {:title ""}})
        e (refused #(seed/load! eng bad {}))]
    (is (some? e) "the load throws, so the boot ends")
    (is (true? (:waymark10/seed-refused (ex-data e))))
    (is (= 3 (:step (ex-data e))) "and it names the step")))

(deftest a-seed-refuses-an-engine-not-named-demo
  (let [eng (engine {:name "work"})
        e (refused #(seed/load! eng toy {}))]
    (is (some? e))
    (is (re-find #"demo-" (str (ex-message e))))
    (is (nil? (dev/row eng :member "ada")) "and nothing was written")
    (is (empty? (dev/rows eng :chore)))))

(deftest a-seed-refuses-an-engine-with-an-idp
  (let [eng (assoc (engine) :oidc {:issuer "https://idp.example"})
        e (refused #(seed/load! eng toy {}))]
    (is (some? e))
    (is (re-find #"identity provider" (str (ex-message e))))
    (is (nil? (dev/row eng :member "ada")) "and nothing was written")))

(deftest a-seeded-engine-is-not-seeded-twice
  (let [eng (engine)
        once (seed/load! eng toy {})
        twice (seed/load! eng toy {})]
    (is (true? (:seeded once)))
    (is (false? (:seeded twice)))
    (is (string? (:skipped twice)) "and it says so")
    (is (= 1 (count (dev/rows eng :chore))) "no second row")))

(deftest a-seed-that-failed-half-way-is-refused-on-a-restart
  (let [eng (engine)
        ;; the schema refuses a chore with no title
        bad (update toy :steps conj
                    {:as :ada :kind :chore :create {:title ""}})
        first-e (refused #(seed/load! eng bad {}))
        again (try (seed/load! eng bad {})
                   (catch ExceptionInfo e e))]
    (is (= 3 (:step (ex-data first-e))) "the last step is refused")
    (is (some? (dev/row eng :member "ada")) "and the cast is already written")
    (is (instance? ExceptionInfo again) "the second load refuses; it never answers skipped")
    (is (true? (:waymark10/seed-refused (ex-data again))))
    (is (re-find #"half-way" (str (ex-message again))))
    (is (= 1 (count (dev/rows eng :chore))) "and no step is walked again")
    (testing "a whole seed is refused there too, and is not skipped"
      (is (some? (refused #(seed/load! eng toy {})))))))

(deftest a-relative-date-is-counted-from-the-boot
  (let [eng (engine)
        dated (assoc toy :steps
                     [{:as :ada :kind :chore :ref :later
                       :create {:title "Later" :due [:days 3]}}
                      {:as :ada :kind :chore :ref :earlier
                       :create {:title "Earlier" :due [:days -2]}}])
        result (seed/load! eng dated
                           {:now (Instant/parse "2026-10-01T23:30:00Z")})
        due (fn [k]
              (str (get-in (dev/row eng :chore (get-in result [:refs k :id]))
                           [:data :due])))]
    (is (= "2026-10-04" (due :later)))
    (is (= "2026-09-29" (due :earlier)))))
