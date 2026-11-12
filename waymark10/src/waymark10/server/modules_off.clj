(ns waymark10.server.modules-off
  "The optional kinds an engine may switch off, engine-wide (ticket
  f66a2057): the `:modules-off` setting names them, and a kind it names
  is OFF only when it has no rows, in any state, at boot. A kind with
  rows stays on whatever the setting says, and the boot says so in one
  line ('work_calendar stays on: 1 row'). That rule is the whole
  safety: nothing with data can be switched off by mistake.

  The setting is the engine option `:modules-off` (a set of kind
  keywords), else the environment's WAYMARK10_MODULES_OFF (kind names
  separated by commas or spaces), read once at boot beside the engine's
  other options in `waymark10.server.engine/engine`. A deployed house
  sets it in its environment: WAYMARK10_MODULES_OFF=work_calendar,goal.
  Absent, it is empty, and the engine serves every kind it always has.

  An off kind is left out of the registry the boot assembles, so
  discover, schema, the cracks audit, the MCP kind list and the plural
  routes never see it. What reads one gets its documented default: no
  work_calendar means no calendar governs (calendar days; the law's
  calendar readers answer unknown, and the boot seeds no default), no
  goal means default-goals writes none, no team confers no role, no
  attachment unmounts its routes and its purge and retention sweepers,
  no effect_call makes a door declaring an :effect refuse naming this
  setting (the integrations module keeps its other routes and its
  applier), and no job stops the jobs worker and its orphan sweep.

  A door, a create or a scope entry naming an off kind refuses with
  `sentence`, naming the setting, rather than the unknown-kind answer."
  (:require [clojure.string :as str]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def optional
  "The kinds the setting may name; a name outside this set refuses the
  boot rather than being silently ignored."
  #{:work_calendar :goal :team :attachment :effect_call :job})

(defn off?
  "Did the engine's setting switch `kind` (a keyword or a name) off?"
  [eng kind]
  (boolean (some->> kind name keyword (contains? (:modules-off eng)))))

(defn sentence
  "Why an off kind is not served, naming the setting and its remedy."
  [kind]
  (str (name kind) " is switched off on this engine by :modules-off"
       " (WAYMARK10_MODULES_OFF); take it out of that setting and restart"
       " to serve it."))

(defn refusal
  "The problem a door or a create naming an off kind answers."
  [kind]
  (p/problem :module-off 404 "Switched off" {:detail (sentence kind)}))

(defn setting
  "The kinds the engine's setting names: `:modules-off` from opts, else
  WAYMARK10_MODULES_OFF read through `getenv`. Empty when neither says.
  A name outside `optional` refuses the boot, naming the ones it may
  name."
  [opts getenv]
  (let [raw (if (contains? opts :modules-off)
              (:modules-off opts)
              (some->> (getenv "WAYMARK10_MODULES_OFF") (re-seq #"[^,\s]+")))
        named (into #{} (map (comp keyword name)) raw)
        unknown (sort (map name (remove optional named)))]
    (when (seq unknown)
      (throw (ex-info (str ":modules-off (WAYMARK10_MODULES_OFF) names "
                           (str/join ", " unknown)
                           ", which cannot be switched off; it may name "
                           (str/join ", " (sort (map name optional))))
                      {:check :modules-off :unknown (vec unknown)})))
    named))

(defn- row-count
  "The kind's rows in every state, or nil when they cannot be counted."
  [storage rdef]
  (try
    (store/ensure-kind! storage rdef)
    (store/with-tx storage
      (fn [tx] (store/count-matching storage tx (:kind rdef) [])))
    (catch Exception _ nil)))

(defn off!
  "The kinds that are off: those `named` that the registry `reg`
  serves and that have no rows in any state. A named kind with rows, or
  whose rows cannot be counted, stays on, and the boot says which."
  [storage reg named]
  (into #{}
        (filter
         (fn [kind]
           (when-some [rdef (get (:kinds reg) kind)]
             (let [n (row-count storage rdef)]
               (binding [*out* *err*]
                 (println
                  (cond
                    (nil? n) (str (name kind) " stays on: its rows could not be counted")
                    (pos? n) (str (name kind) " stays on: " n (if (= 1 n) " row" " rows"))
                    :else (str (name kind) " is off (:modules-off)"))))
               (and (some? n) (zero? n))))))
        (sort named)))
