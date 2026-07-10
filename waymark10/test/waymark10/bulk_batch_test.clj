(ns waymark10.bulk-batch-test
  "Phase-7 acceptance: bulk (one input, N resources) and batch (N
  inputs, one resource) over the wire. Obligations: the partial-
  success report counts honestly and carries the guard's own sentence,
  a refused row stays untouched, the atomic twin rolls everything
  back, batch lands one transition per input in order, and the named
  an over-threshold call defers to the job resource with a 202 (the
  phase-9b closure of the phase-7 punt; the worker's own story lives
  in waymark10.jobs-test)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── suite-local kinds ───────────────────────────────────────────────

(def ready-gate
  (g/expr {:name :ready
           :when '(= (data :ready) true)
           :explain "This chore is not ready."}))

(def chore
  (r/resource
   {:kind :chore
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:ready {:optional true} [:maybe :boolean]]]
    :actions
    {:complete {:from #{:open} :to :done
                :bulk {:max-items 5}
                :guards [ready-gate]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}}
     :complete_all {:from #{:open} :to :done
                    :bulk {:atomic true :max-items 5}
                    :guards [ready-gate]
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "Done is done."}}
     :sweep {:from #{:open} :to :done
             :bulk {:defer-over 2 :max-items 10}
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Done is done."}}}}))

(r/defhandler note-handler [row inp _ctx]
  (update-in row [:data :notes] (fnil conj []) (:text inp)))

(def ledger
  (r/resource
   {:kind :ledger
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:notes {:optional true} [:maybe [:vector [:string {:max 200}]]]]]
    :actions
    {:note {:from #{:open} :to :open
            :batch {:max-items 3}
            :input [:map [:text [:string {:min 1 :max 200}]]]
            :record true
            :safety {:idempotent false :reversible true :confirm false}
            :handler note-handler}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closed books stay closed."}}}}))

(def ^:dynamic *h* nil)
(def ^:dynamic *st* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["chores" "ledgers" "definitions" "jobs"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts" "waymark10_job_leases"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*st* st
                  *h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [chore ledger]}))]
          (f))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- req
  ([method uri body] (req method uri body nil))
  ([method uri body headers]
   (*h* (cond-> {:request-method method
                 :uri uri
                 :headers (merge {"x-waymark-principal" "colton"} headers)}
          body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))
(defn- get-row [uri] (json (req :get uri nil)))

(defn- chores!
  "Fresh chores; ready-mask marks which are ready. → [ids]"
  [ready-mask]
  (mapv (fn [i ready?]
          (id-of (req :post "/api/chores"
                      {:title (str "chore " i) :ready ready?})))
        (range (count ready-mask)) ready-mask))

;; ── 1. the partial-success report ───────────────────────────────────

(deftest bulk-partial-report
  (let [[a b c] (chores! [true false true])
        resp (req :post "/api/chores/-/complete" {:ids [a b c]})
        doc (json resp)]
    (is (= 200 (:status resp)))
    (is (= "application/waymark+json" (get-in resp [:headers "Content-Type"])))
    (let [vs (conf/bulk-report-violations doc {:action :complete :items 3})]
      (is (empty? vs) (str/join "\n" vs)))
    (is (= {:succeeded 2 :refused 1 :failed 0}
           (select-keys (:data doc) [:succeeded :refused :failed])))
    (testing "the refusal carries the guard's own sentence and the row's self"
      (let [[refusal] (get-in doc [:data :refusals])]
        (is (= (str "/api/chores/" b) (:self refusal)))
        (is (= "This chore is not ready." (:reason refusal)))))
    (testing "the refused row is untouched; its neighbors moved"
      (let [rb (get-row (str "/api/chores/" b))]
        (is (= "open" (:state rb)))
        (is (= 1 (get-in rb [:meta :version]))))
      (is (= "done" (:state (get-row (str "/api/chores/" a)))))
      (is (= "done" (:state (get-row (str "/api/chores/" c))))))))

;; ── 2. the atomic twin rolls all back ───────────────────────────────

(deftest bulk-atomic-rolls-back
  (let [[a b c] (chores! [true false true])
        resp (req :post "/api/chores/-/complete_all" {:ids [a b c]})
        doc (json resp)]
    (is (= 409 (:status resp)))
    (is (= "application/problem+json" (get-in resp [:headers "Content-Type"])))
    (is (str/includes? (:detail doc) "nothing committed"))
    (is (= "This chore is not ready."
           (-> doc :report :refusals first :reason)))
    (is (= (str "/api/chores/" b) (-> doc :report :refusals first :self)))
    (testing "every row is untouched, the ones that had already passed too"
      (doseq [id [a b c]]
        (let [row (get-row (str "/api/chores/" id))]
          (is (= "open" (:state row)) (str id " moved"))
          (is (= 1 (get-in row [:meta :version]))))))
    (testing "an all-ready atomic bulk lands whole"
      (let [ids (chores! [true true])
            doc (json (req :post "/api/chores/-/complete_all" {:ids ids}))]
        (is (= {:succeeded 2 :refused 0 :failed 0}
               (select-keys (:data doc) [:succeeded :refused :failed])))
        (doseq [id ids]
          (is (= "done" (:state (get-row (str "/api/chores/" id))))))))))

;; ── 3. validation and the named punt ────────────────────────────────

(deftest bulk-refusals
  (let [ids (chores! [true true true])]
    (testing "defer-over answers 202 with the job envelope in Location"
      (let [resp (req :post "/api/chores/-/sweep" {:ids ids})
            b (json resp)]
        (is (= 202 (:status resp)))
        (is (= "job" (:kind b)))
        (is (= "running" (:state b)))
        (is (= (:self b) (get-in resp [:headers "Location"])))
        (is (= {:done 0 :total 3 :refusals []} (get-in b [:data :progress])))
        (is (= "sweep" (get-in b [:data :action])))
        (is (= "chore" (get-in b [:data :kind])))
        (testing "…and nothing moved yet — the worker owns the fan-out"
          (doseq [id ids]
            (is (= "open" (:state (get-row (str "/api/chores/" id)))))))))
    (testing "the max-items cap"
      (let [six (into ids (chores! [true true true]))
            b (json (req :post "/api/chores/-/complete" {:ids six}))]
        (is (= ["at most 5 ids per call"] (get-in b [:errors :ids])))))
    (testing "a missing or empty ids array"
      (doseq [body [nil {} {:ids []} {:ids "a"}]]
        (let [resp (req :post "/api/chores/-/complete" body)]
          (is (= 422 (:status resp)))
          (is (contains? (:errors (json resp)) :ids)))))
    (testing "an unknown bulk action is 404"
      (is (= 404 (:status (req :post "/api/chores/-/zap" {:ids ids}))))
      (is (= 404 (:status (req :post "/api/ledgers/-/close" {:ids ids})))
          "a declared action without :bulk has no bulk form"))))

;; ── 4. bulk is a collection affordance, never a row's ───────────────

(deftest bulk-lives-on-the-collection
  (let [[id] (chores! [true])]
    (testing "the collection envelope advertises the bulk actions with ids"
      (let [b (json (req :get "/api/chores" nil))
            entry (get-in b [:actions :complete])]
        (is (some? entry))
        (is (= "/api/chores/-/complete" (:href entry)))
        (is (true? (get-in entry [:effect :bulk])))
        (is (true? (get-in entry [:effect :terminal])))
        (is (= {:type "array" :items {:type "string"} :minItems 1 :maxItems 5}
               (get-in entry [:input :properties :ids])))
        (is (some #{"ids"} (get-in entry [:input :required])))
        (is (contains? (:actions b) :complete_all))
        (is (contains? (:actions b) :sweep))))
    (testing "the row envelope shows no bulk action, available or narrated"
      (let [row (get-row (str "/api/chores/" id))]
        (is (not (contains? (:actions row) :complete)))
        (is (not (contains? (:unavailable row) :complete)))))
    (testing "single-invoking a bulk action does not exist"
      (is (= 404 (:status (req :post (str "/api/chores/" id "/-/complete")
                               nil)))))))

;; ── 5. bulk idempotency ─────────────────────────────────────────────

(deftest bulk-replays-bytes
  (let [ids (chores! [true false])
        k {"idempotency-key" "bulk-key-1"}
        one (req :post "/api/chores/-/complete" {:ids ids} k)
        two (req :post "/api/chores/-/complete" {:ids ids} k)]
    (is (= 200 (:status one)))
    (is (= (:body one) (:body two)) "byte-identical replay")
    (is (= 1 (get-in (json two) [:data :refused])))
    (testing "the key refuses a different body"
      (let [resp (req :post "/api/chores/-/complete"
                      {:ids [(first ids)]} k)]
        (is (= 409 (:status resp)))
        (is (= "https://waymark.dev/problems/idempotency-key-reuse"
               (:type (json resp))))))))

;; ── 6. batch: N inputs, one row, in order ───────────────────────────

(deftest batch-lands-in-order
  (let [id (id-of (req :post "/api/ledgers" {:name "july"}))
        uri (str "/api/ledgers/" id "/-/note/batch")]
    (testing "a non-idempotent batch demands one key for the whole call"
      (is (= 428 (:status (req :post uri {:inputs [{:text "a"}]})))))
    (let [k {"idempotency-key" "batch-key-1"}
          resp (req :post uri {:inputs [{:text "first"} {:text "second"}]} k)
          doc (json resp)]
      (is (= 200 (:status resp)))
      (let [vs (conf/bulk-report-violations doc {:action :note :items 2})]
        (is (empty? vs) (str/join "\n" vs)))
      (is (= 2 (get-in doc [:data :succeeded])))
      (is (= (str "/api/ledgers/" id) (get-in doc [:links :target :href])))
      (testing "two transitions on one row, versions +2, order preserved"
        (let [row (get-row (str "/api/ledgers/" id))]
          (is (= 3 (get-in row [:meta :version])))
          (is (= ["first" "second"] (get-in row [:data :notes]))))
        (let [ts (store/with-tx *st*
                   #(store/transitions *st* % {:kind :ledger :resource-id id} {}))]
          (is (= [:create :note :note] (mapv :action ts)))
          (is (= ["first" "second"]
                 (keep #(get-in % [:inputs :text]) ts))
              "the recorded inputs ride the log in order")))
      (testing "the whole-call key replays the report byte-identically"
        (let [again (req :post uri {:inputs [{:text "first"} {:text "second"}]} k)]
          (is (= (:body resp) (:body again)))
          (is (= 3 (get-in (get-row (str "/api/ledgers/" id)) [:meta :version]))
              "the replay wrote nothing"))))))

;; ── 7. batch is atomic: the first refusal aborts everything ────────

(deftest batch-refusal-rolls-back
  (let [id (id-of (req :post "/api/ledgers" {:name "august"}))
        uri (str "/api/ledgers/" id "/-/note/batch")
        k (fn [s] {"idempotency-key" s})]
    (testing "a broken input aborts the batch, naming its index"
      (let [resp (req :post uri {:inputs [{:text "kept?"} {:evil 1}]}
                      (k "batch-abort-1"))
            b (json resp)]
        (is (= 409 (:status resp)))
        (is (= "https://waymark.dev/problems/batch-refused" (:type b)))
        (is (= 1 (:index b)))
        (is (str/includes? (:detail b) "nothing committed")))
      (let [row (get-row (str "/api/ledgers/" id))]
        (is (= 1 (get-in row [:meta :version])) "the first input rolled back")
        (is (nil? (get-in row [:data :notes])))))
    (testing "the cap and the body contract"
      (let [b (json (req :post uri {:inputs (vec (repeat 4 {:text "x"}))}
                        (k "batch-cap-1")))]
        (is (= ["at most 3 inputs per batch"] (get-in b [:errors :inputs]))))
      (let [b (json (req :post uri {:inputs [{:text "x"}] :stray 1}
                        (k "batch-stray-1")))]
        (is (= ["unexpected field"] (get-in b [:errors :stray]))))
      (let [resp (req :post uri {:inputs []} (k "batch-empty-1"))]
        (is (= 422 (:status resp)))))
    (testing "a batch route for a batchless action is 404"
      (is (= 404 (:status (req :post (str "/api/ledgers/" id "/-/close/batch")
                               {:inputs [{}]})))))))
