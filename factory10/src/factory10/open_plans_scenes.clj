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

(defn refusal
  "Why the scene check refuses the first-week scene of the org at
  `org-dir`, rendered by this code at `head-sha` with day 1 on
  `day-one`: the refusal's sentence, or nil when the scene checks
  ready. A scene that does not render is refused with what stopped it."
  [org-dir head-sha day-one]
  (try
    (let [kinds (first-week/read-resources org-dir (:org (first-week/read-seed org-dir)))
          scene (:scene (edn/read-string (first-week/org-scene org-dir head-sha day-one)))
          eng (engine/engine {:storage (memory/storage)
                              :resources (into (vec (main/resources)) kinds)})
          opts {:principal mayor}
          id (str (:id (:row (inv/create! eng :scene scene opts))))]
      (inv/invoke! eng :scene id :check {}
                   (assoc opts :idempotency-key (str (random-uuid))))
      nil)
    (catch Exception e
      (or (inv/problem-reason e) (ex-message e) (str (class e))))))

(def none-open
  "What the report says when no open plan carries a week."
  "No open plan pull request carries a week.edn: nothing to check.")

(defn report
  "Each plan of `args` (`<label>=<head sha>=<org dir>`) checked with day
  1 on `day-one`: {:ok true when none was refused, :lines one line a
  plan, `ready: ...` or `refused: ...` with the refusal's sentence}."
  [args day-one]
  (if (empty? args)
    {:ok true :lines [none-open]}
    (let [lines (vec (for [arg args
                           :let [[label sha dir] (str/split arg #"=" 3)
                                 plan (str label " (" (.getName (io/file dir))
                                           " at " (subs sha 0 (min 7 (count sha))) ")")
                                 why (refusal dir sha day-one)]]
                       (if why
                         (str "refused: " plan ": " why)
                         (str "ready: " plan))))]
      {:ok (not-any? #(str/starts-with? % "refused: ") lines)
       :lines lines})))

(defn -main
  "Print the report of the plans `args` name, day 1 today, and exit 1
  when any was refused."
  [& args]
  (let [{:keys [ok lines]} (report args (str (LocalDate/now)))]
    (doseq [l lines] (println l))
    (flush)
    (shutdown-agents)
    (System/exit (if ok 0 1))))
