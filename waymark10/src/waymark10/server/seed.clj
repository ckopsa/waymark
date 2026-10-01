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
  and the boot ends.

  IT MARKS A WHOLE LOAD. After the last step the loader makes one more
  row through the member door, `seed-<name>`: the completion marker. An
  engine that holds the marker was seeded before, and the whole seed is
  skipped. An engine that holds a member of the cast and no marker
  stopped half-way through a seed, and the load refuses.

  A step's values may name what is only known at load: `[:ref :t1]` is
  the id of the row a step made, `[:self :t1]` is that row's path,
  `[:cast :ada]` is a cast member's id, and `[:days 3]` is a date
  counted from the boot."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
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

(defn admit!
  "Throws unless this engine may be seeded: it is named `demo-…` and
  has no IdP. A working engine is neither."
  [eng]
  (let [n (str (:name eng))]
    (cond
      (not (str/starts-with? n "demo-"))
      (throw (refusal (str "The seed is refused: this engine is named `" n
                           "`, and a seed loads only on an engine whose name begins with `demo-`.")
                      {:engine n}))

      (some? (:oidc eng))
      (throw (refusal "The seed is refused: this engine has an identity provider, and a seed loads only on an engine with none."
                      {:engine n})))))

;; ── the cast ────────────────────────────────────────────────────────

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
  member's `:roles` ride the create, as a provider's claim would."
  [eng cast who]
  (let [{:keys [display type acts-for roles]} (get cast who)
        id (member-id cast who)]
    (inv/create! eng :member
                 (cond-> {:display (str display)
                          :actor_type (name (or type :human))
                          :subject id}
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
  [{:keys [eng cast refs now] :as ctx} form]
  (let [[op arg] (when (and (vector? form) (= 2 (count form))) form)]
    (case op
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

(defn- kind-of [refs step]
  (cond
    (:hold step) :held_call
    (contains? step :create) (:kind step)
    :else (:kind (ref-of refs (:on step)))))

(defn- step!
  "Walk one step. → the row it made or moved."
  [{:keys [eng cast refs] :as ctx} step]
  (cond
    ;; the one step that is not an ordinary invoke: the engine's own
    ;; door, the one the power door mints a held call through
    (:hold step)
    (:row (held-calls/hold! eng (value-of ctx (:hold step))))

    (contains? step :create)
    (:row (inv/create! eng (:kind step) (value-of ctx (:create step))
                       {:principal (principal-of cast (:as step))}))

    :else
    (let [{:keys [kind id]} (ref-of refs (:on step))
          action (keyword (:action step))
          rdef (get (inv/resources eng) kind)
          ;; a fenced door names the version it read (dev/act!'s rule)
          fenced? (get-in rdef [:actions action :safety :fence])
          opts (cond-> {:principal (principal-of cast (:as step))}
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
  it is not, none is made."
  [eng {:keys [cast steps walled] :as seed} {:keys [now wall-url]}]
  (admit! eng)
  (cond
    (seeded? eng seed)
    {:seed (:seed seed) :seeded false
     :skipped "This engine already holds the seed's completion marker, so the seed was applied before and is skipped whole."}

    (enrolled? eng cast)
    (throw (refusal "The seed is refused: a seed failed half-way on this database; bring the clone down."
                    {:seed (:seed seed) :half-seeded true}))

    :else
    (let [now (or now ((or (:now-fn eng) #(Instant/now))))]
      (register-roles! eng cast)
      ;; people first: an agent's row names the person it acts for
      (doseq [who (sort-by (juxt #(some? (get-in cast [% :acts-for])) str)
                           (keys cast))]
        (enrol! eng cast who))
      (when-some [url (some-> wall-url str not-empty)]
        (wall-servers! eng walled url))
      (let [refs (reduce
                  (fn [refs [i step]]
                    (let [ctx {:eng eng :cast cast :refs refs :now now}
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
  `load!`'s: `:wall-url` is the clone's wall, from WAYMARK_WALL_URL."
  ([eng seed-name] (boot! eng seed-name {}))
  ([eng seed-name opts]
   (admit! eng)
   (let [seed (or (read-seed seed-name)
                  (throw (refusal (str "The seed is refused: this commit carries no seed named `"
                                       seed-name "`.")
                                  {:seed (str seed-name)})))
         result (load! eng seed (or opts {}))]
     (println (if (:seeded result)
                (str "waymark10 seed: `" (:seed result) "` loaded, "
                     (:members result) " members and " (:steps result) " steps.")
                (str "waymark10 seed: " (:skipped result))))
     result)))
