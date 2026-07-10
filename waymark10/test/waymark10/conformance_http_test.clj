(ns waymark10.conformance-http-test
  "Phase-4b conformance: the envelope obligations — generic assertions
  that the WIRE keeps the declaration's promises, driven as ring
  request maps through the real handler (no live server). The suite
  knows Waymark, not meal plans: obligations parametrize over the
  enrolled kinds, and each deftest folds every broken promise into a
  vector of violation strings so a red run names them all at once.
  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.

  Suite-local kinds provoke the shapes the fixtures don't:
  - errand: a non-idempotent action (the 428/byte-replay half of
    safety-truth) and a warning guard (the acknowledge problem shape)
  - vault: a hide-flagged guard (concealment on the wire), an
    always-denying guard (unavailable honesty), and a bulk action
    (excluded from the envelope per render's behavior)
  - permit (adopt-truth only): adoption :never, so a promoted law
    leaves a lagging row that must advertise adopt."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.router :as router]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── suite-local kinds ───────────────────────────────────────────────

(r/defhandler errand-run [row _inp _ctx]
  (update-in row [:data :runs] (fnil inc 0)))

(def ^:private errand-risky
  (g/expr {:name :errand-risky
           :severity :warning
           :when '(not (data :risky))
           :explain "This errand is flagged risky."}))

(def errand
  (r/resource
   {:kind :errand
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:risky {:optional true} [:maybe :boolean]]
             [:runs {:optional true} [:maybe :int]]]
    :actions
    {:run {:from #{:open} :to :open
           :safety {:idempotent false :reversible true :confirm false}
           :handler errand-run}
     :finish {:from #{:open} :to :done
              :guards [errand-risky]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished errand is history."}}}}))

(def ^:private sealed-eyes-only
  (g/guard {:name :sealed-eyes-only
            :explain "The crack door does not exist for you."
            :hide true
            :check (fn [_ _ _] (t/deny))}))

(def ^:private audit-window
  (g/guard {:name :audit-window
            :explain "The audit window is closed."
            :check (fn [_ _ _] (t/deny))}))

(def vault
  (r/resource
   {:kind :vault
    :states [:open :sealed :emptied]
    :initial :open
    :terminal #{:emptied}
    :summary "{data.label} · {state}"
    :schema [:map [:label [:string {:min 1 :max 40}]]]
    :actions
    {:seal {:from #{:open} :to :sealed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A sealed vault stays sealed."}}
     :audit {:from #{:open} :to :open
             :guards [audit-window]
             :safety {:idempotent true :reversible true :confirm false}}
     :crack {:from #{:sealed} :to :emptied
             :guards [sealed-eyes-only]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A cracked vault is empty."}}
     :purge {:from #{:open :sealed} :to :emptied
             :bulk true
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "Every vault in the filter empties at once."}}}}))

;; plan: same enrollment as the factory-level suite — a past, covered
;; week so finalize and begin are walkable
(fac/example-input! :plan :create
  {:start_date "2025-01-06" :weeks 1
   :days [{:date "2025-01-06" :eating_out true}
          {:date "2025-01-07" :eating_out true}]})

;; ── the world: one law-governed engine, one handler ─────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ["meals" "plans" "errands" "vaults" "permits" "definitions"
   "waymark10_transitions" "waymark10_idempotency"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources [fx/meal fx/plan errand vault]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def kinds [:meal :plan :errand :vault])

;; ── request sugar ───────────────────────────────────────────────────

(def walker-headers
  {"x-waymark-principal" "walker" "x-waymark-actor-type" "system"})

(def principals {:anonymous {} :walker walker-headers})

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
(defn- get-env [kind id headers] (req :get (self-of kind id) nil headers))

(defn- action-def [kind aname]
  (some-> (get-in (rdef kind) [:actions aname]) (assoc :name aname)))

(defn- declared-name
  "The declared keyword behind a wire-keyed envelope name."
  [kind wire-kw]
  (or (some #(when (= wire-kw (conf/wire-name (:name %))) (:name %))
            (machine/actions-seq (rdef kind)))
      wire-kw))

(defn- invoke-http
  "POST an action over the wire the way an honest client would: a
  fresh Idempotency-Key when the action is non-idempotent, the row's
  CURRENT ETag when fenced, extra headers/query on top."
  [kind id aname body & [{:keys [headers query]}]]
  (let [a (action-def kind aname)
        etag (when (get-in a [:safety :fence])
               (get-in (*h* {:request-method :get :uri (self-of kind id)
                             :headers walker-headers})
                       [:headers "ETag"]))
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
;; Cached across obligations — the shape/refusal obligations never
;; move a row (guard 409s, wrong-state 409s, dry-runs and 404s leave
;; the version alone). Obligations that WRITE walk fresh rows.

(def ^:private staged (atom {}))

(defn- row-in-state
  "The cached walked row, or nil on an honest skip (the skip map is
  cached so staging-honesty can name it)."
  [kind state]
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

(deftest staging-honesty
  (testing "the fixture kinds walk every reachable state"
    (doseq [kind [:meal :plan :errand]
            state (sort (machine/reachable-states (rdef kind)))]
      (is (some? (row-in-state kind state))
          (str (name kind) " → " (name state) " skipped: "
               (get-in @staged [[kind state] :skip :reason])))))
  (testing "the locked door skips honestly, naming action and fix"
    (is (nil? (row-in-state :vault :emptied)))
    (let [reason (get-in @staged [[:vault :emptied] :skip :reason])]
      (is (re-find #"crack" (str reason)))
      (is (re-find #"example-input!" (str reason))))))

;; ── 1. envelope-shape ───────────────────────────────────────────────

(deftest envelope-shape
  (let [violations
        (vec
         (for [kind kinds
               [state row] (states-with-rows kind)
               :let [resp (get-env kind (:id row) walker-headers)
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
                  ;; self round-trips: GET self answers the same
                  ;; document identity
                  (let [again (json (req :get (:self env)))]
                    (concat
                     (when (not= (:self env) (:self again))
                       [(str where ": self " (:self env) " round-tripped to "
                             (:self again))])
                     (when (not= (get-in env [:meta :version])
                                 (get-in again [:meta :version]))
                       [(str where ": version changed across an idle round-trip")]))))]
           v))]
    (is (empty? violations) (str/join "\n" violations))))

;; ── 2. affordance-completeness ──────────────────────────────────────
;;
;; GAP (assert-as-is): a hide-concealed action whose :from INCLUDES
;; the row's state is omitted from the envelope but, invoked
;; directly, refuses 409 through the guard loop — invoke! only maps
;; a hide deny to 404 in its out-of-state branch, so the wire
;; narrates (and leaks the reason of) a door the envelope conceals.
;; Spec: 404, never 409. Out-of-state hidden actions 404 correctly.

(deftest affordance-completeness
  (let [hidden-checked (atom 0)
        violations
        (vec
         (for [kind kinds
               [state row] (states-with-rows kind)
               [pname headers] principals
               :let [env (json (get-env kind (:id row) headers))
                     where (str (name kind) "@" (name state)
                                " as " (name pname))]
               v (concat
                  (conf/affordance-violations (rdef kind) env)
                  ;; concealment on the wire: each hidden action,
                  ;; invoked directly, must not exist
                  (for [aname (conf/hidden-actions (rdef kind) env)
                        :let [a (action-def kind aname)
                              in-state? (contains? (:from a) state)
                              _ (swap! hidden-checked inc)
                              resp (invoke-http kind (:id row) aname nil
                                                {:headers headers})
                              expected (if in-state? 409 404)] ; 409 = the GAP above
                        :when (not= expected (:status resp))]
                    (str where ": hidden " (name aname) " answered "
                         (:status resp) ", expected " expected)))]
           v))]
    (is (empty? violations) (str/join "\n" violations))
    (is (pos? @hidden-checked)
        "the staging provoked at least one concealed action")))

;; ── 3. unavailable-honesty ──────────────────────────────────────────

(deftest unavailable-honesty
  (let [refused-checked (atom 0)
        check-env
        (fn [kind state row env]
          (let [where (str (name kind) "@" (name state))]
            (concat
             (conf/unavailable-violations env)
             (apply concat
                    (for [[wname entry] (:unavailable env)
                          :let [aname (declared-name kind wname)
                                a (action-def kind aname)
                                ctx (fac/probe-ctx *eng*)
                                body (fac/synthesize-input
                                      *eng* (rdef kind) a row ctx {:seed 3})]
                          :when (not (and (nil? body) (:input a)
                                          (fac/skip-reason)))]
                      (let [resp (invoke-http kind (:id row) aname body)
                            b (json resp)]
                        (cond
                          ;; a 200 is honest only as the natural
                          ;; replay: the row already sits at the
                          ;; action's outcome
                          (= 200 (:status resp))
                          (when (not= (name (:to a)) (:state b))
                            [(str where "." (name aname)
                                  ": advertised unavailable but invoked 200 into "
                                  (:state b))])

                          (not= 409 (:status resp))
                          [(str where "." (name aname)
                                ": advertised unavailable but invoking answered "
                                (:status resp))]

                          :else
                          (concat
                           (do (swap! refused-checked inc) nil)
                           (when (not= (:reason entry) (:detail b))
                             [(str where "." (name aname)
                                   ": advertisement " (pr-str (:reason entry))
                                   " ≠ enforcement " (pr-str (:detail b)))])
                           ;; token-prose over the problem surface
                           (when (str/includes? (str (:detail b)) "{")
                             [(str where "." (name aname)
                                   ": problem detail holds an unresolved {placeholder}")])))))))))
        violations
        (vec
         (concat
          (mapcat (fn [kind]
                    (mapcat (fn [[state row]]
                              (check-env kind state row
                                         (json (get-env kind (:id row)
                                                        walker-headers))))
                            (states-with-rows kind)))
                  kinds)
          ;; an uncovered draft: the require-gate's reason must survive
          ;; the wire into the finalize 409
          (let [resp (req :post "/api/plans"
                          {:start_date "2025-02-03" :weeks 1
                           :days [{:date "2025-02-03"}]})
                env (json resp)
                row {:id (last (str/split (:self env) #"/"))}]
            (check-env :plan :draft row env))))]
    (is (empty? violations) (str/join "\n" violations))
    (is (pos? @refused-checked)
        "at least one advertised refusal was enforced over the wire")))

;; ── 4. token-prose-honesty ──────────────────────────────────────────

(deftest token-prose-honesty
  (let [violations
        (vec
         (for [kind kinds
               [_state row] (states-with-rows kind)
               :let [env (json (get-env kind (:id row) walker-headers))]
               v (conf/prose-violations (rdef kind) env)]
           v))]
    (is (empty? violations) (str/join "\n" violations))))

;; ── 5. input-schema-honesty ─────────────────────────────────────────

(deftest input-schema-honesty
  (let [members-checked (atom 0)
        violations
        (vec
         (for [kind kinds
               [state row] (states-with-rows kind)
               :let [env (json (get-env kind (:id row) walker-headers))
                     where (str (name kind) "@" (name state))]
               v (concat
                  (conf/input-schema-violations env)
                  ;; every folded enum member is schema-valid by
                  ;; construction: a dry_run invoke never 422s
                  (apply concat
                         (for [{:keys [action field enum]} (conf/folded-enums env)
                               :let [aname (declared-name kind action)
                                     a (action-def kind aname)
                                     ctx (fac/probe-ctx *eng*)
                                     base (fac/synthesize-input
                                           *eng* (rdef kind) a row ctx {:seed 5})]
                               :when base]
                           (for [member enum
                                 :let [_ (swap! members-checked inc)
                                       resp (invoke-http
                                             kind (:id row) aname
                                             (assoc base field member)
                                             {:query "dry_run=1"})]
                                 :when (= 422 (:status resp))]
                             (str where "." (name aname) ": advertised enum member "
                                  (pr-str member) " for " (name field)
                                  " was refused 422: " (:body resp))))))]
           v))]
    (is (empty? violations) (str/join "\n" violations))
    (is (pos? @members-checked)
        "at least one folded enum member was dry-run over the wire")))

;; ── 6. safety-truth-on-the-wire ─────────────────────────────────────

(deftest safety-truth-on-the-wire
  (testing "the fence: 412 bare, with the fresh etag in the problem"
    (let [row (fac/walk-to-state *eng* :meal :on_list {:seed 111})
          _ (is (not (:skip row)))
          get1 (get-env :meal (:id row) walker-headers)
          etag (get-in get1 [:headers "ETag"])
          env (json get1)]
      (is (true? (get-in env [:actions :update_recipe :safety :fence]))
          "the fixture's edit action advertises the fence")
      (let [bare (req :post (str (:self env) "/-/update_recipe")
                      {:recipe "pork shoulder 1400g, Traeger at 275F"})
            b (json bare)]
        (is (= 412 (:status bare)))
        (is (empty? (conf/problem-violations 412 (ctype bare) b
                                             {:where "meal 412"}))
            (pr-str b))
        (is (= etag (get-in b [:resource :etag]))
            "the 412 problem hints the fresh etag"))
      (testing "with the GET's ETag the write succeeds"
        (let [fenced (req :post (str (:self env) "/-/update_recipe")
                         {:recipe "pork shoulder 1400g, Traeger at 275F"}
                         (assoc walker-headers "if-match" etag))]
          (is (= 200 (:status fenced)))
          (is (= (inc (get-in env [:meta :version]))
                 (get-in (json fenced) [:meta :version])))))))
  (testing "non-idempotent without a key is 428"
    (let [row (fac/walk-to-state *eng* :errand :open {:seed 113})
          resp (req :post (str (self-of :errand (:id row)) "/-/run"))
          b (json resp)]
      (is (= 428 (:status resp)))
      (is (empty? (conf/problem-violations 428 (ctype resp) b
                                           {:where "errand 428"}))
          (pr-str b))))
  (testing "the same key answers with the first execution's bytes"
    (let [row (fac/walk-to-state *eng* :errand :open {:seed 115})
          uri (str (self-of :errand (:id row)) "/-/run")
          hs (assoc walker-headers "idempotency-key" "conf-http-key-1")
          one (req :post uri nil hs)
          two (req :post uri nil hs)]
      (is (= 200 (:status one)))
      (is (= 200 (:status two)))
      (is (= (:body one) (:body two)) "byte-identical replay")
      (is (= router/media-type (ctype two)))
      (is (= 1 (get-in (json two) [:data :runs])) "ran once, not twice"))))

;; ── 7. adopt-truth on the wire ──────────────────────────────────────

(defn- permit-resource
  "adoption :never, with the close gate's law as the parameter — the
  two-deploy shape that leaves a row lagging the promoted law."
  [close-when]
  (r/resource
   {:kind :permit
    :plural "permits"
    :adoption :never
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:ticks {:optional true} [:maybe :int]]]
    :actions
    {:close {:from #{:open} :to :done
             :guards [(g/expr {:name :enough-ticks
                               :when close-when
                               :explain "Not enough ticks yet ({n})."
                               :vars {:n '(data :ticks)}})]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed permit is history."}}}}))

(defn- with-permit-engine [close-when f]
  (let [st (pg/storage db/dsn)]
    (try
      (let [eng (engine/engine {:storage st
                                :resources [(permit-resource close-when)]})]
        (f eng (engine/handler eng)))
      (finally (pg/close! st)))))

(deftest adopt-truth-on-the-wire
  (let [pid (atom nil)]
    ;; deploy 1: the strict gate; one row born under revision 1
    (with-permit-engine '(<= 3 (data :ticks))
      (fn [_eng h]
        (let [resp (h {:request-method :post :uri "/api/permits"
                       :headers walker-headers
                       :body (wire/write-json {:title "p1" :ticks 2})})]
          (is (= 201 (:status resp)))
          (reset! pid (last (str/split (:self (wire/read-json (:body resp)))
                                       #"/"))))))
    ;; deploy 2: the lenient gate auto-promotes; revision 1
    ;; grandfathers (adoption :never) and the row lags
    (with-permit-engine '(<= 1 (data :ticks))
      (fn [eng h]
        (let [uri (str "/api/permits/" @pid)
              get1 (h {:request-method :get :uri uri :headers walker-headers})
              env (json get1)]
          (testing "the lagging row advertises adopt and judges under
                    its birth law"
            (is (= 1 (get-in env [:meta :law_revision])))
            (is (= (str uri "/-/adopt") (get-in env [:actions :adopt :href])))
            (is (= {:to "open"} (get-in env [:actions :adopt :effect])))
            (is (= "Not enough ticks yet (2)."
                   (get-in env [:unavailable :close :reason])))
            (let [vs (conf/affordance-violations
                      (get (inv/resources eng) :permit) env
                      {:adopt-expected? true})]
              (is (empty? vs) (str/join "\n" vs))))
          (testing "invoking adopt restamps and the advertisement disappears"
            (let [resp (h {:request-method :post :uri (str uri "/-/adopt")
                           :headers walker-headers})
                  env' (json resp)]
              (is (= 200 (:status resp)))
              (is (= 2 (get-in env' [:meta :law_revision]))
                  "meta.law_revision advanced")
              (is (= (inc (get-in env [:meta :version]))
                     (get-in env' [:meta :version])))
              (is (not (contains? (:actions env') :adopt)))
              (is (not (contains? (:unavailable env') :adopt)))
              (is (contains? (:actions env') :close)
                  "the adopted row judges under the lenient law"))))))))

;; ── 8. problem-shape ────────────────────────────────────────────────

(deftest problem-shape
  (let [plan-row (row-in-state :plan :draft)
        vault-row (row-in-state :vault :open)
        errand-resp (req :post "/api/errands" {:title "risky one" :risky true})
        errand-id (last (str/split (:self (json errand-resp)) #"/"))
        provocations
        [["400 malformed body"
          (*h* {:request-method :post :uri "/api/meals"
                :headers walker-headers :body "{not json"})]
         ["404 unknown id" (req :get "/api/plans/nope")]
         ["404 unknown action"
          (invoke-http :plan (:id plan-row) :zap nil)]
         ["409 wrong state"
          (invoke-http :plan (:id plan-row) :reopen nil)]
         ["409 guard refused"
          (invoke-http :vault (:id vault-row) :audit nil)]
         ["409 acknowledgement required"
          (invoke-http :errand errand-id :finish nil)]
         ["412 fence" (req :post (str (self-of :meal (:id (row-in-state
                                                          :meal :on_list)))
                                      "/-/update_recipe")
                       {:recipe "x"})]
         ["422 schema invalid"
          (invoke-http :plan (:id plan-row) :assign_meal
                       {:date "not-a-date" :meal_id "m" :evil 1})]
         ["428 key required"
          (req :post (str (self-of :errand errand-id) "/-/run"))]]
        violations
        (vec
         (mapcat (fn [[who resp]]
                   (let [b (json resp)]
                     (concat
                      (when (= 200 (:status resp))
                        [(str who ": the provocation answered 200")])
                      (conf/problem-violations (:status resp) (ctype resp) b
                                               {:where who}))))
                 provocations))]
    (is (= 201 (:status errand-resp)))
    (is (empty? violations) (str/join "\n" violations))
    (testing "the acknowledge protocol names its warnings"
      (let [b (json (invoke-http :errand errand-id :finish nil))]
        (is (= ["errand-risky"] (get-in b [:acknowledge :names])))
        (is (= "This errand is flagged risky." (-> b :warnings first :reason)))))
    (testing "the 422 keys its errors by field"
      (let [b (json (invoke-http :plan (:id plan-row) :assign_meal
                                 {:date "2025-01-06" :meal_id "m" :evil 1}))]
        (is (= ["disallowed key"] (get-in b [:errors :evil])))))))

;; ── 9. collection-honesty ───────────────────────────────────────────

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
                  (when (not= (str (name kind) "_collection") (:kind b))
                    [(str (name kind) " collection kind: " (:kind b))])
                  (apply concat
                         (for [item (take 5 (get-in b [:data :items]))]
                           (concat
                            (conf/collection-item-violations item)
                            ;; row affordances are honest: an advertised
                            ;; action invoked on that row never 404s
                            (for [wname (keys (:actions item))
                                  :let [aname (declared-name kind wname)
                                        id (last (str/split (:self item) #"/"))
                                        r (invoke-http kind id aname nil
                                                       {:query "dry_run=1"})]
                                  :when (= 404 (:status r))]
                              (str (:self item) ": advertised " (name wname)
                                   " answered 404 on its own row"))))))]
           v))]
    (is (empty? violations) (str/join "\n" violations))))
