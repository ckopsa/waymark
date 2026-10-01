(ns waymark10.scheduled-approval-test
  "Approval at scheduling (docs/spec-scheduled-actions.md R-4.3, child
  4a): a call that would be held for a person is approved when it is
  scheduled, and its run needs no second tap. Memory storage, the real
  engine and a clock a test moves by hand. The wire boundary's effects
  are called as the router calls them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(g/defguard the-person-says-when
  {:hold true
   :reads [:principal :held_call :within]
   :explain "Held for the person's tap. An agent finishes a chore only when its person said yes."}
  [row _inp ctx]
  (if (or (not= :agent (:type (:principal ctx)))
          (holds/approved-hold? ctx :approval_chore :finish (:id row)))
    (t/allow)
    (t/deny)))

(def ^:private chore
  "The row a scheduled call acts on. An agent's `finish` is a hold."
  (r/resource
   {:kind :approval_chore
    :plural "approval_chores"
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
              :guards [the-person-says-when]
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

(def ^:private t0 (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk
  "An agent that acts for Colton, and wears a grant."
  (assoc (t/principal {:id "approval-clerk" :type :agent}) :acts-for "colton"))

(defn- member! [eng id data]
  (inv/create! eng :member
               (merge {:display (str id) :actor_type "human"} data)
               {:principal scheduled/engine-actor :id id}))

(defn- world []
  (let [clock (atom t0)
        eng (engine/engine {:storage (memory/storage)
                            :resources [chore]
                            :now-fn (fn [] @clock)})]
    (member! eng "colton" {})
    (member! eng "approval-clerk" {:actor_type "agent" :acts_for "colton"})
    {:eng eng :clock clock}))

(defn- at! [clock s] (reset! clock (Instant/parse s)))

(defn- row-of [eng kind id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(defn- state-of [eng kind id] (some-> (row-of eng kind id) :state name))
(defn- why-of [eng id] (get-in (row-of eng :scheduled_action id) [:data :outcome_why]))
(defn- held-of [eng id] (get-in (row-of eng :scheduled_action id) [:data :held_call]))

(defn- held-calls [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (count (store/query-rows st tx :held_call {} {:limit 50}))))))

(defn- chore! [eng]
  (:id (:row (inv/create! eng :approval_chore {:title "Dishes"} {:principal person}))))

(defn- grant!
  "A grant over `finish` for the clerk, minted by its person and
  accepted → its id."
  [eng]
  (let [gid (str (:id (:row (inv/create! eng :grant
                                         {:audience (:id clerk)
                                          :scope [{:kind "approval_chore"
                                                   :actions ["finish"]}]}
                                         {:principal person}))))]
    (inv/invoke! eng :grant gid :accept {} {:principal clerk})
    gid))

(defn- worn
  "The clerk at the wire, under the grant it presents."
  [gid]
  {:principal clerk
   :grant {:id gid :action? (fn [_ _] true) :row? (fn [_ _] true)}})

(defn- effects
  "What the router does after a committed write on a scheduled action."
  [eng action out]
  (scheduled/after-write! eng (get (inv/resources eng) :scheduled_action) action out))

(defn- schedule!
  "The clerk schedules `finish` on a chore, for 12:05 unless told. → the
  row's id."
  ([eng c gid] (schedule! eng c gid "2026-10-01T12:05:00Z"))
  ([eng c gid run-at]
   (:id (:row (effects eng :create
                       (inv/create! eng :scheduled_action
                                    {:target {:kind "approval_chore" :action "finish"
                                              :id (str c)}
                                     :run_at run-at}
                                    (worn gid)))))))

(defn- decide!
  "A verdict on a held call, with the two effects the router owes it."
  [eng hid action body who]
  (let [rdef (get (inv/resources eng) :held_call)]
    (->> (inv/invoke! eng :held_call (str hid) action body
                      {:principal who :idempotency-key (str (random-uuid))})
         (held/after-allow! eng rdef action)
         (scheduled/after-write! eng rdef action))))

(defn- refused-by
  "The guard that refused `f`, or nil when it was not refused."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (some-> (:guard (ex-data e)) name keyword))))

(defn- log-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (vec (store/transitions st tx {:kind kind :resource-id id} {}))))))

;; ── the engine-door cases of R-8 item 4 ──────────────────────────────

(deftest the-caller-does-not-approve-its-own-schedule
  (let [{:keys [eng]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        hid (held-of eng id)
        call (:data (row-of eng :held_call hid))]
    (testing "a call that would be held is born proposed, and one held call asks about it"
      (is (= "proposed" (state-of eng :scheduled_action id)))
      (is (= 1 (held-calls eng)))
      (is (= "held" (state-of eng :held_call hid)))
      (is (= {:kind "scheduled_action" :action "arm" :id (str id)}
             (select-keys (:door call) [:kind :action :id])))
      (is (= "approval-clerk" (:caller call)))
      (is (= "colton" (:owner call))))
    (testing "the person reads the call and the time"
      (is (= (str "finish approval_chore " c " · 2026-10-01 12:05") (:shown call)))
      (is (str/includes? (str (:why call)) "Held for the person's tap")))
    (testing "the scheduler is the held call's caller, and the caller does not decide"
      (is (= :the-caller-does-not-decide
             (refused-by #(decide! eng hid :allow {} clerk))))
      (is (= "proposed" (state-of eng :scheduled_action id))))
    (testing "no hand at the wire arms it"
      (is (some? (try (inv/invoke! eng :scheduled_action id :arm {} (worn gid))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))))
      (is (= "proposed" (state-of eng :scheduled_action id))))))

(deftest allow-arms
  (let [{:keys [eng]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        hid (held-of eng id)]
    (decide! eng hid :allow {} person)
    (is (= "scheduled" (state-of eng :scheduled_action id)))
    (is (= "done" (state-of eng :held_call hid)))
    (is (= (str hid) (str (held-of eng id))))
    (is (= "open" (state-of eng :approval_chore c)) "the yes runs nothing before its time")))

(deftest refuse-cancels
  (let [{:keys [eng clock]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        hid (held-of eng id)]
    (decide! eng hid :refuse {:reason "Not this week."} person)
    (is (= "cancelled" (state-of eng :scheduled_action id)))
    (is (= "Not this week." (why-of eng id)))
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (is (= "open" (state-of eng :approval_chore c)))))

(deftest expiry-skips-with-the-sentence
  (let [sentence "Nobody approved this before its time."]
    (testing "the time passes first"
      (let [{:keys [eng clock]} (world)
            gid (grant! eng)
            c (chore! eng)
            id (schedule! eng c gid)]
        (at! clock "2026-10-01T12:05:20Z")
        (is (= {:ran 0 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
        (is (= "skipped" (state-of eng :scheduled_action id)))
        (is (= sentence (why-of eng id)))
        (is (= "open" (state-of eng :approval_chore c)))))
    (testing "the held call's day runs out first"
      (let [{:keys [eng clock]} (world)
            gid (grant! eng)
            c (chore! eng)
            id (schedule! eng c gid "2026-10-03T12:00:00Z")
            hid (held-of eng id)]
        (at! clock "2026-10-02T12:30:00Z")
        (is (= 1 (held/sweep-expired! eng)))
        (is (= "expired" (state-of eng :held_call hid)))
        (scheduled/sweep-due! eng)
        (is (= "skipped" (state-of eng :scheduled_action id)))
        (is (= sentence (why-of eng id)))))))

(deftest no-second-tap-at-run-at
  (let [{:keys [eng clock]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        hid (held-of eng id)]
    (decide! eng hid :allow {} person)
    (testing "a hand that names the row is no yes: the row is not running"
      (is (= :the-person-says-when
             (refused-by #(inv/invoke! eng :approval_chore c :finish {}
                                       (assoc (worn gid)
                                              :within {:kind :scheduled_action
                                                       :action :run
                                                       :id (str id)})))))
      (is (= "open" (state-of eng :approval_chore c))))
    (testing "the run walks the held door, and nobody is asked again"
      (at! clock "2026-10-01T12:05:20Z")
      (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
      (is (= "done" (state-of eng :scheduled_action id)) (pr-str (why-of eng id)))
      (is (= "done" (state-of eng :approval_chore c)))
      (is (= 1 (held-calls eng))))
    (testing "the target's history says whose hand, and that the clock moved it"
      (let [actor (:actor (last (log-of eng :approval_chore c)))]
        (is (= "approval-clerk" (:id actor)))
        (is (= (str id) (:scheduled actor)))))))

(deftest reschedule-asks-again
  (let [{:keys [eng clock]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)
        first-yes (held-of eng id)]
    (decide! eng first-yes :allow {} person)
    (effects eng :reschedule
             (inv/invoke! eng :scheduled_action id :reschedule
                          {:run_at "2026-10-01T12:10:00Z"} (worn gid)))
    (let [second-ask (held-of eng id)]
      (testing "the yes covered the old time, so the row waits on a new one"
        (is (= "proposed" (state-of eng :scheduled_action id)))
        (is (not= (str first-yes) (str second-ask)))
        (is (= "held" (state-of eng :held_call second-ask)))
        (is (str/ends-with? (str (get-in (row-of eng :held_call second-ask) [:data :shown]))
                            "2026-10-01 12:10")))
      (testing "the old time passes and nothing runs"
        (at! clock "2026-10-01T12:05:20Z")
        (scheduled/sweep-due! eng)
        (is (= "proposed" (state-of eng :scheduled_action id)))
        (is (= "open" (state-of eng :approval_chore c))))
      (testing "the new yes arms it, and it runs at the new time"
        (decide! eng second-ask :allow {} person)
        (is (= "scheduled" (state-of eng :scheduled_action id)))
        (at! clock "2026-10-01T12:10:20Z")
        (scheduled/sweep-due! eng)
        (is (= "done" (state-of eng :scheduled_action id)) (pr-str (why-of eng id)))
        (is (= "done" (state-of eng :approval_chore c)))))))

(deftest a-revoked-grant-still-skips-an-approved-row
  (let [{:keys [eng clock]} (world)
        gid (grant! eng)
        c (chore! eng)
        id (schedule! eng c gid)]
    (decide! eng (held-of eng id) :allow {} person)
    (inv/invoke! eng :grant gid :revoke {} {:principal person})
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (is (= "skipped" (state-of eng :scheduled_action id)))
    (is (= "The grant this was scheduled under no longer admits it." (why-of eng id)))
    (is (= "open" (state-of eng :approval_chore c)))))
