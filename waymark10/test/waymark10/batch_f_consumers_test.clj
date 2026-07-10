(ns waymark10.batch-f-consumers-test
  "Batch F, deliverable 5: consumers-as-API. A named consumer is a
  function of one transition with a durable cursor in
  waymark10_cursors: registration seeds at the newest transition
  (:from-origin? hears history), the cursor checkpoints per processed
  event and survives restarts, a throwing consumer parks (at-least-
  once, nothing skipped), and the live consumer rides the dispatcher
  exactly as the webhook deliverer does. Real Postgres
  (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private note
  (r/resource
   {:kind :f_note
    :plural "f_notes"
    :states [:open :filed]
    :initial :open
    :terminal #{:filed}
    :summary "{data.text} · {state}"
    :schema [:map [:text [:string {:min 1 :max 80}]]]
    :actions {:file {:from #{:open} :to :filed
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Filed is filed."}}}}))

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["f_notes" "members" "roles" "grants" "attachments"
                           "subscriptions" "jobs" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_cursors"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st :resources [note]})]
          (f))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- note! [text]
  (:row (inv/create! *eng* :f_note {:text text} {:principal elena})))

(defn- cursor-of [name*]
  (store/with-tx (:storage *eng*)
    #(store/cursor-get (:storage *eng*) % (str "consumer:" (name name*)))))

(defn- await-pred [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

;; ── 1. the durable cursor: seed, drain, resume ──────────────────────

(deftest cursor-seeds-checkpoints-and-resumes
  (note! "before registration")
  (let [seen (atom [])
        f #(swap! seen conj (:id %))]
    (testing "registration hears the world from NOW, not history"
      (is (= 0 (consumers/drain-consumer! *eng* :f-audit f)))
      (is (empty? @seen))
      (is (some? (cursor-of :f-audit)) "the seed checkpointed"))
    (let [n1 (note! "one")
          n2 (note! "two")]
      (inv/invoke! *eng* :f_note (:id n1) :file nil {:principal elena})
      (testing "one drain delivers everything past the cursor, in id order"
        (is (= 3 (consumers/drain-consumer! *eng* :f-audit f)))
        (is (= @seen (sort @seen)))
        (is (= (last @seen) (cursor-of :f-audit))
            "the cursor checkpointed per event"))
      (testing "a second drain replays nothing"
        (is (= 0 (consumers/drain-consumer! *eng* :f-audit f))))
      (testing "the cursor is DURABLE: a fresh drain (a restart) resumes"
        (inv/invoke! *eng* :f_note (:id n2) :file nil {:principal elena})
        (is (= 1 (consumers/drain-consumer! *eng* :f-audit f)))))))

(deftest from-origin-hears-the-whole-log
  (let [seen (atom [])]
    (consumers/drain-consumer! *eng* :f-historian
                               #(swap! seen conj (:id %))
                               {:from-origin? true})
    (is (pos? (count @seen)) "history replayed from the log's origin")
    (is (= 1 (first @seen)))))

;; ── 2. a throwing consumer parks — at-least-once, nothing skipped ───

(deftest throwing-consumer-parks-and-retries
  (let [n (note! "fragile")
        {file-t :transition} (inv/invoke! *eng* :f_note (:id n) :file nil
                                          {:principal elena})
        poison (:id file-t)
        broken? (atom true)
        seen (atom [])
        f (fn [t]
            (when (and @broken? (= poison (:id t)))
              (throw (ex-info "downstream is down" {})))
            (swap! seen conj (:id t)))]
    ;; seed BEFORE the writes above would defeat the scenario — seed
    ;; from origin so the poison event is in this consumer's stream
    (testing "the drain stops at the refusing event; the cursor parks"
      (consumers/drain-consumer! *eng* :f-fragile f {:from-origin? true})
      (is (= (dec poison) (cursor-of :f-fragile))
          "parked exactly before the refusing event")
      (is (not-any? #(= poison %) @seen)))
    (testing "the next drain retries the SAME event first — at-least-once"
      (reset! broken? false)
      (consumers/drain-consumer! *eng* :f-fragile f)
      (is (= poison (first (drop-while #(< % poison) @seen))))
      (is (= (cursor-of :f-fragile) (last @seen))))))

;; ── 3. the live consumer rides the dispatcher ───────────────────────

(deftest live-consumer-rides-the-dispatcher
  (let [d (events/dispatcher *eng* {:poll-ms 200})
        seen (atom [])
        c (consumers/register-consumer! *eng* :f-live
                                        #(swap! seen conj [(:kind %) (:action %)])
                                        {:dispatcher d :poll-ms 200})]
    (try
      (let [n (note! "live wire")]
        (inv/invoke! *eng* :f_note (:id n) :file nil {:principal elena})
        (is (await-pred #(some #{[:f_note :file]} @seen) 10000)
            "the dispatcher woke the consumer to the committed write")
        (is (some #{[:f_note :create]} @seen)))
      (finally
        (consumers/stop-consumer! c)
        (events/stop! d)))
    (testing "stopping keeps the cursor: re-registering resumes, replaying nothing"
      (let [before (count @seen)]
        (is (= 0 (consumers/drain-consumer! *eng* :f-live
                                            #(swap! seen conj [(:kind %) (:action %)]))))
        (is (= before (count @seen)))))))
