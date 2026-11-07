(ns waymark10.server.quiet
  "Quiet mode: WAYMARK10_QUIET=1 boots an engine that serves and reads
  but sends and writes nothing outside — the green half of a
  blue/green pair, on a database kept in sync from the live one by
  logical replication, checked end to end before cutover
  (docs/quiet-mode.md).

  The choke point is the runtime walker (waymark10.server.runtime):
  in quiet mode a lifecycle hook starts only when it declares
  `:quiet :serves` — a surface that serves requests and neither
  writes nor sends. Every other hook is a background pass and does
  not start, so a new pass is quiet by default and a pass that cannot
  be made quiet never starts at all. The walker says, once, which
  passes it held back."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn env-quiet?
  "Does `env` — any (fn [name] value); tests pass a map — say
  WAYMARK10_QUIET? 1, true and yes say it; anything else, and
  absence, does not."
  ([] (env-quiet? #(System/getenv ^String %)))
  ([env]
   (contains? #{"1" "true" "yes"}
              (some-> (env "WAYMARK10_QUIET") str/trim str/lower-case))))

(defn quiet?
  "Is this engine quiet? An engine carrying :quiet? answers for itself
  (a test, an embedding); otherwise the environment does."
  [eng]
  (if (contains? eng :quiet?)
    (boolean (:quiet? eng))
    (env-quiet?)))

(defn would!
  "Say on stderr what a quiet engine would have written, and did not."
  [what]
  (binding [*out* *err*]
    (println (str "waymark10 quiet: would have written " what))))

(defn announce!
  "The one loud boot line, and the background passes quiet held back,
  counted and named — what this engine WOULD have run."
  [held]
  (binding [*out* *err*]
    (println "waymark10: QUIET MODE, nothing leaves this engine (WAYMARK10_QUIET)")
    (when (seq held)
      (println (str "waymark10 quiet: would have started " (count held)
                    " background pass(es): "
                    (str/join ", " (map name held)))))))
