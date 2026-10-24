(ns waymark10.door-receivers-test
  "A subscription whose receiver is a seat's fire door
  (docs/spec-events.md §4.2, E-3.1 to E-3.8). Real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.door-receivers :as doors]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.wakes :as wakes]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as factories]
            [waymark10.time :refer [instant-of]]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private door-task
  (r/resource
   {:kind :door_task :plural "door_tasks" :summary "{data.title} · {state}"
    :states [:open :complete] :initial :open :terminal #{:complete}
    :schema [:map [:title {:examples ["Read the morning post"]
                           :x-display {:label "What it is" :help "One line naming the work."}}
                   [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}} :default-filters {:state "open"}
    :actions {:complete {:from #{:open} :to :complete :display {:label "Complete" :order 1}
                         :safety {:idempotent true :reversible false :confirm false
                                  :one-way "Done is done."}}
              :touch {:from #{:open} :to :open :display {:label "Touch" :order 2}
                      :safety {:idempotent true :reversible true :confirm false}}}}))

(def ^:private tables
  ["door_tasks" "schedules" "seats" "models" "sittings" "definitions" "members" "roles"
   "grants" "approval_requests" "attachments" "subscriptions" "jobs" "invitations"
   "walkthroughs" "waymark10_transitions" "waymark10_idempotency" "waymark10_cursors"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *fire* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st (fn [tx] (doseq [table tables]
                                     (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [fire (sch/fake-fire)]
          (binding [*eng* (assoc (engine/engine {:storage st :resources [door-task]})
                                 :fire-adapter fire)
                    *fire* fire]
            (f)))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))
(def ^:private engine-hand (t/principal {:id "waymark10-test-engine" :type :system}))

;; a grant's guard's-eye view admitting all, under an id no row holds
(defn- granted [] {:id (str (random-uuid)) :action? (fn [_ _] true) :row? (fn [_ _] true)})

(defn- seat! [nm extra]
  (:id (:row (inv/create! *eng* :seat
                          (merge {:name nm :charter "Decide whether a row in this queue asks something of this house."
                                  :scope [{:kind "door_task" :actions ["complete"]}]
                                  :held_for [] :substitute_for []
                                  :standing_ttl_seconds 604800 :cadence_seconds 3600
                                  :budget_usd_per_week 5M :sitting_budget_tokens 60000
                                  :rows_per_firing 20}
                                 extra)
                          {:principal elena}))))

(defn- raw [kind id]
  (store/with-tx (:storage *eng*) #(store/load-row (:storage *eng*) % kind (str id) {})))

(defn- seat-do! [seat-id action]
  (inv/invoke! *eng* :seat (str seat-id) action nil
               {:principal elena :acknowledged #{:opens-no-crack}}))

(defn- task! [title]
  (:id (:row (inv/create! *eng* :door_task {:title title} {:principal elena}))))

(defn- task-do! [id action]
  (inv/invoke! *eng* :door_task (str id) action nil {:principal elena}))

(defn- sched-of [seat-id] (sch/schedule-for-seat *eng* seat-id))

(defn- seat-fires [seat-id]
  (filterv #(= :fire (:action %))
           (store/with-tx (:storage *eng*)
             #(store/transitions (:storage *eng*) % {:kind :seat :resource-id (str seat-id)} {}))))

(defn- actor-of [t]
  (let [a (:actor t)] (str (if (map? a) (:id a) a))))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (if (:waymark10/problem (ex-data e)) (ex-data e) (throw e)))))

(defn- drain-fires! [c] (consumers/drain-consumer! *eng* c (sch/consumer-fn *eng*)))
(defn- drain-wakes! [c] (consumers/drain-consumer! *eng* c (wakes/consumer-fn *eng*)))

(defn- linked-seat! [nm extra fire-cursor]
  (let [seat-id (seat! nm extra)
        token (str "rk-test-" nm "-0123456789abcdef")]
    (drain-fires! fire-cursor)
    (inv/invoke! *eng* :schedule (str (:id (sched-of seat-id))) :link
                 {:fire_url (str "https://api.anthropic.com/v1/claude_code/routines/trig_" nm "/fire")
                  :token token}
                 {:principal elena})
    {:seat seat-id :token token}))

(defn- subscribe!
  ([seat-id entry] (subscribe! seat-id entry {:principal elena}))
  ([seat-id entry opts] (factories/door-subscription! *eng* opts seat-id entry)))

(def ^:private on-complete {:kind "door_task" :actions ["complete"]})

(deftest a-door-subscription-wakes-its-seat-and-the-seat-says-which
  (let [fc :door-wake-fires
        _ (drain-fires! fc)
        {:keys [seat token]} (linked-seat! "doorclerk" {} fc)
        sub (subscribe! seat on-complete)
        task (task! "a thing the subscription hears")]
    (is (= "elena" (get-in sub [:data :owner])) "the writer is its owner")
    (task-do! task :complete)
    (testing "the match fires nothing: it leaves the wake pending"
      (doors/drain! *eng*)
      (is (empty? (seat-fires seat)))
      (is (true? (get-in (sched-of seat) [:data :wake_pending]))))
    (testing "the sweep fires it once, naming the row, as the subscription"
      (wakes/sweep-pending! *eng*)
      (let [fs (seat-fires seat)]
        (is (= 1 (count fs)))
        (is (= (str "subscription:" (:id sub)) (actor-of (first fs))))
        (is (str/includes? (str (get-in (first fs) [:inputs :text])) (str task))))
      (is (not (get-in (sched-of seat) [:data :wake_pending])))
      (drain-fires! fc)
      (is (= 1 (count (filter #(= token (:token %)) (sch/fires *fire*))))))
    (seat-do! seat :retire)))

(deftest a-seats-own-wake-mark-wakes-no-seat
  (let [fc :door-mark-fires
        _ (drain-fires! fc)
        {:keys [seat]} (linked-seat! "doormarked" {} fc)
        sub (subscribe! seat on-complete)
        task (task! "a thing whose wake is marked")
        sched-id (str (:id (sched-of seat)))
        marks #(filterv (fn [t] (= :mark_wake (:action t)))
                        (store/with-tx (:storage *eng*)
                          (fn [tx] (store/transitions (:storage *eng*) tx
                                                      {:kind :schedule :resource-id sched-id} {}))))]
    (task-do! task :complete)
    (doors/drain! *eng*)
    (testing "the pending mark is a mark_wake line on the schedule"
      (is (true? (get-in (sched-of seat) [:data :wake_pending])))
      (is (seq (marks))))
    (testing "a schedule move that reaches a door receiver is a skip, not a wake"
      (let [e (try (#'doors/deliver! *eng* sub {:kind :schedule :action :mark_wake
                                                :resource-id sched-id})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :own-mark (::doors/skip (ex-data e))))))
    (testing "the marks the fire wrote wake nothing more"
      (wakes/sweep-pending! *eng*)
      (doors/drain! *eng*)
      (wakes/sweep-pending! *eng*)
      (is (= 1 (count (seat-fires seat))))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))
    (seat-do! seat :retire)))

(deftest only-a-holder-of-seat-fire-writes-a-door-receiver
  (let [seat (seat! "doorwritten" {})
        door {:type "door" :kind "seat" :id (str seat) :action "fire"}]
    (testing "an agent whose grant does not admit seat.fire is refused, in the grant check's words"
      (let [p (refusal #(subscribe! seat on-complete {:principal clerk}))]
        (is (some? p))
        (is (str/includes? (pr-str p) "seat.fire"))))
    (testing "under a grant that admits it the row is written, and keeps that grant"
      (let [g (granted)
            sub (subscribe! seat on-complete {:principal clerk :grant g})]
        (is (= "clerk" (get-in sub [:data :owner])))
        (is (= (:id g) (get-in sub [:data :owner_grant])))))
    (testing "a door receiver that names no kinds is refused"
      (is (some? (refusal #(inv/create! *eng* :subscription {:receiver door}
                                        {:principal elena})))))
    (seat-do! seat :retire)))

(deftest a-managed-subscription-refuses-a-direct-write
  (let [seat (seat! "doormanaged" {})
        sub (factories/door-subscription! *eng* {:principal engine-hand} seat
                                          on-complete {:managed_by "seat"})
        id (str (:id sub))]
    (is (= "seat" (get-in sub [:data :managed_by])))
    (testing "a person's restate is refused: its door is the seat's"
      (is (some? (refusal #(inv/invoke! *eng* :subscription id :restate
                                        {:description "by hand"}
                                        {:principal elena
                                         :if-match (inv/etag :subscription id
                                                             (:version sub))})))))
    (testing "and so is a pause"
      (is (some? (refusal #(inv/invoke! *eng* :subscription id :pause nil
                                        {:principal elena})))))
    (seat-do! seat :retire)))

(deftest an-owner-who-lost-seat-fire-fires-nothing
  (let [fc :door-lost-fires
        _ (drain-fires! fc)
        {:keys [seat]} (linked-seat! "doorlost" {} fc)
        ;; written under a grant that stands nowhere now
        sub (subscribe! seat on-complete {:principal clerk :grant (granted)})]
    (task-do! (task! "a thing nobody may wake the seat for") :complete)
    (doors/drain! *eng*)
    (testing "nothing waits and nothing fires"
      (is (not (get-in (sched-of seat) [:data :wake_pending])))
      (wakes/sweep-pending! *eng*)
      (is (empty? (seat-fires seat))))
    (testing "the miss is a skip recorded on the subscription, which stays active"
      (let [row (raw :subscription (:id sub))]
        (is (= :active (:state row)))
        (is (= 1 (get-in row [:data :skipped_count])))
        (is (str/includes? (str (get-in row [:data :last_skip_error])) "seat.fire"))))
    (seat-do! seat :retire)))

(deftest a-halted-seat-keeps-the-wake-until-the-halt-lifts
  (let [fc :door-halt-fires
        _ (drain-fires! fc)
        {:keys [seat]} (linked-seat! "doorhalted" {} fc)]
    (subscribe! seat on-complete)
    (is (true? (seats/seat-halt! *eng* seat "model_not_held"
                                 "doorhalted is held for 1 model(s).")))
    (task-do! (task! "a thing behind the halt") :complete)
    (doors/drain! *eng*)
    (wakes/sweep-pending! *eng*)
    (testing "the halt holds the fire, and the wake waits"
      (is (empty? (seat-fires seat)))
      (is (true? (get-in (sched-of seat) [:data :wake_pending]))))
    (testing "the halt lifted, the next sweep fires it once"
      (is (true? (seats/seat-clear-halt! *eng* seat)))
      (wakes/sweep-pending! *eng*)
      (is (= 1 (count (seat-fires seat))))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))
    (seat-do! seat :retire)))

(deftest the-settle-is-capped-by-the-smallest-max-wait-among-the-receivers
  (let [fc :door-cap-fires
        _ (drain-fires! fc)
        {:keys [seat]} (linked-seat! "doorcapped" {} fc)
        task (task! "a thing heard twice")
        due #(instant-of (get-in (sched-of seat) [:data :wake_due_at]))]
    (subscribe! seat {:kind "door_task" :actions ["touch"]
                      :settle_seconds 600 :max_wait_seconds 3600})
    (subscribe! seat {:kind "door_task" :actions ["complete"]
                      :settle_seconds 600 :max_wait_seconds 60})
    (task-do! task :touch)
    (doors/drain! *eng*)
    (let [first-at (instant-of (get-in (sched-of seat) [:data :wake_first_at]))]
      (testing "the first receiver's match is due after its settle"
        (is (= (.plusSeconds ^Instant first-at 600) (due))))
      (task-do! task :complete)
      (doors/drain! *eng*)
      (testing "the second's shorter max_wait pulls it to the first match plus that"
        (is (= (.plusSeconds ^Instant first-at 60) (due)))
        (is (empty? (seat-fires seat)))))
    (seat-do! seat :retire)))

(deftest a-seat-its-wake-on-and-a-subscription-both-match-fires-once
  (let [wc :door-both-wakes
        fc :door-both-fires
        _ (drain-wakes! wc)
        _ (drain-fires! fc)
        {:keys [seat]} (linked-seat! "doorboth" {:wake_on [on-complete]} fc)]
    (subscribe! seat on-complete)
    (task-do! (task! "a thing both of them hear") :complete)
    (doors/drain! *eng*)
    (drain-wakes! wc)
    (wakes/sweep-pending! *eng*)
    (is (= 1 (count (seat-fires seat))))
    (seat-do! seat :retire)))
