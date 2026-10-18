(ns waymark10.server.org
  "The org boot (docs/orgkit.md, epic cafa6106 M1b): WAYMARK10_ORG names
  an org, by its directory (`orgs/example`) or by its module namespace
  (`example.org`), and the engine serves that org's kinds and loads its
  seed on a fresh engine.

  THE ORG IS ON THE CLASSPATH. Its directory is a deps root (a
  `:local/root`, or `-Sdeps` at the boot), so `<org>.org` requires like
  any module and `<org>/seed.edn` is a resource. Naming the directory
  names the org: its last segment is the org's name.

  ITS KINDS ARE CHECKED WITH EVERYTHING ELSE. `resources` is the
  module's own vector; the boot folds it into the engine's `:resources`,
  so `full-registry` judges it beside the kinds the engine enrols, and a
  registry that fails never boots.

  ITS SEED IS A LIVE SEED. `loader-seed` puts the org's seed in the
  loader's grammar (`waymark10.server.seed`): each of its `:domains`,
  then its `:seats` and `:judgments`, becomes a step walked as the
  cast's `:owner`, before the org's own `:steps`, and the seed is
  `:live-ok`, so it loads on a fresh engine and is skipped, by its
  marker, on every boot after."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.seed :as seed]))

(set! *warn-on-reflection* true)

(def ^:private org-pattern
  "An org's name is snake_case (docs/orgkit.md § Naming rules)."
  #"[a-z][a-z0-9_]*")

(defn org-name
  "The org a WAYMARK10_ORG value names: a directory's last segment, or
  the root of a `<org>.org` namespace; nil when the value is blank.
  Throws for a value that names no org."
  [value]
  (when-some [v (some-> value str str/trim not-empty)]
    (let [n (-> v
                (str/replace #"/+$" "")
                (str/split #"/")
                last
                (str/replace #"\.org$" ""))]
      (if (re-matches org-pattern n)
        n
        (throw (ex-info (str "WAYMARK10_ORG is `" v "`, and `" n
                             "` is not an org's name: it is snake_case, [a-z][a-z0-9_]* (docs/orgkit.md).")
                        {:org v}))))))

(defn resources
  "The kinds the org's module `<org>.org` declares. Throws when the
  module is not on the classpath."
  [org]
  (if (io/resource (str org "/org.clj"))
    (vec ((requiring-resolve (symbol (str org ".org") "resources"))))
    (throw (ex-info (str "WAYMARK10_ORG names the org `" org "`, and `" org
                         ".org` is not on the classpath: add the org's directory as a deps root.")
                    {:org org}))))

(defn read-seed
  "The org's seed, `<org>/seed.edn` on the classpath; nil when the org
  carries none."
  [org]
  (some-> (io/resource (str org "/seed.edn")) slurp edn/read-string))

(defn- made-step
  "One entry of a seed's `:seats` or `:judgments` as a loader step,
  walked as the owner: a factory kit template and its params, or a
  whole seat (or judgment) map."
  [{:keys [template params seat judgment] :as entry}]
  (cond
    template {:as :owner :template template :params (or params {})}
    seat {:as :owner :kind :seat :create seat}
    judgment {:as :owner :kind :judgment :create judgment}
    :else (throw (ex-info "The org's seed holds a seat or judgment that names neither a :template nor a whole :seat or :judgment."
                          {:waymark10/seed-refused true :entry entry}))))

(def ^:private domain-budget
  "A seed domain's week of fuel when its entry names none: the factory's."
  3600M)

(defn- made-domain
  "One entry of a seed's `:domains` as a loader step: a create through
  the domain door, walked as the owner. Its charter is its `:charter`,
  or its `:label`; its budget is its `:budget_usd_per_week`, or the
  factory's. Its `:ref`, when given, names the row for a later step's
  `[:ref …]`, as a seat template's `:domain_id`."
  [{:keys [name label charter budget_usd_per_week ref] :as entry}]
  (when-not (and (string? name) (seq name))
    (throw (ex-info "The org's seed holds a domain that names no :name."
                    {:waymark10/seed-refused true :entry entry})))
  (cond-> {:as :owner :kind :domain
           :create (merge (select-keys entry [:mayor :owner])
                          {:name name
                           :charter (or charter label name)
                           :budget_usd_per_week (or budget_usd_per_week domain-budget)})}
    ref (assoc :ref ref)))

(defn loader-seed
  "An org's seed in the loader's grammar: `:live-ok`, with its domains,
  then its seats and judgments, made first, as the owner, and its own
  steps after."
  [{:keys [domains seats judgments steps] :as org-seed}]
  (assoc org-seed
         :live-ok true
         :steps (-> []
                    (into (map made-domain) domains)
                    (into (map made-step) seats)
                    (into (map made-step) judgments)
                    (into steps))))

(defn boot!
  "The boot step behind WAYMARK10_ORG: load the org's seed, or skip it
  when this engine was seeded with it before, saying which. → `seed/load!`'s
  answer; nil for an org that carries no seed. Throws for everything
  `seed/load!` throws for. `opts` are `seed/load!`'s, and `:params`, when
  absent, are read from WAYMARK10_SEED_PARAMS."
  ([eng org] (boot! eng org {}))
  ([eng org opts]
   (when-some [s (read-seed org)]
     (let [opts (cond-> (or opts {})
                  (not (contains? opts :params))
                  (assoc :params (seed/read-params (System/getenv "WAYMARK10_SEED_PARAMS"))))
           result (seed/load! eng (loader-seed s) opts)]
       (println (if (:seeded result)
                  (str "waymark10 org: `" org "` seeded, " (:members result)
                       " members and " (:steps result) " steps.")
                  (str "waymark10 org: `" org "`: " (:skipped result))))
       result))))
