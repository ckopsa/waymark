(ns factory10.scene-check
  "The plan audit's scene check (ticket 50ab921e).

  THE CHECK RUNS BEFORE PROPOSE. The scene kind's `check` door judges a
  scene with scenes/problem-of, and until now it ran only when the
  director created the org's first-week scene on a clone: a scene it
  refused was found after the director had sat. The org-findings job of
  tests.yml now runs `-main` over the scene it has just rendered, and a
  refusal is an error finding, `film/scene-check`. An error finding
  already blocks propose, so a plan whose scene the director cannot play
  is never proposed.

  THE REGISTRY IS THE ONE THE CHECK READS. problem-of consults the
  ctx's :rdef-of, as invoke builds it: the kinds an engine serving
  factory10's kinds and the org's assembles, on memory storage. No
  clone, no database."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [factory10.main :as main]
            [factorykit.conform :as conform]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scenes :as scenes]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(def remedy
  "What a `film/scene-check` finding asks of the org."
  "Fix the org's week or the render so the scene check passes.")

(defn registry
  "The registry consult the scene check reads, as invoke's ctx :rdef-of
  answers it: a kind token or a collection plural to the assembled rdef
  of an engine serving factory10's kinds and `resources`, nil for a kind
  it does not serve."
  [resources]
  (let [rs (inv/resources (engine/engine {:storage (memory/storage)
                                          :resources (into (vec (main/resources)) resources)}))]
    (fn [token]
      (let [t (name token)]
        (or (get rs (keyword t))
            (some (fn [[_ r]] (when (= t (:plural r)) r)) rs))))))

(defn- clip [s n]
  (let [s (str s)] (if (> (count s) n) (subs s 0 n) s)))

(defn findings
  "The scene check over `doc`, first-week/scene's answer, beside the
  org's kind declarations `resources`: [] when its scene would check
  ready, else the one error finding its refusal makes."
  [doc resources]
  (if-some [problem (scenes/problem-of (:scene doc) (registry resources))]
    [{:key "film/scene-check"
      :severity "error"
      :class "film"
      :subject "first_week_scene"
      :sentence (clip problem 240)
      :remedy remedy}]
    []))

(defn org-findings
  "The scene check over `text`, the first-week scene the org at
  `org-dir` rendered as edn, its kinds loaded as first-week loads them.
  A blank text is no scene, and no finding: the render's own step
  already says it carries none."
  [org-dir text]
  (if (str/blank? text)
    []
    (let [doc (edn/read-string text)
          org (str (get-in doc [:scene :seed]))]
      (conform/with-org-classpath
        (io/file org-dir)
        #(findings doc ((requiring-resolve (symbol (str org ".org") "resources"))))))))

(defn -main
  "Print, as one JSON array on the last line, the scene check's findings
  over the scene at `scene-file` rendered for the org at `org-dir`."
  [org-dir scene-file]
  (println (wire/write-json (org-findings org-dir (slurp scene-file))))
  (flush)
  ;; the engine it booted for its registry holds no thread worth waiting on
  (System/exit 0))
