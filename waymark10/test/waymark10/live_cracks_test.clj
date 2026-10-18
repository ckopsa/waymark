(ns waymark10.live-cracks-test
  "The live cracks audit (ticket 26e51658): a row past its state's
  expected age, a seat silent with rows in its walk, a ticket shelved
  twice for one reason, a seat refused out on one guard, and a change
  whose ticket is gone. `find-cracks` is pure over a world of rows, so
  the world here is a map and no engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-10-13T12:00:00Z"))

(defn- ago [seconds] (.minusSeconds ^Instant now (long seconds)))

(def ^:private task
  {:kind :task
   :states [:draft :open :done]
   :initial :draft
   :terminal #{:done}
   :expected-age {:open 3600}
   :actions {:groom {:from #{:draft} :to :open}
             :finish {:from #{:open} :to :done}}})

(defn- seat [name data]
  {:id name :state :active :data (assoc data :name name)})

(defn- of [cracks word]
  (filterv #(= word (:crack %)) cracks))

(def ^:private finisher
  (seat "finisher" {:scope [{:kind "task" :actions ["finish"]}]
                    :cadence_seconds 3600}))

(deftest an-aged-row-is-reported-once-across-two-passes
  (let [world {:rdefs [task]
               :seats [finisher]
               :rows {:task [{:id "t1" :state :open :updated-at (ago 7200) :data {}}
                             {:id "t2" :state :open :updated-at (ago 60) :data {}}
                             {:id "t3" :state :draft :updated-at (ago 999999) :data {}}
                             {:id "t4" :state :done :updated-at (ago 999999) :data {}}]}
               :now now}
        first-pass (live/unfiled (live/find-cracks world) [])]
    (testing "the first pass names the row past its state's age, and the seat"
      (is (= 1 (count first-pass)))
      (let [[c] first-pass]
        (is (= ["aged" "task" "t1" "open"] ((juxt :crack :kind :id :state) c)))
        (is (= ["finisher"] (:seats c)))
        (is (= 3600 (:expected_seconds c)))))
    (let [ticket (fn [state]
                   {:id "filed" :state state :data (live/ticket-of (first first-pass))})]
      (testing "the ticket names the row, the crack and the seat"
        (let [detail (get-in (ticket :draft) [:data :detail])]
          (is (re-find #"Row: task t1" detail))
          (is (re-find #"Crack: aged" detail))
          (is (re-find #"finisher" detail))))
      (testing "a second pass, with that ticket in draft or open, adds nothing"
        (is (empty? (live/unfiled (live/find-cracks world) [(ticket :draft)])))
        (is (empty? (live/unfiled (live/find-cracks world) [(ticket :open)]))))
      (testing "a ticket that is done stands for nothing, and the crack is filed again"
        (is (= 1 (count (live/unfiled (live/find-cracks world) [(ticket :done)]))))))
    (testing "one crack found twice in a pass is filed one time"
      (let [found (live/find-cracks world)]
        (is (= 1 (count (live/unfiled (concat found found) []))))))))

(deftest a-state-with-no-declared-age-takes-the-default-for-its-name
  (let [plain (dissoc task :expected-age)
        found (live/find-cracks
               {:rdefs [plain]
                :seats [finisher]
                :rows {:task [{:id "t1" :state :open :updated-at (ago 7200) :data {}}
                              {:id "t2" :state :open :updated-at (ago 172800) :data {}}
                              {:id "t3" :state :draft :updated-at (ago 9999999) :data {}}]}
                :now now})]
    (is (= ["t2"] (mapv :id (of found "aged"))))
    (is (nil? (live/expected-age plain :draft)))))

(deftest a-seat-with-a-walk-row-and-a-stale-fire-is-silent
  (let [walker (seat "walker" {:scope [{:kind "task"
                                        :actions ["finish"]
                                        :filter {:state "open"}}]
                               :walk "task"
                               :cadence_seconds 600})
        rows {:task [{:id "t1" :state :open :updated-at (ago 60) :data {}}]}
        stale {:data {:wake_fired_at (str (ago 3600))
                      :last_silent_wake "fire_gap"
                      :last_silent_wake_at (str (ago 1800))}}
        world (fn [rows sched]
                {:rdefs [task] :seats [walker] :rows rows
                 :schedules {"walker" sched} :now now})]
    (testing "it is reported with what its schedule says held the wake"
      (let [[c & more] (of (live/find-cracks (world rows stale)) "silent")]
        (is (some? c))
        (is (empty? more))
        (is (= ["seat" "walker"] ((juxt :kind :id) c)))
        (is (= "fire_gap" (:last_silent_wake c)))
        (is (= (str (ago 1800)) (:last_silent_wake_at c)))
        (is (= 1 (:waiting c)))
        (is (re-find #"fire_gap" (:detail (live/ticket-of c))))))
    (testing "a fire inside the cadence is not silence"
      (is (empty? (of (live/find-cracks
                       (world rows {:data {:wake_fired_at (str (ago 60))}}))
                      "silent"))))
    (testing "a seat with nothing in its walk is not silent"
      (is (empty? (of (live/find-cracks
                       (world {:task [{:id "t1" :state :draft
                                       :updated-at (ago 60) :data {}}]}
                              stale))
                      "silent"))))
    (testing "a seat whose only walk rows its sit withholds is not silent"
      (is (empty? (of (live/find-cracks
                       (assoc (world rows stale) :withheld {"walker" #{"t1"}}))
                      "silent"))))))

(deftest a-ticket-shelved-twice-for-one-reason-is-a-bounce
  (let [ticket {:kind :ticket
                :states [:draft :open]
                :initial :draft
                :terminal #{}
                :actions {:groom {:from #{:draft} :to :open}
                          :shelve {:from #{:open} :to :draft}}}
        groomer (seat "groomer" {:scope [{:kind "ticket" :actions ["groom"]}]})
        world (fn [reasons]
                {:rdefs [ticket] :seats [groomer]
                 :rows {:ticket [{:id "k1" :state :draft :updated-at (ago 60)
                                  :data {:shelved_because "no repo"}}]}
                 :shelvings {"k1" reasons} :now now})]
    (let [[c] (of (live/find-cracks (world ["other" "no repo" "no repo"])) "bounce")]
      (is (= "k1" (:id c)))
      (is (= "no repo" (:shelved_because c)))
      (is (= ["groomer"] (:seats c))))
    (is (empty? (of (live/find-cracks (world ["no repo"])) "bounce")))
    (is (empty? (of (live/find-cracks (world ["no repo" "other"])) "bounce")))
    (is (empty? (of (live/find-cracks (world [nil nil])) "bounce")))))

(deftest a-seat-refused-out-on-one-guard-is-a-refusal-loop
  (let [builder (seat "builder" {:scope []})
        out (fn [guard] {:outcome "refused_out" :flags [(str "refused_by:" guard)]})
        cut (fn [guard] {:outcome "cut_short" :last_refusal {:guard guard}})
        loops (fn [sittings]
                (of (live/find-cracks {:rdefs [] :seats [builder]
                                       :sittings {"builder" sittings} :now now})
                    "refusal_loop"))]
    (is (= ["task/needs-reopen"]
           (mapv :guard (loops [(out "task/needs-reopen") (cut "task/needs-reopen")
                                (out "task/needs-reopen")]))))
    (is (empty? (loops [(out "a") (out "b") (out "a")])))
    (is (empty? (loops [(out "a") (out "a")])))
    (is (empty? (loops [(out "a") {:outcome "submitted"} (out "a")])))))

(deftest an-aged-row-goes-to-the-seat-that-holds-it-and-to-the-mayor-when-none-does
  (let [change {:kind :change
                :states [:open :submitted :merged]
                :initial :open
                :terminal #{:merged}
                :actions {:submit {:from #{:open} :to :submitted}
                          :stall {:from #{:open} :to :open}}}
        ticket {:kind :ticket
                :states [:draft :open :done]
                :initial :draft
                :terminal #{:done}
                :actions {:groom {:from #{:draft} :to :open}}}
        coder (seat "coder" {:walk "ticket"
                             :scope [{:kind "ticket" :actions ["groom"]}
                                     {:kind "change" :actions ["stall" "submit"]}]})
        world (fn [seats]
                {:rdefs [change ticket]
                 :seats seats
                 :rows {:change [{:id "c1" :state :open :updated-at (ago 100000)
                                  :data {:born_from "ticket:k1"}}]}
                 :tickets {"k1" {:id "k1" :state :open :data {}}}
                 :now now})
        aged (fn [seats] (first (of (live/find-cracks (world seats)) "aged")))]
    (testing "an aged change in open wakes the code seat that holds stall and submit"
      (let [c (aged [coder])]
        (is (= {:seat "coder" :name "coder"} (:wake c)))
        (is (= :wake (first (live/route c nil now))))
        (is (re-find #"\"id\":\s*\"c1\"" (live/wake-text c)))
        (is (re-find #"aged:change:c1" (live/wake-text c)))
        (testing "and waits on that seat for one expected age"
          (is (= :wait (first (live/route c (ago 60) now)))))
        (testing "and then reaches the mayor, saying which seat was tried"
          (let [[door tried] (live/route c (ago 90000) now)]
            (is (= :file door))
            (is (re-find #"Seat tried: coder" (:detail (live/ticket-of tried))))))))
    (testing "an aged row no seat holds still reaches the mayor"
      (let [c (aged [])
            [door filed] (live/route c nil now)]
        (is (nil? (:wake c)))
        (is (= :file door))
        (is (not (re-find #"Seat tried" (:detail (live/ticket-of filed)))))))
    (testing "a seat that holds the door and does not walk the row is not woken"
      (is (nil? (:wake (aged [(seat "closer" {:scope [{:kind "change"
                                                         :actions ["submit"]}]})])))))
    (testing "of two seats that hold the row, the shorter cadence is woken"
      (let [slow (seat "slow" (assoc (:data coder) :cadence_seconds 86400))
            fast (seat "fast" (assoc (:data coder) :cadence_seconds 600))]
        (is (= "fast" (get-in (aged [slow fast]) [:wake :seat])))
        (testing "and on a tie, the first seat name"
          (is (= "a-coder" (get-in (aged [(seat "b-coder" (:data coder))
                                          (seat "a-coder" (:data coder))])
                                   [:wake :seat]))))))))

(deftest an-orphan-wakes-the-seat-that-walks-changes
  (let [change {:kind :change
                :states [:open :submitted :merged :closed]
                :initial :open
                :terminal #{:merged :closed}
                :actions {:close {:from #{:open :submitted} :to :closed}}}
        ticket {:kind :ticket
                :states [:open :done]
                :initial :open
                :terminal #{:done}
                :actions {}}
        sweeper (seat "sweeper" {:walk "change"
                                 :scope [{:kind "change" :actions ["close"]}]})
        coder (seat "coder" {:walk "ticket"
                             :scope [{:kind "ticket" :actions []}
                                     {:kind "change" :actions ["close"]}]})
        orphan (fn [seats]
                 (first (of (live/find-cracks
                             {:rdefs [change ticket]
                              :seats seats
                              :rows {:change [{:id "c1" :state :submitted
                                               :updated-at (ago 60)
                                               :data {:born_from "ticket:k1"}}]}
                              :tickets {"k1" {:id "k1" :state :done :data {}}}
                              :now now})
                            "orphan")))]
    (testing "the seat whose walk is change is woken on it"
      (let [c (orphan [coder sweeper])]
        (is (= {:seat "sweeper" :name "sweeper"} (:wake c)))
        (is (= 86400 (:expected_seconds c)))
        (is (= :wake (first (live/route c nil now))))
        (is (re-find #"orphan:change:c1" (live/wake-text c)))
        (testing "and the mayor is told one expected age later"
          (let [[door tried] (live/route c (ago 90000) now)]
            (is (= :file door))
            (is (re-find #"Seat tried: sweeper" (:detail (live/ticket-of tried))))))))
    (testing "a ticket walk does not get it: its sit withholds the change"
      (is (nil? (:wake (orphan [coder])))))))

(deftest a-silent-seat-is-woken-itself-before-the-mayor-is-told
  (let [walker (seat "walker" {:scope [{:kind "task" :actions ["finish"]}]
                               :walk "task"
                               :cadence_seconds 600})
        [c] (of (live/find-cracks
                 {:rdefs [task] :seats [walker]
                  :rows {:task [{:id "t1" :state :open :updated-at (ago 60) :data {}}]}
                  :schedules {"walker" {:data {:wake_fired_at (str (ago 3600))}}}
                  :now now})
                "silent")]
    (is (= {:seat "walker" :name "walker"} (:wake c)))
    (is (= :wake (first (live/route c nil now))))
    (testing "by a fire that names no row, so its sit walks its queue"
      (is (re-find #"silent:seat:walker" (live/wake-text c)))
      (is (not (re-find #"\"id\"" (live/wake-text c)))))
    (testing "and waits one cadence on it"
      (is (= :wait (first (live/route c (ago 60) now)))))
    (testing "and then reaches the mayor, saying it was tried"
      (let [[door tried] (live/route c (ago 700) now)]
        (is (= :file door))
        (is (re-find #"Seat tried: walker" (:detail (live/ticket-of tried))))))))

(deftest a-change-whose-ticket-is-dropped-is-an-orphan
  (let [change {:kind :change
                :states [:open :submitted :merged :closed]
                :initial :open
                :terminal #{:merged}
                :actions {:submit {:from #{:open} :to :submitted}
                          :close {:from #{:open :submitted} :to :closed}}}
        closer (seat "closer" {:scope [{:kind "change" :actions ["close"]}]})
        found (live/find-cracks
               {:rdefs [change]
                :seats [closer]
                :rows {:change [{:id "c1" :state :submitted :updated-at (ago 60)
                                 :data {:born_from "ticket:k1"}}
                                {:id "c2" :state :open :updated-at (ago 60)
                                 :data {:born_from "ticket:k2"}}
                                {:id "c3" :state :closed :updated-at (ago 60)
                                 :data {:born_from "ticket:k1"}}
                                {:id "c4" :state :open :updated-at (ago 60)
                                 :data {:born_from "ticket:k3"}}]}
                :tickets {"k1" {:id "k1" :state :dropped}
                          "k2" {:id "k2" :state :open}
                          "k3" {:id "k3" :state :done}}
                :now now})
        orphans (of found "orphan")]
    (is (= ["c1" "c4"] (mapv :id orphans)))
    (let [[c] orphans]
      (is (= ["k1" "dropped"] ((juxt :ticket :ticket_state) c)))
      (is (= ["closer"] (:seats c)))
      (is (re-find #"\[crack orphan:change:c1\]" (:detail (live/ticket-of c)))))))

;; ── the pass against an engine (ticket 4ff29b4a) ──────────────────────
;;
;; `pass!` over a memory engine: the wake goes out with the crack's key
;; in its recorded text, a second pass reads it back and waits, a
;; refused fire files the draft, and an open sitting holds the wake.

(def ^:private crack-ticket
  (r/resource
   {:kind :ticket
    :plural "tickets"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :expected-age {:open 1}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 200}]]
             [:detail {:optional true} [:string {:max 4000}]]
             [:type {:optional true} [:string {:max 20}]]
             [:priority {:optional true} :int]
             [:repo {:optional true} [:string {:max 80}]]]
    :filterable {:state #{:eq :in}}
    :default-filters {:state "open"}
    :actions {:finish {:from #{:open} :to :done
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Done is done."}}}}))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))
(def ^:private holder-scope [{:kind "ticket" :actions ["finish"]}])

(defn- pass-engine []
  (assoc (engine/engine {:storage (memory/storage) :resources [crack-ticket]})
         :schedule-adapters {:claude_routine (sch/fake-scheduler)}
         :fire-adapter (sch/fake-fire)))

(defn- holder!
  "The one seat that holds an open ticket: it walks tickets and may
  finish one. Linked, its schedule is minted and carries a person's
  link, so its `fire` door goes out."
  [eng linked?]
  (let [drain! #(consumers/drain-consumer! eng :crack-fires (sch/consumer-fn eng))
        _ (when linked? (drain!))
        seat-id (str (:id (:row (inv/create! eng :seat
                                             {:name "holder"
                                              :charter "Finish the tickets this house leaves open."
                                              :scope holder-scope
                                              :walk "ticket"
                                              :substitute_drop []
                                              :held_for []
                                              :substitute_for []
                                              :standing_ttl_seconds 604800
                                              :cadence_seconds 3600
                                              :budget_usd_per_week 5M
                                              :sitting_budget_tokens 60000
                                              :rows_per_firing 20}
                                             {:principal elena}))))]
    (when linked?
      (drain!)
      (inv/invoke! eng :schedule (str (:id (sch/schedule-for-seat eng seat-id))) :link
                   {:fire_url "https://api.anthropic.com/v1/claude_code/routines/trig_holder/fire"
                    :token "rk-test-holder-0123456789abcdef"}
                   {:principal elena}))
    seat-id))

(defn- sitting! [eng seat-id]
  (let [model (inv/create! eng :model
                           {:name "holder-model" :display "holder-model"
                            :vendor "anthropic" :tier "strong"
                            :price_input_per_mtok 3M
                            :price_output_per_mtok 15M
                            :price_cache_read_per_mtok 0.3M
                            :price_cache_write_per_mtok 3.75M}
                           {:principal elena})
        grant (inv/create! eng :grant {:audience "clerk" :scope holder-scope}
                           {:principal elena})]
    (inv/create! eng :sitting {:seat seat-id
                               :model (str (get-in model [:row :id]))
                               :grant (str (get-in grant [:row :id]))}
                 {:principal clerk})))

(defn- aged-ticket! [eng]
  (str (:id (:row (inv/create! eng :ticket {:title "Left open"} {:principal elena})))))

(defn- later
  "The engine ten minutes on, past the open ticket's expected age."
  [eng]
  (let [at (.plusSeconds (Instant/now) 600)]
    (assoc eng :now-fn (constantly at))))

(defn- fires-of [eng seat-id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (filterv #(= "fire" (name (:action %)))
               (store/transitions (:storage eng) tx
                                  {:kind :seat :resource-id seat-id} {})))))

(defn- filed-for
  "The tickets the pass filed for this row's aged crack."
  [eng row-id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (filterv #(str/includes? (str (get-in % [:data :detail]))
                               (str "[crack aged:ticket:" row-id "]"))
               (store/query-rows (:storage eng) tx :ticket {:state :open}
                                 {:limit 200})))))

(deftest a-pass-wakes-the-seat-that-holds-an-aged-row-and-then-waits
  ;; on the real clock, which the seat's log records the fire by: the
  ;; row ages past its one second, and the second pass comes inside
  ;; one expected age of the fire
  (let [eng (pass-engine)
        seat-id (holder! eng true)
        row-id (aged-ticket! eng)]
    (Thread/sleep 1500)
    (live/pass! eng)
    (testing "the first pass fires the seat once, the crack's key in the recorded text"
      (let [fires (fires-of eng seat-id)]
        (is (= 1 (count fires)))
        (is (str/includes? (str (get-in (first fires) [:inputs :text]))
                           (str "aged:ticket:" row-id)))))
    (testing "and files nothing for the row"
      (is (empty? (filed-for eng row-id))))
    (testing "a second pass reads the fire back from the seat's log and waits"
      (live/pass! eng)
      (is (= 1 (count (fires-of eng seat-id))))
      (is (empty? (filed-for eng row-id))))))

(deftest a-refused-wake-files-the-draft-saying-the-seat-would-not-be-woken
  (let [eng (pass-engine)
        seat-id (holder! eng false)
        row-id (aged-ticket! eng)
        eng (later eng)]
    (live/pass! eng)
    (is (empty? (fires-of eng seat-id)))
    (let [filed (filed-for eng row-id)]
      (is (= 1 (count filed)))
      (is (re-find #"Seat tried: holder, which would not be woken"
                   (str (get-in (first filed) [:data :detail])))))))

(deftest an-open-sitting-holds-the-wake-for-a-later-pass
  (let [eng (pass-engine)
        seat-id (holder! eng true)
        _ (sitting! eng seat-id)
        row-id (aged-ticket! eng)
        eng (later eng)]
    (live/pass! eng)
    (testing "a seat of one slot with a sitting open is not fired a second run"
      (is (empty? (fires-of eng seat-id))))
    (testing "and the crack waits rather than reaching the mayor"
      (is (empty? (filed-for eng row-id))))))
