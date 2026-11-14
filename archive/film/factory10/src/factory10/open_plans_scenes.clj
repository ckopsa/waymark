(ns factory10.open-plans-scenes
  "Every open plan's first week, checked by THIS code (ticket d9937a28).

  #1996 changed how a first-week scene renders a person's calls. Its
  test used a toy row name, and the open dress plan's \"birch-2025\"
  broke on it: nothing ran the change against the weeks of the plans
  open at the time. The `open-plans-scenes` job of tests.yml fetches the
  org directory of each open bench/* pull request that carries a
  week.edn, at that request's head, and hands them here: each org's
  first-week scene is rendered by this checkout's `first-week/org-scene`
  and judged by the scene kind's own `check` door, beside the org's
  kinds, in a memory engine. Any refusal fails the job, naming the
  plan's pull request and the refusal's sentence.

  An org that does not load at its head (a definition or assembly
  error) is the plan's own error, which org-findings reports on its own
  pull request (ticket 5b3b7878): it is listed as skipped with the first
  line of that error, and does not fail the job.

  Each argument is `<label>=<head sha>=<org dir>`: the label names the
  plan's pull request (`#1996`), the sha the head its directory was
  fetched at."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [factory10.first-week :as first-week]
            [factory10.main :as main]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

(def ^:private mayor (t/principal {:id "mayor" :display "Mayor"}))

(defn- first-line
  "The first line of what stopped `e`: its innermost cause's message."
  [^Throwable e]
  (let [^Throwable root (last (take-while some? (iterate ex-cause e)))]
    (first (str/split-lines (or (not-empty (ex-message root)) (str (class root)))))))

(defn- load-org
  "A memory engine serving the kinds of the org at `org-dir` beside the
  factory's, or throws what stopped the org loading."
  [org-dir]
  (let [kinds (first-week/read-resources org-dir (:org (first-week/read-seed org-dir)))]
    (engine/engine {:storage (memory/storage)
                    :resources (into (vec (main/resources)) kinds)})))

(defn- scene-refusal
  "Why the scene check on `eng` refuses the org's first-week scene,
  rendered by this code: the refusal's sentence, or nil when it checks
  ready. A scene that does not render is refused with what stopped it."
  [eng org-dir head-sha day-one]
  (try
    (let [scene (:scene (edn/read-string (first-week/org-scene org-dir head-sha day-one)))
          opts {:principal mayor}
          id (str (:id (:row (inv/create! eng :scene scene opts))))]
      (inv/invoke! eng :scene id :check {}
                   (assoc opts :idempotency-key (str (random-uuid))))
      nil)
    (catch Exception e
      (or (inv/problem-reason e) (ex-message e) (str (class e))))))

(defn verdict
  "The org at `org-dir`, checked by this code at `head-sha` with day 1 on
  `day-one`: {:skipped first error line} when the org does not load,
  {:refused sentence} when it loads and its scene is refused or does
  not render, {} when it checks ready."
  [org-dir head-sha day-one]
  (let [loaded (try {:engine (load-org org-dir)}
                    (catch Exception e {:skipped (first-line e)}))]
    (if-let [eng (:engine loaded)]
      (if-let [why (scene-refusal eng org-dir head-sha day-one)] {:refused why} {})
      loaded)))

(defn refusal
  "Why the org at `org-dir` does not check ready: the scene refusal's
  sentence, or the first line of what stopped the org loading; nil when
  it checks ready."
  [org-dir head-sha day-one]
  (let [{:keys [refused skipped]} (verdict org-dir head-sha day-one)]
    (or refused skipped)))

(def none-open
  "What the report says when no open plan carries a week."
  "No open plan pull request carries a week.edn: nothing to check.")

(defn report
  "Each plan of `args` (`<label>=<head sha>=<org dir>`) checked with day
  1 on `day-one`: {:ok true when none was refused, :lines one line a
  plan, `ready: ...`, `skipped: does not load at its head: ...` with the
  first error line or `refused: ...` with the refusal's sentence, then
  one `summary: ...` line counting each}."
  [args day-one]
  (if (empty? args)
    {:ok true :lines [none-open]}
    (let [verdicts (vec (for [arg args
                              :let [[label sha dir] (str/split arg #"=" 3)
                                    plan (str label " (" (.getName (io/file dir))
                                              " at " (subs sha 0 (min 7 (count sha))) ")")
                                    {:keys [skipped refused]} (verdict dir sha day-one)]]
                          (cond
                            skipped [:skipped (str "skipped: does not load at its head: " plan ": " skipped)]
                            refused [:refused (str "refused: " plan ": " refused)]
                            :else [:ready (str "ready: " plan)])))
          n (frequencies (map first verdicts))]
      {:ok (zero? (n :refused 0))
       :lines (conj (mapv second verdicts)
                    (str "summary: " (+ (n :ready 0) (n :refused 0)) " checked, "
                         (n :skipped 0) " skipped, " (n :refused 0) " failed"))})))

(defn -main
  "Print the report of the plans `args` name, day 1 today, and exit 1
  when any was refused."
  [& args]
  (let [{:keys [ok lines]} (report args (str (LocalDate/now)))]
    (doseq [l lines] (println l))
    (flush)
    (shutdown-agents)
    (System/exit (if ok 0 1))))
