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

  IT REFUSES A WORKING ENGINE. The engine's name must begin with
  `demo-` and no IdP may be configured. A step the law refuses throws,
  and the boot ends. An engine that already holds a member of the cast
  was seeded before, and the whole seed is skipped.

  A step's values may name what is only known at load: `[:ref :t1]` is
  the id of the row a step made, `[:self :t1]` is that row's path,
  `[:cast :ada]` is a cast member's id, and `[:days 3]` is a date
  counted from the boot."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
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

(defn- enrol!
  "One cast member's row: `members/ensure-sitter!`'s create, with the
  cast's id as the row id, so the dev principal header finds it."
  [eng cast who]
  (let [{:keys [display type acts-for]} (get cast who)
        id (member-id cast who)]
    (inv/create! eng :member
                 (cond-> {:display (str display)
                          :actor_type (name (or type :human))
                          :subject id}
                   acts-for (assoc :acts_for (member-id cast acts-for)))
                 {:principal members/registrar :id id})))

(defn- load-row [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- seeded? [eng cast]
  (boolean (some #(load-row eng :member (member-id cast %)) (keys cast))))

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
  "Seed this engine: enrol the cast, then walk the steps in order.
  → {:seed :seeded true :members n :steps n :refs {name {:kind :id}}},
  or {:seed :seeded false :skipped sentence} for an engine seeded
  before. Throws on an engine `admit!` refuses and on the first step
  the law refuses. `:now` is the boot's moment, the engine's clock
  when absent; relative dates count from it."
  [eng {:keys [cast steps] :as seed} {:keys [now]}]
  (admit! eng)
  (if (seeded? eng cast)
    {:seed (:seed seed) :seeded false
     :skipped "This engine already holds a member of the seed's cast, so the seed was applied before and is skipped whole."}
    (let [now (or now ((or (:now-fn eng) #(Instant/now))))]
      ;; people first: an agent's row names the person it acts for
      (doseq [who (sort-by (juxt #(some? (get-in cast [% :acts-for])) str)
                           (keys cast))]
        (enrol! eng cast who))
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
        {:seed (:seed seed) :seeded true
         :members (count cast) :steps (count steps) :refs refs}))))

(defn boot!
  "The boot step behind WAYMARK10_SEED: read the named seed and load
  it, saying what happened. Throws for a name this commit carries no
  seed for, and for everything `load!` throws for."
  [eng seed-name]
  (admit! eng)
  (let [seed (or (read-seed seed-name)
                 (throw (refusal (str "The seed is refused: this commit carries no seed named `"
                                      seed-name "`.")
                                 {:seed (str seed-name)})))
        result (load! eng seed {})]
    (println (if (:seeded result)
               (str "waymark10 seed: `" (:seed result) "` loaded, "
                    (:members result) " members and " (:steps result) " steps.")
               (str "waymark10 seed: " (:skipped result))))
    result))
