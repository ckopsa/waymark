(ns waymark10.wake-subscriptions-test
  "A seat's `wake_on` entries become subscriptions on its fire door, and
  a walk seat's computed default a managed one (docs/spec-events.md
  E-5.4). Real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.definitions :as defs]
            [waymark10.server.door-receivers :as doors]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.wakes :as wakes]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(defn- task-kind [kind plural]
  (r/resource
   {:kind kind :plural plural :summary "{data.title} · {state}"
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
  ["move_tasks" "move_notes" "schedules" "seats" "models" "sittings" "definitions"
   "members" "roles" "grants" "approval_requests" "attachments" "subscriptions" "jobs"
   "invitations" "walkthroughs" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_cursors" "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *fire* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st (fn [tx] (doseq [table tables]
                                     (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [fire (sch/fake-fire)]
          (binding [*eng* (assoc (engine/engine {:storage st
                                                 :resources [(task-kind :move_task "move_tasks")
                                                             (task-kind :move_note "move_notes")]})
                                 :schedule-adapters {:claude_routine (sch/fake-scheduler)}
                                 :fire-adapter fire)
                    *fire* fire]
            (f)))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(defn- seat-body [extra]
  (merge {:charter "Decide whether a row in this queue asks something of this house."
          :scope [{:kind "move_task" :actions ["complete"]}
                  {:kind "move_note" :actions ["complete"]}]
          :substitute_drop [] :held_for [] :substitute_for []
          :standing_ttl_seconds 604800 :cadence_seconds 3600
          :budget_usd_per_week 5M :sitting_budget_tokens 60000
          :rows_per_firing 20}
         extra))

(defn- seat! [nm extra]
  (:id (:row (inv/create! *eng* :seat (seat-body (assoc extra :name nm))
                          {:principal elena}))))

(defn- raw [kind id]
  (store/with-tx (:storage *eng*) #(store/load-row (:storage *eng*) % kind (str id) {})))

(defn- restate! [seat-id extra]
  (inv/invoke! *eng* :seat (str seat-id) :restate (seat-body extra)
               {:principal elena
                :if-match (inv/etag :seat (str seat-id) (:version (raw :seat seat-id)))}))

(defn- seat-do! [seat-id action]
  (inv/invoke! *eng* :seat (str seat-id) action nil
               {:principal elena :acknowledged #{:opens-no-crack}}))

(defn- task! [title]
  (:id (:row (inv/create! *eng* :move_task {:title title} {:principal elena}))))

(defn- task-do! [id action]
  (inv/invoke! *eng* :move_task (str id) action nil {:principal elena}))

(defn- seat-fires [seat-id]
  (filterv #(= :fire (:action %))
           (store/with-tx (:storage *eng*)
             #(store/transitions (:storage *eng*) % {:kind :seat :resource-id (str seat-id)} {}))))

(defn- drain-fires! [c] (consumers/drain-consumer! *eng* c (sch/consumer-fn *eng*)))

(defn- linked-seat! [nm extra fire-cursor]
  (let [seat-id (seat! nm extra)]
    (drain-fires! fire-cursor)
    (inv/invoke! *eng* :schedule (str (:id (sch/schedule-for-seat *eng* seat-id))) :link
                 {:fire_url (str "https://api.anthropic.com/v1/claude_code/routines/trig_" nm "/fire")
                  :token (str "rk-test-" nm "-0123456789abcdef")}
                 {:principal elena})
    seat-id))

(defn- subs-of
  "The subscriptions on a seat's fire door, active or paused."
  [seat-id]
  (let [st (:storage *eng*)]
    (filterv #(= (str seat-id) (str (get-in % [:data :receiver :id])))
             (store/with-tx st
               (fn [tx] (into (store/query-rows st tx :subscription {:state :active} {:limit 500})
                              (store/query-rows st tx :subscription {:state :paused} {:limit 500})))))))

(defn- move! [] (defs/move-wakes-to-subscriptions! *eng* {:again? true}))

(deftest each-wake-entry-becomes-a-subscription-owned-by-the-seats-owner
  (let [two (seat! "movetwo" {:wake_on [{:kind "move_task" :actions ["complete"]}
                                         {:kind "move_note" :actions ["create"]
                                          :settle_seconds 60}]})
        walker (seat! "movewalker" {:walk "move_task"})
        owner (some-> (get-in (raw :seat two) [:data :owner]) str)]
    (is (= 3 (move!)))
    (testing "two entries, two subscriptions, each owned by the seat's owner"
      (let [subs (subs-of two)]
        (is (= 2 (count subs)))
        (is (some? owner))
        (is (= #{owner} (set (map #(str (get-in % [:data :owner])) subs))))
        (is (= #{["move_task"] ["move_note"]} (set (map #(get-in % [:data :kinds]) subs))))
        (is (every? #(nil? (get-in % [:data :managed_by])) subs))
        (is (= 60 (some #(get-in % [:data :receiver :settle_seconds]) subs)))))
    (testing "a walk seat that wrote none gets one managed row for its default"
      (let [[sub & more] (subs-of walker)]
        (is (nil? more))
        (is (= "seat" (get-in sub [:data :managed_by])))
        (is (= ["move_task"] (get-in sub [:data :kinds])))
        (is (= ["create"] (get-in sub [:data :actions])))))
    (testing "a seat that holds its subscriptions is not moved twice"
      (is (zero? (move!)))
      (is (= 2 (count (subs-of two)))))
    (seat-do! two :retire)
    (seat-do! walker :retire)))

(deftest a-wake-between-the-seed-and-the-stop-is-heard-once
  (let [fc :move-fires
        _ (drain-fires! fc)
        seat (linked-seat! "movehandover"
                           {:wake_on [{:kind "move_task" :actions ["complete"]}]} fc)
        heard (task! "heard by the wakes consumer")
        _ (task-do! heard :complete)
        st (:storage *eng*)
        ;; the `:wakes` consumer heard everything up to here
        pos (:id (first (store/settled-transitions st {} {:newest-first true :limit 1} 4)))
        _ (store/with-tx st #(store/cursor-set! st % (consumers/member-cursor :consumer "wakes") pos))
        between (task! "landed after its cursor")
        _ (task-do! between :complete)
        text #(str (get-in % [:inputs :text]))]
    (is (= 1 (move!)))
    (doors/drain! *eng*)
    (wakes/sweep-pending! *eng*)
    (let [fs (seat-fires seat)]
      (is (= 1 (count fs)) "woken once, not zero times or twice")
      (is (str/includes? (text (first fs)) (str between)))
      (is (not (str/includes? (text (first fs)) (str heard)))))
    (testing "and a second pass hears nothing more"
      (doors/drain! *eng*)
      (wakes/sweep-pending! *eng*)
      (is (= 1 (count (seat-fires seat)))))
    (seat-do! seat :retire)))

(deftest the-seats-door-restates-its-managed-row-when-the-walk-changes
  (let [seat (seat! "moverestate" {:walk "move_task"})
        _ (move!)
        [sub] (subs-of seat)]
    (is (= ["move_task"] (get-in sub [:data :kinds])))
    (restate! seat {:walk "move_note"})
    (let [[after & more] (subs-of seat)]
      (is (nil? more) "restated, not written again")
      (is (= (str (:id sub)) (str (:id after))))
      (is (= ["move_note"] (get-in after [:data :kinds])))
      (is (= (get-in sub [:data :owner]) (get-in after [:data :owner]))))
    (seat-do! seat :retire)))
