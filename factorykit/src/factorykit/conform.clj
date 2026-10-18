(ns factorykit.conform
  "Org conformance (ticket 86f96963, M1c): one suite every org passes,
  generated or hand-written, judging its kinds and seats the way the
  engine would, as forge-conformance judges every forge.
  `(conform org-dir)` answers findings, each
  {:key :severity :class :subject :sentence :remedy}, in a stable order
  and with keys that are the same on every run. An org passes when it
  answers none.

      clojure -M:conform ../orgs/example     ; from factorykit/
      clojure -M:conform ../orgs             ; every org under it

  The classes, in the order they are judged:

  load      the org's module loads, and `resources` answers its kinds
  seed      the seed reads, names the org, and every seat template it
            names fills with no hole left
  resource  every kind passed the defresource gate
  registry  the kinds assemble beside the ones the engine enrols
  crack     cracks/audit over the org's kinds and its seed's seats,
            pure: declarations and seat maps, no store
  sentence  every guard explains itself, and every confirm door says
            its consequence
  boot      a memory engine answers discover, schema, query, get and
            a dry run of each create, for every kind
  scope     every seat's scope names kinds and actions the booted
            engine serves; a seat whose seed entry names an :owner
            names a cast member, and sits inside that member's :scope,
            its ceiling (a member with no :scope holds the whole org)

  Every finding is an error: the CLI exits 1 on any."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.check :as check]
            [waymark10.dev :as dev]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.wire :as wire])
  (:import (clojure.lang Compiler DynamicClassLoader)
           (java.io File))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- text
  "A keyword's whole spelling, namespace and all; anything else as str."
  [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn- finding [class parts subject sentence remedy]
  {:key (str/join "/" (cons (name class) (map text parts)))
   :severity :error
   :class class
   :subject subject
   :sentence sentence
   :remedy remedy})

(defn- root-message [^Throwable e]
  (loop [e e]
    (if-some [c (ex-cause e)] (recur c) (or (ex-message e) (str e)))))

(defn- sentence? [s]
  (and (string? s) (not (str/blank? s))))

;; ── load ────────────────────────────────────────────────────────────

(defn- with-org-classpath
  "Call f with the org's src and resources on the classpath, so an org
  directory need not be a dep of whoever runs the suite."
  [^File dir f]
  (let [t (Thread/currentThread)
        prev (.getContextClassLoader t)
        cl (DynamicClassLoader. prev)]
    (doseq [p ["src" "resources"]]
      (.addURL cl (.toURL (.toURI (io/file dir p)))))
    (.setContextClassLoader t cl)
    (try
      (with-bindings {Compiler/LOADER cl} (f))
      (finally (.setContextClassLoader t prev)))))

(defn- load-org [dir org]
  (let [module (str org ".org")]
    (try
      (let [rs (with-org-classpath
                 dir #((requiring-resolve (symbol module "resources"))))]
        (if (and (sequential? rs) (seq rs))
          {:resources (vec rs)}
          {:findings [(finding :load [org] module
                               (str module "/resources answered no kinds.")
                               "Declare the org's kinds in resources, stamped by in-domain.")]}))
      (catch Throwable e
        {:findings [(finding :load [org] module
                             (str module " did not load: " (root-message e))
                             (str "Make src/" org "/org.clj load and declare resources, as docs/orgkit.md says."))]}))))

;; ── seed ────────────────────────────────────────────────────────────

(defn- escaped
  "A param value as it may stand inside an EDN string."
  [v]
  (let [s (pr-str (str v))] (subs s 1 (dec (count s)))))

(defn- fill-template [{:keys [template params]}]
  (when-some [res (io/resource (str "seats/" template ".edn"))]
    (let [s (reduce-kv (fn [s k v] (str/replace s (str "{{" (name k) "}}") (escaped v)))
                       (pr-str (:seat (edn/read-string (slurp res))))
                       (or params {}))]
      {:seat (edn/read-string s)
       :holes (vec (distinct (re-seq #"\{\{[^}]*\}\}" s)))})))

(defn- seed-seats
  "The seed's seats as whole seat maps, and what kept any from filling."
  [seed]
  (reduce
   (fn [acc [i {:keys [seat template owner] :as entry}]]
     (cond
       seat (update acc :seats conj (cond-> seat owner (assoc :owner owner)))
       template
       (if-some [{:keys [seat holes]} (fill-template entry)]
         (cond-> (update acc :seats conj (cond-> seat owner (assoc :owner owner)))
           (seq holes)
           (update :findings conj
                   (finding :seed ["holes" (:name seat)] (str "seat " (:name seat))
                            (str "The " template " template's seat keeps holes its params do not fill: "
                                 (str/join ", " holes) ".")
                            "Give the seed's :params a value for every hole the template names.")))
         (update acc :findings conj
                 (finding :seed ["template" template] (str "seats[" i "]")
                          (str "The seed names the seat template " template ", and factorykit ships none by that name.")
                          "Name a template under factorykit/resources/seats/, or write the whole seat under :seat.")))
       :else
       (update acc :findings conj
               (finding :seed ["seat" i] (str "seats[" i "]")
                        "A seed seat names neither a template nor a whole seat."
                        "Write {:template … :params …} or {:seat {…}}."))))
   {:seats [] :findings []}
   (map-indexed vector (:seats seed))))

;; ── resource, registry ──────────────────────────────────────────────

(defn- resource-findings [rs]
  (for [r rs
        :when (not (check/declaration? r))
        :let [k (text (:kind r))]]
    (finding :resource [k] k
             (str "The kind " k " did not pass the defresource gate.")
             "Declare it with defresource, so the gate judges it before the engine does.")))

(defn- registry-findings [org rs]
  (try
    (engine/full-registry rs)
    nil
    (catch Throwable e
      [(finding :registry [org] org
                (str "The kinds do not assemble beside the engine's own: " (root-message e))
                "Rename a kind the engine already enrols, or mend what the registry names.")])))

;; ── crack ───────────────────────────────────────────────────────────

(defn- seat-row [seat]
  {:id (:name seat) :state :active :data seat})

(def ^:private crack-key
  [:kind :state :door :remedy :on :awaits :outcome :field :value])

(defn- crack-findings [rs seats]
  (for [c (cracks/audit rs (map seat-row seats))
        :let [subject (str/join "/" (keep #(some-> (get c %) text) [:kind :state]))]]
    (finding :crack (cons (:crack c) (keep #(some-> (get c %) text) crack-key))
             subject
             (str "The cracks audit finds " (:crack c) " at " subject
                  (when-some [d (:door c)] (str ", door " d))
                  (when-some [on (:on c)] (str ", waiting on " on))
                  ".")
             (or (:what_would_close_it c)
                 "Give an active seat a door out of the state and a wake_on that brings it there."))))

;; ── sentence ────────────────────────────────────────────────────────

(defn- sentence-findings [rs]
  (for [r (sort-by (comp text :kind) rs)
        :let [k (text (:kind r))]
        [aname a] (sort-by (comp text key) (:actions r))
        :let [door (str k "." (text aname))]
        f (concat
           (for [g (:guards a)
                 :when (and (map? g) (not (sentence? (:explain g))))]
             (finding :sentence [k aname (:name g)] door
                      (str "The guard " (text (:name g)) " on " door
                           " carries no sentence, so its refusal would say nothing.")
                      "Give the guard its sentence: (refuse \"…\") in defguard, or :explain."))
           (let [c (get-in a [:safety :consequence])]
             (when (and (true? (get-in a [:safety :confirm]))
                        (not (or (sentence? c) (and (map? c) (seq c)))))
               [(finding :sentence [k aname "consequence"] door
                         (str "The confirm door " door " says no consequence to acknowledge.")
                         "Write the door's :confirm sentence: what happens, that cannot be undone.")])))]
    f))

;; ── boot ────────────────────────────────────────────────────────────

(def ^:private walker
  {"x-waymark-principal" "conformance" "x-waymark-actor-type" "system"})

(defn- call [h method uri query body]
  (h (cond-> {:request-method method :uri uri :headers walker}
       query (assoc :query-string query)
       body (assoc :body (wire/write-json body)))))

(defn- ok? [resp] (<= 200 (long (or (:status resp) 0)) 299))
(defn- broke? [resp] (<= 500 (long (or (:status resp) 500))))

(defn- boot-finding [door k resp]
  (finding :boot [door k] k
           (str "A booted memory engine does not answer " door " for " k
                ": status " (:status resp) ".")
           "Boot the org with waymark10.dev/scratch! and read what the engine says."))

(defn- first-creates
  "Each kind's first create body among the seed's steps."
  [seed]
  (reduce (fn [m {:keys [kind create]}]
            (let [kind (keyword (text kind))]
              (cond-> m (and (map? create) (not (contains? m kind))) (assoc kind create))))
          {} (:steps seed)))

(defn- boot
  "Boot the kinds over memory and walk the doors every kind must
  answer. {:engine e :findings […]}; no engine when it did not boot."
  [org rs seed]
  (try
    (let [eng (dev/scratch! rs)
          h (engine/handler eng)
          served (inv/resources eng)
          creates (first-creates seed)
          known (call h :get "/api/.well-known/waymark" nil nil)
          listed (when (ok? known) (:resources (wire/read-json (:body known))))]
      {:engine eng
       :findings
       (into (if (ok? known) [] [(boot-finding "discover" org known)])
             (for [r (sort-by (comp text :kind) rs)
                   :let [kind (:kind r)
                         k (text kind)
                         base (str "/api/" (or (:plural (get served kind)) (:plural r)))
                         body (get creates kind)]
                   f (concat
                      (when (and listed (not (contains? listed kind)))
                        [(boot-finding "discover" k known)])
                      (let [resp (call h :get (str "/api/schemas/" k) nil nil)]
                        (when-not (ok? resp) [(boot-finding "schema" k resp)]))
                      (let [resp (call h :get base nil nil)]
                        (when-not (ok? resp) [(boot-finding "query" k resp)]))
                      (let [resp (call h :post base "dry_run=1" (or body {}))]
                        (when (broke? resp) [(boot-finding "dry_run" k resp)]))
                      (when body
                        (let [made (call h :post base nil body)
                              id (when (ok? made) (:id (wire/read-json (:body made))))
                              resp (when id (call h :get (str base "/" id) nil nil))]
                          (cond
                            (broke? made) [(boot-finding "create" k made)]
                            (and resp (not (ok? resp))) [(boot-finding "get" k resp)]))))]
               f))})
    (catch Throwable e
      {:findings [(finding :boot ["engine" org] org
                           (str "A memory engine does not boot the org: " (root-message e))
                           "Boot the org with waymark10.dev/scratch! and mend what it throws.")]})))

;; ── scope ───────────────────────────────────────────────────────────

(defn- holds? [entries kind action]
  (some #(and (= kind (text (:kind %)))
              (some #{action "*"} (map text (:actions %))))
        entries))

(defn- scope-findings [eng seats seed]
  (let [vocab (into {} (map (fn [[k rd]] [(text k) (inv/action-names rd)]))
                    (inv/resources eng))]
    (for [{seat :name :keys [scope owner]} seats
          :let [member (when owner (get (:cast seed) (keyword (text owner))))
                ceiling (:scope member)]
          f (concat
             (when (and owner (nil? member))
               [(finding :scope ["owner" seat] (str "seat " seat)
                         (str "The seat " seat " names the owner " (text owner) ", who is not in the cast.")
                         "Name a cast member as the seat's :owner.")])
             (for [{:keys [kind actions]} scope
                   :let [k (text kind)
                         known (get vocab k)]
                   f (if (nil? known)
                       [(finding :scope ["unknown-kind" seat k] (str "seat " seat)
                                 (str "The seat " seat "'s scope names the kind " k ", which the engine does not serve.")
                                 "Name a kind the org declares or the engine enrols.")]
                       (for [a (map text actions)
                             f (concat
                                (when-not (contains? known a)
                                  [(finding :scope ["unknown-action" seat k a] (str "seat " seat)
                                            (str "The seat " seat "'s scope names " k "." a ", which " k " does not declare.")
                                            (str "Name one of " k "'s actions: " (str/join ", " known) "."))])
                                (when (and ceiling (not (holds? ceiling k a)))
                                  [(finding :scope ["outside-owner" seat k a] (str "seat " seat)
                                            (str "The seat " seat " holds " k "." a ", which its owner " (text owner) " does not.")
                                            "Narrow the seat's scope to its owner's, or widen the owner's first.")]))]
                         f))]
               f))]
      f)))

;; ── the suite ───────────────────────────────────────────────────────

(defn conform
  "Every finding the org at org-dir earns, in a stable order; [] when
  it conforms. The directory's name is the org's name."
  [org-dir]
  (let [dir (.getCanonicalFile (io/file org-dir))
        org (.getName dir)
        seed-file (io/file dir "resources" org "seed.edn")
        seed (try (edn/read-string (slurp seed-file)) (catch Throwable e e))]
    (if (instance? Throwable seed)
      [(finding :seed ["file" org] org
                (str "resources/" org "/seed.edn does not read: " (root-message seed))
                "Write the seed as docs/orgkit.md § The seed says.")]
      (let [{rs :resources :as loaded} (load-org dir org)
            {:keys [seats] :as seeded} (seed-seats seed)]
        (if-not rs
          (:findings loaded)
          (let [booted (boot org rs seed)]
            (vec (concat
                  (when (not= org (:org seed))
                    [(finding :seed ["org" org] org
                              (str "The seed names the org " (pr-str (:org seed)) ", not " org ".")
                              "Set the seed's :org to the org's directory name.")])
                  (:findings seeded)
                  (resource-findings rs)
                  (registry-findings org rs)
                  (crack-findings rs seats)
                  (sentence-findings rs)
                  (:findings booted)
                  (when-some [eng (:engine booted)]
                    (scope-findings eng seats seed))))))))))

(defn- org-dirs
  "An org directory as itself; any other directory as the orgs in it."
  [arg]
  (let [d (io/file arg)]
    (if (.isFile (io/file d "deps.edn"))
      [d]
      (sort-by #(.getName ^File %)
               (filter #(.isFile (io/file ^File % "deps.edn")) (.listFiles d))))))

(defn -main
  "Judge each org named, or each under a directory named. Exit 1 on
  any error finding, 2 when no org was found."
  [& args]
  (let [dirs (mapcat org-dirs (or (seq args) ["."]))
        errors (reduce
                (fn [n ^File d]
                  (let [fs (conform d)]
                    (println (str (.getName (.getCanonicalFile d)) ": "
                                  (if (seq fs) (str (count fs) " finding(s)") "conforms")))
                    (doseq [{:keys [severity key sentence remedy]} fs]
                      (println (str "  [" (name severity) "] " key " " sentence))
                      (println (str "      " remedy)))
                    (+ n (count (filter #(= :error (:severity %)) fs)))))
                0 dirs)]
    (shutdown-agents)
    (System/exit (cond (empty? dirs) 2 (pos? (long errors)) 1 :else 0))))
