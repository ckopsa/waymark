(ns waymark10.bulk-batch-test
  "Phase-7 acceptance: bulk (one input, N resources) and batch (N
  inputs, one resource) over the wire. Obligations: the partial-
  success report counts honestly and carries the guard's own sentence,
  a refused row stays untouched, the atomic twin rolls everything
  back, batch lands one transition per input in order, and the named
  an over-threshold call defers to the job resource with a 202 (the
  phase-9b closure of the phase-7 punt; the worker's own story lives
  in waymark10.jobs-test).

  The items shape (waymark-pywy.4, §9): many ids each with its own
  input at the same door, acknowledged PER ITEM (the call-level
  header is refused there), on_error continue|stop|atomic, per-item
  rehearsal verdicts that say what would move, and the same
  whole-call idempotency convention."
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

(def risky-warning
  (g/expr {:name :risky
           :severity :warning
           :when '(not (data :risky))
           :explain "This chore is marked risky."}))

(r/defhandler tag-handler [row inp _ctx]
  (assoc-in row [:data :label] (:label inp)))

(def chore
  (r/resource
   {:kind :chore
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:ready {:optional true} [:maybe :boolean]]
             [:risky {:optional true} [:maybe :boolean]]
             [:label {:optional true} [:maybe [:string {:max 20}]]]]
    :actions
    {;; the items shape's own door: an input per row, a warning to
     ;; acknowledge per row, non-idempotent so the call owes a key
     :tag {:from #{:open} :to :open
           :bulk {:max-items 5}
           :input [:map [:label [:string {:min 1 :max 20}]]]
           :guards [risky-warning]
           :record true
           :safety {:idempotent false :reversible true :confirm false}
           :handler tag-handler}
     :complete {:from #{:open} :to :done
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

(defn- chore! [data]
  (id-of (req :post "/api/chores" (merge {:title "chore" :ready true} data))))

(defn- transitions-of [id]
  (store/with-tx *st*
    #(store/transitions *st* % {:kind :chore :resource-id id} {})))

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
        (is (= "queued" (:state b)))
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

;; ── 8. the rehearsal fans out too (design §23) ──────────────────────
;; ?dry_run=1 on the bulk and batch doors: per-item verdicts, nothing
;; committed, no key demanded or recorded, never a deferral.

(defn- dry-req
  ([uri body] (dry-req uri body nil))
  ([uri body headers]
   (*h* {:request-method :post
         :uri uri
         :query-string "dry_run=1"
         :headers (merge {"x-waymark-principal" "colton"} headers)
         :body (wire/write-json body)})))

(deftest bulk-dry-run-judges-without-committing
  (let [[a b c] (chores! [true false true])
        resp (dry-req "/api/chores/-/complete" {:ids [a b c]})
        doc (json resp)]
    (is (= 200 (:status resp)))
    (is (= "application/waymark+json" (get-in resp [:headers "Content-Type"])))
    (is (false? (:valid doc)))
    (is (= ["ok" "refused" "ok"] (mapv :verdict (:verdicts doc))))
    (testing "the refusing verdict names the row and speaks the guard's
              own sentence"
      (let [v (second (:verdicts doc))]
        (is (= (str "/api/chores/" b) (:self v)))
        (is (= "This chore is not ready." (:reason v)))))
    (testing "no row moved — the ok verdicts included"
      (doseq [id [a b c]]
        (let [row (get-row (str "/api/chores/" id))]
          (is (= "open" (:state row)))
          (is (= 1 (get-in row [:meta :version]))))))))

(deftest bulk-dry-run-never-defers-or-records
  (testing "an over-threshold rehearsal judges inline — a job is an
            effect, and a rehearsal mints none"
    (let [ids (chores! [true true true])   ;; sweep defers over 2
          total #(get-in (json (req :get "/api/jobs" nil)) [:data :total])
          before (total)
          resp (dry-req "/api/chores/-/sweep" {:ids ids})
          doc (json resp)]
      (is (= 200 (:status resp)))
      (is (true? (:valid doc)))
      (is (= 3 (count (:verdicts doc))))
      (is (= before (total)) "no job minted")))
  (testing "a keyed rehearsal records nothing under the key"
    (let [[a] (chores! [true])
          k {"idempotency-key" "bulk-dry-key-1"}
          dry (dry-req "/api/chores/-/complete" {:ids [a]} k)
          real (req :post "/api/chores/-/complete" {:ids [a]} k)]
      (is (= 200 (:status dry)))
      (is (= "bulk_report" (:kind (json real)))
          "the real call ran fresh — the rehearsal stored nothing")
      (is (= 1 (get-in (json real) [:data :succeeded]))))))

(deftest batch-dry-run-full-verdicts
  (let [id (id-of (req :post "/api/ledgers" {:name "rehearsal"}))
        uri (str "/api/ledgers/" id "/-/note/batch")
        resp (dry-req uri {:inputs [{:text "one"} {:text ""} {:text "three"}]})
        doc (json resp)]
    (is (= 200 (:status resp)))
    (is (false? (:valid doc)))
    (is (= ["ok" "refused" "ok"] (mapv :verdict (:verdicts doc)))
        "EVERY verdict reports — the real atomic batch stops at the first")
    (is (= 1 (:index (second (:verdicts doc)))))
    (testing "nothing landed: no transition, no version bump"
      (let [row (get-row (str "/api/ledgers/" id))]
        (is (= 1 (get-in row [:meta :version])))
        (is (nil? (get-in row [:data :notes])))))
    (testing "no key demanded for the rehearsal — the real batch still
              demands one"
      (is (= 428 (:status (req :post uri {:inputs [{:text "one"}]})))))))

;; ── 9. the items shape: many ids, each with its own input ───────────
;; (waymark-pywy.4) The same door, the third body: {items: [{id,
;; input?, acknowledge?}]}. Every obligation above holds per item;
;; what is new is that the input and the acknowledgement are the
;; item's own.

(deftest bulk-items-each-with-its-own-input
  (let [[a b c] (chores! [true true true])
        k {"idempotency-key" "items-key-1"}
        body {:items [{:id a :input {:label "one"}}
                      {:id b :input {:label "two"}}
                      {:id c :input {:label "three"}}]}
        resp (req :post "/api/chores/-/tag" body k)
        doc (json resp)]
    (is (= 200 (:status resp)) (:body resp))
    (let [vs (conf/bulk-report-violations doc {:action :tag :items 3})]
      (is (empty? vs) (str/join "\n" vs)))
    (is (= {:succeeded 3 :refused 0 :failed 0}
           (select-keys (:data doc) [:succeeded :refused :failed])))
    (testing "each row got its own input"
      (is (= ["one" "two" "three"]
             (mapv #(get-in (get-row (str "/api/chores/" %)) [:data :label])
                   [a b c]))))
    (testing "the log records each item's input; the items share the
              call's correlation id and carry no key of their own —
              the call's key stores the report (today's convention)"
      (let [ts (mapv #(last (transitions-of %)) [a b c])]
        (is (= [:tag :tag :tag] (mapv :action ts)))
        (is (= ["one" "two" "three"] (mapv #(get-in % [:inputs :label]) ts)))
        (is (= 1 (count (distinct (map :correlation-id ts)))))
        (is (every? nil? (map :idempotency-key ts)))))
    (testing "a non-idempotent items call demands the call-level key"
      (is (= 428 (:status (req :post "/api/chores/-/tag" body)))))
    (testing "the key replays the report byte-identically"
      (let [again (req :post "/api/chores/-/tag" body k)]
        (is (= (:body resp) (:body again)))
        (is (= 2 (get-in (get-row (str "/api/chores/" a)) [:meta :version]))
            "the replay wrote nothing")))))

(deftest bulk-items-acknowledge-is-per-item
  (let [a (chore! {})
        b (chore! {:risky true})
        items (fn [ack] [{:id a :input {:label "x"}}
                         (cond-> {:id b :input {:label "y"}}
                           ack (assoc :acknowledge ack))])]
    (testing "an unacknowledged warning refuses ITS item and no other"
      (let [doc (json (req :post "/api/chores/-/tag" {:items (items nil)}
                          {"idempotency-key" "items-ack-1"}))]
        (is (= {:succeeded 1 :refused 1 :failed 0}
               (select-keys (:data doc) [:succeeded :refused :failed])))
        (let [[refusal] (get-in doc [:data :refusals])]
          (is (= (str "/api/chores/" b) (:self refusal)))
          (is (str/includes? (:reason refusal) "cknowledge")))
        (is (= "x" (get-in (get-row (str "/api/chores/" a)) [:data :label])))
        (is (nil? (get-in (get-row (str "/api/chores/" b)) [:data :label])))))
    (testing "a blanket call-level acknowledgement is refused, and
              nothing moves — the header would spread one reading
              over rows nobody read that way"
      (let [resp (req :post "/api/chores/-/tag" {:items (items nil)}
                      {"idempotency-key" "items-ack-2"
                       "waymark-acknowledge" "risky"})
            p (json resp)]
        (is (= 422 (:status resp)))
        (is (= "https://waymark.dev/problems/acknowledge-per-item" (:type p)))
        (is (= "items[].acknowledge" (get-in p [:acknowledge :field])))
        (is (= ["risky"] (get-in p [:acknowledge :names])))
        (is (= 1 (get-in (get-row (str "/api/chores/" b)) [:meta :version])))
        (is (= 2 (get-in (get-row (str "/api/chores/" a)) [:meta :version]))
            "a did not run twice either — the whole call was refused")))
    (testing "the item's own acknowledgement lets it through"
      (let [doc (json (req :post "/api/chores/-/tag" {:items (items ["risky"])}
                          {"idempotency-key" "items-ack-3"}))]
        (is (= {:succeeded 2 :refused 0 :failed 0}
               (select-keys (:data doc) [:succeeded :refused :failed])))
        (is (= "y" (get-in (get-row (str "/api/chores/" b)) [:data :label])))
        (is (= ["risky"] (mapv name (:acknowledged (last (transitions-of b)))))
            "recorded as overridden on that row's own transition")))
    (testing "the ids shape keeps its call-level header — one input,
              one reading, one acknowledgement"
      (let [c (chore! {:risky true})
            doc (json (req :post "/api/chores/-/tag" {:ids [c] :label "z"}
                          {"idempotency-key" "items-ack-4"
                           "waymark-acknowledge" "risky"}))]
        (is (= 1 (get-in doc [:data :succeeded])))))))

(deftest bulk-on-error-modes
  (testing "stop halts at the first refusal and says what did not run"
    (let [[a b c] (chores! [true false true])
          resp (req :post "/api/chores/-/complete" {:ids [a b c] :on_error "stop"})
          doc (json resp)]
      (is (= 200 (:status resp)))
      (let [vs (conf/bulk-report-violations doc {:action :complete :items 3})]
        (is (empty? vs) (str/join "\n" vs)))
      (is (= {:succeeded 1 :refused 1 :failed 0 :skipped 1}
             (select-keys (:data doc) [:succeeded :refused :failed :skipped])))
      (is (= "This chore is not ready."
             (-> doc :data :refusals first :reason)))
      (is (= [{:self (str "/api/chores/" c)}] (get-in doc [:data :not_run])))
      (is (= ["done" "open" "open"]
             (mapv #(:state (get-row (str "/api/chores/" %))) [a b c])))))
  (testing "stop with nothing refusing runs everything and skips none"
    (let [ids (chores! [true true])
          doc (json (req :post "/api/chores/-/complete"
                        {:items (mapv #(hash-map :id %) ids) :on_error "stop"}))]
      (is (= {:succeeded 2 :refused 0 :failed 0 :skipped 0}
             (select-keys (:data doc) [:succeeded :refused :failed :skipped])))
      (is (= [] (get-in doc [:data :not_run])))))
  (testing "atomic tightens a non-atomic declaration: one refusal
            rolls the items that had passed back too"
    (let [[a b c] (chores! [true false true])
          resp (req :post "/api/chores/-/complete"
                    {:items [{:id a} {:id b} {:id c}] :on_error "atomic"})
          p (json resp)]
      (is (= 409 (:status resp)))
      (is (= "https://waymark.dev/problems/bulk-refused" (:type p)))
      (is (= (str "/api/chores/" b) (-> p :report :refusals first :self)))
      (doseq [id [a b c]]
        (is (= "open" (:state (get-row (str "/api/chores/" id))))))))
  (testing "continue, spelled out, is the report as ever"
    (let [[a b] (chores! [true false])
          doc (json (req :post "/api/chores/-/complete"
                        {:items [{:id a} {:id b}] :on_error "continue"}))]
      (is (= {:succeeded 1 :refused 1 :failed 0}
             (select-keys (:data doc) [:succeeded :refused :failed])))
      (is (not (contains? (:data doc) :skipped)))))
  (testing "the vocabulary is closed"
    (let [[a] (chores! [true])
          b (json (req :post "/api/chores/-/complete" {:ids [a] :on_error "retry"}))]
      (is (= ["one of continue, stop, atomic"] (get-in b [:errors :on_error])))))
  (testing "a declared-atomic action can only be atomic — the body
            may tighten the declaration, never loosen it"
    (let [[a] (chores! [true])]
      (is (= 422 (:status (req :post "/api/chores/-/complete_all"
                               {:ids [a] :on_error "continue"}))))
      (is (= 200 (:status (req :post "/api/chores/-/complete_all"
                               {:ids [a] :on_error "atomic"}))))))
  (testing "stop and atomic do not defer — a job runs continue"
    (let [ids (chores! [true true true])   ;; sweep defers over 2
          resp (req :post "/api/chores/-/sweep" {:ids ids :on_error "stop"})]
      (is (= 422 (:status resp)))
      (is (contains? (:errors (json resp)) :on_error))
      (doseq [id ids]
        (is (= "open" (:state (get-row (str "/api/chores/" id)))))))))

(deftest bulk-items-dry-run-says-what-would-move
  (let [[a b] (chores! [true false])
        resp (dry-req "/api/chores/-/complete" {:items [{:id a} {:id b}]})
        doc (json resp)]
    (is (= 200 (:status resp)))
    (is (false? (:valid doc)))
    (is (= ["ok" "refused"] (mapv :verdict (:verdicts doc))))
    (testing "an ok verdict says what would move; a refusal speaks the
              guard's own sentence"
      (is (= {:from "open" :to "done"} (:would (first (:verdicts doc)))))
      (is (= "This chore is not ready." (:reason (second (:verdicts doc)))))
      (is (nil? (:would (second (:verdicts doc))))))
    (testing "the fields an input names ride the would"
      (let [doc (json (dry-req "/api/chores/-/tag"
                               {:items [{:id a :input {:label "one"}}
                                        {:id b :input {:label ""}}]}))]
        (is (= ["ok" "refused"] (mapv :verdict (:verdicts doc))))
        (is (= {:from "open" :to "open" :fields ["label"]}
               (:would (first (:verdicts doc)))))))
    (testing "nothing moved, and the rehearsal demanded no key for a
              non-idempotent action"
      (doseq [id [a b]]
        (let [row (get-row (str "/api/chores/" id))]
          (is (= "open" (:state row)))
          (is (= 1 (get-in row [:meta :version])))
          (is (nil? (get-in row [:data :label]))))))))

(deftest bulk-items-contract
  (let [[a] (chores! [true])
        errors (fn [body] (:errors (json (req :post "/api/chores/-/tag" body
                                              {"idempotency-key" (str (random-uuid))}))))]
    (is (= ["one of ids or items, not both"]
           (:items (errors {:ids [a] :items [{:id a}]}))))
    (is (contains? (errors {:items []}) :items))
    (is (contains? (errors {:items "a"}) :items))
    (is (= ["item 0: id must be a non-blank string"]
           (:items (errors {:items [{:input {:label "x"}}]}))))
    (is (= ["item 1: unexpected field foo"]
           (:items (errors {:items [{:id a} {:id a :foo 1}]}))))
    (is (= ["item 0: acknowledge must be an array of guard names"]
           (:items (errors {:items [{:id a :acknowledge "risky"}]}))))
    (testing "the items shape carries no top-level input — each item does"
      (is (= ["unexpected field — each item carries its own input"]
             (:label (errors {:items [{:id a}] :label "x"})))))
    (testing "the cap counts items"
      (is (= ["at most 5 items per call"]
             (:items (errors {:items (vec (repeat 6 {:id a :input {:label "x"}}))})))))
    (testing "an item's own input is validated as any input is"
      (let [doc (json (req :post "/api/chores/-/tag"
                          {:items [{:id a :input {:label ""}}]}
                          {"idempotency-key" "items-contract-1"}))]
        (is (= 1 (get-in doc [:data :refused])))))
    (testing "over the threshold the items defer, each row's input
              riding the job"
      (let [ids (chores! [true true true])
            resp (req :post "/api/chores/-/sweep"
                      {:items (mapv #(hash-map :id %) ids)})
            job (json resp)]
        (is (= 202 (:status resp)))
        (is (= "job" (:kind job)))
        (is (= ids (get-in job [:data :ids])))
        (is (= [{} {} {}] (get-in job [:data :inputs])))
        (is (= [[] [] []] (get-in job [:data :acknowledged])))))))

