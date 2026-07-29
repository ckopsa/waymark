(ns workqueue10.conformance-test
  "The queue's two kinds enrolled in the waymark10 conformance
  library: the machine walks itself, and every framework promise —
  envelope shape, affordance completeness, unavailable honesty, token
  prose, input schemas, collection shape, replay history — is checked
  over the real ring handler. Mirrors choreplan10.conformance-test's
  shape; the registrations are the only domain-specific part:

  - task is a Mirror, so it registers the same pair choreplan's
    prep_task and paydesk's mirrors do: an external-identity create and a
    wire-shaped observe_external document (generation would invent
    non-JSON). The create's external id carries a SOURCE TAG
    (\"chore:walk-…\") — every row of this kind is born through the
    confluence, and an untagged id would refuse at the routing seam.
  - task_list is the pull-only Mirror beside it (the list a task
    belongs to, as a row): the same pair, no local writes at all.
  - its :complete pushes through main's module fake sources, whose
    push treats a never-seeded doc as an open task — the walker's
    rows push clean (the FakeFeed auto-vivify spirit).
  - no state factories: no action gates a transition behind a guard
    the walk can't satisfy, so the generic shortest-path walk reaches
    every state on its own.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.main :as main]
            [waymark10.machine :as machine]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
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
  ;; the WHOLE folded registry's tables (task_queue_test's rule, and
  ;; the reason it exists): this engine boots every kind main/resources
  ;; declares, chore and meal included, so a fixture that drops only
  ;; "tasks" boots into whatever shape another suite left behind —
  ;; a promoted column added to a folded kind refuses at boot
  ["tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "members" "roles" "grants" "approval_requests"
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
        ;; :suppress-mirror-refresh — a Mirror breaks the walker's
        ;; reads-are-pure assumption (a GET on a staged stale row
        ;; would heal it to fresh under the assertions); production
        ;; reads pull through, only this fixture suppresses.
        ;; with-push mirrors production wiring (main/start!): the
        ;; task kind's :complete pushes through the fakes
        (let [eng (mirror/with-push
                   (engine/engine {:storage st
                                   :resources (main/check-resources)
                                   :suppress-mirror-refresh true}))]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def kinds [:task :task_list :media])

;; ── the enrollment ──────────────────────────────────────────────────

;; a :create-push mirror's create speaks the CREATE-SCHEMA (the birth
;; input is the author's law): the walker's captures push through
;; main's fake todo source, which mints the identity claim_external
;; stamps back — so walked rows are real mirror rows end to end
(fac/example-input! :task :create
  (fn [_] {:title (str "walked capture " (random-uuid))}))

(fac/example-input! :task :observe_external
  {:document {:title "Dishes"
              :source "chore"
              :assignee_name "colton"
              :due_at "2026-01-07T00:00:00Z"
              :status "open"
              :detail "load and run before bed"
              :list_key "todo:todo.woodworking"}
   :etag "conformance-etag-1"})

;; :task_list is the PULL-ONLY half of the pair (no local writes —
;; the queue mirrors the household's lists and never writes them,
;; though the NATIVE birth door stands beside the mirrors since
;; waymark-fnl), so it registers the plain mirror shape: an
;; external-identity create carrying the confluence's source tag
;; (the paired birth law reads it as mirrored), and a wire-shaped
;; document
(fac/example-input! :task_list :create
  (fn [_] {:external_id (str "gtasks:walk-" (random-uuid))}))

(fac/example-input! :task_list :observe_external
  {:document {:title "Woodworking" :source "gtasks"}
   :etag "conformance-etag-1"})

;; :media is the SECOND confluence's kind and registers the same
;; :create-push pair the task does: the walker's captures are hub
;; births (main's noop hub source mints the identity claim_external
;; stamps back — spec-media.md's shape 1), and the observed document
;; is the flickr addendum's verified live shape, wire-shaped
(fac/example-input! :media :create
  (fn [_] {:title (str "walked queue " (random-uuid)) :medium "movie"}))

(fac/example-input! :media :observe_external
  {:document {:title "12 Angry Men"
              :medium "movie"
              :status "active"
              :year 1957
              :progress 0.0137M
              :progress_text "1:19"
              :work_key "movie:12-angry-men-1957"
              :audience_name "Colton"
              :source "flickr"
              :source_ui_href "https://stream.kopsa.info/#/item/51"}
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

;; ── 7. replay history: every logged edge is in its stored law ───────

(deftest replay-history
  (doseq [kind kinds]
    (row-in-state kind (:initial (rdef kind))))
  (let [violations (conf/replay-violations *eng*)]
    (is (empty? violations) (str/join "\n" (map pr-str violations)))))
