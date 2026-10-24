(ns waymark10.wake-subscriptions-test
  "A seat's `wake_on` entries become subscriptions on its fire door, and
  a walk seat's computed default a managed one (docs/spec-events.md
  E-5.4). Real Postgres."
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

(defn- step-kind
  "Two doors in a row, so a wake can staff one state and not the other."
  []
  (r/resource
   {:kind :move_step :plural "move_steps" :summary "{data.title} · {state}"
    :states [:open :review :complete] :initial :open :terminal #{:complete}
    :schema [:map [:title {:examples ["Sort the morning post"]
                           :x-display {:label "What it is" :help "One line naming the work."}}
                   [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}} :default-filters {:state "open"}
    :actions {:submit {:from #{:open} :to :review :display {:label "Submit" :order 1}
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Sent to review."}}
              :complete {:from #{:review} :to :complete :display {:label "Complete" :order 2}
                         :safety {:idempotent true :reversible false :confirm false
                                  :one-way "Done is done."}}}}))

(def ^:private tables
  ["move_tasks" "move_notes" "move_steps" "schedules" "seats" "models" "sittings" "definitions"
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
                                                             (task-kind :move_note "move_notes")
                                                             (step-kind)]})
                                 :fire-adapter fire)
                    *fire* fire]
            (f)))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(defn- seat-body [extra]
  (merge {:charter "Decide whether a row in this queue asks something of this house."
          :scope [{:kind "move_task" :actions ["complete"]}
                  {:kind "move_note" :actions ["complete"]}]
          :held_for [] :substitute_for []
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

(defn- warned
  "The warning a write stopped on, {:name … :reason …}, or nil when it
  was served."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (= :warning-required (:waymark10/problem d))
             (first (:warnings d))
             (throw e))))))

(defn- task! [title]
  (:id (:row (inv/create! *eng* :move_task {:title title} {:principal elena}))))

(defn- task-do! [id action]
  (inv/invoke! *eng* :move_task (str id) action nil {:principal elena}))

(defn- seat-fires [seat-id]
  (filterv #(= :fire (:action %))
           (store/with-tx (:storage *eng*)
             #(store/transitions (:storage *eng*) % {:kind :seat :resource-id (str seat-id)} {}))))

(defn- drain-fires! [c] (consumers/drain-consumer! *eng* c (sch/consumer-fn *eng*)))

(defn- linked-seat! [make nm extra fire-cursor]
  (let [seat-id (make nm extra)]
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

(deftest the-seats-door-restates-its-managed-row-when-the-walk-changes
  (let [seat (seat! "moverestate" {:walk "move_task"})
        [sub] (subs-of seat)]
    (is (= ["move_task"] (get-in sub [:data :kinds])))
    (restate! seat {:walk "move_note"})
    (let [[after & more] (subs-of seat)]
      (is (nil? more) "restated, not written again")
      (is (= (str (:id sub)) (str (:id after))))
      (is (= ["move_note"] (get-in after [:data :kinds])))
      (is (= (get-in sub [:data :owner]) (get-in after [:data :owner]))))
    (seat-do! seat :retire)))

(deftest a-seat-born-after-the-move-gets-its-subscriptions-from-its-own-create
  (let [fc :born-fires
        _ (drain-fires! fc)
        two (linked-seat! seat! "borntwo"
                          {:wake_on [{:kind "move_task" :actions ["complete"]}
                                     {:kind "move_note" :actions ["create"]}]} fc)]
    (testing "two entries, two subscriptions that are its owner's, with no boot between"
      (let [subs (subs-of two)]
        (is (= 2 (count subs)))
        (is (every? #(= "owner" (get-in % [:data :managed_by])) subs))
        (is (= #{["move_task"] ["move_note"]} (set (map #(get-in % [:data :kinds]) subs))))))
    (testing "and the seat wakes on a matching transition"
      (let [t (task! "heard by the born seat")]
        (task-do! t :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (let [fs (seat-fires two)]
          (is (= 1 (count fs)))
          (is (str/includes? (str (get-in (first fs) [:inputs :text])) (str t))))))
    (testing "a walk seat gets its default as one managed row"
      (let [walker (seat! "bornwalker" {:walk "move_task"})
            [sub & more] (subs-of walker)]
        (is (nil? more))
        (is (= "seat" (get-in sub [:data :managed_by])))
        (is (= ["move_task"] (get-in sub [:data :kinds])))
        (seat-do! walker :retire)))
    (seat-do! two :retire)))

(deftest a-walk-seats-default-row-stays-its-own
  (let [walker (seat! "releasewalker" {:walk "move_task"})
        [sub] (subs-of walker)]
    (is (= "seat" (get-in sub [:data :managed_by])))
    (testing "its release is refused to a person, and on a row that is not its seat's"
      (is (thrown? Exception
                   (inv/invoke! *eng* :subscription (str (:id sub)) :release_to_owner
                                {:managed_by "owner"} {:principal elena})))
      (let [mine (:row (inv/create! *eng* :subscription
                                    (assoc (seats/wake-subscription (raw :seat walker)
                                                                    {:kind "move_note" :actions ["complete"]})
                                           :managed_by "owner")
                                    {:principal seats/seats-actor}))]
        (is (thrown? Exception
                     (inv/invoke! *eng* :subscription (str (:id mine)) :release_to_owner
                                  {:managed_by "owner"} {:principal seats/seats-actor})))))
    (seat-do! walker :retire)))

(deftest wake-on-is-written-once-and-its-rows-are-the-owners-to-restate
  (let [seat (seat! "bornrestate" {:wake_on [{:kind "move_task" :actions ["complete"]}]})
        [sub & more] (subs-of seat)]
    (is (nil? more))
    (is (= "owner" (get-in sub [:data :managed_by])))
    (testing "a restate that sends wake_on is refused"
      (is (thrown? Exception
                   (restate! seat {:wake_on [{:kind "move_note" :actions ["create"]}]})))
      (is (= [["move_task"]] (mapv #(get-in % [:data :kinds]) (subs-of seat)))))
    (testing "a restate that omits it leaves the subscriptions as they are"
      (restate! seat {:cadence_seconds 7200})
      (is (= [(:version sub)] (mapv :version (subs-of seat)))))
    (testing "a person's restate of the owner's row survives the seat's next
              restate"
      (inv/invoke! *eng* :subscription (str (:id sub)) :restate
                   {:actions ["complete" "touch"]}
                   {:principal elena
                    :if-match (inv/etag :subscription (str (:id sub))
                                        (:version (raw :subscription (:id sub))))})
      (restate! seat {:cadence_seconds 3600})
      (let [[now & more] (subs-of seat)]
        (is (nil? more) "no write gave it more rows")
        (is (= "owner" (get-in now [:data :managed_by])))
        (is (= ["complete" "touch"] (get-in now [:data :actions])))))
    (seat-do! seat :retire)))

(deftest an-owner-who-revoked-every-wake-row-gets-none-back
  ;; ticket 28df52ad: the wake_on a seat was born with is written once,
  ;; at its create, and no later write of the seat writes it again
  (let [seat (seat! "bornrevoked" {:wake_on [{:kind "move_task" :actions ["complete"]}
                                             {:kind "move_note" :actions ["create"]}]})]
    (is (= 2 (count (subs-of seat))))
    ;; the row's own owner is the hand that created it, the seats actor
    (doseq [sub (subs-of seat)]
      (inv/invoke! *eng* :subscription (str (:id sub)) :revoke nil
                   {:principal seats/seats-actor}))
    (is (empty? (subs-of seat)))
    (testing "a restate, and a second one, write none"
      (restate! seat {:cadence_seconds 7200})
      (restate! seat {:cadence_seconds 3600})
      (is (empty? (subs-of seat))))
    (seat-do! seat :retire)))

;; ticket 9698ffbd: the subscription doors' crack guards leave the seats
;; actor's writes inside a seat write to the seat door, which judged the
;; whole write, and judge a person's own pause.

(def ^:private step-scope [{:kind "move_step" :actions ["submit" "complete"]}])

(deftest a-narrowing-seat-restate-is-not-rolled-back-by-its-managed-rows
  (let [seat (seat! "stepnarrow" {:walk "move_step" :scope step-scope})
        [sub & more] (subs-of seat)
        narrowed {:walk "move_step"
                  :scope [{:kind "move_step" :actions ["submit" "complete"]
                           :filter {:state "review"}}]}]
    (is (nil? more))
    (is (= "seat" (get-in sub [:data :managed_by])))
    (is (nil? (get-in sub [:data :filter])))
    (testing "a person's narrowing restate commits, and its managed row follows"
      (restate! seat narrowed)
      (is (= {"state" "review"}
             (update-keys (seats/walk-filter (raw :seat seat)) name)))
      (let [[after & more] (subs-of seat)]
        (is (nil? more) "restated, not written again")
        (is (= (str (:id sub)) (str (:id after))))
        (is (= "active" (name (:state after))))
        (is (= "review" (str (get (update-keys (get-in after [:data :filter]) name)
                                  "state"))))))
    (seat-do! seat :retire)))

(deftest pausing-the-only-owner-wake-for-a-state-is-warned
  (let [seat (seat! "steppause" {:scope step-scope
                                 :wake_on [{:kind "move_step" :actions ["submit"]}]})
        [sub & more] (subs-of seat)
        pause (fn [acknowledged]
                (inv/invoke! *eng* :subscription (str (:id sub)) :pause nil
                             {:principal elena :acknowledged acknowledged}))]
    (is (nil? more))
    (is (= "owner" (get-in sub [:data :managed_by])))
    (testing "a person's pause of the one row woken for move_step/review is refused, the crack named"
      (let [w (warned #(pause #{}))]
        (is (= :a-dropped-wake-opens-no-crack (:name w)) (pr-str w))
        (is (re-find #"move_step/review \w+: \S" (str (:reason w))) (:reason w)))
      (is (= ["active"] (mapv (comp name :state) (subs-of seat)))))
    (testing "acknowledged, the pause stands"
      (pause #{:a-dropped-wake-opens-no-crack})
      (is (= ["paused"] (mapv (comp name :state) (subs-of seat)))))
    (seat-do! seat :retire)))
