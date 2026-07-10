(ns waymark10.server.judgment
  "The judgment overlay (waymark9 design §1): the row's law judges the
  row. Every path that applies law to a row — render's probe, the
  out-of-state concealment probe, the invoker's enforcement loop —
  resolves the row's guards through resolve-action, which substitutes
  a non-resident revision's stored guard trees for the resident
  declaration's.

  v10 collapses waymark9's judgment_served/judgment_laws twins into
  one rdef slot, :judgment-laws — {revision → stored fingerprint},
  one entry per revision that must be served from the store: the
  grandfathered laws, and the CURRENT law while a propose-mode hold
  or a pilot keeps newer code resident. Resolution is then a single
  lookup: a row whose stamp has an entry is judged from that
  revision's stored trees; everything else (the resident revision,
  a proposed/piloted stamp, the nil pre-law horizon) gets the
  resident guards verbatim — the resident code IS that law.

  Substitution is positional AND name-checked, waymark9's rule
  verbatim: a stored entry applies only to the resident guard at the
  same index with the same name. Code guards (a check= hash), the
  composite internals, a mismatched position — the resident guard
  serves, which is exactly v8's behavior, never worse. A stored entry
  that cannot be read back does not crash the request: the resident
  guard serves and *err* says so (a law served approximately is
  named, never silent).

  What a fingerprint cannot hold rides the resident guard at the same
  position: :becomes-available-at is a callable, so structured hope
  evaluates resident (waymark9's recorded deviation, kept). The
  derived-law overlay — recomputing a row's FACTS under stored trees
  — is this namespace's structural twin and a NAMED PUNT for phase 5:
  judgment diffs flip no stored value, so the laws this overlay can
  hold never need it; a data-law derivation pilot is the trigger.

  Rebuilt guard vectors cache per (kind, revision, action): the cache
  atom lives on the rdef (:judgment-cache, installed and reset by the
  definitions lifecycle beside :judgment-laws), so per-kind identity
  and invalidation come free with the slot swap."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn- parse-remedy
  "The fingerprint's remedy spelling back to a token: \"plan.assign_meal\"
  → :plan/assign_meal, \"assign_meal\" → :assign_meal."
  [s]
  (if (str/includes? s ".")
    (let [[ns' n] (str/split s #"\." 2)]
      (keyword ns' n))
    (keyword s)))

(defn- rebuild-guard
  "One stored judgment, made an ordinary expression guard again — the
  wire->form seam applied to verdicts. nil when the entry cannot be
  read back (the resident guard serves, and *err* names it)."
  [entry resident]
  (try
    (g/expr {:name (keyword (get entry "name"))
             :when (wire/wire->form (get entry "expr"))
             :vars (into {}
                         (map (fn [[k t]] [(keyword k) (wire/wire->form t)]))
                         (get entry "vars_exprs"))
             :explain (or (get entry "explain") (:explain resident))
             :remedies (mapv parse-remedy (get entry "remedies"))
             :hide (boolean (get entry "hide"))
             :severity (keyword (or (get entry "severity") "refuse"))
             :requires-token (get entry "requires_token")
             :becomes-available-at (:becomes-available-at resident)})
    (catch Exception e
      (binding [*out* *err*]
        (println (str "waymark10 judgment: stored guard "
                      (pr-str (get entry "name"))
                      " could not be rebuilt; the resident guard serves — "
                      (ex-message e))))
      nil)))

(defn- rebuild-guards
  "The action's guard vector under one stored revision: positional +
  name-checked substitution of every recoverable expression-guard
  entry; everything else keeps the resident guard at its position."
  [defn' fp]
  (let [entries (get-in fp ["machine" "actions" (name (:name defn')) "guards"])]
    (vec (map-indexed
          (fn [i resident]
            (let [e (when (sequential? entries) (nth entries i nil))]
              (if (and (map? e)
                       (some? (get e "expr"))
                       (= (get e "name") (name (:name resident))))
                (or (rebuild-guard e resident) resident)
                resident)))
          (:guards defn')))))

(defn- guards-under [rdef defn' revision fp]
  (let [cache (:judgment-cache rdef)
        k [revision (:name defn')]]
    (or (when cache (get @cache k))
        (let [gs (rebuild-guards defn' fp)]
          (when cache (swap! cache assoc k gs))
          gs))))

(defn resolve-action
  "THE per-row judgment seam: the action definition whose guards judge
  THIS row. Returns defn' untouched when nothing overlays — a nil
  stamp (pre-law), the resident revision, an engine built without the
  definitions boot — so the common case costs one map lookup.
  Everything else about the action (safety, display, input schema,
  handler) is resident by construction: the data-law gate refuses to
  let those differ between live revisions."
  [rdef defn' revision]
  (if-some [fp (and revision (get (:judgment-laws rdef) revision))]
    (if (seq (:guards defn'))
      (assoc defn' :guards (guards-under rdef defn' revision fp))
      defn')
    defn'))
