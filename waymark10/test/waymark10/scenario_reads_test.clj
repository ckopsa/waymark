(ns waymark10.scenario-reads-test
  "Check-tier reads (docs/spec-related-row-law.md §5, build step 5): a
  scenario whose guards read only through follow, exists and did is
  judged with no database — its :given rows answer :read and the
  existence hooks, its :history answers :actor-of. Over a fixture pair
  of kinds: a meal, and the day that plans and serves it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.declare :refer [defguard defscenario refuse]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.packs :as packs]
            [waymark10.test.suite :as suite]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────────────────

(defguard sr-meal-is-listed
  (refuse "That meal is not on the family meal list yet. Accept a suggestion first.")
  '(= (follow (input :meal_id) :state) "on_list")
  {:remedies [{:door :sr_meal/accept :id '(input :meal_id)}]})

(defguard sr-meal-is-free
  (refuse "That meal is already planned for {d.title}.")
  '(none [d :sr_day {:meal_id (= (input :meal_id)) :state (in "planned")}])
  {:remedies [:approval_request/create]})

(defguard sr-meal-is-named
  (refuse "The day's meal has a name before it is served.")
  '(is-set (follow (data :meal_id) :name))
  {:remedies [:sr_day/plan]})

(defguard sr-planner-does-not-serve
  (refuse "Whoever planned the day does not also serve it; ask someone else.")
  '(not (did :plan))
  {:remedies [:approval_request/create]})

(def ^:private safety
  {:idempotent true :reversible false :confirm false :one-way "Done is done."})

(def ^:private meal
  (delay
    (r/resource
     {:kind :sr_meal
      :plural "sr_meals"
      :states [:suggested :on_list]
      :initial :suggested
      :terminal #{:on_list}
      :summary "{data.name} · {state}"
      :schema [:map [:name [:string {:min 1 :max 40}]]]
      :actions {:accept {:from #{:suggested} :to :on_list :safety safety}}})))

(def ^:private day
  (delay
    (r/resource
     {:kind :sr_day
      :plural "sr_days"
      :states [:open :planned :served]
      :initial :open
      :terminal #{:served}
      :summary "{data.title} · {state}"
      :schema [:map
               [:title [:string {:min 1 :max 40}]]
               [:meal_id {:optional true :kind :sr_meal} [:maybe :waymark/ref]]]
      :filterable {:state #{:eq :in} :meal_id #{:eq}}
      :actions {:plan {:from #{:open} :to :planned
                       :input [:map [:meal_id {:kind :sr_meal} :waymark/ref]]
                       :guards [sr-meal-is-listed sr-meal-is-free]
                       :safety safety}
                :serve {:from #{:planned} :to :served
                        :guards [sr-meal-is-named sr-planner-does-not-serve]
                        :safety safety}}})))

(def ^:private soup
  {:kind :sr_meal :handle :soup :state :on_list :data {:name "Soup"}})

(defscenario a-listed-meal-is-planned
  "A meal on the list goes on an open day."
  {:kind :sr_day :attempt :plan
   :given [soup]
   :row {:state :open :data {:title "Monday"}}
   :input {:meal_id "{given/soup}"}
   :as {:id "mom" :type :person}
   :expect {:allowed true}})

(defscenario a-suggestion-is-not-planned
  "A meal only suggested does not go on a day until it is accepted."
  {:kind :sr_day :attempt :plan
   :given [(assoc soup :state :suggested)]
   :row {:state :open :data {:title "Monday"}}
   :input {:meal_id "{given/soup}"}
   :as {:id "mom" :type :person}
   :expect {:refused :sr-meal-is-listed}})

(defscenario a-meal-is-planned-once
  "A meal already planned for another day is not planned twice, and
   the refusal names that day."
  {:kind :sr_day :attempt :plan
   :given [soup {:kind :sr_day :state :planned
                 :data {:title "Sunday" :meal_id "{given/soup}"}}]
   :row {:state :open :data {:title "Monday"}}
   :input {:meal_id "{given/soup}"}
   :as {:id "mom" :type :person}
   :expect {:refused :sr-meal-is-free
            :because "already planned for Sunday"}})

(defscenario the-planner-does-not-serve
  "Whoever planned the day does not also serve it."
  {:kind :sr_day :attempt :serve
   :given [soup]
   :history [{:action :plan :as "mom" :input {:meal_id "{given/soup}"}}]
   :row {:state :planned :data {:title "Monday" :meal_id "{given/soup}"}}
   :as {:id "mom" :type :person}
   :expect {:refused :sr-planner-does-not-serve}})

(defscenario another-hand-serves
  "Someone who did not plan the day serves it."
  {:kind :sr_day :attempt :serve
   :given [soup]
   :history [{:action :plan :as "mom" :input {:meal_id "{given/soup}"}}]
   :row {:state :planned :data {:title "Monday" :meal_id "{given/soup}"}}
   :as {:id "dad" :type :person}
   :expect {:allowed true}})

(defscenario an-unnamed-meal-is-not-served
  "A day whose meal is not one the list knows is not served."
  {:kind :sr_day :attempt :serve
   :history [{:action :plan :as "mom"}]
   :row {:state :planned :data {:title "Monday" :meal_id "nowhere"}}
   :as {:id "dad" :type :person}
   :expect {:refused :sr-meal-is-named}})

(def ^:private scenarios
  [a-listed-meal-is-planned a-suggestion-is-not-planned a-meal-is-planned-once
   the-planner-does-not-serve another-hand-serves an-unnamed-meal-is-not-served])

;; ── the claims ─────────────────────────────────────────────────────────────────

(deftest declarative-reads-stay-check-tier
  (doseq [s scenarios]
    (testing (name (:name s))
      (is (scenario/check-tier? @day s))
      (is (nil? (scenario/deferral-reason @day s))))))

(deftest follow-exists-and-did-are-judged-without-a-database
  (doseq [s scenarios]
    (testing (name (:name s))
      (is (= [] (scenario/judge @day s))))))

(deftest the-scenario-is-the-whole-world
  (testing "without the log, the planner is not known to have planned"
    (is (seq (scenario/judge @day (assoc the-planner-does-not-serve :history [])))))
  (testing "without the other day, the meal is free"
    (is (seq (scenario/judge @day (update a-meal-is-planned-once :given (comp vec (partial take 1)))))))
  (testing "without the meal row, follow finds nothing"
    (is (seq (scenario/judge @day (assoc a-listed-meal-is-planned :given []))))))

;; ── a code guard opts into the check tier ──────────────────────────────────────

(def ^:private soup-only
  {:name :sr-soup-only :judges [:meal_id] :reads [:sr_meal]
   :explain "Only soup goes on a day."
   :check (fn [_row inp ctx]
            (if-some [read (:read ctx)]
              (if (= "Soup" (get-in (read :sr_meal (:meal_id inp)) [:data :name]))
                (t/allow)
                (t/deny))
              (t/allow)))})

(defn- planning-under [gd]
  (assoc-in @day [:actions :plan :guards] [(g/guard gd)]))

(deftest a-code-guard-defers-unless-it-opts-in
  (testing "by default, a code guard's kind reads defer its door"
    (let [rd (planning-under soup-only)]
      (is (not (scenario/check-tier? rd a-listed-meal-is-planned)))
      (is (= "stages 1 given row" (scenario/deferral-reason rd a-listed-meal-is-planned)))))
  (testing ":check-tier :given serves its reads from the :given rows"
    (let [rd (planning-under (assoc soup-only :check-tier :given))]
      (is (scenario/check-tier? rd a-listed-meal-is-planned))
      (is (= [] (scenario/judge rd a-listed-meal-is-planned)))
      (is (seq (scenario/judge rd (assoc-in a-listed-meal-is-planned [:given 0 :data :name] "Stew")))))))

(deftest check-tier-is-for-kind-reads-alone
  (testing "accepted where every read is a kind"
    (is (= :given (:check-tier (g/guard (assoc soup-only :check-tier :given))))))
  (doseq [reads [[:storage] [:sr_meal :within] [:principal] []]]
    (testing (str "refused on :reads " (pr-str reads))
      (is (thrown-with-msg? Exception #"only a guard whose every read is a kind can opt in"
                            (g/guard (assoc soup-only :reads reads :check-tier :given))))))
  (testing "refused with any value but :given"
    (is (thrown-with-msg? Exception #":check-tier is :given"
                          (g/guard (assoc soup-only :check-tier :always)))))
  (testing "refused on a :when guard"
    (is (thrown-with-msg? Exception #"a :when guard's reads are served by its tree"
                          (g/guard (assoc soup-only :when '(= 1 1) :check-tier :given))))))

(deftest history-is-a-closed-log
  (is (thrown? Exception
               (scenario/scenario :bad "A log entry names its door."
                                  {:kind :sr_day :attempt :serve
                                   :history [{:as "mom"}]
                                   :row {:state :planned :data {:title "Monday"}}
                                   :expect {:allowed true}})))
  (is (thrown? Exception
               (scenario/scenario :bad "A log entry is closed."
                                  {:kind :sr_day :attempt :serve
                                   :history [{:action :plan :as "mom" :when "now"}]
                                   :row {:state :planned :data {:title "Monday"}}
                                   :expect {:allowed true}})))
  (is (thrown? Exception
               (scenario/scenario :bad "A log entry's input is a map."
                                  {:kind :sr_day :attempt :serve
                                   :history [{:action :plan :as "mom" :input "soup"}]
                                   :row {:state :planned :data {:title "Monday"}}
                                   :expect {:allowed true}})))
  (is (thrown-with-msg? Exception #"which no :given row"
                        (scenario/scenario :bad "A log entry's handle is staged."
                                           {:kind :sr_day :attempt :serve
                                            :history [{:action :plan :as "mom"
                                                       :input {:meal_id "{given/stew}"}}]
                                            :row {:state :planned :data {:title "Monday"}}
                                            :expect {:allowed true}}))))

;; ── the conformance tier stages the same log through the real door ─────────────
;; Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.

(def ^:private tables
  ["sr_meals" "sr_days" "members" "roles" "grants" "approval_requests" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "events"])

(defn- with-engine [f]
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [eng (engine/engine {:storage st :resources [@meal @day]})]
        (f (suite/context {:engine eng :handler (engine/handler eng)
                           :kinds [:sr_meal :sr_day]})))
      (finally (pg/close! st)))))

(deftest a-history-entry-carries-its-input-through-the-door
  (with-engine
    (fn [ctx]
      (let [run #(#'packs/run-scenario ctx @day %)]
        (testing "the :plan entry stages with its :input, and the did guard reads it"
          (is (nil? (run the-planner-does-not-serve)))
          (is (nil? (run another-hand-serves))))
        (testing "without its :input the :plan entry refuses, naming its door's answer"
          (let [v (run (assoc another-hand-serves :history [{:action :plan :as "mom"}]))]
            (is (str/includes? (str v) "cannot be staged by the conformance tier") v)))
        (testing "an acted-as entry points to the check tier"
          (let [v (run (assoc another-hand-serves
                              :history [{:action :plan :as "mom" :acted-by "seat:robot"
                                         :input {:meal_id "{given/soup}"}}]))]
            (is (str/includes? (str v) (str "An acted-as history entry is judged in the check tier;"
                                            " mark the scenario :check-tier :given or move it there."))
                v)))))))
