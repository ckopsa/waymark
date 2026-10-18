(ns waymark10.server.seed
  "The demo seed's loader (docs/spec-demo-clones.md § 1).

  A SEED IS A CAST AND AN ORDERED LIST OF STEPS, read from the
  classpath (`waymark10/<name>/seed.edn`), so it rides the image built
  for a commit and is versioned with the kinds it names.

  IT LOADS THROUGH THE DOORS, NOT THROUGH STORAGE. Each cast member is
  born through the member door as the registrar, with the member's id
  as the row's id: the create the identity gate makes at first sight.
  Each step is then an ordinary `inv/create!` or `inv/invoke!` with
  the cast member `:as` names as its principal. So the law judges the
  seed, and every seeded row has a history.

  ONE STEP IS NOT AN ORDINARY INVOKE: `:hold`. A held call is minted
  by `held-calls/hold!` as the engine, which is the door the power
  door uses, because no hand at the wire may create one.

  AND ONE MINTS A GRANT: `:grant`. An agent that presents no grant
  authors no invitation (`the-author-sees-the-step`), and nobody is
  there at a boot to approve an ask. So the grant is minted the way an
  approved ask mints one (`grants/approval-effects!`): created through
  the grant door as the approvals actor, then accepted through the
  grant's own accept. A later step that names it under `:wearing` is
  walked by its cast member with that grant presented.

  TWO THINGS ARE THE REGISTRAR'S AND THE ENGINE'S, NOT A CAST MEMBER'S.
  A cast member's `:roles` are registered and then given at the
  member's birth, as an identity provider's claim would give them:
  assigning a role is not a door a cast member may walk. And a seed's
  `:walled` servers are made only when the boot names a wall
  (`:wall-url`, from WAYMARK_WALL_URL): one `mcp_server` row each, at
  the wall's address. A held call a person allows is then forwarded to
  the wall and ends `failed` with the wall's own sentence. With no
  wall no row is made, and the same allow ends `failed` with the
  engine's sentence that no server answers to the tool. Nothing leaves
  the clone either way (docs/spec-demo-clones.md § 4).

  IT REFUSES A WORKING ENGINE. The engine's name must begin with
  `demo-` and no IdP may be configured. A step the law refuses throws,
  and the boot ends. A seed marked `:live-ok` needs no `demo-` name: it
  loads on a fresh engine, one that has no member yet, and refuses one
  that has (a self-hosted factory's first boot).

  IT MARKS A WHOLE LOAD. After the last step the loader makes one more
  row through the member door, `seed-<name>`: the completion marker. An
  engine that holds the marker was seeded before, and the whole seed is
  skipped. An engine that holds a member of the cast and no marker
  stopped half-way through a seed, and the load refuses.

  A step's values may name what is only known at load: `[:ref :t1]` is
  the id of the row a step made, `[:self :t1]` is that row's path,
  `[:cast :ada]` is a cast member's id, `[:days 3]` is a date
  counted from the boot, and `[:param :repo]` is the boot's param of
  that name, from WAYMARK10_SEED_PARAMS (a JSON or EDN map).

  A CAST MEMBER'S `:id` AND `:display` MAY BE PARAMS TOO:
  `{:owner {:id [:param :owner] :display [:param :owner_display]}}`,
  so a live seed makes its owner from the boot. Only those two are
  filled; `:acts-for` names another cast key, not a value. A secret a
  step's create needs, such as a pull `runner_link`'s `fire_token`, is
  a param the same way: `{:fire_token [:param :pull_token]}`.

  A STEP MAY FILL A FACTORY KIT TEMPLATE: `{:template \"code-seat\"
  :params {:repo [:param :repo]}}` reads `seats/code-seat.edn` (or
  `judgments/<name>.edn`) from the classpath, fills each `{{hole}}` its
  params name, and creates the seat (or judgment) through its door as
  the cast member the step is `:as`. Its `:with` map, values filled
  the same way, is merged over the filled body: `{:with {:held_for
  [[:ref :model]]}}` holds the seat for a model the seed made.

  A STEP MAY FIND A ROW THE BOOT MADE BEFORE THE SEED: `{:find :domain
  :where {:name \"factory\"} :ref :factory}` makes no row and names the
  first one the query answers, so a later step's `[:ref :factory]` is
  its id. A query that answers none refuses the seed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [jsonista.core :as json])
  (:import (java.time Instant LocalDate ZoneOffset)))

(set! *warn-on-reflection* true)

(defn- refusal [sentence data]
  (ex-info sentence (assoc data :waymark10/seed-refused true)))

(def ^:private name-pattern
  "A seed's name is a path segment, so it is letters, digits and dashes."
  #"[a-z][a-z0-9-]{0,39}")

(defn read-seed
  "The seed a name stands for, from the classpath; nil when this commit
  carries no seed of that name."
  [seed-name]
  (let [n (str seed-name)]
    (when (re-matches name-pattern n)
      (some-> (io/resource (str "waymark10/" n "/seed.edn"))
              slurp
              edn/read-string))))

(defn- keyed
  "A params map with keyword keys, however they were spelled."
  [m]
  (into {} (map (fn [[k v]] [(keyword (if (keyword? k) (name k) (str k))) v])) m))

(defn read-params
  "WAYMARK10_SEED_PARAMS's map, read as JSON or as EDN, with keyword
  keys; {} when it is blank. Throws for text that is not a map."
  [text]
  (if (str/blank? text)
    {}
    (let [m (try (json/read-value text)
                 (catch Exception _
                   (try (edn/read-string text) (catch Exception _ nil))))]
      (if (map? m)
        (keyed m)
        (throw (refusal "The seed is refused: WAYMARK10_SEED_PARAMS is not a JSON or EDN map."
                        {}))))))

(declare seeded?)

(defn- members? [eng]
  (let [st (:storage eng)]
    (boolean (seq (store/with-tx st
                    (fn [tx] (store/query-rows st tx :member {} {:limit 1})))))))

(defn admit!
  "Throws unless this engine may be seeded: it is named `demo-…` and
  has no IdP. A working engine is neither. A `:live-ok` seed needs no
  `demo-` name and may load on an engine with an IdP, since a live
  engine has one; it is refused instead on an engine that already has a
  member, unless that engine holds the seed's own marker (`load!` then
  skips it)."
  ([eng] (admit! eng nil))
  ([eng seed]
   (let [n (str (:name eng))
         demo? (str/starts-with? n "demo-")]
     (cond
       (and (not demo?) (not (:live-ok seed)))
       (throw (refusal (str "The seed is refused: this engine is named `" n
                            "`, and a seed loads only on an engine whose name begins with `demo-`.")
                       {:engine n}))

       (and (some? (:oidc eng)) (not (:live-ok seed)))
       (throw (refusal "The seed is refused: this engine has an identity provider, and only a live seed loads on an engine with one."
                       {:engine n}))

       (and (not demo?) (not (seeded? eng seed)) (members? eng))
       (throw (refusal (str "The seed is refused: this engine is named `" n
                            "` and already has members, and a live seed loads only on an engine that has none.")
                       {:engine n :seed (:seed seed)}))))))

;; ── the cast ────────────────────────────────────────────────────────

(defn- param-of
  "A `[:param k]` form's value from the boot's params, or the form
  itself when it is not one. Throws for a param the boot does not give."
  [params form]
  (if (and (vector? form) (= 2 (count form)) (= :param (first form)))
    (let [k (second form)]
      (if (contains? params k)
        (get params k)
        (throw (refusal (str "The seed names the param `" (name k)
                             "`, and WAYMARK10_SEED_PARAMS holds none of that name.")
                        {:param k}))))
    form))

(defn- filled-cast
  "The cast with each member's `:id` and `:display` filled from the
  boot's params, so `member-id`, `principal-of` and `enrol!` read
  values."
  [cast params]
  (into {}
        (map (fn [[who m]]
               [who (cond-> m
                      (contains? m :id) (update :id #(param-of params %))
                      (contains? m :display) (update :display #(param-of params %)))]))
        cast))

(defn- member-id [cast who]
  (or (some-> (get-in cast [who :id]) str not-empty)
      (throw (refusal (str "The seed names `" who "`, and its cast holds no such member.")
                      {:cast who}))))

(defn- principal-of [cast who]
  (let [{:keys [display type acts-for]} (get cast who)
        id (member-id cast who)]
    (cond-> (t/principal {:id id :display (str display) :type (or type :human)})
      acts-for (assoc :acts-for (member-id cast acts-for)))))

(defn- register-roles!
  "One `role` row for each role the cast holds, before any member is
  born holding it: a member's create refuses a role nobody registered."
  [eng cast]
  (doseq [role (sort (distinct (map str (mapcat :roles (vals cast)))))]
    (inv/create! eng :role {:name role} {:principal members/registrar})))

(defn- enrol!
  "One cast member's row: `members/ensure-sitter!`'s create, with the
  cast's id as the row id, so the dev principal header finds it. The
  member's `:roles` ride the create, as a provider's claim would, and
  the row carries the cast marker, so a clone's acts_as_cast seat may
  act as it (router/cast-principal)."
  [eng cast who]
  (let [{:keys [display type acts-for roles]} (get cast who)
        id (member-id cast who)]
    (inv/create! eng :member
                 (cond-> {:display (str display)
                          :actor_type (name (or type :human))
                          :subject id
                          :cast true}
                   acts-for (assoc :acts_for (member-id cast acts-for))
                   (seq roles) (assoc :roles (mapv str roles)))
                 {:principal members/registrar :id id})))

(defn- wall-servers!
  "The seed's `:walled` servers: one `mcp_server` row each, at the
  wall's address, made as the engine. A wall answers no discover, so
  each row is born dark with the wall's sentence on it, and a call
  forwarded to it fails with that sentence. → the rows."
  [eng walled wall-url]
  (mapv (fn [{:keys [server powers]}]
          (or (servers/row-by-name eng server)
              (:row (inv/create! eng :mcp_server
                                 {:name (str server)
                                  :transport "http"
                                  :url (str wall-url)
                                  :powers (vec powers)
                                  :note "The demo's wall. Nothing behind this row sends anything: the wall answers every call with its sentence."}
                                 {:principal servers/engine-actor}))))
        walled))

(defn- load-row [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- enrolled? [eng cast]
  (boolean (some #(load-row eng :member (member-id cast %)) (keys cast))))

;; ── the completion marker ───────────────────────────────────────────

(defn- marker-id [seed]
  (str "seed-" (:seed seed)))

(defn- mark!
  "The completion marker: one member row the loader owns, born through
  the member door as the registrar after the last step succeeds."
  [eng seed]
  (let [id (marker-id seed)]
    (inv/create! eng :member
                 {:display (str "Seed `" (:seed seed) "`, loaded whole")
                  :actor_type "agent"
                  :subject id}
                 {:principal members/registrar :id id})))

(defn- seeded? [eng seed]
  (some? (load-row eng :member (marker-id seed))))

;; ── the steps ───────────────────────────────────────────────────────

(defn- ref-of [refs k]
  (or (get refs k)
      (throw (refusal (str "The seed points at `" k "`, and no earlier step made a row of that name.")
                      {:ref k}))))

(defn- value-of
  "A step's value with its load-time forms filled in."
  [{:keys [eng cast refs now params] :as ctx} form]
  (let [[op arg] (when (and (vector? form) (= 2 (count form))) form)]
    (case op
      :param (param-of params form)
      :ref (:id (ref-of refs arg))
      :self (let [{:keys [kind id]} (ref-of refs arg)]
              (str "/api/" (:plural (get (inv/resources eng) kind)) "/" id))
      :cast (member-id cast arg)
      :days (str (.plusDays (LocalDate/ofInstant ^Instant now ZoneOffset/UTC)
                            (long arg)))
      (cond
        (map? form) (into {} (map (fn [[k v]] [k (value-of ctx v)])) form)
        (vector? form) (mapv #(value-of ctx %) form)
        :else form))))

;; ── the factory kit's templates ──────────────────────────────────────

(def ^:private template-places
  "Where a template of a name is looked for on the classpath, and the
  kind its filled body is created as: the kit's seats, then its
  judgments."
  [["seats/" :seat] ["judgments/" :judgment]])

(defn- template-of
  "The factory kit template a name stands for: {:kind :params :body}.
  Throws when the classpath holds none of that name."
  [template-name]
  (let [n (str template-name)]
    (or (when (re-matches name-pattern n)
          (some (fn [[dir kind]]
                  (when-some [res (io/resource (str dir n ".edn"))]
                    (let [t (edn/read-string (slurp res))]
                      {:kind kind
                       :params (get-in t [:template :params])
                       :body (get t kind)})))
                template-places))
        (throw (refusal (str "The seed names the template `" n
                             "`, and the classpath holds no seat or judgment template of that name.")
                        {:template n})))))

(defn- hole-text
  "A param's value as it stands inside the printed body's strings."
  [v]
  (let [s (pr-str (str v))]
    (subs s 1 (dec (count s)))))

(defn- fill
  "A template's body with each `{{hole}}` its params name filled from
  `given`. Throws for a param not given and for a hole left over."
  [{:keys [params body]} given template-name]
  (when-some [missing (seq (remove #(contains? given %) (keys params)))]
    (throw (refusal (str "The seed's template `" template-name "` needs the params "
                         (str/join ", " (map name missing)) ", and the step does not give them.")
                    {:template template-name :missing (vec missing)})))
  (let [text (reduce (fn [s k]
                       (str/replace s (str "{{" (name k) "}}") (hole-text (get given k))))
                     (pr-str body)
                     (keys params))]
    (when-some [left (re-find #"\{\{[a-z_]+\}\}" text)]
      (throw (refusal (str "The seed's template `" template-name "` has the hole " left
                           ", and no param of the template fills it.")
                      {:template template-name :hole left})))
    (edn/read-string text)))

(defn- kind-of [refs step]
  (cond
    (:template step) (:kind (template-of (:template step)))
    (:find step) (:find step)
    (:hold step) :held_call
    (:grant step) :grant
    (contains? step :create) (:kind step)
    :else (:kind (ref-of refs (:on step)))))

(defn- mint-grant!
  "A grant for a cast member, minted the way an approved ask mints one
  (`grants/approval-effects!`): the approvals actor creates it through
  the grant door, so the scope guards judge it, and accepts it through
  the grant's own accept. → the accepted row."
  [eng input]
  (let [made (:row (inv/create! eng :grant input
                                {:principal grants/approvals-actor}))]
    (:row (inv/invoke! eng :grant (str (:id made)) :accept nil
                       {:principal grants/approvals-actor}))))

(defn- hand-of
  "The invoke options for the cast member a step is `:as`: its
  principal, and the guard's-eye view of the grant the step is
  `:wearing`, when it names one."
  [{:keys [eng cast refs]} step]
  (let [p (principal-of cast (:as step))]
    (cond-> {:principal p}
      (:wearing step)
      (assoc :grant (:grant (grants/visibility
                             eng (:id (ref-of refs (:wearing step))) p))))))

(defn- step!
  "Walk one step. → the row it made or moved."
  [{:keys [eng refs] :as ctx} step]
  (cond
    ;; the one step that is not an ordinary invoke: the engine's own
    ;; door, the one the power door mints a held call through
    (:hold step)
    (:row (held-calls/hold! eng (value-of ctx (:hold step))))

    ;; the grant an agent of the cast wears: the approval effect's mint
    (:grant step)
    (mint-grant! eng (value-of ctx (:grant step)))

    ;; a factory kit template, filled and created through its kind's door
    (:template step)
    (let [t (template-of (:template step))
          given (keyed (value-of ctx (or (:params step) {})))]
      (:row (inv/create! eng (:kind t)
                         (merge (fill t given (:template step))
                                (value-of ctx (or (:with step) {})))
                         (hand-of ctx step))))

    ;; a row the boot made before the seed: found, not made
    (:find step)
    (let [kind (:find step)
          where (value-of ctx (or (:where step) {}))
          st (:storage eng)]
      (or (first (store/with-tx st
                   (fn [tx] (store/query-rows st tx kind where {:limit 1}))))
          (throw (refusal (str "The seed finds a `" (name kind) "` row where "
                               (pr-str where) ", and this engine holds none.")
                          {:find kind :where where}))))

    (contains? step :create)
    (:row (inv/create! eng (:kind step) (value-of ctx (:create step))
                       (hand-of ctx step)))

    :else
    (let [{:keys [kind id]} (ref-of refs (:on step))
          action (keyword (:action step))
          rdef (get (inv/resources eng) kind)
          ;; a fenced door names the version it read (dev/act!'s rule)
          fenced? (get-in rdef [:actions action :safety :fence])
          opts (cond-> (hand-of ctx step)
                 fenced? (assoc :if-match
                                (inv/etag kind id
                                          (:version (inv/decode-row
                                                     rdef (load-row eng kind id))))))]
      (:row (inv/invoke! eng kind id action
                         (value-of ctx (or (:input step) {})) opts)))))

(defn load!
  "Seed this engine: enrol the cast, walk the steps in order, then
  write the completion marker.
  → {:seed :seeded true :members n :steps n :refs {name {:kind :id}}},
  or {:seed :seeded false :skipped sentence} for an engine that holds
  the marker. Throws on an engine `admit!` refuses, on the first step
  the law refuses, and on an engine that holds a cast member and no
  marker. `:now` is the boot's moment, the engine's clock when absent;
  relative dates count from it. `:wall-url` is the clone's wall: when
  it is given, the seed's `:walled` servers are made at it, and when
  it is not, none is made. `:params` are the values `[:param k]` reads."
  [eng {:keys [cast steps walled] :as seed} {:keys [now wall-url params]}]
  (admit! eng seed)
  (cond
    (seeded? eng seed)
    {:seed (:seed seed) :seeded false
     :skipped "This engine already holds the seed's completion marker, so the seed was applied before and is skipped whole."}

    (enrolled? eng (filled-cast cast (keyed params)))
    (throw (refusal "The seed is refused: a seed failed half-way on this database; bring the clone down."
                    {:seed (:seed seed) :half-seeded true}))

    :else
    (let [now (or now ((or (:now-fn eng) #(Instant/now))))
          cast (filled-cast cast (keyed params))]
      (register-roles! eng cast)
      ;; people first: an agent's row names the person it acts for
      (doseq [who (sort-by (juxt #(some? (get-in cast [% :acts-for])) str)
                           (keys cast))]
        (enrol! eng cast who))
      (when-some [url (some-> wall-url str not-empty)]
        (wall-servers! eng walled url))
      (let [refs (reduce
                  (fn [refs [i step]]
                    (let [ctx {:eng eng :cast cast :refs refs :now now
                               :params (keyed params)}
                          row (try
                                (step! ctx step)
                                (catch Exception e
                                  (throw (ex-info
                                          (str "The seed's step " (inc i)
                                               " was refused, so the boot ends: "
                                               (or (inv/problem-reason e) "refused"))
                                          {:waymark10/seed-refused true
                                           :step (inc i)
                                           :seed-step step}
                                          e))))]
                      (cond-> refs
                        (:ref step) (assoc (:ref step)
                                           {:kind (kind-of refs step)
                                            :id (str (:id row))}))))
                  {}
                  (map-indexed vector steps))]
        ;; last, and only after every step went through
        (mark! eng seed)
        {:seed (:seed seed) :seeded true
         :members (count cast) :steps (count steps) :refs refs}))))

(defn boot!
  "The boot step behind WAYMARK10_SEED: read the named seed and load
  it, saying what happened. Throws for a name this commit carries no
  seed for, and for everything `load!` throws for. `opts` are
  `load!`'s: `:wall-url` is the clone's wall, from WAYMARK_WALL_URL, and
  `:params`, when absent, are read from WAYMARK10_SEED_PARAMS."
  ([eng seed-name] (boot! eng seed-name {}))
  ([eng seed-name opts]
   (let [seed (or (read-seed seed-name)
                  (throw (refusal (str "The seed is refused: this commit carries no seed named `"
                                       seed-name "`.")
                                  {:seed (str seed-name)})))
         _ (admit! eng seed)
         opts (cond-> (or opts {})
                (not (contains? opts :params))
                (assoc :params (read-params (System/getenv "WAYMARK10_SEED_PARAMS"))))
         result (load! eng seed opts)]
     (println (if (:seeded result)
                (str "waymark10 seed: `" (:seed result) "` loaded, "
                     (:members result) " members and " (:steps result) " steps.")
                (str "waymark10 seed: " (:skipped result))))
     result)))
