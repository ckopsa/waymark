(ns waymark10.makes-test
  "A door makes the next row of the work (ticket 442ca14b): quote/accept
  declares :makes, and in its own transaction a job is born through the
  job's create door, naming the quote. A job create guard that refuses
  rolls the accept back with that guard's refusal. Suite-local kinds;
  real Postgres; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.checks-assembly :as ca]
            [waymark10.guards :as g]
            [waymark10.law-words :as lw]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["mk_quotes" "mk_jobs" "definitions"
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

(defn- jobs-of [eng quote-id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/query-rows (:storage eng) tx :mk_job
                        {:quote_id quote-id} {:limit 100}))))

;; ── fixtures: an accepted quote opens its job ───────────────────────

(def ^:private crew-free
  (g/guard {:name :crew-free
            :judges [:price]
            :check (fn [_row inp _ctx]
                     (if (> (:price inp) 1000) (t/deny) (t/allow)))
            :explain "No crew is free for a job that size."}))

(def ^:private quote-kind
  (r/resource
   {:kind :mk_quote
    :plural "mk_quotes"
    :states [:draft :accepted]
    :initial :draft
    :terminal #{:accepted}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:price :int]]
    :actions {:accept
              {:from #{:draft} :to :accepted
               :makes [{:kind :mk_job
                        :input {:quote_id '(id) :price '(data :price)}}]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An accepted quote stays accepted."}}}}))

(def ^:private job-kind
  (r/resource
   {:kind :mk_job
    :plural "mk_jobs"
    :states [:booked :done]
    :initial :booked
    :terminal #{:done}
    :summary "{data.price} · {state}"
    :schema [:map
             [:quote_id {:kind :mk_quote :filter #{:eq}} :waymark/ref]
             [:price :int]]
    :create-guards [crew-free]
    :actions {:finish
              {:from #{:booked} :to :done
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A finished job stays finished."}}}}))

;; ── 1. the accept opens one job naming the quote ────────────────────

(deftest accept-opens-its-job
  (fresh!)
  (with-eng [quote-kind job-kind]
    (fn [eng]
      (let [q (:row (inv/create! eng :mk_quote {:title "patio" :price 800}
                                 {:principal elena}))
            res (inv/invoke! eng :mk_quote (:id q) :accept nil
                             {:principal elena})
            jobs (jobs-of eng (:id q))]
        (testing "the quote moved, and one job names it"
          (is (= :accepted (:state (:row res))))
          (is (= 1 (count jobs)))
          (is (= 800 (get-in (first jobs) [:data :price])))
          (is (= :booked (:state (first jobs)))))
        (testing "the made row rides out as an inner write"
          (is (= [:mk_job] (mapv :kind (:inner-writes res)))))))))

;; ── 2. a refused job refuses the accept ─────────────────────────────

(deftest a-refused-job-rolls-the-accept-back
  (fresh!)
  (with-eng [quote-kind job-kind]
    (fn [eng]
      (let [q (:row (inv/create! eng :mk_quote {:title "estate" :price 5000}
                                 {:principal elena}))
            p (try (inv/invoke! eng :mk_quote (:id q) :accept nil
                                {:principal elena})
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (or (fac/problem-data e) (throw e))))]
        (testing "the job's guard refuses, in its own sentence"
          (is (= :crew-free (:guard p)))
          (is (re-find #"No crew is free" (pr-str p))))
        (testing "nothing moved and nothing was made"
          (is (= "draft" (name (:state (reload eng :mk_quote (:id q))))))
          (is (empty? (jobs-of eng (:id q)))))))))

;; ── 3. the law page and the audit ───────────────────────────────────

(deftest the-law-says-it-and-the-audit-reads-it
  (is (= ["Accepting a mk quote opens its mk job."]
         (lw/makes-lines quote-kind :accept)))
  (is (= [] (ca/check-makes {:kinds {:mk_quote quote-kind :mk_job job-kind}}))
      "the ref back fills quote_id; price is in the input")
  (let [bare (assoc-in quote-kind [:actions :accept :makes]
                       [{:kind :mk_job :input {}}])]
    (is (= 1 (count (filter #(re-find #"\[makes-unguarded\].*price" %)
                            (ca/check-makes {:kinds {:mk_quote bare :mk_job job-kind}})))))))
