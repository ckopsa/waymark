(ns waymark10.expire-floor-test
  "The engine's floor on `expire` (docs/spec-spend.md §4, spend step 6),
  judged on the handler's arithmetic with a ctx that serves the rows in
  memory: the transcript beyond the reports, each request once, priced
  per line at its model; a late report's reversal and remainder across
  sessions; and bill_basis. No database."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.money :as money]
            [waymark10.server.seats :as seats]))

(def ^:private economy
  {:id "7d1e0f6a-0000-4000-8000-000000000001"
   :data {:name "economy" :price_input_per_mtok 1M :price_output_per_mtok 5M
          :price_cache_read_per_mtok 0.1M :price_cache_write_per_mtok 1.25M}})

(def ^:private premium
  {:id "7d1e0f6a-0000-4000-8000-000000000002"
   :data {:name "premium" :price_input_per_mtok 10M :price_output_per_mtok 50M
          :price_cache_read_per_mtok 1M :price_cache_write_per_mtok 12.5M}})

(def ^:private models [economy premium])

(def ^:private now (java.time.Instant/parse "2026-10-24T00:00:00Z"))

(defn- ctx-of
  "A handler ctx over these transcript lines and this sitting's seat
  postings, held in an atom so a test can grow the ledger."
  [lines ledger]
  {:now now
   :read (fn [kind id]
           (when (= kind :model) (first (filter #(= (str id) (:id %)) models))))
   :find (fn [kind where _opts]
           (case kind
             :transcript_entry (mapv (fn [d] {:data d}) lines)
             :model (filterv #(= (:name where) (get-in % [:data :name])) models)
             :posting (mapv (fn [p] {:data (merge {:kind "sitting"} p)}) @ledger)
             nil))})

(defn- line [n rid model input]
  {:n n :request_id rid :model model
   :usage {:input_tokens input :output_tokens 0
           :cache_read_tokens 0 :cache_write_tokens 0}})

(defn- sitting [usage & {:as more}]
  {:id "sitting-1"
   :data (merge {:seat "seat-1" :model (:id economy)
                 :usage_by_session usage}
                more)})

(defn- used [input posted]
  {:input_tokens input :output_tokens 0 :cache_read_tokens 0
   :cache_write_tokens 0 :posted_usd posted})

(defn- usd= [a b] (zero? (compare (bigdec a) (bigdec b))))

(defn- posting [basis amount at]
  {:basis basis :amount (money/->Money (bigdec amount) "USD") :at at})

(def ^:private floor-onto #'seats/floor-onto)
(def ^:private apply-report #'seats/apply-report)
(def ^:private late-report #'seats/late-report)

(defn- report [row inp ctx]
  (let [[row' changed?] (apply-report row inp ctx)]
    (late-report row row' changed? ctx)))

(defn- book!
  "What report-posts posts onto the seat's spend, onto the ledger atom."
  [ledger row]
  (let [{:keys [estimate_reversed_usd delta_usd estimate_usd]} (get-in row [:data :last_report])]
    (swap! ledger into
           (cond-> []
             (pos? (compare estimate_reversed_usd 0M))
             (conj (posting "estimated_from_transcript" (- estimate_reversed_usd) "2026-10-24T01:00:00Z"))
             (pos? (compare delta_usd 0M))
             (conj (posting "reported" delta_usd "2026-10-24T01:00:00Z"))
             (pos? (compare estimate_usd 0M))
             (conj (posting "estimated_from_transcript" estimate_usd "2026-10-24T01:00:00Z"))))))

(deftest a-crash-after-one-report-bills-the-tail
  (let [ledger (atom [(posting "reported" 1M "2026-10-23T23:00:00Z")])
        ctx (ctx-of [(line 1 "r1" "economy" 1000000) (line 2 "r2" "economy" 1000000)] ledger)
        row (floor-onto (sitting {:a (used 1000000 1M)} :cost_usd 1M :bill_basis "reported") ctx)]
    (is (usd= 1M (get-in row [:data :floor :estimate_usd])))
    (is (= ["economy"] (get-in row [:data :floor :models])))
    (is (usd= 2M (get-in row [:data :cost_usd])) "cost_usd grows as the ledger does")
    (is (= "estimated_from_transcript" (get-in row [:data :bill_basis])))))

(deftest a-crash-with-no-report-bills-the-whole-transcript
  (let [ctx (ctx-of [(line 1 "r1" "economy" 1000000) (line 2 "r2" "economy" 2000000)] (atom []))
        row (floor-onto (sitting nil) ctx)]
    (is (usd= 3M (get-in row [:data :floor :estimate_usd])))
    (is (= "estimated_from_transcript" (get-in row [:data :bill_basis])))))

(deftest a-full-report-means-no-estimate
  (testing "the report covers the transcript: nothing posts, the bill rests on the report"
    (let [ledger (atom [(posting "reported" 2M "2026-10-23T23:00:00Z")])
          ctx (ctx-of [(line 1 "r1" "economy" 2000000)] ledger)
          row (floor-onto (sitting {:a (used 2000000 2M)} :cost_usd 2M :bill_basis "reported") ctx)]
      (is (usd= 0M (get-in row [:data :floor :estimate_usd])))
      (is (usd= 2M (get-in row [:data :cost_usd])))
      (is (= "reported" (get-in row [:data :bill_basis])))))
  (testing "no usage at all: none"
    (let [row (floor-onto (sitting nil) (ctx-of [] (atom [])))]
      (is (usd= 0M (get-in row [:data :floor :estimate_usd])))
      (is (= "none" (get-in row [:data :bill_basis]))))))

(deftest lines-of-one-request-count-once
  (let [ctx (ctx-of [(line 1 "r1" "economy" 1000000)
                     (line 2 "r1" "economy" 1000000)
                     (line 3 "r1" "economy" 1000000)
                     (line 4 nil "economy" 1000000)]
                    (atom []))
        row (floor-onto (sitting nil) ctx)]
    (is (usd= 2M (get-in row [:data :floor :estimate_usd]))
        "three lines of r1 count once, and the line with no request id once on its own")))

(deftest mixed-models-are-priced-per-line
  (let [ctx (ctx-of [(line 1 "r1" "economy" 1000000) (line 2 "r2" "premium" 1000000)]
                    (atom [(posting "reported" 1M "2026-10-23T23:00:00Z")]))
        row (floor-onto (sitting {:a (used 1000000 1M)} :cost_usd 1M) ctx)]
    (is (usd= 10M (get-in row [:data :floor :estimate_usd]))
        "the report covers the oldest line; the tail is the premium one")
    (is (= ["premium"] (get-in row [:data :floor :models]))))
  (testing "a line naming no model the house knows is priced at the sitting's"
    (let [row (floor-onto (sitting nil) (ctx-of [(line 1 "r1" "unknown" 1000000)] (atom [])))]
      (is (usd= 1M (get-in row [:data :floor :estimate_usd]))))))

(deftest a-late-report-reverses-and-reposts
  (let [lines [(line 1 "r1" "economy" 1000000) (line 2 "r2" "economy" 1000000)]
        ledger (atom [(posting "reported" 1M "2026-10-23T23:00:00Z")])
        ctx (ctx-of lines ledger)
        expired (-> (sitting {:a (used 1000000 1M)} :cost_usd 1M :bill_basis "reported")
                    (assoc-in [:data :closed_by] "expiry")
                    (floor-onto ctx))
        _ (swap! ledger conj (posting "estimated_from_transcript" 1M "2026-10-24T00:00:00Z"))
        row (report expired {:harness_session "a" :input_tokens 2000000 :output_tokens 0
                             :cache_read_tokens 0 :cache_write_tokens 0}
                    ctx)]
    (is (usd= 1M (get-in row [:data :last_report :estimate_reversed_usd])))
    (is (usd= 1M (get-in row [:data :last_report :delta_usd])))
    (is (usd= 0M (get-in row [:data :last_report :estimate_usd])))
    (is (= "reported" (get-in row [:data :bill_basis])))
    (book! ledger row)
    (is (usd= 2M (reduce + 0M (map #(:amount (:amount %)) @ledger)))
        "the seat's ledger total equals the real report")
    (is (usd= 2M (get-in row [:data :cost_usd])) "and the sitting's cost agrees with it")))

(deftest a-late-report-keeps-the-other-sessions-tail
  (let [lines (mapv #(line % (str "r" %) "economy" 1000000) (range 1 5))
        ledger (atom [(posting "reported" 2M "2026-10-23T23:00:00Z")])
        ctx (ctx-of lines ledger)
        expired (-> (sitting {:a (used 1000000 1M) :b (used 1000000 1M)} :cost_usd 2M
                             :bill_basis "reported")
                    (assoc-in [:data :closed_by] "expiry")
                    (floor-onto ctx))
        _ (is (usd= 2M (get-in expired [:data :floor :estimate_usd])))
        _ (swap! ledger conj (posting "estimated_from_transcript" 2M "2026-10-24T00:00:00Z"))
        b (report expired {:harness_session "b" :input_tokens 2000000 :output_tokens 0
                           :cache_read_tokens 0 :cache_write_tokens 0}
                  ctx)]
    (testing "b's report reverses the whole estimate and re-posts a's tail"
      (is (usd= 2M (get-in b [:data :last_report :estimate_reversed_usd])))
      (is (usd= 1M (get-in b [:data :last_report :delta_usd])))
      (is (usd= 1M (get-in b [:data :last_report :estimate_usd])))
      (is (= "estimated_from_transcript" (get-in b [:data :bill_basis]))))
    (book! ledger b)
    (let [a (report b {:harness_session "a" :input_tokens 2000000 :output_tokens 0
                       :cache_read_tokens 0 :cache_write_tokens 0}
                    ctx)]
      (testing "a second late report reverses that remainder in turn"
        (is (usd= 1M (get-in a [:data :last_report :estimate_reversed_usd])))
        (is (usd= 1M (get-in a [:data :last_report :delta_usd])))
        (is (usd= 0M (get-in a [:data :last_report :estimate_usd])))
        (is (= "reported" (get-in a [:data :bill_basis]))))
      (book! ledger a)
      (is (usd= 4M (reduce + 0M (map #(:amount (:amount %)) @ledger))))
      (is (usd= 4M (get-in a [:data :cost_usd]))))))
