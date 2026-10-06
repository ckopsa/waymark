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
            [waymark10.server.routes.seats :as seat-routes]
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

;; ── 1b. two writers that commit out of id order ─────────────────────

(deftest out-of-order-commits-are-both-delivered
  (note! "a transition to copy")
  (let [st (:storage *eng*)
        seen (atom [])
        f #(swap! seen conj (:id %))
        _ (consumers/drain-consumer! *eng* :f-ordered f)
        record (-> (store/with-tx st
                     #(store/transitions st % {} {:newest-first true :limit 1}))
                   first
                   (dissoc :id :at :idempotency-key :correlation-id))
        appended (promise)
        release (promise)
        ;; the slow writer takes the LOWER id and holds its transaction open
        slow (future
               (store/with-tx st
                 (fn [tx]
                   (deliver appended (:id (store/append-transition! st tx record)))
                   (deref release 20000 nil))))]
    (try
      (let [low (deref appended 10000 nil)
            ;; the fast writer takes the HIGHER id and commits first
            high (:id (store/with-tx st
                        #(store/append-transition! st % record)))]
        (is (some? low))
        (is (< low high))
        (testing "a drain in the window does not pass the id still in flight"
          (is (= 0 (consumers/drain-consumer! *eng* :f-ordered f)))
          (is (empty? @seen))
          (is (< (cursor-of :f-ordered) low)))
        (deliver release true)
        @slow
        (testing "once the lower id commits, both arrive, in id order"
          (is (= 2 (consumers/drain-consumer! *eng* :f-ordered f)))
          (is (= [low high] @seen))
          (is (= high (cursor-of :f-ordered)))))
      (finally (deliver release true)))))

;; ── 1c. what the order lock costs writers behind a long transaction ─
;; A measurement, printed: timings are the runner's, so the one bound
;; asserted is a count. Readers that take no lock (:settled false) are
;; the log as it was before the order lock.

(defn- append-latencies!
  "For hold-ms, `drains` threads read the log (:settled as given) and
  `writers` threads append, each append in a transaction of its own;
  with long-writer? one more transaction appends first and stays open
  for the whole of it. → the appends' latencies in ms, sorted."
  [st record {:keys [hold-ms drains writers settled long-writer?]}]
  (let [appended (promise)
        hold (future
               (if long-writer?
                 (store/with-tx st
                   (fn [tx]
                     (deliver appended (:id (store/append-transition! st tx record)))
                     (Thread/sleep (long hold-ms))))
                 (do (deliver appended nil)
                     (Thread/sleep (long hold-ms)))))
        since (deref appended 10000 nil)
        drainers (mapv (fn [_]
                         (future
                           (while (not (future-done? hold))
                             (store/with-tx st
                               (fn [tx] (store/transitions st tx {:since since}
                                                           {:limit 200 :settled settled})))
                             (Thread/sleep 20))))
                       (range drains))
        appenders (mapv (fn [_]
                          (future
                            (loop [ms []]
                              (if (future-done? hold)
                                ms
                                (let [t0 (System/nanoTime)]
                                  (store/with-tx st
                                    #(store/append-transition! st % record))
                                  (recur (conj ms (/ (- (System/nanoTime) t0) 1e6))))))))
                        (range writers))]
    @hold
    (run! deref drainers)
    (vec (sort (mapcat deref appenders)))))

(deftest order-lock-cost-to-writers
  (note! "a transition to copy")
  (let [st (:storage *eng*)
        record (-> (store/with-tx st
                     #(store/transitions st % {} {:newest-first true :limit 1}))
                   first
                   (dissoc :id :at :idempotency-key :correlation-id))
        writers 3
        run (fn [settled long-writer?]
              (let [ms (append-latencies! st record {:hold-ms 1500 :drains 2
                                                     :writers writers
                                                     :settled settled
                                                     :long-writer? long-writer?})
                    at #(nth ms (min (dec (count ms)) (long (* % (count ms)))))]
                (println (format (str "log-order-lock settled=%s long-writer=%s"
                                      " appends=%d p50=%.1fms p99=%.1fms max=%.1fms")
                                 settled long-writer? (count ms)
                                 (at 0.5) (at 0.99) (peek ms)))
                ms))]
    (run false false)
    (run true false)
    (run false true)
    (testing "behind a long writer a settled drain yields: appends still land"
      ;; held for the whole hold, each writer would land exactly one
      (is (< writers (count (run true true)))))))

;; ── 1d. the other readers that walk the log by id ───────────────────

(defn- in-the-window
  "Two appends that commit out of id order. Calls (f low high
  commit-low!) while the LOWER id is still in flight and the higher
  one has committed."
  [f]
  (note! "a transition to copy")
  (let [st (:storage *eng*)
        record (-> (store/with-tx st
                     #(store/transitions st % {} {:newest-first true :limit 1}))
                   first
                   (dissoc :id :at :idempotency-key :correlation-id))
        appended (promise)
        release (promise)
        slow (future
               (store/with-tx st
                 (fn [tx]
                   (deliver appended (:id (store/append-transition! st tx record)))
                   (deref release 20000 nil))))]
    (try
      (let [low (deref appended 10000 nil)
            high (:id (store/with-tx st
                        #(store/append-transition! st % record)))]
        (is (some? low))
        (is (< low high))
        (f low high (fn [] (deliver release true) @slow)))
      (finally (deliver release true)))))

(deftest a-seed-does-not-pass-the-id-in-flight
  (in-the-window
   (fn [_low high commit-low!]
     (let [seen (atom [])
           f #(swap! seen conj (:id %))]
       (testing "a first drain in the window writes no cursor"
         (is (= 0 (consumers/drain-consumer! *eng* :f-seeded f)))
         (is (nil? (cursor-of :f-seeded))))
       (commit-low!)
       (testing "once the lower id commits, the seed is the newest id"
         (is (= 0 (consumers/drain-consumer! *eng* :f-seeded f)))
         (is (= high (cursor-of :f-seeded)))
         (is (empty? @seen)))))))

(deftest a-replay-waits-for-the-id-in-flight
  (in-the-window
   (fn [low high commit-low!]
     (let [rows (future (mapv :id (#'events/backlog (:storage *eng*) (dec low))))]
       (testing "a replay in the window does not answer without the lower id"
         (is (= ::waiting (deref rows 200 ::waiting))))
       (commit-low!)
       (testing "once the lower id commits, both are replayed, in id order"
         (is (= [low high] (deref rows 10000 nil))))))))

(deftest the-inbox-page-does-not-pass-the-id-in-flight
  (in-the-window
   (fn [low high commit-low!]
     (let [page #(mapv :id (#'seat-routes/log-after *eng* (dec low)))]
       (testing "a page in the window answers no row"
         (is (empty? (page))))
       (commit-low!)
       (testing "once the lower id commits, the page holds both, in id order"
         (is (= [low high] (page))))))))

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
