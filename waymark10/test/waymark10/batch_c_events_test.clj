(ns waymark10.batch-c-events-test
  "Batch C, derivation-class events: the observations outbox, the
  dispatcher's two-class fan-out, and the SSE frame shape. Covers the
  three live-update gaps by name — cross-row count recomputes, clock
  flips, bulk law restamps. Suite-local kinds; real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [next.jdbc.result-set :as rs]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.wire :as wire]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["c_evt_projects" "c_evt_chores" "c_evt_reminders" "definitions"
   "waymark10_transitions" "waymark10_idempotency"
   "waymark10_observations"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- project [open-where]
  (r/resource
   {:kind :c_evt_project
    :plural "c_evt_projects"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:open_chores {:optional true} [:maybe :int]]]
    :owns [{:kind :c_evt_chore :via :project_id}]
    :derived {:open_chores {:count {:owns :c_evt_chore
                                    :where {:state open-where}}}}
    :actions {:finish {:from #{:open} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

(def ^:private chore
  (r/resource
   {:kind :c_evt_chore
    :plural "c_evt_chores"
    :states [:todo :done]
    :initial :todo
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:project_id {:kind :c_evt_project} :waymark/ref]]
    :filterable {:project_id #{:eq}}
    :actions {:finish {:from #{:todo} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

(def ^:private reminder
  (r/resource
   {:kind :c_evt_reminder
    :plural "c_evt_reminders"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:due_date :waymark/date]
             [:overdue {:optional true} [:maybe :boolean]]]
    :derived {:overdue {:over [:due_date :now]
                        :expr '(< (var :due_date) (date-of (var :now)))}}
    :actions {:close {:from #{:open} :to :closed
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Closed is history."}}}}))

(defn- with-eng [resources opts f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine (merge {:storage st :resources resources} opts)))
      (finally (pg/close! st)))))

(defn- observations [eng kind]
  (store/with-tx (:storage eng)
    (fn [tx]
      (jdbc/execute! tx ["SELECT * FROM waymark10_observations
                          WHERE kind = ? ORDER BY id" (name kind)]
                     {:builder-fn rs/as-unqualified-lower-maps}))))

;; ── 1. the outbox: recompute + restamp rows exist iff commits do ────

(deftest observation-rows-record-the-three-gap-classes
  (fresh!)
  ;; boot 1: the cross-row recompute
  (with-eng [(project #{"todo"}) chore] {}
    (fn [eng]
      (let [p (:row (inv/create! eng :c_evt_project {:title "p"}
                                 {:principal elena}))]
        (inv/create! eng :c_evt_chore {:title "c" :project_id (:id p)}
                     {:principal elena})
        (testing "a chore's birth dirtied the project — recorded
                  (the project's own birth recompute precedes it)"
          (let [obs (observations eng :c_evt_project)]
            (is (= 2 (count obs)))
            (is (every? #(= "recompute" (:class %)) obs))
            (is (every? #(= (:id p) (:resource_id %)) obs)))))))
  ;; boot 2: the count.where widens — data_law + :immediate adoption →
  ;; the promote restamps (kind-wide) and the repair recomputes
  (with-eng [(project #{"todo" "done"}) chore] {}
    (fn [eng]
      (let [obs (observations eng :c_evt_project)
            classes (mapv :class obs)]
        (testing "the bulk restamp announced itself, kind-wide"
          (is (some #{"restamp"} classes))
          (is (some #(and (= "restamp" (:class %)) (nil? (:resource_id %)))
                    obs)))))))

;; ── 2. the clock flip ───────────────────────────────────────────────

(deftest clock-flip-emits-a-derivation
  (fresh!)
  (let [now (atom (Instant/parse "2026-07-10T12:00:00Z"))]
    (with-eng [reminder] {:now-fn (fn [] @now)}
      (fn [eng]
        (let [row (:row (inv/create! eng :c_evt_reminder
                                     {:title "r" :due_date "2026-07-11"}
                                     {:principal elena}))]
          (is (false? (get-in row [:data :overdue])))
          (reset! now (Instant/parse "2026-07-12T12:00:00Z"))
          (is (pos? (maintainer/sweep-clocks! eng)) "the due row swept")
          (let [obs (observations eng :c_evt_reminder)]
            (is (= ["flip"] (mapv :class obs)))
            (is (= (:id row) (:resource_id (first obs))))
            (is (= ["overdue"]
                   (wire/read-json
                    (.getValue ^org.postgresql.util.PGobject
                               (:changed (first obs))))))))))))

;; ── 3. the dispatcher fans out both classes; frames differ ──────────

(deftest dispatcher-delivers-derivations-to-opted-in-subscribers
  (fresh!)
  (with-eng [(project #{"todo"}) chore] {}
    (fn [eng]
      (let [d (events/dispatcher eng {:poll-ms 200})]
        (try
          (let [both (events/subscribe d {:kinds #{:c_evt_project}
                                          :classes #{:transition :derivation}})
                classic (events/subscribe d {:kinds #{:c_evt_project}})
                p (:row (inv/create! eng :c_evt_project {:title "p"}
                                     {:principal elena}))
                _ (inv/create! eng :c_evt_chore
                               {:title "c" :project_id (:id p)}
                               {:principal elena})
                evts (loop [acc [] tries 0]
                       (if (or (= 3 (count acc)) (> tries 40))
                         acc
                         (if-some [e (events/take-event both 500)]
                           (recur (conj acc e) (inc tries))
                           (recur acc (inc tries)))))
                derivations (filterv #(= :derivation
                                         (:waymark10.server.events/class %))
                                     evts)]
            (testing "the opted-in subscriber hears the recompute"
              (is (seq derivations))
              (let [e (first derivations)]
                (is (= :c_evt_project (:kind e)))
                (is (= (:id p) (:resource-id e)))
                (is (= "recompute" (:class e)))
                (testing "…framed as event: derivation, id-less"
                  (let [f (events/frame eng e)]
                    (is (str/starts-with? f "event: derivation\n"))
                    (is (not (str/includes? f "\nid:")))
                    (is (str/includes? f "\"class\":\"recompute\""))
                    (is (str/includes? f "\"changed\":[\"open_chores\"]"))))))
            (testing "a transition frame still carries its id"
              (let [t (first (remove #(= :derivation
                                         (:waymark10.server.events/class %))
                                     evts))]
                (is (some? t))
                (is (str/includes? (events/frame eng t) "\nid: "))))
            (testing "the default subscription never sees a derivation"
              (let [seen (loop [acc []]
                           (if-some [e (events/take-event classic 800)]
                             (recur (conj acc e))
                             acc))]
                (is (seq seen) "…it does hear the transitions")
                (is (not-any? #(= :derivation
                                  (:waymark10.server.events/class %))
                              seen)))))
          (finally (events/stop! d)))))))
