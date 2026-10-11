(ns waymark10.scheduled-actions-test
  "The scheduled_action kind (docs/spec-scheduled-actions.md, children
  1a, 1b and 1c): its stamps, its walls, its limits, its zone rules, its
  scheduling check and its run. Memory storage, the real engine and a
  fixed clock. The clock that starts a run is child 2's, so a test
  claims a row and runs it by hand."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.confirm :as confirm]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private not-before-monday
  "A wall its scheduler expects to lift by the time."
  (g/guard {:name :not-before-monday
            :explain "Chores are scrubbed from Monday on."
            :check (fn [_row _inp _ctx] (t/deny))}))

(r/defhandler file-under [row inp _ctx]
  (assoc-in row [:data :title] (:title inp)))

(def ^:private chore
  "The row a scheduled call acts on."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}
     ;; a door with an input, a door behind a guard and a confirm door
     :file {:from #{:open} :to :done
            :input [:map
                    [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
            :handler file-under
            :safety {:idempotent true :reversible true :confirm false}
            :display {:label "File" :order 3}}
     :scrub {:from #{:open} :to :done
             :guards [not-before-monday]
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Scrub" :order 4}}
     :scrap {:from #{:open} :to :done
             :safety {:idempotent true :reversible true :confirm true
                      :consequence "The chore is closed without being done."}
             :display {:label "Scrap" :order 5}}
     ;; an edit: the title moves and the state does not
     :retitle {:from #{:open} :to :open
               :input [:map
                       [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
               :handler file-under
               :edit {:fence false
                      :unfenced-reason "The title is written whole, and a test has one writer."}
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Retitle" :order 6}}}}))

(def ^:private now (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private other (t/principal {:id "iris" :display "Iris"}))
(def ^:private planner
  "A delegate: an agent that acts for Colton."
  (assoc (t/principal {:id "planner" :type :agent}) :acts-for "colton"))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [chore]
                  :now-fn (constantly now)}))

(defn- row-of [eng id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) :scheduled_action)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx :scheduled_action (str id) {})
                 (inv/decode-row rdef))))))

(defn- state-of [eng id] (some-> (row-of eng id) :state name))

(defn- chore! [eng]
  (:id (:row (inv/create! eng :chore {:title "Dishes"} {:principal person}))))

(defn- schedule!
  "Schedule `finish` on a chore for 08:30 the next morning in Denver,
  with `extra` laid over the body."
  ([eng c extra] (schedule! eng c extra {:principal person}))
  ([eng c extra opts]
   (:row (inv/create! eng :scheduled_action
                      (merge {:target {:kind "chore" :action "finish" :id (str c)}
                              :run_at "2026-10-02T08:30:00-06:00"}
                             extra)
                      opts))))

(defn- move! [eng id action body principal]
  (inv/invoke! eng :scheduled_action (str id) action body {:principal principal}))

(defn- refusal
  "The refusal's sentence, or nil when the call went through."
  [f]
  (try (f) nil
       (catch Exception e (str (or (inv/problem-reason e) "refused")))))

(defn- member! [eng id data]
  (inv/create! eng :member
               (merge {:display (str id) :actor_type "human"} data)
               {:principal scheduled/engine-actor :id id}))

(deftest the-engine-stamps-who-scheduled-it
  (let [eng (fresh-engine)
        c (chore! eng)
        row (schedule! eng c {:run_at "2026-10-02T08:30:45-06:00"})]
    (is (= "scheduled" (name (:state row))))
    (is (= "colton" (get-in row [:data :scheduler])))
    (is (= {:id "colton" :type "human"} (get-in row [:data :acts_as])))
    (is (nil? (get-in row [:data :grant])) "a person's own hand wore no grant")
    (is (= (Instant/parse "2026-10-02T14:30:00Z") (get-in row [:data :run_at]))
        "run_at is an instant from then on, in whole minutes")
    (is (= "-06:00" (get-in row [:data :zone]))
        "with no zone anywhere, it is shown in the offset it was written in")
    (testing "the defaults the scheduler did not name"
      (is (= "state" (get-in row [:data :validity])))
      (is (= "all" (get-in row [:data :tell])))
      (is (= 3600 (get-in row [:data :grace_seconds]))))
    (testing "a delegate is stamped with the person it acts for and its grant"
      (let [row (schedule! eng c {}
                           {:principal planner
                            :grant {:id "grant-planner"
                                    :action? (fn [_ _] true)
                                    :row? (fn [_ _] true)}})]
        (is (= "planner" (get-in row [:data :scheduler])))
        (is (= {:id "planner" :type "agent" :acts_for "colton"}
               (get-in row [:data :acts_as])))
        (is (= "grant-planner" (get-in row [:data :grant])))))))

(deftest nobody-names-acts-as-or-scheduler
  (let [eng (fresh-engine)
        c (chore! eng)]
    (is (some? (refusal #(schedule! eng c {:scheduler "iris"}))))
    (is (some? (refusal #(schedule! eng c {:acts_as {:id "iris" :type "human"}}))))
    (is (some? (refusal #(schedule! eng c {:grant "grant-of-somebody"}))))
    (is (nil? (refusal #(schedule! eng c {})))
        "the same body without them is stored")))

(deftest the-scheduler-alone-cancels-and-reschedules
  (let [eng (fresh-engine)
        c (chore! eng)
        mine (:id (schedule! eng c {}))
        later {:run_at "2026-10-03T09:00:00-06:00"}]
    (testing "somebody else"
      (is (some? (refusal #(move! eng mine :cancel {} other))))
      (is (some? (refusal #(move! eng mine :reschedule later other))))
      (is (= "scheduled" (state-of eng mine))))
    (testing "the scheduler moves it, and the row has not run"
      (move! eng mine :reschedule later person)
      (is (= "scheduled" (state-of eng mine)))
      (is (= (Instant/parse "2026-10-03T15:00:00Z")
             (get-in (row-of eng mine) [:data :run_at]))))
    (testing "the scheduler cancels it, and a cancelled row takes no door"
      (move! eng mine :cancel {} person)
      (is (= "cancelled" (state-of eng mine)))
      (is (some? (refusal #(move! eng mine :reschedule later person)))))
    (testing "the person a delegate acts for stops what the delegate scheduled"
      (let [theirs (:id (schedule! eng c {}
                                   {:principal planner
                                    :grant {:id "grant-planner"
                                            :action? (fn [_ _] true)
                                            :row? (fn [_ _] true)}}))]
        (is (some? (refusal #(move! eng theirs :cancel {} other))))
        (move! eng theirs :cancel {} person)
        (is (= "cancelled" (state-of eng theirs)))))
    (testing "the own surface is the scheduler's, and confers the create and the two doors"
      (is (= {:by [[:scheduler]] :all false :actions #{"create" "cancel" "reschedule"}}
             (:own-surface scheduled/scheduled-action))))))

(deftest only-the-engine-starts-and-ends-it
  (let [eng (fresh-engine)
        c (chore! eng)
        id (:id (schedule! eng c {}))]
    (testing "the scheduler's own hand does not start or skip it"
      (doseq [action [:start :skip]]
        (is (some? (refusal #(move! eng id action {} person))) (name action)))
      (is (= "scheduled" (state-of eng id))))
    (move! eng id :start {} scheduled/engine-actor)
    (is (= "running" (state-of eng id)))
    (is (= now (get-in (row-of eng id) [:data :ran_at])))
    (testing "nor does it write an ending, or cancel a row that is running"
      (doseq [action [:land :fail :skip :cancel]]
        (is (some? (refusal #(move! eng id action {} person))) (name action)))
      (is (= "running" (state-of eng id))))
    (move! eng id :land
           {:outcome {:kind "chore" :action "finish" :id (str c) :state "done"}
            :outcome_why "Finished chore at 08:30, as scheduled."}
           scheduled/engine-actor)
    (let [row (row-of eng id)]
      (is (= "done" (name (:state row))))
      (is (= "done" (get-in row [:data :outcome :state])))
      (is (= "Finished chore at 08:30, as scheduled."
             (get-in row [:data :outcome_why]))))))

(deftest the-limits-refuse-with-a-sentence
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "one minute ahead at least"
      (is (str/includes? (refusal #(schedule! eng c {:run_at "2026-10-01T12:00:30Z"}))
                         "less than one minute"))
      (is (nil? (refusal #(schedule! eng c {:run_at "2026-10-01T12:01:00Z"})))))
    (testing "366 days ahead at most"
      (is (str/includes? (refusal #(schedule! eng c {:run_at "2027-10-03T12:00:00Z"}))
                         "more than 366 days"))
      (is (nil? (refusal #(schedule! eng c {:run_at "2027-10-02T12:00:00Z"})))))
    (testing "a reschedule keeps them"
      (let [id (:id (schedule! eng c {}))]
        (is (str/includes?
             (refusal #(move! eng id :reschedule {:run_at "2026-10-01T12:00:30Z"} person))
             "less than one minute"))))
    (testing "one scheduler holds only so many rows that have not run"
      (with-redefs [scheduled/open-limit 3]
        (let [eng (fresh-engine)
              c (chore! eng)
              held (mapv (fn [_] (:id (schedule! eng c {}))) (range 3))]
          (is (str/includes? (refusal #(schedule! eng c {})) "at most 3"))
          (is (nil? (refusal #(schedule! eng c {} {:principal other})))
              "the limit is per scheduler")
          (move! eng (first held) :cancel {} person)
          (is (nil? (refusal #(schedule! eng c {})))
              "a cancelled row gives its room back"))))
    (testing "an input over the ceiling is refused, never stored cut"
      (is (str/includes?
           (refusal #(schedule! eng c {:input {:note (apply str (repeat 17000 "a"))}}))
           "at most 16384")))))

(deftest the-zone-rules
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "a zone that is no zone"
      (is (str/includes?
           (refusal #(schedule! eng c {:run_at "2026-10-02T08:30" :zone "Mars/Olympus"}))
           "not an IANA time zone")))
    (testing "a local time with no zone, from a scheduler whose member row names none"
      (is (str/includes? (refusal #(schedule! eng c {:run_at "2026-10-02T08:30"}))
                         "never guesses UTC")))
    (testing "a time that is neither form"
      (is (str/includes? (refusal #(schedule! eng c {:run_at "tomorrow morning"}))
                         "RFC 3339")))
    (testing "the spring gap does not exist"
      (is (str/includes?
           (refusal #(schedule! eng c {:run_at "2027-03-14T02:30" :zone "America/Denver"}))
           "does not exist")))
    (testing "the autumn hour occurs twice, and takes the earlier"
      (is (= (Instant/parse "2026-11-01T07:30:00Z")
             (get-in (schedule! eng c {:run_at "2026-11-01T01:30" :zone "America/Denver"})
                     [:data :run_at]))))
    (testing "a reschedule that names no zone reads the time in the row's own"
      (let [id (:id (schedule! eng c {:run_at "2026-10-02T08:30" :zone "America/Denver"}))]
        (move! eng id :reschedule {:run_at "2026-10-05T07:00"} person)
        (is (= (Instant/parse "2026-10-05T13:00:00Z")
               (get-in (row-of eng id) [:data :run_at])))
        (is (= "America/Denver" (get-in (row-of eng id) [:data :zone])))))))

(deftest a-local-time-is-read-in-the-members-zone
  (let [eng (fresh-engine)
        c (chore! eng)
        local {:run_at "2026-10-02T08:30"}]
    (member! eng "colton" {:zone "America/Denver"})
    (member! eng "iris" {})
    (testing "the scheduler's own member row, and a delegate's person's"
      (doseq [opts [{:principal person}
                    {:principal planner
                     :grant {:id "grant-planner"
                             :action? (fn [_ _] true)
                             :row? (fn [_ _] true)}}]]
        (let [row (schedule! eng c local opts)]
          (is (= (Instant/parse "2026-10-02T14:30:00Z") (get-in row [:data :run_at])))
          (is (= "America/Denver" (get-in row [:data :zone]))))))
    (testing "the zone the body names wins over the member's"
      (is (= (Instant/parse "2026-10-02T12:30:00Z")
             (get-in (schedule! eng c (assoc local :zone "America/New_York"))
                     [:data :run_at]))))
    (testing "an instant is shown in the member's zone"
      (is (= "America/Denver"
             (get-in (schedule! eng c {:run_at "2026-10-02T14:30:00Z"}) [:data :zone]))))
    (testing "somebody whose member row names no zone is still refused"
      (is (str/includes? (refusal #(schedule! eng c local {:principal other}))
                         "never guesses UTC")))))

(deftest what-a-later-child-builds-is-refused-by-name
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "a power target is checked against the scheduler's powers (child 4b)"
      (is (str/includes?
           (refusal #(schedule! eng c {:target {:tool "telegram__send_message"}
                                       :input {:text "Good morning."}}))
           "among your powers")))
    (testing "conditions and their rule are named together (child 5)"
      (is (some? (refusal #(schedule! eng c {:validity "conditions"}))))
      (is (some? (refusal #(schedule! eng c {:conditions {:state "open"}}))))
      (is (= "conditions"
             (get-in (schedule! eng c {:validity "conditions"
                                       :conditions {:state "open"}})
                     [:data :validity]))))
    (testing "a target names a kind and an action"
      (is (some? (refusal #(schedule! eng c {:target {:kind "chore"}})))))
    (testing "strict is stored, and a create names no row"
      (let [row (schedule! eng c {:validity "strict"
                                  :target {:kind "chore" :action "create"}
                                  :input {:title "Laundry"}})]
        (is (= "strict" (get-in row [:data :validity])))
        (is (= {:title "Laundry"} (get-in row [:data :input])))))))

;; ── the scheduling check (child 1b) ─────────────────────────────────

(defn- on
  "A body that names `action` on chore `c`."
  [c action & [extra]]
  (merge {:target {:kind "chore" :action action :id (str c)}} extra))

(defn- chore-move! [eng c action]
  (inv/invoke! eng :chore (str c) action {} {:principal person}))

(defn- chore-of [eng c]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx :chore (str c) {})))))

(deftest the-scheduling-check-refuses-what-the-door-refuses
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "a door the row is not in the state for"
      (is (str/includes? (refusal #(schedule! eng c (on c "reopen")))
                         "Available in state")))
    (testing "an input the door's schema refuses"
      (is (str/includes? (refusal #(schedule! eng c (on c "file" {:input {:title ""}})))
                         "failed validation"))
      (is (nil? (refusal #(schedule! eng c (on c "file" {:input {:title "Dishes, done"}}))))))
    (testing "a guard that refuses, in the guard's own sentence"
      (is (str/includes? (refusal #(schedule! eng c (on c "scrub")))
                         "from Monday on")))
    (testing "a row that is not there, and a door that is not"
      (is (str/includes?
           (refusal #(schedule! eng c {:target {:kind "chore" :action "finish"
                                                :id "no-such-chore"}}))
           "No chore"))
      (is (str/includes? (refusal #(schedule! eng c (on c "polish")))
                         "has no action"))
      (is (str/includes?
           (refusal #(schedule! eng c {:target {:kind "errand" :action "finish"
                                                :id (str c)}}))
           "no door"))
      (is (str/includes?
           (refusal #(schedule! eng c {:target {:kind "chore" :action "finish"}}))
           "names the row's `id`")))
    (testing "a create is rehearsed in full"
      (is (str/includes?
           (refusal #(schedule! eng c {:target {:kind "chore" :action "create"}
                                       :input {}}))
           "failed validation")))
    (testing "a door the scheduler's grant does not admit is not there"
      (is (str/includes?
           (refusal #(schedule! eng c {}
                                {:principal planner
                                 :grant {:id "grant-planner"
                                         :action? (fn [_ a] (not= :finish a))
                                         :row? (fn [_ _] true)}}))
           "no door")))))

(deftest an-expected-state-is-passed-over-and-no-guard-is-judged
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "`reopen` of a chore that is still open, expected done by then"
      (let [row (schedule! eng c (on c "reopen" {:expect_state "done"}))]
        (is (= "done" (get-in row [:data :snapshot :state])))
        (is (nil? (get-in row [:data :snapshot :version]))
            "a row that has not got there has no version to pin")))
    (testing "`strict` is refused with it"
      (is (str/includes?
           (refusal #(schedule! eng c (on c "reopen" {:expect_state "done"
                                                      :validity "strict"})))
           "no version to pin")))
    (testing "the action is declared from that state"
      (is (str/includes?
           (refusal #(schedule! eng c (on c "finish" {:expect_state "done"})))
           "not declared from")))
    (chore-move! eng c :finish)
    (testing "the input's schema is still judged"
      (is (str/includes?
           (refusal #(schedule! eng c (on c "file" {:expect_state "open"
                                                    :input {:title ""}})))
           "does not fit")))
    (testing "and no guard is"
      (is (nil? (refusal #(schedule! eng c (on c "scrub" {:expect_state "open"}))))))
    (testing "a create has no row to expect a state of"
      (is (str/includes?
           (refusal #(schedule! eng c {:target {:kind "chore" :action "create"}
                                       :input {:title "Laundry"}
                                       :expect_state "open"}))
           "no row")))))

(deftest an-expected-refusal-is-passed-over-by-name
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "the guard the scheduler names, and the names stay on the row"
      (let [row (schedule! eng c (on c "scrub" {:expect_refusals ["not-before-monday"]}))]
        (is (= ["not-before-monday"] (get-in row [:data :expect_refusals])))
        (is (= "open" (get-in row [:data :snapshot :state])))))
    (testing "a guard it did not name still refuses"
      (is (str/includes?
           (refusal #(schedule! eng c (on c "scrub" {:expect_refusals ["not-on-a-holiday"]})))
           "from Monday on")))
    (testing "a name passes over a guard and no other refusal"
      (is (str/includes?
           (refusal #(schedule! eng c (on c "reopen" {:expect_refusals ["not-before-monday"]})))
           "Available in state")))))

(deftest the-snapshot-is-written-at-scheduling
  (let [eng (fresh-engine)
        c (chore! eng)
        snap #(get-in % [:data :snapshot])]
    (testing "the row's version, state and law revision"
      (let [s (snap (schedule! eng c {}))]
        (is (= {:version 1 :state "open"} (select-keys s [:version :state])))
        (is (string? (:law_revision s)))))
    (testing "it is the row as it is now, not as it was born"
      (chore-move! eng c :finish)
      (chore-move! eng c :reopen)
      (let [s (snap (schedule! eng c {}))]
        (is (< 1 (:version s)))
        (is (= (:version (chore-of eng c)) (:version s)))
        (is (= "open" (:state s)))))
    (testing "it is kept on the stored row"
      (let [id (:id (schedule! eng c {}))]
        (is (= "open" (get-in (row-of eng id) [:data :snapshot :state])))))
    (testing "a create has no row, and keeps the law a birth is stamped by"
      (let [s (snap (schedule! eng c {:target {:kind "chore" :action "create"}
                                      :input {:title "Laundry"}}))]
        (is (= #{:law_revision} (set (keys s))))))))

(deftest a-confirm-door-takes-its-sentence-at-scheduling
  (let [eng (fresh-engine)
        c (chore! eng)
        sentence "The chore is closed without being done."]
    (testing "without the sentence it is refused, and the refusal states it"
      (is (str/includes? (refusal #(schedule! eng c (on c "scrap"))) sentence)))
    (testing "a sentence that is not the door's is refused"
      (is (str/includes?
           (refusal #(schedule! eng c (on c "scrap" {:acknowledge "Yes, do it."})))
           sentence)))
    (testing "the sentence, exactly as written, is stored with the call"
      (let [row (schedule! eng c (on c "scrap" {:acknowledge sentence}))]
        (is (= sentence (get-in row [:data :acknowledge])))))))

;; ── the run (child 1c) ──────────────────────────────────────────────

(defn- run!
  "Claim the row and run it, as the clock will: the ending's name."
  [eng id]
  (when (scheduled/start! eng id)
    (some-> (scheduled/run! eng id) name)))

(defn- why-of [eng id] (get-in (row-of eng id) [:data :outcome_why]))

(defn- chore-state [eng c] (some-> (chore-of eng c) :state name))

(defn- retitle! [eng c]
  (inv/invoke! eng :chore (str c) :retitle {:title "Pans"} {:principal person}))

(deftest strict-skips-on-any-version-move
  (let [eng (fresh-engine)
        c (chore! eng)]
    (member! eng "colton" {})
    (testing "a row nobody wrote since runs, and the row keeps what it wrote"
      (let [id (:id (schedule! eng c {:validity "strict"}))]
        (is (= "done" (run! eng id)))
        (is (= "done" (chore-state eng c)))
        (is (= {:kind "chore" :action "finish" :id (str c) :state "done"}
               (get-in (row-of eng id) [:data :outcome])))
        (is (= now (get-in (row-of eng id) [:data :ran_at])))))
    (testing "an edit leaves the state alone and still moves the version"
      (chore-move! eng c :reopen)
      (let [id (:id (schedule! eng c {:validity "strict"}))
            was (:version (chore-of eng c))]
        (retitle! eng c)
        (is (= "skipped" (run! eng id)))
        (is (= "open" (chore-state eng c)))
        (is (str/includes? (why-of eng id)
                           (str "(version " was ", now " (inc was) ")")))))))

(deftest state-runs-past-an-edit-and-skips-on-a-state-move
  (let [eng (fresh-engine)]
    (member! eng "colton" {})
    (testing "an edit that did not move the state does not matter"
      (let [c (chore! eng)
            id (:id (schedule! eng c {}))]
        (retitle! eng c)
        (is (= "done" (run! eng id)))
        (is (= "done" (chore-state eng c)))))
    (testing "a row that left the state and came back runs"
      (let [c (chore! eng)
            id (:id (schedule! eng c {}))]
        (chore-move! eng c :finish)
        (chore-move! eng c :reopen)
        (is (= "done" (run! eng id)))
        (is (= "done" (chore-state eng c)))))
    (testing "a row that left the state is skipped"
      (let [c (chore! eng)
            id (:id (schedule! eng c (on c "file" {:input {:title "Filed"}})))]
        (chore-move! eng c :finish)
        (is (= "skipped" (run! eng id)))
        (is (str/includes? (why-of eng id) "`done`"))
        (is (nil? (get-in (row-of eng id) [:data :outcome])))))
    (testing "an expected state is the state the run reads"
      (let [c (chore! eng)
            id (:id (schedule! eng c (on c "reopen" {:expect_state "done"})))]
        (chore-move! eng c :finish)
        (is (= "done" (run! eng id)))
        (is (= "open" (chore-state eng c)))))
    (testing "a guard that still refuses at the time skips, in its own sentence"
      (let [c (chore! eng)
            id (:id (schedule! eng c (on c "scrub" {:expect_refusals ["not-before-monday"]})))]
        (is (= "skipped" (run! eng id)))
        (is (str/includes? (why-of eng id) "from Monday on"))
        (is (= "open" (chore-state eng c)))))
    (testing "a scheduler who is no member at the time is skipped"
      (let [c (chore! eng)
            id (:id (schedule! eng c {} {:principal other}))]
        (is (= "skipped" (run! eng id)))
        (is (= "open" (chore-state eng c)))))))

(deftest a-create-runs-under-both-rules
  (let [eng (fresh-engine)
        c (chore! eng)
        create (fn [title validity]
                 (:id (schedule! eng c {:validity validity
                                        :target {:kind "chore" :action "create"}
                                        :input {:title title}})))
        born #(get-in (row-of eng %) [:data :outcome])]
    (member! eng "colton" {})
    (doseq [validity ["state" "strict"]]
      (testing validity
        (let [id (create "Laundry" validity)]
          (is (= "done" (run! eng id)))
          (is (= "create" (:action (born id))))
          (is (= "open" (:state (born id))))
          (is (= "open" (chore-state eng (:id (born id))))))))
    (testing "strict pins the law a birth is stamped by"
      (let [id (create "Windows" "strict")]
        (with-redefs [inv/create-law-revision (constantly "another-law")]
          (is (= "skipped" (run! eng id))))
        (is (nil? (born id)))
        (is (str/includes? (why-of eng id) "now another-law"))))))

(deftest the-confirm-sentence-is-read-again
  (let [eng (fresh-engine)
        sentence "The chore is closed without being done."
        scrap (fn [c] (:id (schedule! eng c (on c "scrap" {:acknowledge sentence}))))]
    (member! eng "colton" {})
    (testing "the sentence the scheduler agreed to still stands"
      (let [c (chore! eng)]
        (is (= "done" (run! eng (scrap c))))
        (is (= "done" (chore-state eng c)))))
    (testing "a sentence that changed skips the row"
      (let [c (chore! eng)
            id (scrap c)]
        (with-redefs [confirm/consequence-of (constantly "The chore is deleted.")]
          (is (= "skipped" (run! eng id))))
        (is (= "open" (chore-state eng c)))
        (is (= "The consequence changed since this was acknowledged."
               (why-of eng id)))))))

(deftest the-run-lands-once-under-a-repeated-call
  (let [eng (fresh-engine)
        c (chore! eng)
        id (:id (schedule! eng c {}))
        finishes (fn []
                   (let [st (:storage eng)]
                     (store/with-tx st
                       (fn [tx]
                         (count (filter #(= "finish" (name (:action %)))
                                        (store/transitions
                                         st tx {:kind :chore :resource-id c} {})))))))]
    (member! eng "colton" {})
    (testing "a row nobody claimed does not run"
      (is (nil? (scheduled/run! eng id)))
      (is (= "open" (chore-state eng c))))
    (testing "one caller claims it"
      (is (= "running" (some-> (scheduled/start! eng id) :state name)))
      (is (nil? (scheduled/start! eng id))))
    (testing "the call lands once"
      (is (= :done (scheduled/run! eng id)))
      (is (nil? (scheduled/run! eng id)))
      (is (nil? (scheduled/start! eng id)))
      (is (= 1 (finishes)))
      (is (= "done" (state-of eng id))))
    (testing "it went under the key the row derives"
      (is (= :idempotency
             (:replayed? (inv/invoke! eng :chore (str c) :finish {}
                                      {:principal person
                                       :idempotency-key (str "scheduled_action:" id)})))))))
