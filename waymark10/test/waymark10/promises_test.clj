(ns waymark10.promises-test
  "Promises steps 2 and 3 (docs/spec-promise.md §14), in memory, the
  engine's clock moved by `:now-fn`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.holds :as holds]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.promises :as promises]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(def ^:private pr-change
  (r/resource
   {:kind :pr_change
    :plural "pr_changes"
    :states [:open :merged :live]
    :initial :open
    :terminal #{:live}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 200}]]]
    :filterable {:state #{:eq :in}}
    :default-filters {:state "merged"}
    :actions {:merge {:from #{:open} :to :merged
                      :safety {:idempotent true :reversible false :confirm false
                               :one-way "Merged is merged."}}
              :deploy {:from #{:merged} :to :live
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Live is live."}}}}))

(defn- eng! []
  (assoc (engine/engine {:storage (memory/storage) :resources [pr-change promises/promise]})
         :fire-adapter (sch/fake-fire)))

(defn- deployer!
  "The one seat that holds `deploy`, linked so its `fire` door goes out."
  [eng]
  (let [drain! #(consumers/drain-consumer! eng :promise-fires (sch/consumer-fn eng))
        _ (drain!)
        seat-id (str (:id (:row (inv/create! eng :seat
                                             {:name "deployer"
                                              :charter "Put merged changes live."
                                              :scope [{:kind "pr_change" :actions ["deploy"]}]
                                              :walk "pr_change"
                                              :held_for []
                                              :substitute_for []
                                              :standing_ttl_seconds 604800
                                              :cadence_seconds 3600
                                              :budget_usd_per_week 5M
                                              :sitting_budget_tokens 60000
                                              :rows_per_firing 20}
                                             {:principal elena}))))]
    (drain!)
    (inv/create! eng :runner_link
                 {:provider "claude_routine"
                  :fire_url "https://api.anthropic.com/v1/claude_code/routines/trig_deployer/fire"
                  :fire_token "rk-test-deployer-0123456789abcdef"
                  :pools [{:kind "schedule"
                           :id (str (:id (sch/schedule-for-seat eng seat-id)))}]}
                 {:principal elena})
    seat-id))

(defn- merged! [eng]
  (let [id (str (:id (:row (inv/create! eng :pr_change {:title "A fix"} {:principal elena}))))]
    (inv/invoke! eng :pr_change id :merge nil {:principal elena})
    id))

(def ^:private live-in-2h
  {:name "merged content is live within 2 h"
   :about {:kind "pr_change" :filter {:state "merged"}}
   :holds {:query {:kind "pr_change" :filter {:id "{id}" :state "merged"} :expect "none"}}
   :by {:after "merge" :within 7200}
   :remedy {:kind "pr_change" :door "deploy"}})

(defn- promise! [eng & {:keys [holds enforced? debtor]}]
  (let [id (str (:id (:row (inv/create! eng :promise (cond-> live-in-2h
                                                        holds (assoc :holds holds)
                                                        debtor (assoc :debtor debtor))
                                         {:principal elena}))))]
    (when enforced? (inv/invoke! eng :promise id :enforce nil {:principal elena}))
    id))

(defn- later [eng seconds]
  (assoc eng :now-fn (constantly (.plusSeconds (Instant/now) (long seconds)))))

(defn- standing [eng pid] (name (:state (promises/row-of eng pid))))

(defn- breaches [eng pid] (get-in (promises/row-of eng pid) [:data :breaches]))

(defn- fires-of [eng seat-id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (filterv #(= "fire" (name (:action %)))
               (store/transitions (:storage eng) tx
                                  {:kind :seat :resource-id seat-id} {})))))

(deftest an-enforced-promise-breaks-past-by-and-wakes-the-remedy-holder-once
  (let [eng (eng!)
        seat-id (deployer! eng)
        _ (merged! eng)
        pid (promise! eng :enforced? true)]
    (testing "half the span gone and still merged: at risk, nobody woken"
      (promises/sweep! (later eng 4000))
      (is (= "at_risk" (standing eng pid)))
      (is (empty? (fires-of eng seat-id))))
    (testing "past `by`: broken, and the deployer is fired once, naming the promise"
      (promises/sweep! (later eng 7300))
      (is (= "broken" (standing eng pid)))
      (let [fires (fires-of eng seat-id)]
        (is (= 1 (count fires)))
        (is (str/includes? (str (get-in (first fires) [:inputs :text])) pid))
        (is (str/includes? (str (get-in (first fires) [:inputs :text])) "deploy"))))
    (testing "a later sweep over the same break wakes nobody again"
      (promises/sweep! (later eng 8300))
      (is (= "broken" (standing eng pid)))
      (is (= 1 (count (fires-of eng seat-id)))))))

(deftest a-pass-keeps-only-after-two-passes-a-sweep-apart
  (let [eng (eng!)
        cid (merged! eng)
        pid (promise! eng)]
    (promises/sweep! (later eng 7300))
    (is (= "broken" (standing eng pid)))
    (inv/invoke! eng :pr_change cid :deploy nil {:principal elena})
    (testing "the first pass is stamped, and the promise stays broken"
      (promises/sweep! (later eng 7400))
      (is (= "broken" (standing eng pid)))
      (is (= 1 (count (breaches eng pid)))))
    (testing "a second pass a sweep later keeps it"
      (promises/sweep! (later eng 8500))
      (is (= "kept" (standing eng pid)))
      (is (empty? (breaches eng pid))))))

(deftest an-error-moves-nothing-and-stamps-last-error
  (let [eng (eng!)
        _ (merged! eng)
        pid (promise! eng :holds {:query {:kind "nope" :expect "none"}})]
    (promises/sweep! (later eng 7300))
    (is (= "kept" (standing eng pid)))
    (is (empty? (breaches eng pid)))
    (is (str/includes? (str (get-in (promises/row-of eng pid) [:data :last_error])) "nope"))))

(deftest a-watched-promise-breaks-writes-its-debtor-and-wakes-nobody
  (let [eng (eng!)
        seat-id (deployer! eng)
        cid (merged! eng)
        pid (promise! eng)]
    (promises/sweep! (later eng 7300))
    (is (= "broken" (standing eng pid)))
    (is (= [cid] (mapv :id (breaches eng pid))))
    (is (= "deployer" (:debtor (first (breaches eng pid)))))
    (is (empty? (fires-of eng seat-id)))))

(defn- refused-guard
  "The refusal of `f`'s call (its guard is hidden), or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (or (ex-data e) {}))))

(deftest the-standing-doors-are-the-engines-alone
  (let [eng (eng!)
        pid (promise! eng)
        move {:row "r1" :cause "not live" :at (str (Instant/now))}
        as (fn [who door]
             #(inv/invoke! eng :promise pid door move
                           {:principal who :idempotency-key (str (:id who) door (gensym))}))]
    (testing "a person may not mark it at risk or broken"
      (is (some? (refused-guard (as elena :risk))))
      (is (some? (refused-guard (as elena :break))))
      (is (= "kept" (standing eng pid))))
    (testing "the engine breaks it, and a person may not keep it"
      (is (nil? (refused-guard (as promises/engine-actor :break))))
      (is (= "broken" (standing eng pid)))
      (is (some? (refused-guard (as elena :keep))))
      (is (= "broken" (standing eng pid))))
    (testing "and the engine keeps it"
      (is (nil? (refused-guard (as promises/engine-actor :keep))))
      (is (= "kept" (standing eng pid))))))

(deftest one-promise-over-a-filter-keeps-a-breach-per-failing-row
  (let [eng (eng!)
        a (merged! eng)
        b (merged! eng)
        pid (promise! eng)]
    (promises/sweep! (later eng 7300))
    (is (= "broken" (standing eng pid)))
    (is (= #{a b} (set (map :id (breaches eng pid)))))
    (testing "one row recovering leaves the promise broken on the other"
      (inv/invoke! eng :pr_change a :deploy nil {:principal elena})
      (promises/sweep! (later eng 7400))
      (promises/sweep! (later eng 8500))
      (is (= "broken" (standing eng pid)))
      (is (= [b] (mapv :id (breaches eng pid)))))))

;; ── step 3: the debtor's accept and the owner's escalation ──────────

(defn- seat-as [seat-id]
  (t/principal {:id (str "seat:" seat-id) :type :agent :display "deployer"}))

(defn- guard-of [refusal] (some-> (:guard refusal) name keyword))

(defn- field [row k] (let [d (:data row)] (if (contains? d k) (get d k) (get d (name k)))))

(defn- held-calls [eng]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/query-rows st tx :held_call {} {:limit 50})))))

(defn- doors-of [call]
  (mapv #(str (or (:door %) (get % "door"))) (field call :choices)))

(deftest a-named-debtor-owes-nothing-until-it-accepts
  (let [eng (eng!)
        seat-id (deployer! eng)
        _ (merged! eng)
        pid (promise! eng :enforced? true :debtor "deployer")]
    (testing "born proposed: a sweep past `by` neither breaks it nor wakes the seat"
      (is (= "proposed" (standing eng pid)))
      (promises/sweep! (later eng 7300))
      (is (= "proposed" (standing eng pid)))
      (is (empty? (breaches eng pid)))
      (is (empty? (fires-of eng seat-id))))
    (testing "only the named seat answers, and its accept waits for its person"
      (is (= :the-named-debtor-answers
             (guard-of (refused-guard #(inv/invoke! eng :promise pid :accept nil {:principal elena})))))
      (is (= :the-seats-person-approves
             (guard-of (refused-guard #(inv/invoke! eng :promise pid :accept nil
                                                    {:principal (seat-as seat-id)})))))
      (is (holds/hold? :the-seats-person-approves))
      (is (= "proposed" (standing eng pid))))
    (testing "the person's allow replays the accept as the seat"
      (let [hid (:id (held/hold-door! eng {:kind :promise :action :accept :id pid :body {}
                                           :caller (str "seat:" seat-id) :owner "elena"
                                           :why "Take on the promise."}))
            out (inv/invoke! eng :held_call (str hid) :allow {} {:principal elena})]
        (held/forward! eng (:row out))
        (is (= "kept" (standing eng pid)))))
    (testing "accepted, its break wakes the seat it names"
      (promises/sweep! (later eng 7400))
      (is (= "broken" (standing eng pid)))
      (is (= 1 (count (fires-of eng seat-id))))
      (is (= 1 (count (held-calls eng))) "the accept's own call, and no escalation"))))

(deftest a-changed-debtor-is-proposed-again-and-a-decline-retires-it
  (let [eng (eng!)
        seat-id (deployer! eng)
        pid (promise! eng)]
    (inv/invoke! eng :promise pid :restate {:debtor "deployer"} {:principal elena})
    (is (= "proposed" (standing eng pid)))
    (inv/invoke! eng :promise pid :decline {:why "I only deploy on Fridays."}
                 {:principal (seat-as seat-id)})
    (is (= "retired" (standing eng pid)))
    (is (= "I only deploy on Fridays." (get-in (promises/row-of eng pid) [:data :declined])))))

(deftest a-breach-only-the-owner-can-fix-proposes-one-held-call
  (let [eng (eng!)
        _ (merged! eng)
        pid (promise! eng :enforced? true)]
    (promises/sweep! (later eng 7300))
    (is (= "broken" (standing eng pid)))
    (let [calls (held-calls eng)]
      (is (= 1 (count calls)))
      (is (= ["deploy" "retire" "watch"] (doors-of (first calls))))
      (is (= "elena" (str (field (first calls) :owner)))))
    (testing "a later sweep over the same breach proposes nothing more"
      (promises/sweep! (later eng 8300))
      (is (= 1 (count (held-calls eng)))))))

(deftest a-breach-the-woken-seat-leaves-standing-a-span-escalates-once
  (let [eng (eng!)
        seat-id (deployer! eng)
        _ (merged! eng)
        pid (promise! eng :enforced? true)]
    (promises/sweep! (later eng 7300))
    (is (= 1 (count (fires-of eng seat-id))))
    (is (empty? (held-calls eng)) "the woken seat has its span first")
    (promises/sweep! (later eng 14600))
    (is (= 1 (count (held-calls eng))))
    (promises/sweep! (later eng 15600))
    (is (= "broken" (standing eng pid)))
    (is (= 1 (count (held-calls eng))) "once per breach")
    (is (= 1 (count (fires-of eng seat-id))) "and the seat is not woken again")))

(deftest a-flapping-promise-escalates-instead-of-waking
  (let [eng (eng!)
        seat-id (deployer! eng)
        _ (merged! eng)
        pid (promise! eng :enforced? true)
        at (str (Instant/now))]
    (dotimes [i 3]
      (doseq [door [:break :keep]]
        (inv/invoke! eng :promise pid door {:row "r" :at at}
                     {:principal promises/engine-actor
                      :idempotency-key (str "flap:" i ":" (name door))})))
    (promises/sweep! (later eng 7300))
    (is (= "broken" (standing eng pid)))
    (is (true? (get-in (promises/row-of eng pid) [:data :flapping])))
    (is (empty? (fires-of eng seat-id)))
    (is (= 1 (count (held-calls eng))))))
