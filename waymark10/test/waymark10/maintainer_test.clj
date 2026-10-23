(ns waymark10.maintainer-test
  "Phase-6 acceptance, part 1: count truth and clock truth. Suite-local
  kinds (fixtures.clj is frozen): an owns pair (mnt_project ←
  mnt_chore), a related pair (mnt_window ↔ mnt_event on :day), and a
  clocked reminder, whose overdue flip also drives a fact notice rule
  through the maintainer to a fake chat. Every deftest owns its world
  against real Postgres; WAYMARK10_TEST_DSN overrides the DSN."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maint]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["mnt_projects" "mnt_chores" "mnt_windows" "mnt_events" "mnt_reminders" "mnt_visits"
   "capabilities" "members" "roles" "grants" "approval_requests"
   "mcp_servers" "held_calls" "notifiers" "notice_rules"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_cursors" "waymark10_job_leases"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng [resources opts f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine (merge {:storage st :resources resources} opts)))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

(defn- transition-count [eng kind id]
  (count (store/with-tx (:storage eng)
           (fn [tx] (store/transitions (:storage eng) tx
                                       {:kind kind :resource-id id} {})))))

;; ── fixtures: the owns pair ─────────────────────────────────────────

(defn- project-map [open-where]
  {:kind :mnt_project
   :plural "mnt_projects"
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 60}]]
            [:open_chores {:optional true} [:maybe :int]]
            [:has_open_chores {:optional true} [:maybe :boolean]]]
   :owns [{:kind :mnt_chore :via :project_id}]
   :derived {:open_chores {:count {:owns :mnt_chore
                                   :where {:state open-where}}}
             :has_open_chores {:over [:open_chores]
                               :expr '(< 0 (var :open_chores))}}
   :actions {:finish {:from #{:open} :to :done
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Done is history."}}}})

(def ^:private project (r/resource (project-map #{"todo"})))

(def ^:private chore
  (r/resource
   {:kind :mnt_chore
    :plural "mnt_chores"
    :states [:todo :done]
    :initial :todo
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:project_id {:kind :mnt_project} :waymark/ref]]
    :filterable {:project_id #{:eq}}
    :actions {:finish {:from #{:todo} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

;; ── fixtures: the related pair ──────────────────────────────────────

(def ^:private window
  (r/resource
   {:kind :mnt_window
    :plural "mnt_windows"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "Window of {data.day} · {state}"
    :schema [:map
             [:day :waymark/date]
             [:clash_count {:optional true} [:maybe :int]]
             [:clashing {:optional true} [:maybe :boolean]]]
    :filterable {:day #{:eq}}
    :related {:same_day {:kind :mnt_event :on [[:day := :day]]}}
    :derived {:clash_count {:count {:related :same_day
                                    :where {:category #{"blocking"}
                                            :state #{"noted"}}}}
              :clashing {:over [:clash_count]
                         :expr '(< 0 (var :clash_count))}}
    :actions {:close {:from #{:open} :to :closed
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "A closed window is history."}}}}))

(def ^:private event
  (r/resource
   {:kind :mnt_event
    :plural "mnt_events"
    :states [:noted :cancelled]
    :initial :noted
    :terminal #{:cancelled}
    :summary "{data.category} on {data.day} · {state}"
    :schema [:map
             [:day :waymark/date]
             [:category [:string {:min 1 :max 20}]]]
    :filterable {:day #{:eq} :category #{:eq}}
    :actions {:cancel {:from #{:noted} :to :cancelled
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Cancelled is history."}}}}))

;; ── fixtures: the clocked reminder ──────────────────────────────────

(def ^:private reminder
  (r/resource
   {:kind :mnt_reminder
    :plural "mnt_reminders"
    :states [:waiting :done]
    :initial :waiting
    :terminal #{:done}
    :summary "{data.note} · {state}"
    :schema [:map
             [:note [:string {:min 1 :max 60}]]
             [:owner {:optional true :kind :member} [:maybe :waymark/ref]]
             [:due_on :waymark/date]
             [:overdue {:optional true} [:maybe :boolean]]]
    :derived {:overdue {:over [:due_on :now]
                        :expr '(<= (var :due_on) (date-of (var :now)))}}
    :actions {:finish {:from #{:waiting} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

;; ── 1a. count truth over an owns edge ───────────────────────────────

(deftest count-truth-owns
  (fresh!)
  (with-eng [project chore] {}
    (fn [eng]
      (let [{prow :row} (inv/create! eng :mnt_project {:title "garden"}
                                     {:principal elena})
            pid (:id prow)]
        (testing "born with its counts computed"
          (is (= 0 (get-in prow [:data :open_chores])))
          (is (false? (get-in prow [:data :has_open_chores]))))
        (let [{crow :row} (inv/create! eng :mnt_chore
                                       {:title "weed" :project_id pid}
                                       {:principal elena})]
          (testing "writing a child updates the parent in the same call"
            (let [p (reload eng :mnt_project pid)]
              (is (= 1 (get-in p [:data :open_chores])))
              (is (true? (get-in p [:data :has_open_chores]))
                  "the derived-over-derived fact flips with it")))
          (testing "maintenance, not a write: version and log untouched"
            (is (= 1 (:version (reload eng :mnt_project pid))))
            (is (= 1 (transition-count eng :mnt_project pid))
                "only the create is logged"))
          (testing "the child's transition flips the count back"
            (inv/invoke! eng :mnt_chore (:id crow) :finish nil
                         {:principal elena})
            (let [p (reload eng :mnt_project pid)]
              (is (= 0 (get-in p [:data :open_chores])))
              (is (false? (get-in p [:data :has_open_chores])))
              (is (= 1 (:version p))))))))))

;; ── 1b. count truth over a related edge ─────────────────────────────

(deftest count-truth-related
  (fresh!)
  (with-eng [window event] {}
    (fn [eng]
      (let [{wrow :row} (inv/create! eng :mnt_window {:day "2026-07-14"}
                                     {:principal elena})
            wid (:id wrow)]
        (is (= 0 (get-in wrow [:data :clash_count])))
        (is (false? (get-in wrow [:data :clashing])))
        (let [{erow :row} (inv/create! eng :mnt_event
                                       {:day "2026-07-14"
                                        :category "blocking"}
                                       {:principal elena})]
          (testing "a matching write on the target flips the source"
            (let [w (reload eng :mnt_window wid)]
              (is (= 1 (get-in w [:data :clash_count])))
              (is (true? (get-in w [:data :clashing])))))
          (testing "where filters hold: a non-blocking event is not counted"
            (inv/create! eng :mnt_event
                         {:day "2026-07-14" :category "social"}
                         {:principal elena})
            (is (= 1 (get-in (reload eng :mnt_window wid)
                             [:data :clash_count]))))
          (testing "join conditions hold: another day is not counted"
            (inv/create! eng :mnt_event
                         {:day "2026-07-15" :category "blocking"}
                         {:principal elena})
            (is (= 1 (get-in (reload eng :mnt_window wid)
                             [:data :clash_count]))))
          (testing "a state transition on the target re-counts through
                    the where filter"
            (inv/invoke! eng :mnt_event (:id erow) :cancel nil
                         {:principal elena})
            (let [w (reload eng :mnt_window wid)]
              (is (= 0 (get-in w [:data :clash_count])))
              (is (false? (get-in w [:data :clashing]))))))))))

;; ── 1c. the fingerprint knows the count facet ───────────────────────

(deftest count-fingerprint-classification
  (testing "a count.where change is :data-law with the fact stale"
    (let [d (fp/diff-fingerprints
             (fp/fingerprint-of (r/resource (project-map #{"todo"})))
             (fp/fingerprint-of (r/resource (project-map #{"todo" "paused"}))))]
      (is (= :data-law (fp/classify-diff d)))
      (is (= ["open_chores"] (fp/stale-facts d)))))
  (testing "where sets are canonical: two spellings, one hash"
    (is (= (fp/fingerprint-hash
            (fp/fingerprint-of (r/resource (project-map #{"b" "a"}))))
           (fp/fingerprint-hash
            (fp/fingerprint-of (r/resource (project-map ["a" "b" "a"])))))))
  (testing "the edge itself is :code-or-shape"
    (let [base (project-map #{"todo"})
          moved (assoc-in base [:derived :open_chores :count :owns]
                          :mnt_other)
          d (fp/diff-fingerprints (fp/fingerprint-of base)
                                  (fp/fingerprint-of moved))]
      (is (= ["derived.open_chores.count.owns"]
             (mapv :path (:changed d))))
      (is (= :code-or-shape (fp/classify-diff d))))))

;; ── 2. clock truth ──────────────────────────────────────────────────

(deftest clock-truth
  (fresh!)
  (let [clock (atom (Instant/parse "2026-07-10T12:00:00Z"))]
    (with-eng [reminder] {:now-fn (fn [] @clock)}
      (fn [eng]
        (let [{row :row} (inv/create! eng :mnt_reminder
                                      {:note "water" :due_on "2026-07-12"}
                                      {:principal elena})
              rid (:id row)]
          (is (false? (get-in (reload eng :mnt_reminder rid)
                              [:data :overdue])))
          (testing "next_flip_at lands on due_on"
            (is (= (Instant/parse "2026-07-12T00:00:00Z")
                   (:next-flip-at (reload eng :mnt_reminder rid)))))
          (testing "a sweep before the moment changes nothing"
            (maint/sweep-clocks! eng)
            (let [r (reload eng :mnt_reminder rid)]
              (is (false? (get-in r [:data :overdue])))
              (is (= (Instant/parse "2026-07-12T00:00:00Z")
                     (:next-flip-at r)))))
          (testing "the fact flips without a write once now passes due_on"
            (reset! clock (Instant/parse "2026-07-12T06:00:00Z"))
            (maint/sweep-clocks! eng)
            (let [r (reload eng :mnt_reminder rid)]
              (is (true? (get-in r [:data :overdue])))
              (is (= 1 (:version r)) "a clock flip is maintenance, not a write")
              (is (= 1 (transition-count eng :mnt_reminder rid)))
              (is (= (Instant/parse "2026-07-13T00:00:00Z")
                     (:next-flip-at r))
                  "the index advances to the next candidate")))
          (testing "past the last candidate the index parks"
            (reset! clock (Instant/parse "2026-07-14T06:00:00Z"))
            (maint/sweep-clocks! eng)
            (let [r (reload eng :mnt_reminder rid)]
              (is (true? (get-in r [:data :overdue])))
              (is (nil? (:next-flip-at r))))))))))

;; ── backfill ────────────────────────────────────────────────────────

(deftest backfill-recomputes-a-kind
  (fresh!)
  (let [clock (atom (Instant/parse "2026-07-10T12:00:00Z"))]
    (with-eng [reminder] {:now-fn (fn [] @clock)}
      (fn [eng]
        (let [a (:id (:row (inv/create! eng :mnt_reminder
                                        {:note "soon" :due_on "2026-07-11"}
                                        {:principal elena})))
              b (:id (:row (inv/create! eng :mnt_reminder
                                        {:note "later" :due_on "2026-07-20"}
                                        {:principal elena})))]
          (reset! clock (Instant/parse "2026-07-15T12:00:00Z"))
          (is (= 2 (maint/backfill! eng :mnt_reminder [:overdue])))
          (is (true? (get-in (reload eng :mnt_reminder a) [:data :overdue])))
          (is (false? (get-in (reload eng :mnt_reminder b) [:data :overdue])))
          (is (= 1 (:version (reload eng :mnt_reminder a)))
              "backfill bumps no versions"))))))

;; ── 3. the fact notice, end to end ──────────────────────────────────

(def ^:private chat-tools
  [{:name "send_message" :description "Send a chat message."
    :inputSchema {:type "object"
                  :properties {:chat_id {:type "string"}
                               :text {:type "string"}}}}])

(defn- with-notice-eng
  "The reminder over real Postgres, with a fake chat behind the
  `tgrambot` server row; every call it hears lands on `log`."
  [clock log f]
  (with-eng [caps/capability reminder]
    {:now-fn (fn [] @clock)
     :services {:mcp-servers
                {:client-fn
                 (fn [row]
                   (when (= "tgrambot" (get-in row [:data :name]))
                     (fn [method params]
                       (swap! log conj {:method method :params params})
                       (case method
                         "tools/list" {:tools chat-tools}
                         "tools/call" {:content [{:type "text" :text "sent"}]
                                       :isError false}))))}}}
    f))

(defn- notice-world!
  "A chat notifier, a member it reaches, and a rule that tells the
  reminder's owner when `overdue` becomes true. → {:member :rule}."
  [eng]
  (inv/create! eng :capability
               {:token "chat.send"
                :description "chat.send through a server row."
                :enforced_by "this engine's own power door"}
               {:principal elena})
  (let [server (:row (inv/create! eng :mcp_server
                                  {:name "tgrambot" :transport "http"
                                   :url "http://fake.invalid/mcp/"
                                   :powers [{:power "chat.send"
                                             :tools ["send_message"]
                                             :approval "person"}]}
                                  {:principal elena}))
        notifier (str (:id (:row (inv/create! eng :notifier
                                              {:name "the house chat"
                                               :server (str (:id server))
                                               :tool "tgrambot__send_message"
                                               :input_template {:chat_id "0"
                                                                :text "{kind} {action}: {summary}"}
                                               :on [{:kind "nothing_here"}]
                                               :audience "elena"
                                               :link_base "https://work.example.org/"}
                                              {:principal elena}))))
        m (:row (inv/create! eng :member {:display "Jack" :actor_type "human"}
                             {:principal elena}))
        member (str (:id m))]
    ;; how a member is reached is their own hand: the member sets it
    (inv/invoke! eng :member member :set_notify
                 {:notify {:notifier notifier :input {:chat_id "42"}}}
                 {:principal (t/principal {:id member :display "Jack"})
                  :if-match (inv/etag :member (:id m) (:version m))})
    {:member member
     :rule (str (:id (:row (inv/create! eng :notice_rule
                                        {:name "tell the owner it is overdue"
                                         :kind "mnt_reminder"
                                         :when {:fact "overdue" :becomes true}
                                         :address {:field "owner"}
                                         :notifier notifier}
                                        {:principal elena}))))}))

(defn- chat-sends [log]
  (filterv #(= "tools/call" (:method %)) @log))

(deftest a-clock-flip-tells-a-fact-rule-once
  (fresh!)
  (let [clock (atom (Instant/parse "2026-07-10T12:00:00Z"))
        log (atom [])]
    (with-notice-eng clock log
      (fn [eng]
        (let [{:keys [member rule]} (notice-world! eng)
              rid (:id (:row (inv/create! eng :mnt_reminder
                                          {:note "water" :due_on "2026-07-12"
                                           :owner member}
                                          {:principal elena})))]
          (is (= [] (chat-sends log)) "a fact rule hears no create")
          (testing "a sweep before the moment tells nobody"
            (maint/sweep-clocks! eng)
            (is (= [] (chat-sends log))))
          (testing "the flip under sweep-clocks! tells exactly once"
            (reset! clock (Instant/parse "2026-07-12T06:00:00Z"))
            (maint/sweep-clocks! eng)
            (is (true? (get-in (reload eng :mnt_reminder rid) [:data :overdue])))
            (is (= 1 (count (chat-sends log))))
            (is (= 1 (get-in (reload eng :notice_rule rule) [:data :sent]))))
          (testing "a later sweep, the fact still true, tells nobody"
            (reset! clock (Instant/parse "2026-07-14T06:00:00Z"))
            (maint/sweep-clocks! eng)
            (is (true? (get-in (reload eng :mnt_reminder rid) [:data :overdue])))
            (is (= 1 (count (chat-sends log))))))))))

;; ── 4. a clock fact that takes a door ───────────────────────────────

(def ^:private visit
  (r/resource
   {:kind :mnt_visit
    :plural "mnt_visits"
    :states [:booked :paused :reminded]
    :initial :booked
    :terminal #{:reminded}
    :summary "{data.patient} · {state}"
    :schema [:map
             [:patient [:string {:min 1 :max 60}]]
             [:starts_on :waymark/date]
             [:reminder_due {:optional true} [:maybe :boolean]]]
    :derived {:reminder_due {:over [:starts_on :now]
                             :expr '(<= (- (var :starts_on) (days 1)) (date-of (var :now)))}}
    :on-fact [{:fact :reminder_due :becomes true :take :remind}]
    :actions {:remind {:from #{:booked} :to :reminded
                       :safety {:idempotent false :reversible false
                                :confirm false
                                :one-way "A reminder sent is sent."}}
              :pause {:from #{:booked} :to :paused
                      :safety {:idempotent true :reversible true :confirm false}}
              :resume {:from #{:paused} :to :booked
                       :safety {:idempotent true :reversible true :confirm false}}}}))

(defn- refusals [eng id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (count (jdbc/execute! tx [(str "SELECT 1 FROM waymark10_observations"
                                     " WHERE class = 'fact-door-refused'"
                                     " AND resource_id::text = ?")
                                (str id)])))))

(deftest a-clock-fact-takes-its-door-once
  (fresh!)
  (let [clock (atom (Instant/parse "2026-07-10T12:00:00Z"))]
    (with-eng [visit] {:now-fn (fn [] @clock)}
      (fn [eng]
        (let [a (:id (:row (inv/create! eng :mnt_visit
                                        {:patient "Ada" :starts_on "2026-07-13"}
                                        {:principal elena})))
              b (:id (:row (inv/create! eng :mnt_visit
                                        {:patient "Bo" :starts_on "2026-07-13"}
                                        {:principal elena})))]
          (inv/invoke! eng :mnt_visit b :pause {} {:principal elena})
          (testing "a sweep before the day before takes nothing"
            (maint/sweep-clocks! eng)
            (is (= "booked" (name (:state (reload eng :mnt_visit a)))))
            (is (= 0 (refusals eng b))))
          (testing "the day before, the sweep takes remind as the house"
            (reset! clock (Instant/parse "2026-07-12T06:00:00Z"))
            (maint/sweep-clocks! eng)
            (is (true? (get-in (reload eng :mnt_visit a) [:data :reminder_due])))
            (is (= "reminded" (name (:state (reload eng :mnt_visit a)))))
            (is (= 2 (transition-count eng :mnt_visit a))))
          (testing "a refused remind is logged on its row"
            (is (= "paused" (name (:state (reload eng :mnt_visit b)))))
            (is (= 1 (refusals eng b))))
          (testing "a later sweep takes nothing again and retries no refusal"
            (reset! clock (Instant/parse "2026-07-14T06:00:00Z"))
            (maint/sweep-clocks! eng)
            (is (= 2 (transition-count eng :mnt_visit a)))
            (is (= 1 (refusals eng b)))))))))

(deftest a-backfill-flip-tells-nobody
  (fresh!)
  (let [clock (atom (Instant/parse "2026-07-10T12:00:00Z"))
        log (atom [])]
    (with-notice-eng clock log
      (fn [eng]
        (let [{:keys [member]} (notice-world! eng)
              rid (:id (:row (inv/create! eng :mnt_reminder
                                          {:note "soon" :due_on "2026-07-11"
                                           :owner member}
                                          {:principal elena})))]
          (reset! clock (Instant/parse "2026-07-15T12:00:00Z"))
          (is (= 1 (maint/backfill! eng :mnt_reminder [:overdue])))
          (is (true? (get-in (reload eng :mnt_reminder rid) [:data :overdue]))
              "the backfill flipped the fact")
          (is (= [] (chat-sends log)) "and told nobody: the law changed, not the row")
          (maint/sweep-clocks! eng)
          (is (= [] (chat-sends log))
              "nor does the next sweep, the fact already true"))))))
