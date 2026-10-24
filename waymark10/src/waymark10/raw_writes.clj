(ns waymark10.raw-writes
  "The declaration-time check over raw `store/update-data!`
  (docs/spec-leases.md §4). A maintenance write goes through
  `store/maintain!` with a reason from `store/maintenance-reasons`;
  anything else is a door. A raw write is the store's own, invoke's and
  the migration code's, and the transition sites listed below until
  the build ticket that makes each one a door.

  It reads the engine's source text, not its vars: the reason is
  written nowhere but the call site, so the call site is what it
  judges. `waymark10.check/run` runs it, so `make check-queue` and
  `make check-factory` fail on what it finds."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.store :as store])
  (:import (java.io File)
           (java.net URL)))

(set! *warn-on-reflection* true)

(defn- own-raw?
  "A source path that writes raw by right: the store and its backends,
  the migration code beside them, and invoke."
  [path]
  (or (= "server/store.clj" path)
      (= "server/invoke.clj" path)
      (str/starts-with? path "server/store/")))

(def known-exceptions
  "The transition sites still written raw, as [path top-level-form],
  each with the build ticket that makes it a door (docs/spec-leases.md
  §4, §6). THE LIST ONLY SHRINKS: an entry that writes nothing raw is
  itself a problem."
  {["server/seats.clj" "claim-sitting*"]
   "leases 3a: the sit's transition writes the connector claim"
   ["server/seats.clj" "claim-rows!"]
   "leases 6: walked_rows goes with its last readers"
   ["server/seats.clj" "claim-rows-atomically!"]
   "leases 6: walked_rows goes with its last readers"
   ["server/runner_links.clj" "rearm!"] "leases 5b"
   ["server/schedules.clj" "defer-fire!"] "leases 5b"
   ["server/schedules.clj" "defer-until-elsewhere-lifts!"] "leases 5b"})

(def migrations
  "Idempotent backfills that live beside their kind: migration code,
  raw by right."
  #{["server/seats.clj" "backfill-expiries!"]
    ["server/seats.clj" "backfill-domain-ids!"]})

(def known-smells
  "Maintenance fields a decision reads (§4, *Smells left as :stamp*):
  written through `store/maintain!`, and listed so the reader of the
  check sees them. Whether the puller holds a lease on its link is a
  follow-up, not leases."
  {["server/runner_links.clj" "seen!"]
   "puller_seen_at: runner_links/pull-waiting holds a fire off a pull link no localfire polls"})

(def ^:private top-form
  #"^\((?:defn-?|defmacro|defmethod|defonce|def)\s+(?:\^\S+\s+)*([^\s\[\]()]+)")

(def ^:private raw-call #"\(store/update-data!(?:\s|$)")

(def ^:private maintain-call #"\(store/maintain!(?:\s+(\S+))?(?:\s|$)")

(defn- file-sites
  [path text]
  (loop [lines (str/split-lines text) n 1 form nil acc []]
    (if-some [line (first lines)]
      (let [form (or (second (re-find top-form line)) form)
            code? (not (str/starts-with? (str/triml line) ";"))
            m (when code? (re-find maintain-call line))
            site {:path path :line n :fn form}
            acc (cond-> acc
                  (and code? (re-find raw-call line)) (conj (assoc site :call :raw))
                  m (conj (assoc site :call :maintain :reason (second m))))]
        (recur (rest lines) (inc n) form acc))
      acc)))

(defn sites
  "Every raw write and every `store/maintain!` call in the .clj files
  under `root`, one map each: :path (relative to root, slash-separated),
  :line, :fn (the top-level form it sits in), :call (:raw or :maintain)
  and, for :maintain, :reason — the token after the name on its line,
  nil when the line has none."
  [^File root]
  (let [base (.toPath root)]
    (into []
          (mapcat (fn [^File f]
                    (file-sites (str/replace (str (.relativize base (.toPath f)))
                                             File/separatorChar \/)
                                (slurp f))))
          (sort (filter (fn [^File f]
                          (and (.isFile f) (str/ends-with? (.getName f) ".clj")))
                        (file-seq root))))))

(defn source-root
  "The engine's source directory (the one holding server/), read from
  the classpath; nil when it is not a directory there."
  ^File []
  (when-some [^URL u (io/resource "waymark10/server/store.clj")]
    (when (= "file" (.getProtocol u))
      (.getParentFile (.getParentFile (io/file u))))))

(defn- reason-ok? [token]
  (and (some? token)
       (str/starts-with? token ":")
       (contains? store/maintenance-reasons (keyword (subs token 1)))))

(defn problems
  "One sentence per problem under `root` (the classpath's engine source
  when none is given): a raw write outside the store, invoke, the
  migrations and the known exceptions; a `store/maintain!` whose reason
  is not one of the set; and a list entry that no longer holds."
  ([]
   (if-some [root (source-root)]
     (problems root)
     ["raw writes went unjudged: waymark10/server is not on the classpath as files"]))
  ([root]
   (let [all (sites root)
         at (fn [call]
              (into #{} (comp (filter #(= call (:call %))) (map (juxt :path :fn))) all))
         raw (at :raw)
         kept (at :maintain)]
     (-> []
         (into (keep (fn [{:keys [path line call reason] form :fn}]
                       (let [where (str path ":" line " " form)]
                         (case call
                           :raw
                           (when-not (or (own-raw? path)
                                         (contains? known-exceptions [path form])
                                         (contains? migrations [path form]))
                             (str where " writes raw store/update-data!: a maintenance"
                                  " write goes through store/maintain! with a reason,"
                                  " anything else is a door"))
                           :maintain
                           (when-not (reason-ok? reason)
                             (str where " names " (or reason "no reason on its line")
                                  ", not one of "
                                  (str/join " " (sort store/maintenance-reasons))))))))
               all)
         (into (for [k (sort (concat (keys known-exceptions) migrations))
                     :when (not (raw k))]
                 (str (str/join " " k)
                      " is listed as raw but writes nothing raw: take it off the list")))
         (into (for [k (sort (keys known-smells))
                     :when (not (kept k))]
                 (str (str/join " " k)
                      " is a known smell with no store/maintain! write: take it off the list")))))))
