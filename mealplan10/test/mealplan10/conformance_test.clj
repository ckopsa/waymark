(ns mealplan10.conformance-test
  "All six kinds enrolled in the waymark10 conformance library: the
  machine walks itself, and every framework promise — envelope shape,
  affordance completeness, unavailable honesty, token prose, input
  schemas, replay history, collection shape, the bulk report — is
  checked over the real ring handler. The suite knows Waymark, not
  meal plans; these registrations are the enrollment:

  - state factories for plan and grocery_list (waymark9's
    @state_factory, ported): finalize's require gate needs seven
    mark_eating_out self-loops a shortest-path walk cannot spell.
  - create examples for prep_task (a real plan_id ref) and event
    (external_id identity), an observe_external document example
    (generation would invent non-JSON documents), and an
    assign_off_theme example (its meal_id has no acceptance set to
    guide generation — waymark9 registered the same one).
  - the engine boots with :suppress-mirror-refresh (waymark9's
    _suppress_mirror_refresh): a Mirror breaks the walker's
    reads-are-pure assumption — a GET on a staged stale event would
    heal it to fresh under the assertions. Production reads pull
    through; only this fixture suppresses.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mealplan10.event-source :as es]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
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
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists" "prep_tasks"
   "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (es/fake-events)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources feed)
                                  :suppress-mirror-refresh true})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def kinds [:meal :meal_line :rotation :plan :plan_day :grocery_list
            :prep_task :ingredient :product :substitution :event])

;; ── the enrollment ──────────────────────────────────────────────────

(def ^:private plan-start "2026-01-06")   ; a Tuesday, safely past

(defn- mk! [eng kind body]
  (:row (inv/create! eng kind body {:principal (fac/walker-principal)})))

(defn- step!
  ([eng kind id action] (step! eng kind id action nil))
  ([eng kind id action body]
   (inv/invoke! eng kind id action body
                {:principal (fac/walker-principal)
                 :acknowledged #{:calendar-clear}})))

(defn- listed-meal! [eng]
  (let [m (mk! eng :meal {:name "Tacos al pastor" :themes ["mexican"]})]
    (step! eng :meal (:id m) :accept)
    (:id m)))

(fac/example-input! :plan :create
  {:start_date plan-start :weeks 1})

;; its meal_id carries no acceptance set (the off-theme door exists to
;; escape the theme narrowing), so generation cannot guide it —
;; waymark9 registered the identical example, one kind lower now
(fac/example-input! :plan_day :assign_off_theme
  (fn [eng] {:meal_id (listed-meal! eng)}))

;; no :create example: a plan births its WHOLE week (the unique
;; (plan_id, date) index holds every in-range slot), so the walker
;; stages days through the factory below — the same posture as plan
;; and grocery_list, whose creates also happen inside their factories.
(fac/state-factory! :plan_day
  (fn [eng target]
    (let [rot (mk! eng :rotation {})
          _ (step! eng :rotation (:id rot) :activate)
          _ (listed-meal! eng)
          plan (mk! eng :plan {:start_date plan-start :weeks 1
                               :rotation_id (:id rot)})
          [d1 d2] (store/with-tx (:storage eng)
                    (fn [tx] (store/query-rows (:storage eng) tx :plan_day
                                               {:plan_id (:id plan)}
                                               {:limit 3})))]
      (case target
        :undecided (inv/decode-row (get (inv/resources eng) :plan_day) d1)
        ;; assign is a fenced edit now — the factory speaks the etag
        :planned (:row (inv/invoke! eng :plan_day (:id d1) :assign_off_theme
                                    {:meal_id (listed-meal! eng)}
                                    {:principal (fac/walker-principal)
                                     :if-match (inv/etag :plan_day (:id d1)
                                                         (:version d1))}))
        :eating_out (:row (step! eng :plan_day (:id d2) :mark_eating_out
                                 {:where "out"}))))))

(fac/state-factory! :plan
  (fn [eng target]
    (let [rot (mk! eng :rotation {})
          _ (step! eng :rotation (:id rot) :activate)
          _ (listed-meal! eng)
          plan (mk! eng :plan {:start_date plan-start :weeks 1
                               :rotation_id (:id rot)})
          pid (:id plan)]
      (case target
        :draft plan
        :abandoned (:row (step! eng :plan pid :abandon))
        (do (doseq [d (store/with-tx (:storage eng)
                        (fn [tx] (store/query-rows (:storage eng) tx
                                                   :plan_day {:plan_id pid}
                                                   {:limit 20})))]
              (step! eng :plan_day (:id d) :mark_eating_out nil))
            (let [res (step! eng :plan pid :finalize)]
              (case target
                :planned (:row res)
                :active (:row (step! eng :plan pid :begin))
                :done (do (step! eng :plan pid :begin)
                          (:row (step! eng :plan pid :complete))))))))))

(fac/state-factory! :grocery_list
  (fn [eng target]
    (let [plan ((get @fac/state-factories :plan) eng :planned)
          g (mk! eng :grocery_list {:plan_id (:id plan)})
          gid (:id g)
          items [{:name "tortillas"} {:name "pork shoulder 1400g"}]]
      (doseq [item items]
        (step! eng :grocery_list gid :add_item item))
      (case target
        :draft (:row (step! eng :grocery_list gid :add_item {:name "limes"}))
        :ready (:row (step! eng :grocery_list gid :finalize))
        :done (do (step! eng :grocery_list gid :finalize)
                  (step! eng :grocery_list gid :check_item {:name "tortillas"})
                  (step! eng :grocery_list gid :check_item
                         {:name "pork shoulder 1400g"})
                  (:row (step! eng :grocery_list gid :complete)))))))

(fac/example-input! :prep_task :create
  (fn [eng]
    (let [plan ((get @fac/state-factories :plan) eng :planned)]
      {:plan_id (:id plan) :date plan-start :meal_name "Tacos al pastor"
       :task_type "prep" :due_at "2026-01-06T12:00:00Z"})))

(fac/example-input! :event :create
  (fn [_] {:external_id (str "walk-" (random-uuid))}))

;; ── the pantry quartet: refs (and the distinct create gate) defeat
;;    generation, so every create is an example; absorb and rematch
;;    mint their own peers ────────────────────────────────────────────

(defn- active-ingredient! [eng nm]
  (let [i (mk! eng :ingredient {:name nm})]
    (step! eng :ingredient (:id i) :accept)
    (:id i)))

(fac/example-input! :ingredient :create
  (fn [_] {:name (str "Chicken thighs " (random-uuid))}))

(fac/example-input! :ingredient :absorb
  (fn [eng] {:duplicate_id (active-ingredient!
                            eng (str "Chicken thigh " (random-uuid)))}))

(fac/example-input! :product :create
  (fn [eng]
    {:ingredient_id (active-ingredient! eng (str "Thighs " (random-uuid)))
     :store "costco"
     :name "Kirkland Organic Chicken Thighs"
     :package_grams 2720
     :sightings [{:seen_on "2026-01-02" :price_cents 1899
                  :source "receipt"}]}))

(fac/example-input! :product :rematch
  (fn [eng] {:ingredient_id (active-ingredient!
                             eng (str "Rematched " (random-uuid)))}))

(fac/example-input! :meal_line :create
  (fn [eng]
    {:meal_id (listed-meal! eng)
     :ingredient_id (active-ingredient! eng (str "Line ing " (random-uuid)))
     :grams 1400}))

(fac/example-input! :substitution :create
  (fn [eng]
    {:from_ingredient_id (active-ingredient!
                          eng (str "Butter " (random-uuid)))
     :to_ingredient_id (active-ingredient!
                        eng (str "Margarine " (random-uuid)))}))

(fac/example-input! :event :observe_external
  {:document {:title "Piano recital" :date "2026-01-08" :kind "note"}
   :etag "conformance-etag-1"})

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
                  ;; the mirror's sync doors are hidden from humans:
                  ;; each concealed action, invoked directly, must not
                  ;; exist for that principal
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

;; ── 6. folded enum members survive a dry run ────────────────────────

(deftest folded-enums-are-live
  (let [members-checked (atom 0)
        violations
        (vec
         (for [kind kinds
               [state row] (states-with-rows kind)
               :let [env (json (get-env kind (:id row)))
                     where (str (name kind) "@" (name state))]
               v (apply concat
                        (for [{:keys [action field enum]} (conf/folded-enums env)
                              :let [aname (declared-name kind action)
                                    a (action-def kind aname)
                                    ctx (fac/probe-ctx *eng*)
                                    base (fac/synthesize-input
                                          *eng* (rdef kind) a row ctx {:seed 5})]
                              :when base]
                          (for [member (take 5 enum)
                                :let [_ (swap! members-checked inc)
                                      resp (invoke-http
                                            kind (:id row) aname
                                            (assoc base field member)
                                            {:query "dry_run=1"})]
                                :when (= 422 (:status resp))]
                            (str where "." (name aname) ": advertised enum "
                                 "member " (pr-str member) " for " (name field)
                                 " was refused 422: " (:body resp)))))]
           v))]
    (is (empty? violations) (str/join "\n" violations))
    (is (pos? @members-checked)
        "at least one folded enum member was dry-run over the wire")))

;; ── 7. collection honesty ───────────────────────────────────────────

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
                  ;; row affordances are honest: an advertised action
                  ;; dry-run on its own row never 404s
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

;; ── 8. the bulk report ──────────────────────────────────────────────

(deftest bulk-report-shape
  (let [m1 (mk! *eng* :meal {:name "Bulk one" :themes ["asian"]})
        m2 (mk! *eng* :meal {:name "Bulk two" :themes ["asian"]})
        resp (req :post "/api/meals/-/accept_many"
                  {:ids [(:id m1) (:id m2)]})
        report (json resp)
        violations (conf/bulk-report-violations
                    report {:action :accept_many :items 2})]
    (is (= 200 (:status resp)))
    (is (empty? violations) (str/join "\n" violations))
    (is (= 2 (get-in report [:data :succeeded])))))

;; ── 9. replay history: every logged edge is in its stored law ───────

(deftest replay-history
  ;; runs over everything the suite (and the walks above) wrote
  (doseq [kind kinds]
    (row-in-state kind (:initial (rdef kind))))
  (let [violations (conf/replay-violations *eng*)]
    (is (empty? violations) (str/join "\n" (map pr-str violations)))))
