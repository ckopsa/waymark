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
            [waymark10.server.live-cracks :as live]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.promises :as promises]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.waiting-on :as waiting-on]
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
                                :one-way "Live is live."}}
              :revert {:from #{:merged} :to :open
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Reverted is reverted."}}}}))

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

(defn- promise! [eng & {:keys [holds enforced? debtor remedy]}]
  (let [id (str (:id (:row (inv/create! eng :promise (cond-> live-in-2h
                                                        holds (assoc :holds holds)
                                                        remedy (assoc :remedy remedy)
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

;; ── a remedy of several doors ───────────────────────────────────────

(def ^:private two-doors {:kind "pr_change" :doors ["deploy" "revert"]})

(deftest a-remedy-of-several-doors-wakes-one-holder-naming-the-doors-it-holds
  (let [eng (eng!)
        seat-id (deployer! eng)
        _ (merged! eng)
        pid (promise! eng :enforced? true :remedy two-doors)]
    (promises/sweep! (later eng 7300))
    (is (= "broken" (standing eng pid)))
    (let [fires (fires-of eng seat-id)
          txt (str (get-in (first fires) [:inputs :text]))]
      (is (= 1 (count fires)) "one seat, woken once")
      (is (re-find #"\"doors\":\s*\[\"deploy\"\]" txt) "the deployer holds deploy, not revert"))
    (is (empty? (held-calls eng)))))

(deftest a-remedy-of-several-doors-offers-each-door-the-owner-holds
  (let [eng (eng!)
        _ (merged! eng)
        pid (promise! eng :enforced? true :remedy two-doors)]
    (promises/sweep! (later eng 7300))
    (is (= "broken" (standing eng pid)))
    (let [calls (held-calls eng)]
      (is (= 1 (count calls)))
      (is (= ["deploy" "revert" "retire" "watch"] (doors-of (first calls)))))))

(deftest a-remedy-names-door-or-doors-and-not-both
  (let [eng (eng!)
        make #(inv/create! eng :promise (assoc live-in-2h :remedy %) {:principal elena})
        both {:kind "pr_change" :door "deploy" :doors ["revert"]}]
    (is (some? (refused-guard #(make both))) "both is refused")
    (is (some? (refused-guard #(make {:kind "pr_change"}))) "neither is refused")
    (is (nil? (refused-guard #(make two-doors))))
    (let [pid (promise! eng)]
      (is (some? (refused-guard #(inv/invoke! eng :promise pid :restate {:remedy both}
                                              {:principal elena})))
          "restate refuses both too")
      (is (= "deploy" (get-in (promises/row-of eng pid) [:data :remedy :door]))))))

;; ── default promises from expected ages (R-P.8.1-8.3) ───────────────

(defn- aged-eng!
  "pr_change with an expected age of 2 h on every state its name gives
  none: `open` keeps its day, `merged` gets 7200."
  []
  (assoc (engine/engine {:storage (memory/storage)
                         :resources [(first (r/in-domain :example {:default-expected-age 7200}
                                                         [pr-change]))
                                     promises/promise]})
         :fire-adapter (sch/fake-fire)))

(defn- defaults [eng]
  (store/with-tx (:storage eng) #(live/default-promises eng %)))

(defn- aged-ids [eng]
  (into #{} (comp (filter #(= "aged" (:crack %))) (map :id))
        (live/find-cracks (#'live/world eng))))

(deftest every-aged-state-gets-one-default-promise-born-watched
  (let [eng (aged-eng!)]
    (is (pos? (live/default-promises! eng)))
    (is (= 0 (live/default-promises! eng)) "a second boot writes nothing")
    (let [ds (defaults eng)
          merged (:data (promises/row-of eng (:id (get ds "pr_change.merged"))))]
      (is (= #{"pr_change.open" "pr_change.merged"}
             (into #{} (filter #(str/starts-with? % "pr_change.")) (keys ds)))
          "one for each aged state, none for the terminal `live`")
      (is (every? #(= "watch" (:mode %)) (vals ds)))
      (is (= 7200 (get-in merged [:by :within])))
      (is (= "merge" (get-in merged [:by :after])))
      (is (= #{"deploy" "revert"} (set (get-in merged [:remedy :doors])))))))

(deftest an-enforced-default-is-never-a-crack-and-its-breach-wakes-the-debtor
  (let [eng (aged-eng!)
        seat-id (deployer! eng)
        id (merged! eng)
        _ (live/default-promises! eng)
        pid (:id (get (defaults eng) "pr_change.merged"))
        late (later eng 7300)]
    (testing "watched: the live pass still has the aged row"
      (is (contains? (aged-ids late) id)))
    (inv/invoke! eng :promise pid :enforce nil {:principal elena})
    (testing "enforced: no crack, and the sweep breaks it and wakes the deployer"
      (is (not (contains? (aged-ids late) id)))
      (promises/sweep! late)
      (is (= "broken" (standing eng pid)))
      (is (= 1 (count (fires-of eng seat-id)))))))

(deftest expected-seconds-reads-the-default-promise
  (let [eng (aged-eng!)
        _ (deployer! eng)
        id (merged! eng)
        _ (live/default-promises! eng)
        pid (:id (get (defaults eng) "pr_change.merged"))
        rdef (get (inv/resources eng) :pr_change)
        expected (fn []
                   (let [raw (store/with-tx (:storage eng)
                               #(store/load-row (:storage eng) % :pr_change id {}))
                         row (inv/decode-row rdef raw)]
                     (get-in (waiting-on/for-row eng rdef row (:data row))
                             [:since :expected_seconds])))]
    (is (= 7200 (expected)))
    (inv/invoke! eng :promise pid :restate {:by {:after "merge" :within 3600}}
                 {:principal elena})
    (is (= 3600 (expected)))))

;; ── a seat walks the breaches of the promises it keeps (R-P.6.1-6.3) ─

(def ^:private incident
  (r/resource
   {:kind :incident
    :plural "incidents"
    :states [:open :resolved]
    :initial :open
    :terminal #{:resolved}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 200}]]]
    :filterable {:state #{:eq :in}}
    :default-filters {:state "open"}
    :actions {:resolve {:from #{:open} :to :resolved
                        :safety {:idempotent true :reversible false :confirm false
                                 :one-way "Resolved is resolved."}}}}))

(defn- two-kinds-eng! []
  (assoc (engine/engine {:storage (memory/storage)
                         :resources [pr-change incident promises/promise]})
         :fire-adapter (sch/fake-fire)))

(def ^:private fixer-office
  {:name "fixer"
   :charter "Fix what the promises say is broken."
   :scope [{:kind "pr_change" :actions ["deploy"]}
           {:kind "incident" :actions ["resolve"]}]
   :held_for []
   :substitute_for []
   :standing_ttl_seconds 604800
   :cadence_seconds 3600
   :budget_usd_per_week 5M
   :sitting_budget_tokens 60000
   :rows_per_firing 20})

(defn- fixer!
  "A seat that holds deploy and resolve, restated to walk the promises
  it keeps, and walking `walk` as well when it is given."
  [eng walk]
  (let [drain! #(consumers/drain-consumer! eng :promise-fires (sch/consumer-fn eng))
        _ (drain!)
        office (cond-> fixer-office walk (assoc :walk walk))
        id (str (:id (:row (inv/create! eng :seat office {:principal elena}))))
        st (:storage eng)
        row (store/with-tx st #(store/load-row st % :seat id {}))]
    (inv/invoke! eng :seat id :restate (-> office (dissoc :name) (assoc :walk_promises true))
                 {:principal elena :if-match (inv/etag :seat id (:version row))})
    (drain!)
    (inv/create! eng :runner_link
                 {:provider "claude_routine"
                  :fire_url "https://api.anthropic.com/v1/claude_code/routines/trig_fixer/fire"
                  :fire_token "rk-test-fixer-0123456789abcdef"
                  :pools [{:kind "schedule"
                           :id (str (:id (sch/schedule-for-seat eng id)))}]}
                 {:principal elena})
    id))

(defn- incident! [eng]
  (str (:id (:row (inv/create! eng :incident {:title "Down"} {:principal elena})))))

(defn- enforced! [eng p]
  (let [id (str (:id (:row (inv/create! eng :promise p {:principal elena}))))]
    (inv/invoke! eng :promise id :enforce nil {:principal elena})
    id))

(def ^:private resolved-soon
  {:name "an open incident is resolved"
   :holds {:query {:kind "incident" :filter {:id "{id}" :state "open"} :expect "none"}}
   :by {:window 900}
   :remedy {:kind "incident" :door "resolve"}})

(defn- walked [eng seat-id]
  (let [st (:storage eng)
        seat (store/with-tx st #(store/load-row st % :seat seat-id {}))]
    (store/with-tx st #(live/promise-walk eng % seat))))

(deftest a-promise-walking-seat-is-offered-exactly-the-breaches-it-keeps
  (let [eng (two-kinds-eng!)
        seat-id (fixer! eng nil)
        pr (merged! eng)
        other (merged! eng)
        down (incident! eng)
        quiet (incident! eng)
        deploy-p (enforced! eng (assoc live-in-2h :about {:kind "pr_change" :id pr}))
        revert-p (enforced! eng (assoc live-in-2h :about {:kind "pr_change" :id other}
                                       :remedy {:kind "pr_change" :door "revert"}))
        resolve-p (enforced! eng (assoc resolved-soon :about {:kind "incident" :id down}))
        watched (str (:id (:row (inv/create! eng :promise
                                             (assoc resolved-soon :about {:kind "incident" :id quiet})
                                             {:principal elena}))))]
    (promises/sweep! (later eng 7300))
    (is (every? #(= "broken" (standing eng %)) [deploy-p revert-p resolve-p watched]))
    (let [w (walked eng seat-id)]
      (is (= #{["pr_change" pr] ["incident" down]} (set (map (juxt :kind :id) w)))
          "two kinds, the doors it holds; not revert's row, not the watched one")
      (is (= 2 (count w)))
      (is (= #{deploy-p resolve-p} (set (map :promise w))))
      (is (every? #(seq (:cause %)) w)))
    (testing "a seat that walks only its kind is handed no breach"
      (let [plain (deployer! eng)]
        (is (empty? (walked eng plain)))))))

(deftest a-seat-with-both-is-handed-the-union-once
  (let [join #'mcp/with-breach-rows
        seat {:data {:charter "Fix it."}}
        k (fn [w] (mapv (juxt #(get % "kind") #(get % "id")) (get w "rows")))
        walk {"kind" "pr_change" "total" 2
              "rows" [{"kind" "pr_change" "id" "a"} {"kind" "pr_change" "id" "b"}]}
        rows [{"kind" "pr_change" "id" "b" "promise" "p1"}
              {"kind" "incident" "id" "b" "promise" "p2"}
              {"kind" "incident" "id" "c" "promise" "p2"}
              {"kind" "incident" "id" "c" "promise" "p3"}]]
    (is (= [["pr_change" "a"] ["pr_change" "b"] ["incident" "b"] ["incident" "c"]]
           (k (join walk seat rows #{} nil))))
    (is (= 4 (get (join walk seat rows #{} nil) "total")))
    (testing "another sitting's rows, and under a named fire every other row, stay out"
      (is (= [["pr_change" "a"] ["pr_change" "b"] ["incident" "b"]]
             (k (join walk seat rows #{"c"} nil))))
      (is (= [["pr_change" "a"] ["pr_change" "b"] ["incident" "c"]]
             (k (join walk seat rows #{} "c")))))
    (testing "a seat that walks no kind is handed the breach rows alone"
      (is (= [["pr_change" "b"] ["incident" "b"] ["incident" "c"]]
             (k (join nil seat rows #{} nil)))))))

(deftest waiting-on-names-the-promise-walking-seat-on-a-breached-row
  (let [eng (two-kinds-eng!)
        _ (fixer! eng nil)
        down (incident! eng)
        _ (enforced! eng (assoc resolved-soon :about {:kind "incident" :id down}))
        rdef (get (inv/resources eng) :incident)
        holder (fn []
                 (let [st (:storage eng)
                       row (inv/decode-row rdef (store/with-tx st #(store/load-row st % :incident down {})))]
                   (:holder (waiting-on/for-row eng rdef row (:data row)))))]
    (is (not= {:type "seat" :name "fixer"} (holder))
        "unbroken, no walk of the seat hands it the row")
    (promises/sweep! (later eng 60))
    (is (= {:type "seat" :name "fixer"} (holder))
        "breached, the seat walks it")))
