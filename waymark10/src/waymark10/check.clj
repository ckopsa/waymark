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
  first definition error OR a broken law scenario; exit 2 when
  nothing checkable was named.

  A broken scenario exits 1 and a usability warning does not, and the
  difference is the whole point of the distinction: a warning is an
  opinion about how a declaration reads, a scenario is a promise the
  household wrote down and the law stopped keeping."
  (:require [clojure.string :as str]
            [waymark10.modules :as modules]
            [waymark10.scenario :as scenario]
            [waymark10.server.engine :as engine]
            [waymark10.usability :as usability])
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
  first definition error (the caller owns the exit code).

  Enrollment goes first, and deliberately: a resources vector that
  fights the enrollment table (waymark10.modules) usually dies in the
  registry a breath later with `one law per kind`, which names the
  collision but not the module that owns the other half. Saying it
  before the assembly runs means the author reads the explanation
  first.

  Law scenarios (waymark-442.2) print under their kind with the tier
  split named: the ones this battery judged for free, and the ones
  waiting for the suite with the reason they wait. Nothing here opens
  a store — a check-tier scenario is judged by g/evaluate over a row
  the author wrote down, which is this namespace's whole posture and
  the reason the tier rule exists at all.

  The USABILITY BATTERY (waymark10.usability, waymark-0ee) prints
  under the kind beside the gate's own warnings, and it runs over the
  ENROLLED kinds too — the grant, the member, the role and their
  siblings, which no application names in its resources vector and
  which every application therefore serves unexamined. An enrolled
  kind gets a row only when the battery has something to say about
  it; the framework's own forms are the first fix-list this bead
  wanted, and they are only visible from here."
  [resources]
  (let [enrollment-warnings (modules/warnings resources nil)
        _ (doseq [w enrollment-warnings]
            (println (str "  [enrollment] " w)))
        reg (engine/full-registry resources)
        assembly-warnings (:waymark10/warnings (meta reg))
        rows (for [r resources]
               {:kind (:kind r)
                :warnings (vec (:waymark10/warnings (meta r)))
                :usability (usability/warnings r)
                :deviations (vec (:deviations r))
                :scenarios (scenario/report r)
                :coverage (scenario/coverage r)})
        enrolled (for [r (modules/enrolled resources nil)
                       :let [u (usability/warnings r)]
                       :when (seq u)]
                   {:kind (:kind r) :enrolled true :warnings []
                    :usability u :deviations []})
        all-rows (concat rows enrolled)]
    (doseq [{:keys [kind enrolled warnings usability deviations scenarios
                    coverage]}
            (sort-by :kind all-rows)]
      (let [n (+ (count warnings) (count usability))]
        (println (str "  " (name kind) (when enrolled " (enrolled)")
                      (if (pos? n)
                        (str " — " n " warning" (when (not= 1 n) "s"))
                        " ✓"))))
      (doseq [w warnings]
        (println (str "      " w)))
      (doseq [w usability]
        (println (str "      " w)))
      (doseq [d deviations]
        (println (str "      deviation: " d)))
      (let [{:keys [total checked deferred violations]} scenarios
            [named walls] coverage]
        (when (pos? (long (or total 0)))
          (println (str "      " checked " scenario"
                        (when (not= 1 checked) "s")
                        (if (seq violations)
                          (str " — " (count violations) " broken")
                          " ✓")
                        (when (seq deferred)
                          (str "  (" (count deferred) " deferred to the suite: "
                               (str/join "; " (distinct (keep :why deferred))) ")"))))
          ;; counted, never enforced: a usability warning here would
          ;; fire on every action in the tree the day it landed, and a
          ;; warning nobody can clear is a warning nobody reads
          (println (str "      " walls " refusing guard"
                        (when (not= 1 walls) "s") ", " named
                        " named by a scenario"))
          (doseq [v violations]
            (println (str "      ✗ " v))))))
    (doseq [w assembly-warnings]
      (println (str "  [assembly] " w)))
    {:kinds (count rows)
     :warnings (+ (reduce + 0 (map (comp count :warnings) all-rows))
                  (reduce + 0 (map (comp count :usability) all-rows))
                  (count assembly-warnings)
                  (count enrollment-warnings))
     :scenarios (reduce + 0 (map (comp :checked :scenarios) rows))
     :broken (reduce + 0 (map (comp count :violations :scenarios) rows))}))

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
      (let [{:keys [kinds warnings scenarios broken]} (report resources)]
        (println (str (cond (pos? broken) "✗ "
                            (zero? warnings) "✓ "
                            :else "△ ")
                      kinds " kind" (when (not= 1 kinds) "s")
                      ", " warnings " warning" (when (not= 1 warnings) "s")
                      (when (pos? scenarios)
                        (str ", " scenarios " scenario"
                             (when (not= 1 scenarios) "s") " judged"))
                      (when (pos? broken)
                        (str ", " broken " BROKEN"))))
        (System/exit (if (pos? broken) 1 0)))
      (catch clojure.lang.ExceptionInfo e
        (when-not (:waymark10/definition-error (ex-data e))
          (throw e))
        (println (str "✗ " (ex-message e)))
        (when-some [c (:check (ex-data e))]
          (println (str "    check: " (name c))))
        (when-some [p (:path (ex-data e))]
          (println (str "    path:  " (pr-str p))))
        (System/exit 1)))))
