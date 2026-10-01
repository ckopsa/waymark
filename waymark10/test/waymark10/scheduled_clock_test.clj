(ns waymark10.scheduled-clock-test
  "The scheduled action's clock (docs/spec-scheduled-actions.md, child
  2): `sweep-due!`, the grace, the recovery of a row left `running`,
  and who the run runs as. Memory storage, the real engine and a clock
  a test moves by hand, so a pass is one call and never a wait."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.modules :as modules]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(r/defhandler clock-retitle [row inp _ctx]
  (assoc-in row [:data :title] (:title inp)))

(def ^:private chore
  "The row a scheduled call acts on."
  (r/resource
   {:kind :clock_chore
    :plural "clock_chores"
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
              :display {:label "Reopen" :order 3}}
     :retitle {:from #{:open} :to :open
               :input [:map
                       [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
               :handler clock-retitle
               :edit {:fence false
                      :unfenced-reason "The title is written whole, and a test has one writer."}
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Retitle" :order 2}}}}))

(def ^:private t0 (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private stranger (t/principal {:id "iris" :display "Iris"}))
(def ^:private clerk
  "An agent that acts for Colton, and wears a grant."
  (assoc (t/principal {:id "clock-clerk" :type :agent}) :acts-for "colton"))

(def ^:private nothing {:ran 0 :late 0 :recovered 0})

(defn- member! [eng id data]
  (inv/create! eng :member
               (merge {:display (str id) :actor_type "human"} data)
               {:principal scheduled/engine-actor :id id}))

(defn- world
  "An engine whose clock is the atom's instant, and the two members a
  run reads at its time."
  []
  (let [clock (atom t0)
        eng (engine/engine {:storage (memory/storage)
                            :resources [chore]
                            :now-fn (fn [] @clock)})]
    (member! eng "colton" {})
    (member! eng "clock-clerk" {:actor_type "agent" :acts_for "colton"})
    {:eng eng :clock clock}))

(defn- at! [clock s] (reset! clock (Instant/parse s)))

(defn- row-of [eng kind id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(defn- state-of [eng id] (some-> (row-of eng :scheduled_action id) :state name))
(defn- why-of [eng id] (get-in (row-of eng :scheduled_action id) [:data :outcome_why]))
(defn- outcome-of [eng id] (get-in (row-of eng :scheduled_action id) [:data :outcome]))
(defn- chore-state [eng c] (some-> (row-of eng :clock_chore c) :state name))

(defn- chore! [eng]
  (:id (:row (inv/create! eng :clock_chore {:title "Dishes"} {:principal person}))))

(defn- schedule!
  "Schedule `finish` on a chore for 12:05, five minutes ahead, with
  `extra` laid over the body. → the row's id."
  ([eng c] (schedule! eng c {} {:principal person}))
  ([eng c extra opts]
   (:id (:row (inv/create! eng :scheduled_action
                           (merge {:target {:kind "clock_chore" :action "finish" :id (str c)}
                                   :run_at "2026-10-01T12:05:00Z"}
                                  extra)
                           opts)))))

(defn- log-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (vec (store/transitions st tx {:kind kind :resource-id id} {}))))))

(defn- finishes [eng c]
  (count (filter #(= "finish" (name (:action %))) (log-of eng :clock_chore c))))

(defn- grant!
  "A grant for the clerk, minted by a person and accepted → its id."
  [eng body]
  (let [gid (str (:id (:row (inv/create! eng :grant
                                         (merge {:audience (:id clerk)} body)
                                         {:principal person}))))]
    (inv/invoke! eng :grant gid :accept {} {:principal clerk})
    gid))

(defn- worn
  "The clerk at the wire, under the grant it presents."
  [gid]
  {:principal clerk
   :grant {:id gid :action? (fn [_ _] true) :row? (fn [_ _] true)}})

(def ^:private finish-chores [{:kind "clock_chore" :actions ["finish"]}])

;; ── the loop and its hook (R-5.1, R-5.2) ────────────────────────────

(deftest a-due-row-runs-and-an-undue-one-does-not
  (let [{:keys [eng clock]} (world)
        c (chore! eng)
        other (chore! eng)
        id (schedule! eng c)
        undue (schedule! eng other {:run_at "2026-10-01T12:30:00Z"} {:principal person})]
    (testing "before its minute, nothing runs"
      (at! clock "2026-10-01T12:04:59Z")
      (is (= nothing (scheduled/sweep-due! eng)))
      (is (= "scheduled" (state-of eng id))))
    (testing "in the pass after its minute begins, it runs"
      (at! clock "2026-10-01T12:05:20Z")
      (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
      (is (= "done" (state-of eng id)))
      (is (= "done" (chore-state eng c)))
      (is (= {:kind "clock_chore" :action "finish" :id (str c) :state "done"}
             (outcome-of eng id))))
    (testing "the undue row waits for its own minute"
      (is (= "scheduled" (state-of eng undue)))
      (is (= "open" (chore-state eng other))))))

(deftest the-loop-is-cores-and-elected
  (testing "the hook carries the role one process per database holds"
    (let [hook (first (filter #(= :scheduled-actions (:hook %)) (modules/hooks nil)))]
      (is (= :scheduled-actions (:elected hook)))
      (is (nil? (:when hook)) "an engine with no seat still runs it")))
  (testing "the loop calls the sweep, and stops when told"
    (let [{:keys [eng clock]} (world)
          c (chore! eng)
          id (schedule! eng c)
          _ (at! clock "2026-10-01T12:05:20Z")
          loop' (scheduled/start-sweeper! eng {:interval-ms 20})]
      (try
        (loop [n 0]
          (when (and (< n 250) (not= "done" (state-of eng id)))
            (Thread/sleep 20)
            (recur (inc n))))
        (is (= "done" (state-of eng id)))
        (finally (scheduled/stop-sweeper! loop'))))))

(deftest two-sweeps-over-one-row-write-one-transition
  (let [{:keys [eng clock]} (world)
        c (chore! eng)
        held (chore! eng)
        id (schedule! eng c)]
    (at! clock "2026-10-01T12:05:20Z")
    (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
    (is (= nothing (scheduled/sweep-due! eng)))
    (is (= 1 (finishes eng c)))
    (is (= "done" (state-of eng id)))
    (testing "`start` is the claim: a row another runner holds is left to it"
      (let [theirs (schedule! eng held {:run_at "2026-10-01T12:10:00Z"} {:principal person})]
        (at! clock "2026-10-01T12:10:05Z")
        (is (some? (scheduled/start! eng theirs)))
        (is (= nothing (scheduled/sweep-due! eng)))
        (is (= "running" (state-of eng theirs)))
        (is (zero? (finishes eng held)))))))

;; ── rows due while the engine was down (R-5.3) ──────────────────────

(deftest a-row-found-late-runs-within-its-grace
  (let [{:keys [eng clock]} (world)
        c (chore! eng)
        id (schedule! eng c)]
    (testing "the last instant of the hour it was given"
      (at! clock "2026-10-01T13:05:00Z")
      (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
      (is (= "done" (state-of eng id)))
      (is (= "done" (chore-state eng c))))))

(deftest a-row-past-its-grace-is-skipped-with-the-sentence
  (testing "the default grace is one hour"
    (let [{:keys [eng clock]} (world)
          c (chore! eng)
          id (schedule! eng c)]
      (at! clock "2026-10-01T13:05:30Z")
      (is (= {:ran 0 :late 1 :recovered 0} (scheduled/sweep-due! eng)))
      (is (= "skipped" (state-of eng id)))
      (is (= "open" (chore-state eng c)))
      (is (= (str "Not run: the engine was down at 12:05 and came back at 13:05, "
                  "past this action's one-hour grace.")
             (why-of eng id)))))
  (testing "the grace is the scheduler's"
    (let [{:keys [eng clock]} (world)
          patient (chore! eng)
          hasty (chore! eng)
          kept (schedule! eng patient)
          dropped (schedule! eng hasty {:grace_seconds 600} {:principal person})]
      (at! clock "2026-10-01T12:15:30Z")
      (is (= {:ran 1 :late 1 :recovered 0} (scheduled/sweep-due! eng)))
      (is (= "done" (state-of eng kept)))
      (is (= "skipped" (state-of eng dropped)))
      (is (= "open" (chore-state eng hasty)))
      (is (str/includes? (why-of eng dropped) "10-minute grace")))))

;; ── rows a stopped engine left running (R-5.4) ──────────────────────

(deftest a-row-left-running-is-recovered-once
  (let [{:keys [eng clock]} (world)
        c (chore! eng)
        id (schedule! eng c)]
    (at! clock "2026-10-01T12:05:20Z")
    (is (some? (scheduled/start! eng id)) "an engine claimed it, and stopped")
    (testing "a row claimed a moment ago still has its runner"
      (at! clock "2026-10-01T12:09:00Z")
      (is (= nothing (scheduled/sweep-due! eng)))
      (is (= "running" (state-of eng id))))
    (testing "past five minutes nobody holds it, and the call runs under its key"
      (at! clock "2026-10-01T12:11:00Z")
      (is (= {:ran 0 :late 0 :recovered 1} (scheduled/sweep-due! eng)))
      (is (= "done" (state-of eng id)))
      (is (= "done" (chore-state eng c))))
    (testing "and only once"
      (is (= nothing (scheduled/sweep-due! eng)))
      (is (= 1 (finishes eng c))))))

(deftest a-call-that-already-landed-ends-done-from-its-stored-answer
  (let [{:keys [eng clock]} (world)
        c (chore! eng)
        id (schedule! eng c)]
    (at! clock "2026-10-01T12:05:20Z")
    (is (some? (scheduled/start! eng id)))
    ;; the stopped engine's call landed, and its ending was never written
    (inv/invoke! eng :clock_chore (str c) :finish {}
                 {:principal person
                  :idempotency-key (str "scheduled_action:" id)})
    (at! clock "2026-10-01T12:11:00Z")
    (is (= {:ran 0 :late 0 :recovered 1} (scheduled/sweep-due! eng)))
    (is (= "done" (state-of eng id))
        "the validity rule would have read the row the call itself moved")
    (is (= {:kind "clock_chore" :action "finish" :id (str c) :state "done"}
           (outcome-of eng id)))
    (is (= 1 (finishes eng c)))))

;; ── who it runs as (R-4.1, R-4.2) ───────────────────────────────────

(deftest the-member-is-read-at-the-run
  (let [{:keys [eng clock]} (world)
        c (chore! eng)
        id (schedule! eng c {} {:principal stranger})]
    (at! clock "2026-10-01T12:05:20Z")
    (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
    (is (= "skipped" (state-of eng id)))
    (is (str/includes? (why-of eng id) "no longer a member"))
    (is (= "open" (chore-state eng c)))))

(deftest a-revoked-grant-skips
  (let [{:keys [eng clock]} (world)
        gid (grant! eng {:scope finish-chores})
        [a b c] (repeatedly 3 #(chore! eng))
        live (schedule! eng a {} (worn gid))
        narrowed (schedule! eng b
                            {:target {:kind "clock_chore" :action "retitle" :id (str b)}
                             :input {:title "Pans"}}
                            (worn gid))
        revoked (schedule! eng c {:run_at "2026-10-01T12:10:00Z"} (worn gid))
        gone "The grant this was scheduled under no longer admits it."]
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (testing "a grant that still admits the call runs it"
      (is (= "done" (state-of eng live)))
      (is (= "done" (chore-state eng a))))
    (testing "a grant that does not admit this call skips it"
      (is (= "skipped" (state-of eng narrowed)))
      (is (= gone (why-of eng narrowed)))
      (is (= "Dishes" (get-in (row-of eng :clock_chore b) [:data :title]))))
    (testing "a grant revoked on the day between skips it"
      (inv/invoke! eng :grant gid :revoke {} {:principal person})
      (at! clock "2026-10-01T12:10:05Z")
      (scheduled/sweep-due! eng)
      (is (= "skipped" (state-of eng revoked)))
      (is (= gone (why-of eng revoked)))
      (is (= "open" (chore-state eng c))))))

(deftest a-parked-seat-skips
  (let [{:keys [eng clock]} (world)
        seat (str (:id (:row (inv/create! eng :seat
                                          {:name "chore-clerk"
                                           :charter "Finish the chores on time."
                                           :scope finish-chores
                                           :standing_ttl_seconds 604800
                                           :cadence_seconds 3600
                                           :budget_usd_per_week 5
                                           :sitting_budget_tokens 60000}
                                          {:principal person}))))
        gid (grant! eng {:seat seat})
        a (chore! eng)
        b (chore! eng)
        while-open (schedule! eng a {} (worn gid))
        while-parked (schedule! eng b {:run_at "2026-10-01T12:10:00Z"} (worn gid))]
    (testing "an open seat's grant confers the seat's scope at the run"
      (at! clock "2026-10-01T12:05:20Z")
      (scheduled/sweep-due! eng)
      (is (= "done" (state-of eng while-open)))
      (is (= "done" (chore-state eng a))))
    (testing "a parked seat does nothing, and that includes what it arranged"
      (inv/invoke! eng :seat seat :park {} {:principal person})
      (at! clock "2026-10-01T12:10:05Z")
      (scheduled/sweep-due! eng)
      (is (= "skipped" (state-of eng while-parked)))
      (is (str/includes? (why-of eng while-parked) "seat"))
      (is (= "open" (chore-state eng b))))))

(deftest the-run-counts-on-no-sitting
  (let [{:keys [eng clock]} (world)
        gid (grant! eng {:scope finish-chores})
        c (chore! eng)
        id (schedule! eng c {} (worn gid))
        st (:storage eng)]
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (is (= "done" (state-of eng id)))
    (testing "the scheduling counted under the grant that made it, and the run under none"
      (is (= ["scheduled_action"]
             (mapv #(name (:kind %))
                   (store/with-tx st
                     (fn [tx] (vec (store/transitions-under-grant st tx gid nil nil {}))))))))
    (testing "the target's history says whose hand, and that the clock moved it"
      (let [actor (:actor (last (log-of eng :clock_chore c)))]
        (is (= "clock-clerk" (:id actor)))
        (is (= (str id) (:scheduled actor)))
        (is (nil? (:grant actor)))))))
