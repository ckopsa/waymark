(ns waymark10.demo-clock-test
  "The demo clock (docs/spec-agent-demo-walks.md § 7): the `clock_shift`
  kind, the three walls that keep it off a working engine, the passes
  run after a shift, and the scheduled run handed to its scheduler's
  walk. Memory storage, the real engine and a real clock a test moves
  by hand, so a shift is one create and never a wait."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [waymark10.modules :as modules]
            [waymark10.resource :as r]
            [waymark10.server.clock-shift :as clock-shift]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.walks :as walks]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private chore
  "The row a scheduled call acts on."
  (r/resource
   {:kind :demo_chore
    :plural "demo_chores"
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

(def ^:private t0 (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))

(defn- world
  "An engine assembled with the demo clock, whose real clock is the
  atom's instant, and the member a run reads at its time. `:storage` is
  a storage an earlier engine wrote, for a restart."
  ([] (world {}))
  ([{:keys [engine-name storage oidc]}]
   (let [real (atom t0)
         fresh? (nil? storage)
         storage (or storage (memory/storage))
         built (engine/engine (clock-shift/with-clock
                               {:storage storage
                                :resources [chore]
                                :name (or engine-name "demo-clock")
                                :now-fn (fn [] @real)}))
         eng (clock-shift/install! (cond-> built oidc (assoc :oidc oidc)))]
     (when fresh?
       (inv/create! eng :member
                    {:display "colton" :actor_type "human"}
                    {:principal scheduled/engine-actor :id "colton"}))
     {:eng eng :real real :storage storage})))

(defn- now [eng] ((:now-fn eng)))

(defn- tick! [real seconds]
  (swap! real #(.plusSeconds ^Instant % (long seconds))))

(defn- row-of [eng kind id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(defn- state-of [eng kind id] (some-> (row-of eng kind id) :state name))

(defn- chore! [eng]
  (:id (:row (inv/create! eng :demo_chore {:title "Dishes"} {:principal person}))))

(defn- schedule!
  "Schedule `finish` on a chore, for 12:05 unless `run-at` says. → the
  row's id."
  ([eng c] (schedule! eng c "2026-10-01T12:05:00Z"))
  ([eng c run-at]
   (:id (:row (inv/create! eng :scheduled_action
                           {:target {:kind "demo_chore" :action "finish" :id (str c)}
                            :run_at run-at}
                           {:principal person})))))

(defn- shift! [eng body]
  (:row (inv/create! eng :clock_shift body {:principal person})))

(defn- refusal
  "What a refused call said, or nil when it went through."
  [f]
  (try (f) nil
       (catch Exception e
         (str (inv/problem-reason e) " " (pr-str (ex-data e))))))

(defn- walk! [eng title]
  (:row (inv/create! eng :walk {:followed "colton" :title title}
                     {:principal person})))

(defn- frames-of [eng walk-id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) :walk_frame)]
    (store/with-tx st
      (fn [tx]
        (mapv #(walk/keywordize-keys (:data (inv/decode-row rdef %)))
              (store/query-rows st tx :walk_frame {:walk (str walk-id)}
                                {:limit 100}))))))

(defn- frame! [eng w]
  (walks/record-frame! eng (:id w) nil {:type "ui" :body {:dialog "open"}}))

;; ── the three walls ─────────────────────────────────────────────────

(deftest a-working-engine-does-not-serve-the-kind
  (testing "no module the inventory enrols carries the kind"
    (is (not-any? #(= :clock_shift (:kind %)) modules/enrollment)))
  (testing "an engine built without the demo clock has no such kind"
    (let [eng (engine/engine {:storage (memory/storage) :resources [chore]})]
      (is (not (contains? (inv/resources eng) :clock_shift)))
      (is (nil? (clock-shift/offset-seconds eng)))
      (is (some? (refusal #(shift! eng {:by "PT1H"}))))
      (is (= eng (clock-shift/install! eng))
          "and installing the clock on it changes nothing")))
  (testing "the kind without the clock moves nothing: there is no offset to set"
    (let [real (atom t0)
          eng (engine/engine {:storage (memory/storage)
                              :resources [chore clock-shift/clock-shift]
                              :name "demo-clock"
                              :now-fn (fn [] @real)})]
      (is (str/includes? (str (refusal #(shift! eng {:by "PT1H"})))
                         "built without the demo clock"))
      (is (= t0 (now eng))))))

(deftest a-shift-is-refused-on-an-engine-not-named-demo
  (let [{:keys [eng]} (world {:engine-name "waymark"})
        said (refusal #(shift! eng {:by "PT1H"}))]
    (is (some? said))
    (is (str/includes? (str said) "begins with `demo-`"))
    (is (= 0 (clock-shift/offset-seconds eng)))
    (is (= t0 (now eng)))))

(deftest a-shift-is-refused-on-an-engine-with-an-idp
  (let [{:keys [eng]} (world {:oidc {:issuer "https://idp.example"}})
        said (refusal #(shift! eng {:by "PT1H"}))]
    (is (some? said))
    (is (str/includes? (str said) "identity provider"))
    (is (= 0 (clock-shift/offset-seconds eng)))
    (is (= t0 (now eng)))))

;; ── the shift ───────────────────────────────────────────────────────

(deftest a-shift-moves-only-forward-and-at-most-fourteen-days
  (let [{:keys [eng]} (world)
        day 86400]
    (testing "a shift names exactly one move"
      (is (some? (refusal #(shift! eng {}))))
      (is (some? (refusal #(shift! eng {:to "2026-10-02T12:00:00Z" :by "PT1H"}))))
      (is (some? (refusal #(shift! eng {:to "tomorrow"}))))
      (is (some? (refusal #(shift! eng {:by "soon"})))))
    (testing "back, and nowhere, are refused"
      (is (some? (refusal #(shift! eng {:by "-PT1H"}))))
      (is (some? (refusal #(shift! eng {:by "PT0S"}))))
      (is (some? (refusal #(shift! eng {:to "2026-10-01T11:00:00Z"})))))
    (testing "more than fourteen days is refused"
      (is (some? (refusal #(shift! eng {:by "P15D"})))))
    (is (= 0 (clock-shift/offset-seconds eng)))
    (is (= t0 (now eng)))
    (testing "a forward shift moves the clock, and the row carries the total"
      (is (= (* 10 day) (get-in (shift! eng {:by "P10D"}) [:data :offset_seconds])))
      (is (= (* 10 day) (clock-shift/offset-seconds eng)))
      (is (= (.plusSeconds ^Instant t0 (* 10 day)) (now eng))))
    (testing "the fourteen days are the total, not one shift's"
      (is (some? (refusal #(shift! eng {:by "P5D"}))))
      (is (= (* 14 day)
             (get-in (shift! eng {:to (str (.plusSeconds ^Instant t0 (* 14 day)))})
                     [:data :offset_seconds])))
      (is (= (.plusSeconds ^Instant t0 (* 14 day)) (now eng)))
      (is (some? (refusal #(shift! eng {:by "PT1S"}))))
      (is (= (* 14 day) (clock-shift/offset-seconds eng))))))

(deftest a-due-scheduled-action-runs-when-the-clock-passes-it
  (let [{:keys [eng]} (world)
        c (chore! eng)
        other (chore! eng)
        id (schedule! eng c)
        undue (schedule! eng other "2026-10-01T12:30:00Z")]
    (is (= "scheduled" (state-of eng :scheduled_action id)))
    (shift! eng {:to "2026-10-01T12:05:00Z"})
    (is (= (Instant/parse "2026-10-01T12:05:00Z") (now eng)))
    (testing "the shift's own pass ran it: nobody called the sweep"
      (is (= "done" (state-of eng :scheduled_action id)))
      (is (= "done" (state-of eng :demo_chore c))))
    (testing "a row the clock has not reached waits"
      (is (= "scheduled" (state-of eng :scheduled_action undue)))
      (is (= "open" (state-of eng :demo_chore other))))))

(deftest the-run-is-recorded-in-the-schedulers-walk
  (let [{:keys [eng]} (world)
        c (chore! eng)
        w (walk! eng "Colton schedules a chore")
        id (schedule! eng c)]
    (is (empty? (frames-of eng (:id w))))
    (shift! eng {:to "2026-10-01T12:05:00Z"})
    (is (= "done" (state-of eng :scheduled_action id)))
    (let [moves (filterv #(= "transition" (some-> (:type %) name))
                         (frames-of eng (:id w)))
          body (:body (first moves))]
      (is (= 1 (count moves)) "the run, and nothing the engine's own hand wrote")
      (is (= "demo_chore" (some-> (:kind body) name)))
      (is (= "finish" (some-> (:action body) name)))
      (is (= "colton" (str (get-in body [:actor :id])))))))

(deftest a-frames-t-is-recording-time
  (let [{:keys [eng real]} (world)
        w (walk! eng "Before the shift")
        _ (tick! real 1)
        before (frame! eng w)
        _ (shift! eng {:by "P1D"})
        _ (tick! real 1)
        after (frame! eng w)]
    (is (= 1000 (get-in before [:data :t])))
    (is (= 2000 (get-in after [:data :t]))
        "a day's shift adds nothing to a frame's t")
    (testing "a walk begun on a shifted engine counts from its own real start"
      (let [w2 (walk! eng "After the shift")
            _ (tick! real 3)
            f (frame! eng w2)]
        (is (= (str (.plusSeconds ^Instant t0 2)) (str (get-in w2 [:data :started_at]))))
        (is (= 3000 (get-in f [:data :t])))))))

(deftest a-restarted-engine-keeps-its-shift
  (let [{:keys [eng storage]} (world)]
    (shift! eng {:by "PT2H"})
    (let [{again :eng} (world {:storage storage})]
      (is (= 7200 (clock-shift/offset-seconds again)))
      (is (= (.plusSeconds ^Instant t0 7200) (now again)))
      (testing "and the next shift adds to it"
        (is (= 10800 (get-in (shift! again {:by "PT1H"}) [:data :offset_seconds])))
        (is (= (.plusSeconds ^Instant t0 10800) (now again)))))))
