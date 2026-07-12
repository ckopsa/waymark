(ns waymark10.check
  "The declaration-time CI gate: load the app's resources, assemble
  them the way boot would, and surface every usability warning where
  the author actually looks — stdout, with an exit code. waymark9's
  `waymark9 check` fast path, reborn.

      clojure -M -m waymark10.check my.app.main/check-resources
      clojure -M -m waymark10.check my.app.resources.meal …

  Each argument is either a qualified symbol (a var holding a
  declaration, a collection of declarations, or a zero-arg fn
  returning either — the spelling for kinds built by a wrapper, like
  a Mirror adapter) or a bare namespace, whose public defresource'd
  values are collected. Exit 0 with a warning count; exit 1 on the
  first definition error; exit 2 when nothing checkable was named."
  (:require [waymark10.server.engine :as engine])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn declaration?
  "A value that passed the defresource gate — it wears the gate's
  warnings metadata (possibly empty), which nothing else mints."
  [v]
  (and (map? v) (contains? (meta v) :waymark10/warnings)))

(defn- resolve-arg
  "One CLI argument to a vector of declarations."
  [arg]
  (let [s (symbol arg)]
    (if (namespace s)
      (let [val @(requiring-resolve s)
            val (if (fn? val) (val) val)
            vals (if (sequential? val) (vec val) [val])]
        (doseq [v vals]
          (when-not (declaration? v)
            (throw (ex-info (str arg " did not yield resource declarations")
                            {:arg arg}))))
        vals)
      (do (require s)
          (into []
                (keep (fn [[_ v]]
                        (let [val (try @v (catch Throwable _ nil))]
                          (when (declaration? val) val))))
                (vals (ns-publics s)))))))

(defn report
  "Assemble and report. Returns {:kinds n :warnings n} or throws the
  first definition error (the caller owns the exit code)."
  [resources]
  (let [reg (engine/full-registry resources)
        assembly-warnings (:waymark10/warnings (meta reg))
        rows (for [r resources]
               {:kind (:kind r)
                :warnings (vec (:waymark10/warnings (meta r)))
                :deviations (vec (:deviations r))})]
    (doseq [{:keys [kind warnings deviations]} (sort-by :kind rows)]
      (println (str "  " (name kind)
                    (if (seq warnings)
                      (str " — " (count warnings) " warning"
                           (when (not= 1 (count warnings)) "s"))
                      " ✓")))
      (doseq [w warnings]
        (println (str "      " w)))
      (doseq [d deviations]
        (println (str "      deviation: " d))))
    (doseq [w assembly-warnings]
      (println (str "  [assembly] " w)))
    {:kinds (count rows)
     :warnings (+ (reduce + 0 (map (comp count :warnings) rows))
                  (count assembly-warnings))}))

(defn -main [& args]
  (when (empty? args)
    (binding [*out* *err*]
      (println "usage: clojure -M -m waymark10.check <ns | ns/var> …"))
    (System/exit 2))
  (let [resources (into [] (mapcat resolve-arg) args)]
    (when (empty? resources)
      (binding [*out* *err*]
        (println (str "waymark10 check: no resource declarations found in "
                      (vec args))))
      (System/exit 2))
    (try
      (let [{:keys [kinds warnings]} (report resources)]
        (println (str (if (zero? warnings) "✓ " "△ ") kinds " kind"
                      (when (not= 1 kinds) "s") ", " warnings " warning"
                      (when (not= 1 warnings) "s")))
        (System/exit 0))
      (catch clojure.lang.ExceptionInfo e
        (when-not (:waymark10/definition-error (ex-data e))
          (throw e))
        (println (str "✗ " (ex-message e)))
        (when-some [c (:check (ex-data e))]
          (println (str "    check: " (name c))))
        (when-some [p (:path (ex-data e))]
          (println (str "    path:  " (pr-str p))))
        (System/exit 1)))))
