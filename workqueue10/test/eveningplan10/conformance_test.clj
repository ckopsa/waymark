(ns eveningplan10.conformance-test
  "All three kinds enrolled in the waymark10 conformance library: the
  machine walks itself, and every framework promise — envelope shape,
  affordance completeness, unavailable honesty, token prose, input
  schemas, folded enums, collection shape, replay history — is
  checked over the real ring handler. Mirrors
  mealplan10.conformance-test's shape; the registrations are the only
  domain-specific part:

  - evening_session's :create needs a real :plan_id (a ref with no
    acceptance set to guide generation — the same reason prep_task
    registers one for its own :plan_id).
  - evening_session's :preparing/:active/:complete states need
    window_minutes set (a :while-open field, not a create input)
    before :lock-in's schedule-fits guard allows it — the generic
    shortest-path walk can't discover \"call a self-loop editor
    first,\" so it's a hand-written state factory, same reason
    mealplan10's :plan/:grocery_list have one.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [eveningplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.dev :as dev]
            [waymark10.machine :as machine]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.router :as router]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ["activities" "evening_plans" "evening_sessions"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources)})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def kinds [:activity :evening_plan :evening_session])

;; ── the enrollment ──────────────────────────────────────────────────

(def ^:private plan-start "2026-01-06")

(defn- mk! [eng kind body]
  (:row (inv/create! eng kind body {:principal (fac/walker-principal)})))

(defn- step!
  "mealplan10's own step! never needed :if-match — none of its actions
  are fenced (that app doesn't use the :fields sugar's generated
  editors). evening_session's update_fields_in_* / update_support_in_*
  are, so this one supplies the current etag, same as dev/act!."
  ([eng kind id action] (step! eng kind id action nil))
  ([eng kind id action body]
   (let [adef (get-in (get (inv/resources eng) kind) [:actions action])
         opts (if (get-in adef [:safety :fence])
                {:principal (fac/walker-principal)
                 :if-match (inv/etag kind (str id)
                                     (:version (dev/row eng kind id)))}
                {:principal (fac/walker-principal)})]
     (inv/invoke! eng kind id action body opts))))

;; evening_session's :plan_id carries no acceptance set (a create
;; input can't be guided by the target's own enum the way a
;; :capacity/:one-of field can) — waymark9/mealplan10 register the
;; identical shape of example for prep_task's plan_id
(fac/example-input! :evening_session :create
  (fn [eng]
    (let [plan (mk! eng :evening_plan {:start_date plan-start
                                       :end_date "2026-01-10"})]
      {:plan_id (:id plan) :date plan-start})))

(fac/state-factory! :evening_session
  (fn [eng target]
    (let [plan (mk! eng :evening_plan {:start_date plan-start
                                       :end_date "2026-01-10"})
          s (mk! eng :evening_session {:plan_id (:id plan) :date plan-start})
          sid (:id s)]
      (case target
        :staged s
        (do (step! eng :evening_session sid :update_fields_in_staged
                   {:window_minutes 90})
            (let [res (step! eng :evening_session sid :lock-in)]
              (case target
                :preparing (:row res)
                :active (:row (step! eng :evening_session sid :start))
                :complete (do (step! eng :evening_session sid :start)
                              (:row (step! eng :evening_session sid :finish))))))))))

;; ── request sugar (the conformance-http pattern) ────────────────────

(def walker-headers
  {"x-waymark-principal" "walker" "x-waymark-actor-type" "system"})

(defn- req
  ([method uri] (req method uri nil walker-headers))
  ([method uri body] (req method uri body walker-headers))
  ([method uri body headers]
   (*h* (cond-> {:request-method method :uri uri :headers (or headers {})}
          body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- ctype [resp] (get-in resp [:headers "Content-Type"]))

(defn- rdef [kind] (get (inv/resources *eng*) kind))
(defn- self-of [kind id] (str "/api/" (:plural (rdef kind)) "/" id))
(defn- get-env [kind id] (req :get (self-of kind id)))

(defn- action-def [kind aname]
  (some-> (get-in (rdef kind) [:actions aname]) (assoc :name aname)))

(defn- declared-name [kind wire-kw]
  (or (some #(when (= wire-kw (conf/wire-name (:name %))) (:name %))
            (machine/actions-seq (rdef kind)))
      wire-kw))

(defn- invoke-http
  "POST an action the way an honest client would: a fresh key when
  non-idempotent, the current ETag when fenced."
  [kind id aname body & [{:keys [headers query]}]]
  (let [a (action-def kind aname)
        etag (when (get-in a [:safety :fence])
               (get-in (get-env kind id) [:headers "ETag"]))
        hs (merge walker-headers
                  (when (and a (not (get-in a [:safety :idempotent])))
                    {"idempotency-key" (str (random-uuid))})
                  (when etag {"if-match" etag})
                  headers)]
    (*h* (cond-> {:request-method :post
                  :uri (str (self-of kind id) "/-/" (name aname))
                  :headers hs}
           query (assoc :query-string query)
           body (assoc :body (wire/write-json body))))))

;; ── staging: one walked row per kind × reachable state ──────────────

(def ^:private staged (atom {}))

(defn- row-in-state [kind state]
  (let [k [kind state]
        out (or (get @staged k)
                (let [out (fac/walk-to-state *eng* kind state {:seed 97})]
                  (swap! staged assoc k out)
                  out))]
    (when-not (:skip out) out)))

(defn- states-with-rows [kind]
  (for [state (sort (machine/reachable-states (rdef kind)))
        :let [row (row-in-state kind state)]
        :when row]
    [state row]))

;; ── 1. every kind walks every reachable state ───────────────────────

(deftest staging-honesty
  (doseq [kind kinds
          state (sort (machine/reachable-states (rdef kind)))]
    (testing (str (name kind) " → " (name state))
      (is (some? (row-in-state kind state))
          (str (name kind) " → " (name state) " skipped: "
               (get-in @staged [[kind state] :skip :reason]))))))

;; ── 2. envelope shape on the wire ───────────────────────────────────

(deftest envelope-shape
  (let [violations
        (vec
         (for [kind kinds
               [state row] (states-with-rows kind)
               :let [resp (get-env kind (:id row))
                     env (json resp)
                     where (str (name kind) "@" (name state))]
               v (concat
                  (when (not= 200 (:status resp))
                    [(str where ": GET returned " (:status resp))])
                  (when (not= router/media-type (ctype resp))
                    [(str where ": Content-Type " (ctype resp))])
                  (conf/envelope-violations
                   env {:kind kind :state state
                        :etag-header (get-in resp [:headers "ETag"])
                        :law? true})
                  (let [again (json (req :get (:self env)))]
                    (when (not= (get-in env [:meta :version])
                                (get-in again [:meta :version]))
                      [(str where ": version changed across an idle round-trip")])))]
           v))]
    (is (empty? violations) (str/join "\n" violations))))

;; ── 3. affordance completeness (+ concealment on the wire) ──────────

(deftest affordance-completeness
  (let [violations
        (vec
         (for [kind kinds
               [state row] (states-with-rows kind)
               [pname headers] {:anonymous {} :walker walker-headers}
               :let [env (json (req :get (self-of kind (:id row)) nil headers))
                     where (str (name kind) "@" (name state)
                                " as " (name pname))]
               v (concat
                  (conf/affordance-violations (rdef kind) env)
                  (for [aname (conf/hidden-actions (rdef kind) env)
                        :let [resp (req :post (str (self-of kind (:id row))
                                                   "/-/" (name aname))
                                        nil headers)]
                        :when (not= 404 (:status resp))]
                    (str where ": hidden " (name aname) " answered "
                         (:status resp) ", expected 404")))]
           v))]
    (is (empty? violations) (str/join "\n" violations))))

;; ── 4. unavailable honesty: advertisement = enforcement ─────────────

(deftest unavailable-honesty
  (let [refused-checked (atom 0)
        violations
        (vec
         (apply concat
                (for [kind kinds
                      [state row] (states-with-rows kind)
                      :let [env (json (get-env kind (:id row)))
                            where (str (name kind) "@" (name state))]]
                  (concat
                   (conf/unavailable-violations env)
                   (apply concat
                          (for [[wname entry] (:unavailable env)
                                :let [aname (declared-name kind wname)
                                      a (action-def kind aname)
                                      ctx (fac/probe-ctx *eng*)
                                      body (fac/synthesize-input
                                            *eng* (rdef kind) a row ctx
                                            {:seed 3})]
                                :when (not (and (nil? body) (:input a)
                                                (fac/skip-reason)))]
                            (let [resp (invoke-http kind (:id row) aname body)
                                  b (json resp)]
                              (cond
                                (= 200 (:status resp))
                                (when (not= (name (:to a)) (:state b))
                                  [(str where "." (name aname)
                                        ": advertised unavailable but invoked "
                                        "200 into " (:state b))])

                                (not= 409 (:status resp))
                                [(str where "." (name aname)
                                      ": advertised unavailable but invoking "
                                      "answered " (:status resp))]

                                :else
                                (concat
                                 (do (swap! refused-checked inc) nil)
                                 (when (not= (:reason entry) (:detail b))
                                   [(str where "." (name aname)
                                         ": advertisement " (pr-str (:reason entry))
                                         " ≠ enforcement " (pr-str (:detail b)))])
                                 (when (str/includes? (str (:detail b)) "{")
                                   [(str where "." (name aname)
                                         ": problem detail holds an unresolved "
                                         "{placeholder}")]))))))))))]
    (is (empty? violations) (str/join "\n" violations))
    (is (pos? @refused-checked)
        "at least one advertised refusal was enforced over the wire")))

;; ── 5. token prose + input schemas ──────────────────────────────────

(deftest token-prose-and-input-schemas
  (let [violations
        (vec
         (for [kind kinds
               [_state row] (states-with-rows kind)
               :let [env (json (get-env kind (:id row)))]
               v (concat (conf/prose-violations (rdef kind) env)
                         (conf/input-schema-violations env))]
           v))]
    (is (empty? violations) (str/join "\n" violations))))

;; ── 6. collection honesty ───────────────────────────────────────────
;; (mealplan10's suite also has a folded-enums-are-live test — folded
;; enums come from an acceptance-set :accepts guard, like the old
;; date-in-plan design; none of this app's guards are that shape
;; (date-in-plan-range is a cross-resource create-guard, schedule-fits
;; reads stored data), so there's nothing for that check to exercise)

(deftest collection-honesty
  (let [violations
        (vec
         (for [kind kinds
               :let [_ (row-in-state kind (:initial (rdef kind)))
                     resp (req :get (str "/api/" (:plural (rdef kind))))
                     b (json resp)]
               v (concat
                  (when (not= 200 (:status resp))
                    [(str (name kind) " collection: GET " (:status resp))])
                  (conf/collection-envelope-violations b {:kind kind})
                  (apply concat
                         (for [item (take 3 (get-in b [:data :items]))]
                           (for [wname (keys (:actions item))
                                 :let [aname (declared-name kind wname)
                                       id (last (str/split (:self item) #"/"))
                                       r (invoke-http kind id aname nil
                                                      {:query "dry_run=1"})]
                                 :when (= 404 (:status r))]
                             (str (:self item) ": advertised " (name wname)
                                  " answered 404 on its own row")))))]
           v))]
    (is (empty? violations) (str/join "\n" violations))))

;; ── 8. replay history: every logged edge is in its stored law ───────

(deftest replay-history
  (doseq [kind kinds]
    (row-in-state kind (:initial (rdef kind))))
  (let [violations (conf/replay-violations *eng*)]
    (is (empty? violations) (str/join "\n" (map pr-str violations)))))
