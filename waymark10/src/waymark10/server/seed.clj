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
  known one still names its row for the steps after it. Beside the
  keys the marker keeps `walked_refs`: for each walked step with a
  `:ref`, the id of the row it made, so a known step the load skips
  still binds its ref, and a gained step may point at a hold, a grant
  or an unnamed row an earlier load made. A step walked before refs
  were kept has no id there, and a step that points at it refuses
  saying so: the loader does not guess. A marker with
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

  AN ORG'S LOAD RECORDS AS IT GOES. Under `:org` the marker is written
  before the first step, with `walked_steps` empty and `loading true`,
  each step's key joins `walked_steps` once it is walked, and `loading`
  is set false at the end of a clean load. A load that finds the marker
  still `loading` resumes: it skips every step whose key is walked and
  walks the rest, so an action step is never walked twice. No step is
  skipped for its row's state.

  A step's values may name what is only known at load: `[:ref :t1]` is
  the id of the row a step made, `[:self :t1]` is that row's path,
  `[:cast :ada]` is a cast member's id, `[:days 3]` is a date
  counted from the boot's day one, and `[:param :repo]` is the boot's
  param of that name, from WAYMARK10_SEED_PARAMS (a JSON or EDN map).

  THE DAY ONE IS THE SCENE'S WHEN IT IS GIVEN (ticket bbf1f253). A
  clone stood up to film a first-week scene is booted with the scene's
  `day_one` (WAYMARK10_SEED_DAY_ONE, YYYY-MM-DD), and `[:days n]` counts
  from it, so the rows the seed dates fall on the days the scene's
  captions read, however many days later the clone is stood up. With
  none, it counts from the day of the boot, as before.

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
  its id. A query that answers none refuses the seed.

  A STEP MAY GIVE A SEEDED SEAT ITS SCHEDULE: `{:schedule :draft-judge
  :runners [[:ref :pull]]}` mints the seat's schedule row as the
  engine (`schedules/ensure-schedule!`, the same row the schedules
  consumer mints at a seat's birth, which a seed's births never wait
  on) and seeds its runner list with the links it names, so an owner's
  fire on the seat goes out through them. An interactive seat has no
  schedule, and the step makes none for it.

  A SEAT THAT NAMES NO MODEL IS HELD FOR THE HOUSE'S (ticket 7e177f8d):
  a template or create step that makes a seat whose body names no
  `held_for` holds it for the active model row the factory seed makes
  (on an org clone the factory boots with the org), so an interactive
  seat, which gets no schedule, still names the model its sit claims.
  A step that names its own `:with {:held_for ...}` keeps it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.members :as members]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
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

(def ^:private seed-org-max
  "The longest org name the marker's `seed_org` holds (members.clj)."
  64)

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
  records another org (`org_mismatch`), and when its name is longer
  than the marker's `seed_org` holds (`org_too_long`), since `mark!`
  would fail only after every step was walked."
  ([eng] (admit! eng nil))
  ([eng seed] (admit! eng seed nil))
  ([eng seed {:keys [org]}]
   (let [n (str (:name eng))
         demo? (str/starts-with? n "demo-")
         org (some-> org str not-empty)
         had (when (and org (:live-ok seed)) (marker-org eng seed))]
     (cond
       (and org (> (count org) seed-org-max))
       (throw (refusal (str "The seed is refused: the org name is " (count org)
                            " characters long, and an org's seed marker holds at most "
                            seed-org-max ".")
                       {:engine n :seed (:seed seed) :org org :reason "org_too_long"}))

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

(defn param-names
  "The name of each `[:param k]` form the seed holds, once each in the
  order they first appear: the params a boot of it must give."
  [seed]
  (->> (tree-seq coll? seq seed)
       (keep #(when (and (vector? %) (not (map-entry? %)) (= 2 (count %))
                         (= :param (first %)))
                (keyword (name (second %)))))
       distinct
       vec))

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

(defn- ref-name
  "A step's ref as the marker keeps it: :c1 → \"c1\"."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- refs-kept
  "The marker's `walked_refs` as its data reads back: ref name → id."
  [m]
  (into {} (map (fn [[k v]] [(ref-name k) (str v)])) m))

(defn- walked-refs-of
  "The ids the marker keeps for the refs of the steps it walked: ref
  name → id, empty for a marker written before refs were kept."
  [eng seed]
  (refs-kept (get-in (load-row eng :member (marker-id seed)) [:data :walked_refs])))

(defn- walk-recorded!
  "Add `ks` to the marker's `walked_steps`, and `rs`, ref name → id, to
  its `walked_refs`: a maintenance write, in place, as the engine keeps
  its other bookkeeping on a member row."
  ([eng seed ks] (walk-recorded! eng seed ks nil))
  ([eng seed ks rs]
   (let [st (:storage eng)
         id (marker-id seed)]
     (store/with-tx st
       (fn [tx]
         (when-some [raw (store/load-row st tx :member id {:for-update true})]
           (store/update-data! st tx :member id
                               (cond-> (assoc (:data raw) :walked_steps
                                              (vec (sort (into (set (map str (get-in raw [:data :walked_steps])))
                                                               ks))))
                                 (seq rs) (assoc :walked_refs
                                                 (into (refs-kept (get-in raw [:data :walked_refs])) rs)))
                               (:next-flip-at raw))))))))

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

(defn- marker-data!
  "Apply `f` to the marker's data: a maintenance write, in place, as
  `walk-recorded!` keeps `walked_steps`."
  [eng seed f]
  (let [st (:storage eng)
        id (marker-id seed)]
    (store/with-tx st
      (fn [tx]
        (when-some [raw (store/load-row st tx :member id {:for-update true})]
          (store/update-data! st tx :member id (f (:data raw))
                              (:next-flip-at raw)))))))

(defn- loading?
  "True when the marker an org's load wrote before its first step still
  says `loading`: that load did not reach its end."
  [eng seed]
  (true? (get-in (load-row eng :member (marker-id seed)) [:data :loading])))

(defn- step-walked!
  "Record that `step` was walked: its key joins the marker's
  `walked_steps`, and the id of `row`, the row it went through with,
  joins its `walked_refs` under the step's ref. The one place a walked
  step is recorded."
  [eng seed step row]
  (let [rn (some-> (:ref step) ref-name)
        id (some-> row :id str)]
    (walk-recorded! eng seed [(step-key step)] (when (and rn id) {rn id}))))

;; ── the steps ───────────────────────────────────────────────────────

(defn- ref-of [refs k]
  (let [r (get refs k)]
    (cond
      (:id r) r
      (:unkept r)
      (throw (refusal (str "The seed points at `" k "`, and the step that made it was walked before the seed's marker kept refs, so its row is not known.")
                      {:ref k :unkept true}))
      :else
      (throw (refusal (str "The seed points at `" k "`, and no earlier step made a row of that name.")
                      {:ref k})))))

(defn- value-of
  "A step's value with its load-time forms filled in."
  [{:keys [eng cast refs now day-one params] :as ctx} form]
  (let [[op arg] (when (and (vector? form) (= 2 (count form))) form)]
    (case op
      :param (param-of params form)
      :ref (:id (ref-of refs arg))
      :self (let [{:keys [kind id]} (ref-of refs arg)]
              (str "/api/" (:plural (get (inv/resources eng) kind)) "/" id))
      :cast (member-id cast arg)
      :days (str (.plusDays ^LocalDate (or day-one
                                           (LocalDate/ofInstant ^Instant now ZoneOffset/UTC))
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
                       :template (:template t)
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

(defn- aliased
  "`given` with each param a template's :aliases names, old → new,
  taken from the old name when the caller gave only that one."
  [tmpl given]
  (reduce-kv (fn [g old new]
               (if (and (contains? g old) (not (contains? g new)))
                 (assoc g new (get g old))
                 g))
             given (:aliases tmpl)))

(defn- defaulted
  "`given` with each param a template's :defaults names, param →
  default, filled when the caller did not give it: a keyword default
  takes the value of the param it names, when that one is given; any
  other default is the value itself."
  [tmpl given]
  (reduce-kv (fn [g k d]
               (cond (contains? g k) g
                     (keyword? d) (if (contains? g d) (assoc g k (get g d)) g)
                     :else (assoc g k d)))
             given (:defaults tmpl)))

(defn- items
  "A list param's value as its items: a comma-separated text split and
  trimmed, a collection as itself."
  [v]
  (if (coll? v)
    (mapv str v)
    (into [] (comp (map str/trim) (remove str/blank?)) (str/split (str v) #","))))

(defn filled
  "A template's body with each `{{hole}}` filled from `given`, param
  keyword → value. A hole inside a string takes the value's text. A
  string that is one hole whole takes a number or a collection as
  itself, so a budget stays a decimal; inside a vector it splices a
  list in, so a template's scope takes an org's own entries beside its
  fixed ones. A seed's unresolved `[:ref …]` stands as its text. A hole
  `given` does not name is left.

  `tmpl`, the template's own :template map, may name :lists, params
  whose value is a comma-separated list: a vector element holding such
  a hole is repeated once for each item, the hole taking that item, so
  a scope gets one entry per kind. Elsewhere the hole takes the list's
  text. Its :aliases, old name → new, let a caller that still gives the
  old param fill the new one. Its :defaults, param → default, make a
  param optional: one not given takes the value of the param a keyword
  default names, or the default itself."
  ([body given] (filled body given nil))
  ([body given tmpl]
   (let [lists (set (:lists tmpl))
         given (into {} (map (fn [[k v]]
                               [k (cond (unresolved? v) (str v)
                                        (and (lists k) (coll? v)) (str/join "," v)
                                        :else v)]))
                     (defaulted tmpl (aliased tmpl given)))
         raw (fn [g x]
               (when-some [k (whole-hole x)]
                 (let [v (get g k)]
                   (when (or (number? v) (coll? v)) v))))
         listed (fn [g x]
                  (some #(when (and (contains? g %)
                                    (str/includes? (pr-str x) (str "{{" (name %) "}}")))
                           %)
                        lists))]
     (letfn [(walk [g x]
               (cond
                 (map? x) (into (empty x) (map (fn [[k v]] [k (walk g v)])) x)
                 (vector? x) (into [] (mapcat #(if-some [k (listed g %)]
                                                 (map (fn [item] (walk (assoc g k item) %))
                                                      (items (get g k)))
                                                 (let [v (raw g %)]
                                                   (if (sequential? v) v [(walk g %)]))))
                                   x)
                 (set? x) (into (empty x) (map (partial walk g)) x)
                 (string? x) (if-some [v (raw g x)]
                               v
                               (reduce-kv (fn [s k v] (str/replace s (str "{{" (name k) "}}") (str v)))
                                          x g))
                 :else x))]
       (walk given body)))))

(defn- fill
  "A template's body with each `{{hole}}` its params name filled from
  `given`. Throws for a param not given, and with no default, and for
  a hole left over."
  [{:keys [params body template]} given template-name]
  (let [given (defaulted template (aliased template given))]
    (when-some [missing (seq (remove #(contains? given %) (keys params)))]
      (throw (refusal (str "The seed's template `" template-name "` needs the params "
                           (str/join ", " (map name missing)) ", and the step does not give them.")
                      {:template template-name :missing (vec missing)})))
    (let [made (filled body (select-keys given (keys params)) template)]
      (when-some [left (re-find #"\{\{[a-z_]+\}\}" (pr-str made))]
        (throw (refusal (str "The seed's template `" template-name "` has the hole " left
                             ", and no param of the template fills it.")
                        {:template template-name :hole left})))
      made)))

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

(defn- house-model
  "The id of the house's model: the active model row the factory seed
  makes. nil when the engine holds none."
  [eng]
  (when (get (inv/resources eng) :model)
    (let [st (:storage eng)
          rows (store/with-tx st (fn [tx] (store/query-rows st tx :model {} {})))]
      (some-> (first (filter #(= "active" (some-> (:state %) name)) rows))
              :id str))))

(defn- house-model!
  "The id of the house's model, made first when the engine holds none
  (ticket 239d8101): an org booted without the factory seed has no
  model row, so its seeded seats could not be sat. The made row is
  named for the boot's `:model` param when it has one, else `house`;
  its prices are zero. nil when the engine serves no model kind."
  [{:keys [eng params] :as ctx} step]
  (when (get (inv/resources eng) :model)
    (or (house-model eng)
        (let [given (some-> (:model params) str str/trim not-empty)]
          (some-> (:row (inv/create! eng :model
                                     {:name (or given "house")
                                      :display (or given "The house's model")
                                      :vendor "house"
                                      :tier "frontier"
                                      :price_input_per_mtok 0M
                                      :price_output_per_mtok 0M
                                      :price_cache_read_per_mtok 0M
                                      :price_cache_write_per_mtok 0M}
                                     (hand-of ctx step)))
                  :id str)))))

(defn- made!
  "Create what a template or create step makes, through its kind's
  door. A seat whose body names no `held_for` is held for the house's
  model (ticket 7e177f8d), made first when there is none (ticket
  239d8101). → the made row."
  [{:keys [eng] :as ctx} kind body step]
  (let [body (if (and (= :seat (keyword kind)) (empty? (:held_for body)))
               (if-some [m (house-model! ctx step)] (assoc body :held_for [m]) body)
               body)]
    (:row (inv/create! eng kind body (made-opts ctx step)))))

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

    ;; a seeded seat's schedule, written as the engine: the kind admits
    ;; no other hand. Its runner list goes in through the seed door of
    ;; the state the row stands in, which leaves a list already named.
    (:schedule step)
    (let [seat (load-row eng :seat (:id (ref-of refs (:schedule step))))]
      (when-not (seats/interactive-seat? seat)
        (let [row (sch/ensure-schedule! eng seat)
              runners (mapv str (value-of ctx (or (:runners step) [])))
              door (get sch/seed-doors (keyword (name (:state row))))]
          (if (and (seq runners) door)
            (:row (inv/invoke! eng :schedule (str (:id row)) door
                               {:runners runners}
                               {:principal sch/system-actor}))
            row))))

    ;; a factory kit template, filled and created through its kind's door
    (:template step)
    (let [[kind body] (made-body ctx step)]
      (made! ctx kind body step))

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
      (made! ctx kind body step))

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
                [(made! ctx kind body step) true])))

    (:template step)
    (let [[kind body] (made-body ctx step)]
      (if-some [nm (:name body)]
        (cond
          (some? (named-row eng kind nm)) [(named-row eng kind nm) false]
          known? [nil false]
          :else [(made! ctx kind body step) true])
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

    (and known? (:ref step) (:on step) (:id (get refs (:on step))))
    [(get refs (:on step)) false]

    known? [nil false]

    :else [(step! ctx step) true]))

(defn- recalled
  "The ref a known step the load skipped binds, from the id the marker
  kept for it. {:unkept true} when it kept none, the step walked before
  refs were kept, so a step that points at it refuses saying so."
  [refs step id]
  (let [kind (if (:on step) (:kind (get refs (:on step))) (kind-of refs step))]
    (if (and id kind) {:kind kind :id id} {:unkept true})))

(defn- day-one-of
  "The day one a boot was given, YYYY-MM-DD or a LocalDate, as a
  LocalDate, or nil when none was given. Throws for one that is not a
  date."
  ^LocalDate [day-one]
  (when-some [s (some-> day-one str str/trim not-empty)]
    (try (LocalDate/parse s)
         (catch java.time.format.DateTimeParseException _
           (throw (refusal (str "The seed is refused: its day one `" s
                                "` is not a date (YYYY-MM-DD).")
                           {:day-one s}))))))

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
  the engine's clock when absent; relative dates count from it, unless
  `:day-one` (YYYY-MM-DD) is given: then `[:days n]` counts from that
  day, the scene's day one the clone was stood up with. `:wall-url` is the clone's wall: when
  it is given, the seed's `:walled` servers are made at it, and when
  it is not, none is made. `:params` are the values `[:param k]` reads.
  `:org` is the org boot's alone (`admit!`): a cast member the engine
  already holds is then reused as it is, and the marker records the org.
  Under `:org` a load that finds no marker but finds a row a step is
  born with stopped half-way: it is resumed, additively as over a
  marker, and `:resumed true` said. An org's load writes its marker
  before its first step, `loading true`, records each step's key as it
  is walked, and sets `loading` false at its end; a load that finds the
  marker still `loading` resumes, skipping the steps it records."
  [eng {:keys [cast steps walled] :as seed} {:keys [now day-one wall-url params org] :as opts}]
  (admit! eng seed opts)
  (let [day-one (day-one-of day-one)
        org (some-> org str not-empty)
        loading (boolean (and org (loading? eng seed)))
        seeded (and (seeded? eng seed) (not loading))
        cast (filled-cast cast (keyed params))
        ;; reused members are expected under :org, so a half-way load
        ;; is told by its marker still loading, or by the rows its steps
        ;; made, and resumed, not refused
        resumed (boolean (and (not seeded) org
                              (or loading (half-walked? eng seed))))
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
      ;; an org's load is marked before its first step, and records
      ;; each step as it is walked
      (when (and org (not seeded) (not loading))
        (mark! eng seed org)
        (marker-data! eng seed #(assoc % :walked_steps [] :loading true)))
      (let [walked (cond
                     seeded
                     (or (walked-of eng seed)
                         ;; a marker from before keys were kept: every
                         ;; step the seed holds now counts as walked
                         (let [all (into #{} (map step-key) steps)]
                           (walk-recorded! eng seed all)
                           all))
                     ;; a marker still loading holds the keys walked;
                     ;; with none, each step is found by its name or
                     ;; its id, or walked
                     resumed (or (walked-of eng seed) #{}))
            kept (when additive (walked-refs-of eng seed))
            bound (when additive (bound-of eng seed))
            {:keys [refs made]}
            (reduce
                  (fn [{:keys [refs lost] :as acc} [i step]]
                    (let [ctx {:eng eng :cast cast :refs refs :now now
                               :day-one day-one
                               :params (keyed params) :seed (:seed seed)
                               :bound bound}
                          [row made? lost? recall]
                          (try
                                (if additive
                                  (let [k (step-key step)
                                        known? (contains? walked k)
                                        [row made? lost?]
                                        (try
                                          (gain! ctx step known?)
                                          ;; a step that needs a ref no row was
                                          ;; bound to is skipped, and not walked
                                          (catch clojure.lang.ExceptionInfo e
                                            (if (contains? lost (:ref (ex-data e)))
                                              [nil false true]
                                              (throw e))))
                                        rn (some-> (:ref step) ref-name)
                                        id (some-> row :id str)]
                                    ;; the key as the step goes through, and
                                    ;; its ref's id beside it
                                    (when (and (not lost?)
                                               (or (not known?) (and rn id (not= id (get kept rn)))))
                                      (step-walked! eng seed step row))
                                    [row made? lost?
                                     (when (and known? rn (nil? row) (not lost?))
                                       (recalled refs step (get kept rn)))])
                                  (let [row (step! ctx step)]
                                    (when org
                                      (step-walked! eng seed step row))
                                    [row true nil]))
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
                        recall (assoc-in [:refs (:ref step)] recall)
                        (and lost? (:ref step)) (update :lost conj (:ref step))
                        (and row (:ref step))
                        (assoc-in [:refs (:ref step)]
                                  {:kind (kind-of refs step)
                                   :id (str (:id row))}))))
                  {:refs {} :made 0 :lost #{}}
                  (map-indexed vector steps))
            refs (into {} (filter (comp :id val)) refs)]
        (if seeded
          {:seed (:seed seed) :seeded false
           :added {:members (count fresh) :steps made} :refs refs
           :skipped (if (and (empty? fresh) (zero? made))
                      "This engine already holds the seed's completion marker, and the seed gained nothing since, so nothing was added."
                      (str "This engine already holds the seed's completion marker, so only what the seed gained was added: "
                           (count fresh) " members and " made " steps."))}
          (do
            ;; last, and only after every step went through; an org's
            ;; marker was written first, and is now done loading
            (when-not org
              (mark! eng seed org))
            (walk-recorded! eng seed (map step-key steps)
                            (into {} (map (fn [[k v]] [(ref-name k) (:id v)])) refs))
            (when org
              (marker-data! eng seed #(assoc % :loading false)))
            (when resumed
              (println (str "resumed half-seeded org " org)))
            (cond-> {:seed (:seed seed) :seeded true
                     :members (count cast) :steps (count steps) :refs refs}
              resumed (assoc :resumed true))))))))

(defn boot!
  "The boot step behind WAYMARK10_SEED: read the named seed and load
  it, saying what happened. Throws for a name this commit carries no
  seed for, and for everything `load!` throws for. `opts` are
  `load!`'s: `:wall-url` is the clone's wall, from WAYMARK_WALL_URL,
  `:params`, when absent, are read from WAYMARK10_SEED_PARAMS, and
  `:day-one`, when absent, from WAYMARK10_SEED_DAY_ONE: the scene's
  day one the clone was stood up with."
  ([eng seed-name] (boot! eng seed-name {}))
  ([eng seed-name opts]
   (let [seed (or (read-seed seed-name)
                  (throw (refusal (str "The seed is refused: this commit carries no seed named `"
                                       seed-name "`.")
                                  {:seed (str seed-name)})))
         _ (admit! eng seed)
         opts (cond-> (or opts {})
                (not (contains? opts :params))
                (assoc :params (read-params (System/getenv "WAYMARK10_SEED_PARAMS")))
                (not (contains? opts :day-one))
                (assoc :day-one (System/getenv "WAYMARK10_SEED_DAY_ONE")))
         result (load! eng seed opts)]
     (println (if (:seeded result)
                (str "waymark10 seed: `" (:seed result) "` loaded, "
                     (:members result) " members and " (:steps result) " steps.")
                (str "waymark10 seed: " (:skipped result))))
     result)))
