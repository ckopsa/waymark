(ns waymark10.scheduled-actions-test
  "The scheduled_action kind (docs/spec-scheduled-actions.md, child
  1a): its stamps, its walls, its limits and its zone rules. Memory
  storage, the real engine and a fixed clock. The scheduling check is
  child 1b's and the run is child 1c's; neither is exercised here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

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
              :display {:label "Reopen" :order 2}}}}))

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
      (let [theirs (:id (schedule! eng c {} {:principal planner}))]
        (is (some? (refusal #(move! eng theirs :cancel {} other))))
        (move! eng theirs :cancel {} person)
        (is (= "cancelled" (state-of eng theirs)))))
    (testing "the own surface is the scheduler's, and confers the two doors"
      (is (= {:by [[:scheduler]] :all false :actions #{"cancel" "reschedule"}}
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
      (doseq [who [person planner]]
        (let [row (schedule! eng c local {:principal who})]
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
    (testing "a power target is child 4's"
      (is (str/includes?
           (refusal #(schedule! eng c {:target {:tool "telegram__send_message"}
                                       :input {:text "Good morning."}}))
           "child 4")))
    (testing "conditions are child 5's, under any rule"
      (is (str/includes?
           (refusal #(schedule! eng c {:validity "conditions"
                                       :conditions {:state "open"}}))
           "child 5"))
      (is (str/includes? (refusal #(schedule! eng c {:conditions {:state "open"}}))
                         "child 5")))
    (testing "a target names a kind and an action"
      (is (some? (refusal #(schedule! eng c {:target {:kind "chore"}})))))
    (testing "strict is stored, and a create names no row"
      (let [row (schedule! eng c {:validity "strict"
                                  :target {:kind "chore" :action "create"}
                                  :input {:title "Laundry"}})]
        (is (= "strict" (get-in row [:data :validity])))
        (is (= {:title "Laundry"} (get-in row [:data :input])))))))
