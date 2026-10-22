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
  `:live-ok`, so it loads whole on a fresh engine, and on every boot
  after it loads additively: by its marker, only the cast members and
  the steps the seed gained since are added (`seed/load!`).

  OR THE ORG IS ON DISK, AND LIVE (ticket 29f8e07a). Its manifest,
  `orgs/<org>/org.edn`, says `{:name \"<org>\" :live true}` once it went
  live. `live-orgs` lists the live org directories under
  WAYMARK10_ORGS_DIR, and `live-resources` and `boot-live!` load each
  one's src and resources through a DynamicClassLoader
  (`with-classpath`), so the house engine serves them with none a
  deps root.

  A LIVE ORG THAT FAILS IS SKIPPED, NOT FATAL (ticket 77ad4315).
  `admit` judges each live org's kinds beside everything admitted
  before it; an org whose kinds do not load or fail the registry — a
  kind name the house or an earlier org already serves among them — is
  left out with a crack naming it, and the engine boots without it.
  So is one whose seed is refused at boot (ticket 087753c1):
  `boot-live!` skips it with a line and a draft ticket, and the boot
  goes on."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store])
  (:import (clojure.lang Compiler DynamicClassLoader)
           (java.io File)))

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

(declare orgs-dir with-classpath manifest)

(defn- on-classpath?
  "True when the org's module `<org>/org.clj` is on the classpath."
  [org]
  (some? (io/resource (str org "/org.clj"))))

(defn disk-dir
  "The org's directory on disk, for an org not on the classpath (ticket
  8c331ba3): `<org>` under the dir the WAYMARK10_ORGS_DIR value
  `dir-value` names (`orgs-dir`), when it is a directory holding
  `org.edn`. Live or not: WAYMARK10_ORG names the org. nil when the org
  is on the classpath, or when no such directory is."
  ^File [org dir-value]
  (when-not (on-classpath? org)
    (let [d (io/file (orgs-dir dir-value) (str org))]
      (when (.isFile (io/file d "org.edn")) d))))

(defn- with-org-dir
  "Call f with the org on the classpath: as it is, or through
  `with-classpath` over its `disk-dir` when only the disk holds it."
  [org dir-value f]
  (if-some [d (disk-dir org dir-value)]
    (with-classpath [d] f)
    (f)))

(defn resources
  "The kinds the org's module `<org>.org` declares: from the classpath
  first, and else from the org's `disk-dir`. `dir-value` is
  WAYMARK10_ORGS_DIR's value, read from the environment when not given.
  Throws when neither holds the module."
  ([org] (resources org (System/getenv "WAYMARK10_ORGS_DIR")))
  ([org dir-value]
   (or (with-org-dir org dir-value
         #(when (on-classpath? org)
            (vec ((requiring-resolve (symbol (str org ".org") "resources"))))))
       (throw (ex-info (str "WAYMARK10_ORG names the org `" org "`, and `" org
                            ".org` is not on the classpath, nor in `"
                            (.getPath (io/file (orgs-dir dir-value) (str org)))
                            "` with an org.edn beside it: add the org's directory as a deps root, or put it under WAYMARK10_ORGS_DIR.")
                       {:org org})))))

(defn read-seed
  "The org's seed, `<org>/seed.edn` on the classpath, or else in the
  org's `disk-dir`; nil when the org carries none. `dir-value` is as
  `resources` takes it."
  ([org] (read-seed org (System/getenv "WAYMARK10_ORGS_DIR")))
  ([org dir-value]
   (with-org-dir org dir-value
     #(some-> (io/resource (str org "/seed.edn")) slurp edn/read-string))))

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

(defn- cast-seat?
  "Whether a seed's seat entry seats a cast member: a whole seat, or a
  template's seat, that says `:acts_as_cast true`."
  [{:keys [template seat]}]
  (true? (:acts_as_cast
          (or seat
              (some-> (io/resource (str "seats/" template ".edn"))
                      slurp edn/read-string :seat)))))

(defn rehearsed
  "The org's seed as this engine loads it (ticket a4149376). Its
  `:rehearsal` — the outside world's `:cast`, `:seats` and `:steps` —
  joins it on a clone and is dropped everywhere else, so a live org
  never holds a pretend client nor seats the world, whose acts_as_cast
  only a clone admits. Off a clone, `:skip-cast-seats?` (org.edn's
  `:rehearsal-seats :skip-off-clone`) leaves out a seat of the seed's
  own `:seats` that acts as cast, with a line, instead of its refusal."
  [{:keys [rehearsal] :as org-seed} {:keys [clone? skip-cast-seats?]}]
  (let [s (dissoc org-seed :rehearsal)]
    (cond
      clone?
      (-> s
          (update :cast merge (:cast rehearsal))
          (update :seats (fnil into []) (:seats rehearsal))
          (update :steps (fnil into []) (:steps rehearsal)))

      skip-cast-seats?
      (let [{skipped true kept false} (group-by (comp boolean cast-seat?) (:seats s))]
        (doseq [e skipped]
          (println (str "waymark10 org: `" (:org s) "`: the seat "
                        (or (get-in e [:seat :name]) (:template e))
                        " acts as cast, and this engine is not a clone: skipped.")))
        (assoc s :seats (vec kept)))

      :else s)))

(defn- made-goal
  "One entry of a seed's `:goals` as a loader step: a create through the
  goal door, walked as the owner — {:measure \"kind.name\" :op \">=\"
  :target 0.95}. A clone boots from the same seed, so it holds the
  house's goals; readings are never seeded, and each engine sweeps its
  own (docs/spec-measures.md §7)."
  [goal]
  {:as :owner :kind :goal :create goal})

(defn loader-seed
  "An org's seed in the loader's grammar: `:live-ok`, with its domains,
  then its seats, judgments and goals, made first, as the owner, and its
  own steps after. `opts` are `rehearsed`'s: with none, the engine is not a
  clone, and the seed's `:rehearsal` is left out."
  ([org-seed] (loader-seed org-seed {}))
  ([org-seed opts]
   (let [{:keys [domains seats judgments goals steps] :as s} (rehearsed org-seed opts)]
     (assoc (dissoc s :goals)
            :live-ok true
            :steps (-> []
                       (into (map made-domain) domains)
                       (into (map made-step) seats)
                       (into (map made-step) judgments)
                       (into (map made-goal) goals)
                       (into steps))))))

(defn boot-line
  "The line `boot!` prints for an org's `seed/load!` answer: what was
  seeded, or, on an engine seeded before, `:added` beside the sentence
  that says what was skipped."
  [org result]
  (if (:seeded result)
    (str "waymark10 org: `" org "` seeded, " (:members result)
         " members and " (:steps result) " steps.")
    (str "waymark10 org: `" org "`: :added " (pr-str (:added result))
         " · " (:skipped result))))

(defn boot!
  "The boot step behind WAYMARK10_ORG: load the org's seed, whole on a
  fresh engine and additively on one seeded with it before, adding only
  what the seed gained since, and say what it added. → `seed/load!`'s
  answer; nil for an org that carries no seed. Throws for everything
  `seed/load!` throws for. `opts` are `seed/load!`'s, and `:params`, when
  absent, are read from WAYMARK10_SEED_PARAMS. An org not on the
  classpath is loaded from its `disk-dir`; `:orgs-dir`, when given, is
  WAYMARK10_ORGS_DIR's value, and otherwise read from the environment."
  ([eng org] (boot! eng org {}))
  ([eng org opts]
   (let [dir-value (get opts :orgs-dir (System/getenv "WAYMARK10_ORGS_DIR"))
         opts (dissoc opts :orgs-dir)]
     (with-org-dir org dir-value
       #(when-some [s (read-seed org dir-value)]
          (let [opts (cond-> (assoc (or opts {}) :org (str org))
                       (not (contains? opts :params))
                       (assoc :params (seed/read-params (System/getenv "WAYMARK10_SEED_PARAMS"))))
                m (try (manifest (io/file (orgs-dir dir-value) (str org)))
                       (catch Exception _ nil))
                result (seed/load! eng
                                   (loader-seed s {:clone? (true? (:clone? eng))
                                                   :skip-cast-seats? (= :skip-off-clone
                                                                        (:rehearsal-seats m))})
                                   opts)]
            (println (boot-line org result))
            result))))))

;; ── live orgs, found on disk ────────────────────────────────────────

(defn manifest
  "The org's manifest, `<org-dir>/org.edn`: at least `{:name \"<org>\"
  :live false}`, which the orgkit starter writes and going live flips
  to `:live true`. nil when the org has none; throws when it does not
  read."
  [org-dir]
  (let [f (io/file org-dir "org.edn")]
    (when (.isFile f)
      (edn/read-string (slurp f)))))

(defn live?
  "True when the org at `org-dir` says it is live: its manifest reads
  and holds `:live true`. A missing or unreadable manifest is not live."
  [org-dir]
  (true? (:live (try (manifest org-dir) (catch Exception _ nil)))))

(def ^:private image-orgs-dir
  "Where the image copies orgs/, from the engine's working directory
  (/app/workqueue10 in the image, workqueue10/ in a checkout)."
  "../orgs")

(defn orgs-dir
  "The directory a WAYMARK10_ORGS_DIR value names, or the image's orgs
  path when the value is blank."
  ^File [value]
  (io/file (or (some-> value str str/trim not-empty) image-orgs-dir)))

(defn live-orgs
  "Every live org under `dir`, each `{:org name :dir file}`, sorted by
  name: a directory whose name is an org's name and whose manifest says
  it is live. [] when `dir` is no directory."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter (fn [^File d]
                 (and (.isDirectory d)
                      (re-matches org-pattern (.getName d))
                      (live? d))))
       (map (fn [^File d] {:org (.getName d) :dir d}))
       (sort-by :org)
       vec))

(defn with-classpath
  "Call f with each org directory's src and resources on the classpath,
  through a DynamicClassLoader over this thread's, so an org on disk
  need not be a deps root. What f loads stays loaded after."
  [dirs f]
  (let [t (Thread/currentThread)
        prev (.getContextClassLoader t)
        cl (DynamicClassLoader. prev)]
    (doseq [d dirs
            p ["src" "resources"]]
      (.addURL cl (.toURL (.toURI (io/file d p)))))
    (.setContextClassLoader t cl)
    (try
      (with-bindings {Compiler/LOADER cl} (f))
      (finally (.setContextClassLoader t prev)))))

(defn live-resources
  "The kinds of the live orgs `live-orgs` answered, each loaded from its
  directory: every `<org>.org`'s `resources`, in order."
  [orgs]
  (with-classpath (map :dir orgs)
    #(into [] (mapcat (fn [{:keys [org]}] (resources org))) orgs)))

(defn admit
  "The live orgs `orgs` folded into `base` one at a time, in order,
  each judged by `check` (the engine's `full-registry`) over `base`
  and every org admitted before it. An org whose kinds do not load, or
  that `check` refuses, is skipped with a crack naming it. → {:resources
  the kinds to serve :orgs the orgs admitted :cracks [{:org :crack
  :org_refused :reason sentence}]}."
  [base orgs check]
  (with-classpath (map :dir orgs)
    #(reduce
      (fn [acc {:keys [org] :as o}]
        (let [rs (try (let [rs (into (:resources acc) (resources org))]
                        (check rs)
                        rs)
                      (catch Exception e e))]
          (if (instance? Exception rs)
            (let [reason (or (ex-message rs) (str rs))]
              (println (str "waymark10 org: `" org "` skipped, and the engine boots without it: " reason))
              (update acc :cracks conj {:org org :crack :org_refused :reason reason}))
            (-> acc (assoc :resources rs) (update :orgs conj o)))))
      {:resources (vec base) :orgs [] :cracks []}
      orgs)))

(defn- crack-ticket!
  "The draft ticket an org skipped at boot earns, filed as the engine for
  the mayor to groom: one per org and sentence, so a boot refused the
  same way again files nothing new. Best effort: a ticket that will not
  file is a line, and the boot goes on. → true when one was filed."
  [eng org reason]
  (when (contains? (inv/resources eng) :ticket)
    (let [st (:storage eng)
          k (str "org-crack:" org ":" (hash reason))
          title (str "org " org " did not load at boot: " reason)]
      (when-not (store/with-tx st (fn [tx] (store/idempotency-lookup st tx k :ticket)))
        (try
          (inv/create! eng :ticket
                       {:title (subs title 0 (min 140 (count title)))
                        :detail (str "The live org `" org "`'s seed was refused at boot,"
                                     " so the house booted without it.\n\n" reason)
                        :repo "ckopsa/waymark"
                        :type "bug"
                        :priority 1}
                       {:principal schedules/system-actor
                        :idempotency-key k})
          true
          (catch Exception e
            (println (str "waymark10 org: the crack ticket for `" org
                          "` would not file — " (ex-message e)))
            nil))))))

(defn boot-live!
  "The boot step behind WAYMARK10_ORGS_DIR: each live org's seed, read
  from its directory and loaded as `boot!` loads one. → per org that
  carries a seed, its `boot!` answer, or, when its seed is refused,
  {:org :crack :org_refused :reason sentence}: that org is skipped with
  a line and a draft ticket (`crack-ticket!`), and the orgs after it
  load (ticket 087753c1). One org's refusal never ends the boot."
  [eng orgs opts]
  (with-classpath (map :dir orgs)
    #(into []
           (keep (fn [{:keys [org]}]
                   (try
                     (boot! eng org opts)
                     (catch Exception e
                       (let [reason (or (ex-message e) (str e))]
                         (println (str "waymark10 org: `" org "` did not load at boot, and the engine boots without it: " reason))
                         (crack-ticket! eng org reason)
                         {:org org :crack :org_refused :reason reason})))))
           orgs)))
