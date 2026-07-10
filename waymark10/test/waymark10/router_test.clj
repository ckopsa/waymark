(ns waymark10.router-test
  "Phase-3 acceptance: the HTTP boundary, driven as ring request maps
  against the real handler (no live server). Same DB fixture pattern
  as invoke-test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.wire :as wire]))

(def dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

;; the warning/idempotency fixture kind (invoke-test's task)

(def risk-noted
  (g/expr {:name :risk-noted
           :severity :warning
           :when '(not (data :risky))
           :explain "This task is flagged risky."}))

(r/defhandler poke-handler [row _inp _ctx]
  (update-in row [:data :pokes] (fnil inc 0)))

(def task
  (r/resource
   {:kind :task
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:max 80}]]
             [:risky {:optional true} [:maybe :boolean]]
             [:pokes {:optional true} [:maybe :int]]]
    :actions
    {:poke {:from #{:open} :to :open
            :safety {:idempotent false :reversible true :confirm false}
            :handler poke-handler}
     :close {:from #{:open} :to :closed
             :guards [risk-noted]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed task is history."}}}}))

(def ^:dynamic *h* nil)

(defn- with-handler [f]
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["plans" "meals" "tasks" "definitions"
                         "waymark10_transitions" "waymark10_idempotency"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (binding [*h* (engine/handler
                     (engine/engine {:storage st
                                     :resources [fx/meal fx/plan task]}))]
        (f))
      (finally (pg/close! st)))))

(use-fixtures :once with-handler)

;; ── request sugar ───────────────────────────────────────────────────

(defn- req
  ([method uri] (req method uri nil nil))
  ([method uri body] (req method uri body nil))
  ([method uri body headers]
   (*h* (cond-> {:request-method method
                 :uri uri
                 :headers (merge {"x-waymark-principal" "colton"} headers)}
          body (assoc :body (wire/write-json body))))))

(defn- json [resp] (wire/read-json (:body resp)))
(defn- ctype [resp] (get-in resp [:headers "Content-Type"]))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- create-plan! [start days]
  (req :post "/api/plans" {:start_date start :weeks 1
                           :days (mapv (fn [d] {:date d}) days)}))

;; ── 1. discovery ────────────────────────────────────────────────────

(deftest well-known-lists-the-kinds
  (let [resp (req :get "/api/.well-known/waymark")
        b (json resp)]
    (is (= 200 (:status resp)))
    (is (= "10" (:waymark b)))
    ;; phase 5: the definition kind is registered beside the
    ;; application kinds — the deploy history is wire-readable.
    ;; phase 9a: the identity-and-access kinds enroll on every engine
    (is (= ["attachment" "definition" "grant" "meal" "member" "plan"
            "role" "task"]
           (:kinds b)))
    (is (= "/api/plans" (get-in b [:resources :plan :href])))
    (is (= "/api/meals" (get-in b [:resources :meal :href])))))

(deftest published-schema
  (let [resp (req :get "/api/schemas/plan")
        b (json resp)]
    (is (= 200 (:status resp)))
    (is (= "object" (:type b)))
    (is (= "date" (get-in b [:properties :start_date :format])))
    (is (= 404 (:status (req :get "/api/schemas/widget"))))))

;; ── 2–3. the create envelope and acceptance folding ─────────────────

(deftest create-plan-envelope
  (let [resp (create-plan! "2026-07-14" ["2026-07-14" "2026-07-15"])
        b (json resp)]
    (is (= 201 (:status resp)))
    (is (= "application/waymark+json" (ctype resp)))
    (is (= (:self b) (get-in resp [:headers "Location"])))

    (testing "the envelope"
      (is (= "10" (:waymark b)))
      (is (= "plan" (:kind b)))
      (is (= "draft" (:state b)))
      (is (= "Week of 2026-07-14 · 1 wk · Draft" (:summary b)))
      (is (= "2026-07-20" (get-in b [:data :end_date]))
          "the derived fact, materialized and encoded")
      (is (= {} (:links b)))
      (is (= 1 (get-in b [:meta :version])))
      (is (str/starts-with? (get-in b [:meta :etag]) "W/\"plan-")))

    (testing "finalize is honestly unavailable, with the way forward"
      (is (= "Not yet: all days covered does not hold."
             (get-in b [:unavailable :finalize :reason])))
      (is (= ["plan.assign_meal"]
             (get-in b [:unavailable :finalize :remedies]))))

    (testing "out-of-state actions narrate their states"
      (is (= "Available in state(s) Planned; the resource is Draft."
             (get-in b [:unavailable :reopen :reason])))
      (is (= ["planned"]
             (get-in b [:unavailable :reopen :becomes_available :in_states]))))

    (testing "assign_meal advertises with the folded acceptance enum"
      (let [entry (get-in b [:actions :assign_meal])]
        (is (= "POST" (:method entry)))
        (is (= (str (:self b) "/-/assign_meal") (:href entry)))
        (is (= ["2026-07-14" "2026-07-15"]
               (get-in entry [:input :properties :date :enum]))
            "the enum values are the plan's days as ISO strings")
        (is (= {:to "draft"} (:effect entry)))
        (is (= {:idempotent true :reversible false :confirm false}
               (:safety entry)))
        (is (= {:label "Assign meal" :style "primary" :order 1}
               (:display entry)))))

    (testing "a terminal outcome says so"
      (is (true? (get-in b [:actions :abandon :effect :terminal]))))))

;; ── 4. the 422 surface ──────────────────────────────────────────────

(deftest unknown-fields-refuse
  (let [pid (id-of (create-plan! "2026-08-04" ["2026-08-04"]))
        resp (req :post (str "/api/plans/" pid "/-/assign_meal")
                  {:date "2026-08-04" :meal_id "m" :evil 1})
        b (json resp)]
    (is (= 422 (:status resp)))
    (is (= "application/problem+json" (ctype resp)))
    (is (= "https://waymark.dev/problems/schema-invalid" (:type b)))
    (is (= {:evil ["disallowed key"]} (:errors b)))
    (is (= "assign_meal" (:action_attempted b)))))

;; ── 5. the acknowledge protocol over HTTP ───────────────────────────

(deftest warnings-acknowledge-over-http
  (let [tid (id-of (req :post "/api/tasks" {:title "rewire panel" :risky true}))
        refuse (req :post (str "/api/tasks/" tid "/-/close"))
        b (json refuse)]
    (is (= 409 (:status refuse)))
    (is (= "application/problem+json" (ctype refuse)))
    (is (= "Waymark-Acknowledge" (get-in b [:acknowledge :header])))
    (is (= ["risk-noted"] (get-in b [:acknowledge :names])))
    (is (= "This task is flagged risky." (-> b :warnings first :reason)))
    (let [retry (req :post (str "/api/tasks/" tid "/-/close") nil
                     {"waymark-acknowledge" "risk-noted"})]
      (is (= 200 (:status retry)))
      (is (= "closed" (:state (json retry)))))))

;; ── 6. the fence ────────────────────────────────────────────────────

(deftest the-fence-demands-if-match
  (let [mid (id-of (req :post "/api/meals" {:name "Tacos al pastor"
                                            :themes ["mexican"]}))
        _ (req :post (str "/api/meals/" mid "/-/accept"))
        bare (req :post (str "/api/meals/" mid "/-/update_recipe")
                  {:recipe "pork shoulder 1400g, Traeger at 275F"})]
    (is (= 412 (:status bare)))
    (is (= "application/problem+json" (ctype bare)))
    (let [got (req :get (str "/api/meals/" mid))
          etag (get-in got [:headers "ETag"])
          fenced (req :post (str "/api/meals/" mid "/-/update_recipe")
                      {:recipe "pork shoulder 1400g, Traeger at 275F"}
                      {"if-match" etag})]
      (is (= (get-in (json got) [:meta :etag]) etag))
      (is (= 200 (:status fenced)))
      ;; NOTE: the fixture declares no :handler for update_recipe, and
      ;; phase-2 invoke! applies input only through a handler — the
      ;; fence is what's under test here, not input application
      (is (= (inc (get-in (json got) [:meta :version]))
             (get-in (json fenced) [:meta :version]))
          "the fenced write advanced the row")
      (is (true? (get-in (json fenced)
                         [:actions :update_recipe :safety :fence]))))))

;; ── 7. idempotency over HTTP ────────────────────────────────────────

(deftest idempotency-replays-bytes
  (let [tid (id-of (req :post "/api/tasks" {:title "water plants"}))]
    (testing "a non-idempotent action without a key is 428"
      (let [resp (req :post (str "/api/tasks/" tid "/-/poke"))]
        (is (= 428 (:status resp)))
        (is (= "https://waymark.dev/problems/idempotency-key-required"
               (:type (json resp))))))
    (testing "the same key answers with the first execution's bytes"
      (let [k {"idempotency-key" "http-key-1"}
            first-run (req :post (str "/api/tasks/" tid "/-/poke") nil k)
            replay (req :post (str "/api/tasks/" tid "/-/poke") nil k)]
        (is (= 200 (:status first-run)))
        (is (= 200 (:status replay)))
        (is (= (:body first-run) (:body replay)) "byte-identical replay")
        (is (= "application/waymark+json" (ctype replay)))
        (is (= 1 (get-in (json replay) [:data :pokes])) "poked once, not twice")))))

;; ── 8. dry-run ──────────────────────────────────────────────────────

(deftest dry-run-changes-nothing
  (let [pid (id-of (create-plan! "2026-08-11" ["2026-08-11"]))
        before (req :get (str "/api/plans/" pid))
        resp (*h* {:request-method :post
                   :uri (str "/api/plans/" pid "/-/assign_meal")
                   :query-string "dry_run=1"
                   :headers {"x-waymark-principal" "colton"}
                   :body (wire/write-json {:date "2026-08-11" :meal_id "m-x"})})
        after (req :get (str "/api/plans/" pid))]
    (is (= 200 (:status resp)))
    (is (= {:valid true} (json resp)))
    (is (= (:body before) (:body after)) "the row did not move")))

;; ── 9. the collection ───────────────────────────────────────────────

(deftest collection-lists-with-affordances
  (let [pid (id-of (create-plan! "2026-08-18" ["2026-08-18"]))
        resp (req :get "/api/plans")
        b (json resp)]
    (is (= 200 (:status resp)))
    (is (= "application/waymark+json" (ctype resp)))
    (is (= "plan_collection" (:kind b)))
    (is (= "/api/plans" (:self b)))
    (is (= (count (get-in b [:data :items])) (get-in b [:data :total])))
    (let [item (some #(when (= (str "/api/plans/" pid) (:self %)) %)
                     (get-in b [:data :items]))]
      (is (some? item))
      (is (not (contains? item :data)) "depth summary drops the document")
      (is (= "draft" (:state item)))
      (is (contains? (:actions item) :assign_meal) "per-row affordances ride along")
      (is (contains? (:unavailable item) :finalize)))))

;; ── 10. 404s ────────────────────────────────────────────────────────

(deftest not-found-problems
  (testing "unknown id"
    (let [resp (req :get "/api/plans/nope")]
      (is (= 404 (:status resp)))
      (is (= "application/problem+json" (ctype resp)))
      (is (= "https://waymark.dev/problems/not-found" (:type (json resp))))))
  (testing "unknown action"
    (let [pid (id-of (create-plan! "2026-08-25" ["2026-08-25"]))
          resp (req :post (str "/api/plans/" pid "/-/zap"))]
      (is (= 404 (:status resp)))
      (is (= "plan has no action zap." (:detail (json resp))))))
  (testing "unknown plural and unknown route"
    (is (= 404 (:status (req :get "/api/widgets"))))
    (is (= 404 (:status (req :post "/api/widgets" {:x 1}))))
    (is (= 404 (:status (req :get "/nope"))))))
