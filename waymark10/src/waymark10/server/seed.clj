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
  row through the member door, `seed-<name>`: the completion marker. Its
  data keeps `walked_steps`, the key of each step the seed has walked:
  a SHA-256 of the step's edn form with its maps sorted, so a step is
  known by what it says and not by where it stands. An engine that
  holds the marker was seeded before, and the load is additive: it
  enrols only the cast members it holds no row for, and walks only the
  steps whose key is not in `walked_steps`, adding each key as the step
  goes through; `:find` steps are read as ever. A step that makes a
  named row (a `:name` in the body) is skipped too while a row of that
  kind and name is there, so an edited step makes no second row, and a
  known one still names its row for the steps after it. A marker with
  no `walked_steps`, written before keys were kept, takes the keys of
  every step the seed holds now and walks none of them on that load. So
  a boot over a seeded org changes nothing, and a seed that gained a
  step or a cast member adds only those. An engine that
  holds a member of the cast and no marker stopped half-way through a
  seed, and the load refuses.

  AN UNNAMED STEP SEEDED BEFORE STEP IDS IS FOUND BY ITS BODY, ONCE. A
  create or template step with a `:ref` is born with an id drawn from
  the seed and the ref (`step-id`); an engine seeded before that holds
  its row under a random id. A known step whose id finds no row looks,
  once, for the rows of its kind whose data holds every field its
  filled body sets: one is bound, and kept in the marker's
  `bound_refs`, ref → id, so a later load reads it and never matches
  again; none or several bind nothing, one line on *err* says so, and
  each gained step that needs that ref is skipped while the rest of
  the seed loads.

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
  [[:ref :model]]}}` holds the seat for a model the seed made. A param
  given a number or a collection fills a string that is that one hole
  whole as itself, and splices a list into the vector around it: a
  budget stays a decimal, and an org's kinds join a template's scope.

  A STEP MAY FIND A ROW THE BOOT MADE BEFORE THE SEED: `{:find :domain
  :where {:name \"factory\"} :ref :factory}` makes no row and names the
  first one the query answers, so a later step's `[:ref :factory]` is
  its id. A query that answers none refuses the seed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [jsonista.core :as json])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Instant LocalDate ZoneOffset)
           (java.util UUID)))

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

(declare seeded? named-row marker-org)

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
  adds only what the seed gained).

  An org's seed, loaded by the org boot with `{:org name}` in `opts`,
  is not refused for the engine's other members: a working house
  engine always has some. It is refused instead when the seed's marker
  records another org (`org_mismatch`)."
  ([eng] (admit! eng nil))
  ([eng seed] (admit! eng seed nil))
  ([eng seed {:keys [org]}]
   (let [n (str (:name eng))
         demo? (str/starts-with? n "demo-")
         org (some-> org str not-empty)
         had (when (and org (:live-ok seed)) (marker-org eng seed))]
     (cond
       (and (not demo?) (not (:live-ok seed)))
       (throw (refusal (str "The seed is refused: this engine is named `" n
                            "`, and a seed loads only on an engine whose name begins with `demo-`.")
                       {:engine n}))

       (and (some? (:oidc eng)) (not (:live-ok seed)))
       (throw (refusal "The seed is refused: this engine has an identity provider, and only a live seed loads on an engine with one."
                       {:engine n}))

       (and had (not= had org))
       (throw (refusal (str "The seed is refused: this engine holds seed `" (:seed seed)
                            "` loaded for org `" had "`, and this load names org `" org "`.")
                       {:engine n :seed (:seed seed) :org org :reason "org_mismatch"}))

       (and (not demo?) (not org) (not (seeded? eng seed)) (members? eng))
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
  born holding it: a member's create refuses a role nobody registered.
  A role already registered is left as it is."
  [eng cast]
  (doseq [role (sort (distinct (map str (mapcat :roles (vals cast)))))
          :when (nil? (named-row eng :role role))]
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

(defn- named-row
  "The first row of `kind` whose `:name` is `nm`, or nil."
  [eng kind nm]
  (let [st (:storage eng)]
    (first (store/with-tx st
             (fn [tx] (store/query-rows st tx kind {:name (str nm)} {:limit 1}))))))

;; ── the completion marker ───────────────────────────────────────────

(defn- marker-id [seed]
  (str "seed-" (:seed seed)))

(defn- mark!
  "The completion marker: one member row the loader owns, born through
  the member door as the registrar after the last step succeeds. An
  org's load records its org in `seed_org`, where `marker-org` reads it."
  [eng seed org]
  (let [id (marker-id seed)]
    (inv/create! eng :member
                 (cond-> {:display (if org
                                     (str "Seed `" (:seed seed) "`")
                                     (str "Seed `" (:seed seed) "`, loaded whole"))
                          :actor_type "agent"
                          :subject id}
                   org (assoc :seed_org org))
                 {:principal members/registrar :id id})))

(defn- seeded? [eng seed]
  (some? (load-row eng :member (marker-id seed))))

(defn- org-recorded!
  "Write `org` into the marker's `seed_org`: a maintenance write, in
  place, as `walk-recorded!` keeps `walked_steps`."
  [eng seed org]
  (let [st (:storage eng)
        id (marker-id seed)]
    (store/with-tx st
      (fn [tx]
        (when-some [raw (store/load-row st tx :member id {:for-update true})]
          (store/update-data! st tx :member id
                              (assoc (:data raw) :seed_org org)
                              (:next-flip-at raw)))))))

(defn- marker-org
  "The org the seed's marker records, or nil: no marker, or one a load
  without `:org` wrote. A marker from before `seed_org` named its org in
  the display: it is read from there once, and rewritten with the field."
  [eng seed]
  (when-some [data (:data (load-row eng :member (marker-id seed)))]
    (or (some-> (or (:seed_org data) (get data "seed_org")) str not-empty)
        (when-some [org (some->> data ((juxt :display #(get % "display")))
                                 (some identity) str
                                 (re-find #"for org `([^`]+)`")
                                 second)]
          (org-recorded! eng seed org)
          org))))

(defn- step-key
  "A step's key: the SHA-256 of its edn form, its maps and sets sorted,
  so the same step says the same key on every load."
  [step]
  (let [by-print #(compare (pr-str %1) (pr-str %2))
        canonical (walk/postwalk
                   (fn [x]
                     (cond
                       (map? x) (into (sorted-map-by by-print) x)
                       (set? x) (into (sorted-set-by by-print) x)
                       :else x))
                   step)
        bs (.digest (MessageDigest/getInstance "SHA-256")
                    (.getBytes ^String (pr-str canonical) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (long %) 0xff)) bs))))

(defn- walked-of
  "The keys the marker holds as walked: a set, or nil for a marker
  written before keys were kept."
  [eng seed]
  (some->> (get-in (load-row eng :member (marker-id seed)) [:data :walked_steps])
           (into #{} (map str))))

(defn- walk-recorded!
  "Add `ks` to the marker's `walked_steps`: a maintenance write, in
  place, as the engine keeps its other bookkeeping on a member row."
  [eng seed ks]
  (let [st (:storage eng)
        id (marker-id seed)]
    (store/with-tx st
      (fn [tx]
        (when-some [raw (store/load-row st tx :member id {:for-update true})]
          (store/update-data! st tx :member id
                              (assoc (:data raw) :walked_steps
                                     (vec (sort (into (set (map str (get-in raw [:data :walked_steps])))
                                                      ks))))
                              (:next-flip-at raw)))))))

(defn- bound-of
  "The refs the marker's `bound_refs` binds by a body match (`rebound`):
  ref name → row id, {} for a marker that holds none."
  [eng seed]
  (into {} (map (fn [[k v]] [(name k) (str v)]))
        (get-in (load-row eng :member (marker-id seed)) [:data :bound_refs])))

(defn- bound-recorded!
  "Add `r` → `id` to the marker's `bound_refs`, in place, as
  `walk-recorded!` keeps its keys."
  [eng seed-name r id]
  (let [st (:storage eng)
        mid (marker-id {:seed seed-name})]
    (store/with-tx st
      (fn [tx]
        (when-some [raw (store/load-row st tx :member mid {:for-update true})]
          (store/update-data! st tx :member mid
                              (assoc-in (:data raw) [:bound_refs (name r)] (str id))
                              (:next-flip-at raw)))))))

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

(defn- whole-hole
  "The param a string is one hole of, whole: `{{budget}}` → :budget."
  [x]
  (when (string? x)
    (some-> (re-matches #"\{\{([a-z_]+)\}\}" x) second keyword)))

(defn- unresolved?
  "A seed's own `[:ref …]` or `[:param …]`, which only a boot resolves."
  [v]
  (and (vector? v) (keyword? (first v))))

(defn filled
  "A template's body with each `{{hole}}` filled from `given`, param
  keyword → value. A hole inside a string takes the value's text. A
  string that is one hole whole takes a number or a collection as
  itself, so a budget stays a decimal; inside a vector it splices a
  list in, so a template's scope takes an org's own entries beside its
  fixed ones. A seed's unresolved `[:ref …]` stands as its text. A hole
  `given` does not name is left."
  [body given]
  (let [given (into {} (map (fn [[k v]] [k (if (unresolved? v) (str v) v)])) given)
        raw (fn [x]
              (when-some [k (whole-hole x)]
                (let [v (get given k)]
                  (when (or (number? v) (coll? v)) v))))]
    (letfn [(walk [x]
              (cond
                (map? x) (into (empty x) (map (fn [[k v]] [k (walk v)])) x)
                (vector? x) (into [] (mapcat #(let [v (raw %)]
                                                (if (sequential? v) v [(walk %)])))
                                  x)
                (set? x) (into (empty x) (map walk) x)
                (string? x) (if-some [v (raw x)]
                              v
                              (reduce-kv (fn [s k v] (str/replace s (str "{{" (name k) "}}") (str v)))
                                         x given))
                :else x))]
      (walk body))))

(defn- fill
  "A template's body with each `{{hole}}` its params name filled from
  `given`. Throws for a param not given and for a hole left over."
  [{:keys [params body]} given template-name]
  (when-some [missing (seq (remove #(contains? given %) (keys params)))]
    (throw (refusal (str "The seed's template `" template-name "` needs the params "
                         (str/join ", " (map name missing)) ", and the step does not give them.")
                    {:template template-name :missing (vec missing)})))
  (let [made (filled body (select-keys given (keys params)))]
    (when-some [left (re-find #"\{\{[a-z_]+\}\}" (pr-str made))]
      (throw (refusal (str "The seed's template `" template-name "` has the hole " left
                           ", and no param of the template fills it.")
                      {:template template-name :hole left})))
    made))

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

(defn- made-body
  "What a template or create step makes, before it is made: its kind
  and its filled body."
  [ctx step]
  (if (:template step)
    (let [t (template-of (:template step))
          given (keyed (value-of ctx (or (:params step) {})))]
      [(:kind t) (merge (fill t given (:template step))
                        (value-of ctx (or (:with step) {})))])
    [(:kind step) (value-of ctx (:create step))]))

(defn- step-id
  "The id a create or template step's row is born with when the step
  names a `:ref`: drawn from the seed and that ref, so a later load
  finds the row again even when it has no `:name` (`gain!`). nil for a
  step with no `:ref`."
  [{:keys [seed]} step]
  (when-some [r (:ref step)]
    (str (UUID/nameUUIDFromBytes
          (.getBytes (str "seed:" seed ":" r) "UTF-8")))))

(defn- half-walked?
  "True when this engine holds a row one of the seed's create or
  template steps is born with (`step-id`): a load of the seed began
  here and did not reach its marker."
  [eng seed]
  (let [ctx {:seed (:seed seed)}]
    (boolean
     (some (fn [step]
             (when-some [id (step-id ctx step)]
               (when-some [kind (cond
                                  (or (:hold step) (:grant step)) nil
                                  (:template step) (:kind (template-of (:template step)))
                                  (:find step) nil
                                  (contains? step :create) (:kind step))]
                 (load-row eng kind id))))
           (:steps seed)))))

(defn- made-opts [ctx step]
  (cond-> (hand-of ctx step)
    (step-id ctx step) (assoc :id (step-id ctx step))))

(defn- plain
  "A value as a stored row's data gives it back: a keyword as its text,
  a number by its value."
  [v]
  (walk/postwalk (fn [x]
                   (cond
                     (keyword? x) (subs (str x) 1)
                     (number? x) (.stripTrailingZeros ^java.math.BigDecimal (bigdec x))
                     :else x))
                 v))

(defn- body-matches
  "The rows of `kind` whose data holds every field `body` sets, each
  equal."
  [eng kind body]
  (let [st (:storage eng)
        want (plain body)]
    (filterv (fn [row]
               (let [have (plain (:data row))]
                 (every? (fn [[k v]] (= v (get have k))) want)))
             (store/with-tx st (fn [tx] (store/query-rows st tx kind {} {}))))))

(defn- rebound
  "The row a known unnamed step's `:ref` names on an engine seeded
  before `step-id`, which holds it under a random id: the one the
  marker's `bound_refs` names, or else the one row of its kind whose
  data holds every field the step's filled body sets, then kept in
  `bound_refs` so a later load never matches again. Zero or several
  bind nothing and say so on *err*. → [row false], or [nil false true]
  for a ref no row was bound to."
  [{:keys [eng seed bound] :as ctx} step]
  (let [r (:ref step)
        [kind body] (made-body ctx step)]
    (if-some [row (some->> (get bound (name r)) (load-row eng kind))]
      [row false]
      (let [found (body-matches eng kind body)]
        (if (= 1 (count found))
          (let [row (first found)]
            (bound-recorded! eng seed r (:id row))
            [row false])
          (do (binding [*out* *err*]
                (println (str "seed `" seed "` binds no row to `" r "`: " (count found)
                              " rows of `" (name kind) "` match its step's body, so each"
                              " gained step that needs it is skipped.")))
              [nil false true]))))))

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
    (let [[kind body] (made-body ctx step)]
      (:row (inv/create! eng kind body (made-opts ctx step))))

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
    (let [[kind body] (made-body ctx step)]
      (:row (inv/create! eng kind body (made-opts ctx step))))

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

(defn- gain!
  "Walk one step on an engine that holds the seed's marker; `known?`
  says its key is in the marker's `walked_steps`. A `:find` is read as
  ever. A template or create whose body names a `:name` is found when a
  row of its kind holds that name, known or not, and made when none
  does and the step is not known. A create's name is read alone, so a
  found row's body, which may point at a step this load skipped, is not
  filled. An unnamed create or template is found, known or not, by the
  id `step-id` gave it, and an action step a known one binds its `:ref`
  to the row it moved, its `:on`, so a gained step may point at either.
  A known unnamed one whose id finds no row was seeded before
  `step-id`, and is bound by its body (`rebound`). Any other step is
  walked when it is not known, and skipped when it is. → [row made?
  lost?], row nil for a skipped step, lost? true for a `:ref` that
  binds no row."
  [{:keys [eng refs] :as ctx} step known?]
  (cond
    (:find step) [(step! ctx step) false]

    (and (contains? step :create) (contains? (:create step) :name))
    (let [kind (:kind step)
          nm (value-of ctx (:name (:create step)))]
      (cond
        (some? (named-row eng kind nm)) [(named-row eng kind nm) false]
        known? [nil false]
        :else (let [[kind body] (made-body ctx step)]
                [(:row (inv/create! eng kind body (made-opts ctx step))) true])))

    (:template step)
    (let [[kind body] (made-body ctx step)]
      (if-some [nm (:name body)]
        (cond
          (some? (named-row eng kind nm)) [(named-row eng kind nm) false]
          known? [nil false]
          :else [(:row (inv/create! eng kind body (made-opts ctx step))) true])
        (if-some [row (some->> (step-id ctx step) (load-row eng kind))]
          [row false]
          (cond
            (and known? (:ref step)) (rebound ctx step)
            known? [nil false]
            :else [(step! ctx step) true]))))

    (contains? step :create)
    (if-some [row (some->> (step-id ctx step) (load-row eng (:kind step)))]
      [row false]
      (cond
        (and known? (:ref step)) (rebound ctx step)
        known? [nil false]
        :else [(step! ctx step) true]))

    (and known? (:ref step) (:on step) (get refs (:on step)))
    [(get refs (:on step)) false]

    known? [nil false]

    :else [(step! ctx step) true]))

(defn load!
  "Seed this engine: enrol the cast, walk the steps in order, then
  write the completion marker.
  → {:seed :seeded true :members n :steps n :refs {name {:kind :id}}},
  or, for an engine that holds the marker, {:seed :seeded false
  :added {:members n :steps n} :refs … :skipped sentence}: only what
  the seed gained since is added (`gain!`), each step known by its key
  in the marker's `walked_steps`. Throws on an engine
  `admit!` refuses, on the first step the law refuses, and on an engine
  that holds a cast member and no marker. `:now` is the boot's moment,
  the engine's clock when absent; relative dates count from it. `:wall-url` is the clone's wall: when
  it is given, the seed's `:walled` servers are made at it, and when
  it is not, none is made. `:params` are the values `[:param k]` reads.
  `:org` is the org boot's alone (`admit!`): a cast member the engine
  already holds is then reused as it is, and the marker records the org.
  Under `:org` a load that finds no marker but finds a row a step is
  born with stopped half-way: it is resumed, additively as over a
  marker, the marker written at its end, and `:resumed true` said."
  [eng {:keys [cast steps walled] :as seed} {:keys [now wall-url params org] :as opts}]
  (admit! eng seed opts)
  (let [seeded (seeded? eng seed)
        org (some-> org str not-empty)
        cast (filled-cast cast (keyed params))
        ;; reused members are expected under :org, so a half-way load
        ;; is told by the rows its steps made, and resumed, not refused
        resumed (boolean (and (not seeded) org (half-walked? eng seed)))
        additive (or seeded resumed)]
    (when (and (not seeded) (not org) (enrolled? eng cast))
      (throw (refusal "The seed is refused: a seed failed half-way on this database; bring the clone down."
                      {:seed (:seed seed) :half-seeded true})))
    (let [now (or now ((or (:now-fn eng) #(Instant/now))))
          fresh (filterv #(nil? (load-row eng :member (member-id cast %)))
                         (keys cast))]
      (register-roles! eng (select-keys cast fresh))
      ;; people first: an agent's row names the person it acts for
      (doseq [who (sort-by (juxt #(some? (get-in cast [% :acts-for])) str)
                           fresh)]
        (enrol! eng cast who))
      (when-some [url (some-> wall-url str not-empty)]
        (wall-servers! eng walled url))
      (let [walked (cond
                     seeded
                     (or (walked-of eng seed)
                         ;; a marker from before keys were kept: every
                         ;; step the seed holds now counts as walked
                         (let [all (into #{} (map step-key) steps)]
                           (walk-recorded! eng seed all)
                           all))
                     ;; no marker holds keys yet: each step is found
                     ;; by its name or its id, or walked
                     resumed #{})
            bound (when additive (bound-of eng seed))
            {:keys [refs made]}
            (reduce
                  (fn [{:keys [refs lost] :as acc} [i step]]
                    (let [ctx {:eng eng :cast cast :refs refs :now now
                               :params (keyed params) :seed (:seed seed)
                               :bound bound}
                          [row made? lost?] (try
                                (if additive
                                  (let [k (step-key step)
                                        known? (contains? walked k)
                                        walked-step (try
                                                      (gain! ctx step known?)
                                                      ;; a step that needs a ref no row was
                                                      ;; bound to is skipped, and not walked
                                                      (catch clojure.lang.ExceptionInfo e
                                                        (if (contains? lost (:ref (ex-data e)))
                                                          [nil false true]
                                                          (throw e))))]
                                    (when-not (or known? (get walked-step 2))
                                      (walk-recorded! eng seed [k]))
                                    walked-step)
                                  [(step! ctx step) true])
                                (catch Exception e
                                  (throw (ex-info
                                          (str "The seed's step " (inc i)
                                               " was refused, so the boot ends: "
                                               (or (inv/problem-reason e) "refused"))
                                          {:waymark10/seed-refused true
                                           :step (inc i)
                                           :seed-step step}
                                          e))))]
                      (cond-> acc
                        made? (update :made inc)
                        (and lost? (:ref step)) (update :lost conj (:ref step))
                        (and row (:ref step))
                        (assoc-in [:refs (:ref step)]
                                  {:kind (kind-of refs step)
                                   :id (str (:id row))}))))
                  {:refs {} :made 0 :lost #{}}
                  (map-indexed vector steps))]
        (if seeded
          {:seed (:seed seed) :seeded false
           :added {:members (count fresh) :steps made} :refs refs
           :skipped (if (and (empty? fresh) (zero? made))
                      "This engine already holds the seed's completion marker, and the seed gained nothing since, so nothing was added."
                      (str "This engine already holds the seed's completion marker, so only what the seed gained was added: "
                           (count fresh) " members and " made " steps."))}
          (do
            ;; last, and only after every step went through
            (mark! eng seed org)
            (walk-recorded! eng seed (map step-key steps))
            (when resumed
              (println (str "resumed half-seeded org " org)))
            (cond-> {:seed (:seed seed) :seeded true
                     :members (count cast) :steps (count steps) :refs refs}
              resumed (assoc :resumed true))))))))

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
