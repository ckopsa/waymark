(ns waymark10.live-cracks-test
  "The live cracks audit (ticket 26e51658): a row past its state's
  expected age, a seat silent with rows in its walk, a ticket shelved
  twice for one reason, a seat refused out on one guard, and a change
  whose ticket is gone. `find-cracks` is pure over a world of rows, so
  the world here is a map and no engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.money :as money]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live]
            [waymark10.server.postings :as postings]
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

(deftest an-orphan-is-filed-only-once-it-has-stayed-orphaned-one-expected-age
  (let [change {:kind :change
                :states [:open :submitted :merged :closed]
                :initial :open
                :terminal #{:merged :closed}
                :actions {:submit {:from #{:open} :to :submitted}}}
        [c] (of (live/find-cracks
                 {:rdefs [change]
                  :seats []
                  :rows {:change [{:id "c1" :state :submitted :updated-at (ago 30)
                                   :data {:born_from "ticket:k1"}}]}
                  :tickets {"k1" {:id "k1" :state :done}}
                  :now now})
                "orphan")
        later (.plusSeconds ^Instant now (long (inc (:expected_seconds c))))]
    (is (nil? (:wake c)))
    (testing "a change whose ticket landed 30 s ago is not filed"
      (is (= :wait (first (live/route c nil now)))))
    (testing "the same change still submitted one expected age later is filed"
      (is (= :file (first (live/route c nil later)))))
    (testing "a no-wake crack with no :since still files at once"
      (is (= :file (first (live/route (dissoc c :since) nil now)))))))

;; ── an org's hiring gaps (ticket 6a3fac3e) ─────────────────────────────

(def ^:private clerk-seat
  (seat "clerk" {:walk "task" :scope [{:kind "task" :actions ["finish"]}]}))

(defn- gaps [seats seat-domains rows]
  (live/hiring-gaps {:rdefs [task] :seats seats :seat-domains seat-domains
                     :rows {:task rows} :now now}
                    #{"acme" "beta"}))

(defn- open-row [id domain seconds]
  {:id id :state :open :updated-at (ago seconds) :data {:domain domain}})

(deftest an-orgs-hiring-gaps-are-a-door-nobody-holds-or-a-queue-behind
  (testing "one aged row in a state nobody in the org holds is a no_holder gap"
    (let [[g :as all] (gaps [] {} [(open-row "t1" "acme" 4000)])]
      (is (= 1 (count all)))
      (is (= {:org "acme" :kind "task" :state "open" :reason "no_holder"
              :row_ids ["t1"] :oldest_since (str (ago 4000))
              :expected_seconds 3600 :key "acme/task/open"}
             g))
      (testing "and a seat that holds it in another domain does not fill it"
        (is (= ["no_holder"]
               (map :reason (gaps [clerk-seat] {"clerk" "beta"}
                                  [(open-row "t1" "acme" 4000)])))))))
  (testing "three held rows, the oldest at 2.5 expected ages, are a pressure gap"
    (let [rows [(open-row "t3" "acme" 4000)
                (open-row "t1" "acme" 9000)
                (open-row "t2" "acme" 5000)]
          [g :as all] (gaps [clerk-seat] {"clerk" "acme"} rows)]
      (is (= 1 (count all)))
      (is (= "pressure" (:reason g)))
      (is (= ["t1" "t2" "t3"] (:row_ids g)))
      (is (= (str (ago 9000)) (:oldest_since g)))))
  (testing "three held rows, the oldest at 1.5 expected ages, are no gap"
    (is (= [] (gaps [clerk-seat] {"clerk" "acme"}
                    [(open-row "t1" "acme" 5400)
                     (open-row "t2" "acme" 4000)
                     (open-row "t3" "acme" 4000)]))))
  (testing "two held rows at five expected ages are no gap"
    (is (= [] (gaps [clerk-seat] {"clerk" "acme"}
                    [(open-row "t1" "acme" 18000)
                     (open-row "t2" "acme" 18000)]))))
  (testing "the same rows outside the org's domains are never gaps"
    (is (= [] (gaps [] {} [(open-row "t1" "elsewhere" 4000)])))
    (is (= [] (gaps [clerk-seat] {"clerk" "elsewhere"}
                    [(open-row "t1" "elsewhere" 9000)
                     (open-row "t2" "elsewhere" 9000)
                     (open-row "t3" "elsewhere" 9000)]))))
  (testing "keys and order are stable, whatever order the rows come in"
    (let [rows [(open-row "b1" "beta" 4000) (open-row "a1" "acme" 4000)]]
      (is (= ["acme/task/open" "beta/task/open"] (map :key (gaps [] {} rows))))
      (is (= (gaps [] {} rows) (gaps [] {} (reverse rows)))))))

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

(deftest with-no-org-plan-kind-the-pass-runs-as-today
  ;; a waymark10-only engine: no org is live, so nothing is hired and
  ;; the aged row's draft is filed as before
  (let [eng (pass-engine)
        row-id (aged-ticket! eng)
        eng (later eng)]
    (is (not (contains? (inv/resources eng) :org_plan)))
    (live/pass! eng)
    (is (= 1 (count (filed-for eng row-id))))
    (testing "a second pass files nothing more"
      (live/pass! eng)
      (is (= 1 (count (filed-for eng row-id)))))))

(defn- at-clock
  "The engine at `seconds` past `t0`."
  [eng ^Instant t0 seconds]
  (assoc eng :now-fn (constantly (.plusMillis t0 (long (* 1000 seconds))))))

(deftest an-open-sitting-holds-the-wake-for-a-later-pass
  ;; the open ticket's expected age is one second, so 2.5 seconds after
  ;; its write the row is aged and still under `held-ages` of them
  (let [eng (pass-engine)
        seat-id (holder! eng true)
        _ (sitting! eng seat-id)
        t0 (Instant/now)
        row-id (aged-ticket! eng)
        eng (at-clock eng t0 2.5)]
    (live/pass! eng)
    (testing "a seat of one slot with a sitting open is not fired a second run"
      (is (empty? (fires-of eng seat-id))))
    (testing "and the crack waits rather than reaching the mayor"
      (is (empty? (filed-for eng row-id))))))

(deftest a-wake-held-past-three-expected-ages-reaches-the-mayor-naming-the-hold
  (let [eng (pass-engine)
        seat-id (holder! eng true)
        sitting-id (str (get-in (sitting! eng seat-id) [:row :id]))
        row-id (aged-ticket! eng)
        eng (later eng)]
    (live/pass! eng)
    (testing "the held seat is still not fired"
      (is (empty? (fires-of eng seat-id))))
    (testing "the draft is filed, naming the seat, the hold and the sitting"
      (let [filed (filed-for eng row-id)
            detail (str (get-in (first filed) [:data :detail]))]
        (is (= 1 (count filed)))
        (is (re-find #"Seat tried: holder, whose wake was held as damped" detail))
        (is (str/includes? detail (str "by an open sitting, " sitting-id)))
        (is (re-find #"open \d+ seconds" detail))))
    (testing "a second pass does not file it again"
      (live/pass! eng)
      (is (= 1 (count (filed-for eng row-id))))
      (is (empty? (fires-of eng seat-id))))))

(deftest a-recurring-rule-with-no-owner-or-a-gone-owner-is-a-recurs-orphan
  (let [contract {:kind :contract
                  :states [:active :ended]
                  :terminal #{:ended}
                  :recurs [{:name :visits :makes :visit :while #{:active}
                            :owner '(data :manager_id)}]}
        visit {:kind :visit :states [:planned :done] :terminal #{:done}}
        planner (seat "planner" {:scope [{:kind "visit" :actions ["create"]}]})
        idler (seat "idler" {:scope [{:kind "visit" :actions ["finish"]}]})
        world (fn [state data]
                {:rdefs [contract visit]
                 :seats [planner idler]
                 :seat-ids #{"planner" "idler" "retired"}
                 :rows {:contract [{:id "k1" :state state :data data}]}
                 :now now})
        orphans (fn [state data] (of (live/find-cracks (world state data)) "recurs_orphan"))]
    (testing "an unset owner is one crack naming the rule and the seats that hold the create door"
      (let [[c :as cs] (orphans :active {})]
        (is (= 1 (count cs)))
        (is (= ["contract" "k1" "visits"] ((juxt :kind :id :rule) c)))
        (is (= ["planner"] (:seats c)))
        (is (str/includes? (:what c) "names no owner"))
        (is (str/includes? (:key c) "visits"))))
    (testing "a retired seat, or an active one without the create door, is an orphan too"
      (is (str/includes? (:what (first (orphans :active {:manager_id "retired"}))) "no longer active"))
      (is (str/includes? (:what (first (orphans :active {:manager_id "idler"}))) "no longer holds")))
    (testing "an owner holding the door, a member, or a row out of :while is none"
      (is (empty? (orphans :active {:manager_id "planner"})))
      (is (empty? (orphans :active {:manager_id "elena"})))
      (is (empty? (orphans :ended {}))))
    (testing "the same orphan is not filed twice"
      (let [w (world :active {})
            [c] (of (live/unfiled (live/find-cracks w) []) "recurs_orphan")
            filed {:id "t" :state :draft :data (live/ticket-of c)}]
        (is (some? c))
        (is (empty? (of (live/unfiled (live/find-cracks w) [filed]) "recurs_orphan")))))))

(deftest a-pull-link-away-a-day-with-a-fire-due-is-filed-once
  (let [link (fn [seen]
               {:id "link-1" :state :live :created-at (ago 999999)
                :data (cond-> {:delivery "pull" :provider "localfire" :owner "sam"}
                        seen (assoc :puller_seen_at (str seen)))})
        due {:id "sched-1" :state :live :data {:retry_after (str now)}}
        idle {:id "sched-2" :state :live :data {}}
        world (fn [l sched]
                {:rdefs [] :seats [] :rows {} :links [l]
                 :pools {"s1" ["link-1"]} :schedules {"s1" sched} :now now})
        cracks (fn [w] (of (live/find-cracks w) "puller_away"))]
    (testing "a day away with a fire due is one crack naming the link, its owner and when"
      (let [[c :as cs] (cracks (world (link (ago 90000)) due))]
        (is (= 1 (count cs)))
        (is (= ["runner_link" "link-1"] ((juxt :kind :id) c)))
        (is (str/includes? (:what c) "sam"))
        (is (str/includes? (:what c) (str (ago 90000))))))
    (testing "never connected, the link a day old, is a crack too"
      (is (= 1 (count (cracks (world (link nil) due))))))
    (testing "an hour away, or no fire due, is none"
      (is (empty? (cracks (world (link (ago 3600)) due))))
      (is (empty? (cracks (world (link (ago 90000)) idle)))))
    (testing "the same absence is not filed twice"
      (let [w (world (link (ago 90000)) due)
            [c] (live/unfiled (live/find-cracks w) [])
            filed {:id "t" :state :draft :data (live/ticket-of c)}]
        (is (= "puller_away" (:crack c)))
        (is (empty? (of (live/unfiled (live/find-cracks w) [filed]) "puller_away")))))))

;; [balance-drift] (docs/spec-ledger-money.md §10, ledger step 10c): a
;; pass rewrites a drifted balance from its postings as the Ledger, with
;; a log row, and files one ticket naming the row, the account and both
;; numbers; a second pass finds nothing to repair and files nothing.

(def ^:private till
  (r/resource {:kind :lc_till
               :plural "lc_tills"
               :states [:open]
               :initial :open
               :terminal #{}
               :allow-dead #{:open}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]]
               :accounts {:cash {:unit [:money "USD"]}}}))

(deftest a-pass-repairs-and-reports-a-drifted-balance-once
  (let [eng (assoc (engine/engine {:storage (memory/storage) :resources [crack-ticket till]})
                   :fire-adapter (sch/fake-fire))
        st (:storage eng)
        till-id (str (:id (:row (inv/create! eng :lc_till {:name "front"} {:principal elena}))))
        cash (fn []
               (:amount (money/from-wire
                         (get-in (store/with-tx st #(store/load-row st % :lc_till till-id {}))
                                 [:data :cash])
                         nil)))
        filed (fn []
                (store/with-tx st
                  (fn [tx]
                    (filterv #(str/includes? (str (get-in % [:data :detail]))
                                             (str "[crack balance_drift:lc_till/" till-id "/cash:"))
                             (store/query-rows st tx :ticket {:state :open} {:limit 200})))))]
    (store/with-tx st
      (fn [tx]
        (postings/post! eng tx {:kind :lc_till :id till-id :action :take :log-id "log-1"}
                        [{:account-kind :lc_till :account-row till-id :account-name :cash
                          :amount (money/money "18.00" "USD")}])
        ;; the cache drifts: a hand past the ledger writes the field
        (inv/update-quiet! eng tx :lc_till till-id
                           #(assoc % :cash (money/money "99.00" "USD")))))
    (is (== 99 (cash)))
    (live/pass! eng)
    (testing "one pass rewrites the field from the postings"
      (is (== 18 (cash))))
    (testing "as the Ledger, with a log row"
      (let [logs (store/with-tx st
                   #(store/transitions st % {:kind :lc_till :resource-id till-id} {}))
            rec (last logs)]
        (is (= "recompute_balance" (name (:action rec))))
        (is (str/includes? (pr-str (:actor rec)) (:id postings/poster)))))
    (testing "and files one ticket naming the row, the account and both numbers"
      (let [[t :as ts] (filed)
            detail (get-in t [:data :detail])]
        (is (= 1 (count ts)))
        (doseq [s [till-id "cash" "99" "18" "Repaired: by the Ledger"]]
          (is (str/includes? detail s) s))))
    (testing "a second pass files nothing"
      (live/pass! eng)
      (is (= 1 (count (filed))))
      (is (== 18 (cash))))))

(deftest the-drift-sweep-reads-past-the-first-row-page
  (let [eng (assoc (engine/engine {:storage (memory/storage) :resources [crack-ticket till]})
                   :fire-adapter (sch/fake-fire))
        st (:storage eng)
        ids (mapv (fn [n]
                    (Thread/sleep 2)
                    (str (:id (:row (inv/create! eng :lc_till {:name (str "till " n)}
                                                 {:principal elena})))))
                  (range 3))
        newest (peek ids)]
    (store/with-tx st
      (fn [tx]
        (inv/update-quiet! eng tx :lc_till newest
                           #(assoc % :cash (money/money "7.00" "USD")))))
    (with-redefs [live/row-page 1]
      (let [cs (live/balance-drift! eng)]
        (testing "the newest row, past a one-row page, is judged and repaired"
          (is (= 1 (count cs)))
          (is (str/includes? (pr-str (first cs)) newest)))))))
