(ns waymark10.follow-up-test
  "The handler's follow-up door (ticket da882851): a handler queues one
  call through ctx :follow-up and the engine makes it AFTER the
  handler's transaction commits — an ordinary invoke under the same
  principal, with its own transition and a key derived from the outer
  transition's id. A refusing follow-up leaves the outer write
  standing; a dry run queues nothing; a replayed outer write runs
  nothing twice; a follow-up queues no follow-up. Suite-local kinds;
  real Postgres; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["fu_tasks" "fu_logs" "definitions"
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

(defn- with-eng [resources f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine {:storage st :resources resources}))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

(defn- moves
  "The row's transitions through one action, oldest first."
  [eng kind id action]
  (->> (store/with-tx (:storage eng)
         (fn [tx] (store/transitions (:storage eng) tx
                                     {:kind kind :resource-id id} {})))
       (filter #(= (name action) (name (:action %))))
       (sort-by :id)
       vec))

;; ── fixtures: a task's poke stamps a log, after the poke commits ────

(defhandler poke-task [row inp ctx]
  ((:follow-up ctx) {:kind :fu_log :id (:log_id inp) :action :stamp
                     :input {:text "poked"}})
  row)

(defhandler relay-task [row inp ctx]
  ;; the follow-up's own handler queues one more (echo-log): the
  ;; second level, which the engine drops
  ((:follow-up ctx) {:kind :fu_log :id (:log_id inp) :action :echo
                     :input {:task_id (:id row)}})
  row)

(defhandler stamp-log [row _inp _ctx]
  (update-in row [:data :stamps] (fnil inc 0)))

(defhandler echo-log [row inp ctx]
  ((:follow-up ctx) {:kind :fu_task :id (:task_id inp) :action :mark})
  row)

(def ^:private once {:idempotent false :reversible false :confirm false})

(def ^:private task
  (r/resource
   {:kind :fu_task
    :plural "fu_tasks"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 40}]]]
    :actions {:poke
              {:from #{:open} :to :open
               :input [:map [:log_id [:string {:min 1 :max 80}]]]
               :safety once
               :handler poke-task}
              :relay
              {:from #{:open} :to :open
               :input [:map [:log_id [:string {:min 1 :max 80}]]]
               :safety once
               :handler relay-task}
              :mark
              {:from #{:open} :to :open
               :safety once}
              :close
              {:from #{:open} :to :closed
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Closed tasks stay closed."}}}}))

(def ^:private log
  (r/resource
   {:kind :fu_log
    :plural "fu_logs"
    :states [:open :sealed]
    :initial :open
    :terminal #{:sealed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:stamps {:optional true} [:maybe :int]]]
    :actions {:stamp
              {:from #{:open} :to :open
               :input [:map [:text [:string {:min 1 :max 40}]]]
               :safety once
               :handler stamp-log}
              :echo
              {:from #{:open} :to :open
               :input [:map [:task_id [:string {:min 1 :max 80}]]]
               :safety once
               :handler echo-log}
              :seal
              {:from #{:open} :to :sealed
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Sealed logs stay sealed."}}}}))

(defn- seed!
  "One open task and one open log. → [task-id log-id]"
  [eng]
  [(:id (:row (inv/create! eng :fu_task {:title "t"} {:principal elena})))
   (:id (:row (inv/create! eng :fu_log {:title "l"} {:principal elena})))])

;; ── 1. the follow-up lands after the outer write, as the same hand ──

(deftest a-follow-up-lands-after-the-outer-write
  (fresh!)
  (with-eng [task log]
    (fn [eng]
      (let [[tk lg] (seed! eng)
            res (inv/invoke! eng :fu_task tk :poke {:log_id lg}
                             {:principal elena
                              :idempotency-key "poke-1"
                              :correlation-id "poke-cid-1"})
            [outer] (moves eng :fu_task tk :poke)
            [follow :as stamps] (moves eng :fu_log lg :stamp)]
        (testing "the follow-up ran once, as its own transition"
          (is (= 1 (count stamps)))
          (is (= 1 (get-in (reload eng :fu_log lg) [:data :stamps]))))
        (testing "it was written after the outer transition"
          (is (< (:id outer) (:id follow))))
        (testing "under the same principal, in the same story"
          (is (= "elena" (get-in follow [:actor :id])))
          (is (= (:actor outer) (:actor follow)))
          (is (= "poke-cid-1" (:correlation-id follow))))
        (testing "under a key derived from the outer transition's id"
          (is (= (str "follow-up:" (:id outer) ":0")
                 (:idempotency-key follow))))
        (testing "the outer answer says what was followed"
          (is (= [{:kind :fu_log :id lg :action :stamp}]
                 (mapv #(select-keys % [:kind :id :action])
                       (:followed res))))
          (is (nil? (:follow-ups res))))))))

;; ── 2. a refusing follow-up leaves the outer write standing ─────────

(deftest a-refusing-follow-up-leaves-the-outer-write
  (fresh!)
  (with-eng [task log]
    (fn [eng]
      (let [[tk lg] (seed! eng)
            _ (inv/invoke! eng :fu_log lg :seal nil {:principal elena})
            before (:version (reload eng :fu_task tk))
            res (inv/invoke! eng :fu_task tk :poke {:log_id lg}
                             {:principal elena :idempotency-key "poke-2"})]
        (is (= 1 (count (moves eng :fu_task tk :poke)))
            "the poke committed")
        (is (= (inc before) (:version (reload eng :fu_task tk))))
        (is (empty? (moves eng :fu_log lg :stamp))
            "a sealed log takes no stamp")
        (is (some? (:refused (first (:followed res))))
            "the answer names the refusal")))))

;; ── 3. a dry run queues nothing ─────────────────────────────────────

(deftest a-dry-run-queues-nothing
  (fresh!)
  (with-eng [task log]
    (fn [eng]
      (let [[tk lg] (seed! eng)
            res (inv/invoke! eng :fu_task tk :poke {:log_id lg}
                             {:principal elena :dry-run true})]
        (is (:valid? res))
        (is (empty? (moves eng :fu_task tk :poke)))
        (is (empty? (moves eng :fu_log lg :stamp))
            "the rehearsal stamped nothing")))))

;; ── 4. a replayed outer write does not follow up twice ──────────────

(deftest a-replayed-outer-write-follows-up-once
  (fresh!)
  (with-eng [task log]
    (fn [eng]
      (let [[tk lg] (seed! eng)
            opts {:principal elena :idempotency-key "poke-3"}
            _ (inv/invoke! eng :fu_task tk :poke {:log_id lg} opts)
            replay (inv/invoke! eng :fu_task tk :poke {:log_id lg} opts)]
        (is (= :idempotency (:replayed? replay)))
        (is (= 1 (count (moves eng :fu_task tk :poke))))
        (is (= 1 (count (moves eng :fu_log lg :stamp)))
            "one stamp for one poke, however often it is delivered")))))

;; ── 5. a follow-up queues no follow-up ──────────────────────────────

(deftest follow-ups-stop-at-one-level
  (fresh!)
  (with-eng [task log]
    (fn [eng]
      (let [[tk lg] (seed! eng)]
        (inv/invoke! eng :fu_task tk :relay {:log_id lg}
                     {:principal elena :idempotency-key "relay-1"})
        (is (= 1 (count (moves eng :fu_log lg :echo)))
            "the first level ran")
        (is (empty? (moves eng :fu_task tk :mark))
            "what the follow-up queued was dropped")
        (testing "the same door, called at the wire, is level one"
          (inv/invoke! eng :fu_log lg :echo {:task_id tk}
                       {:principal elena :idempotency-key "echo-1"})
          (is (= 1 (count (moves eng :fu_task tk :mark)))))))))
