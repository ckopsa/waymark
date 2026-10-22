(ns waymark10.registry
  "The assembly: one rdef per kind, every kind known before any serves.
  A registry is a plain map {:kinds {kind rdef}} where each rdef is the
  normalized resource map (the output of resource/defresource) plus the
  assembly slots and its fingerprint. Construction runs the cross-kind
  battery (waymark10.checks-assembly) — a registry that fails never
  boots; ported from waymark9 core/registry.py.

  Duplicate :kind or :plural across resources is a :registry error:
  one law per kind, one collection URL per plural."
  (:require [waymark10.bounds :as bounds]
            [waymark10.checks-assembly :as assembly]
            [waymark10.fingerprint :as fp]
            [waymark10.occupies :as occupies]
            [waymark10.qualifies :as qualifies]
            [waymark10.recurs :as recurs]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def law-slots
  "Phase-5 assembly slots the definitions machinery fills at boot
  (waymark9 ResourceDef, design §3 / 7.0 §1–3): :current-law — the
  definition revision NUMBER governing this kind, nil before the first
  revise (the pre-law horizon); :proposed-law — the held proposal a
  propose-mode boot registered while the overlay serves the current
  law; :piloted-law — the piloted revision whose declared population's
  rows live under it; :law-ids — revision number → definition revision
  row id, how a row's integer stamp resolves to the law its envelope
  names; :judgment-laws — revision number → stored fingerprint, one
  entry per non-resident revision that still needs serving (the
  grandfathered laws, and the current law while a hold or pilot keeps
  newer code resident) — waymark9's judgment_served/judgment_laws
  twins collapsed into one map, affordable because \"evaluate revision
  N\" is just wire->form + evaluate (waymark10.server.judgment). At
  most one proposal and one pilot per kind — a stage, not a lattice."
  {:current-law nil
   :proposed-law nil
   :piloted-law nil
   :law-ids {}
   :judgment-laws {}})

(defn- registry-err [msg]
  (throw (t/definition-error msg {:check :registry})))

(defn- assemble
  "One rdef: the normalized declaration plus the law slots and the
  canonical projection the diff gate and the overlays read."
  [r]
  (let [f (fp/fingerprint-of r)]
    (merge r law-slots {:fingerprint f
                        :fingerprint-hash (fp/fingerprint-hash f)})))

(defn registry
  "Assemble normalized resource maps into {:kinds {kind rdef}} and run
  the assembly-time battery — throws the named check's definition
  error. Warnings surface on *err* and ride the metadata
  (:waymark10/warnings), mirroring the per-declaration gate."
  [resources]
  (doseq [[k rs] (sort-by key (group-by :kind resources))]
    (when (< 1 (count rs))
      (registry-err (str "kind " k " is declared " (count rs)
                         " times — one law per kind"))))
  (doseq [[p rs] (sort-by key (group-by :plural resources))]
    (when (< 1 (count rs))
      (registry-err (str "plural " (pr-str p) " is claimed by "
                         (vec (sort (map :kind rs)))
                         " — two kinds cannot share a collection URL"))))
  ;; :occupies writes its guards before any kind is fingerprinted: the
  ;; derived trees are law, hashed as an authored guard is
  ;; …and :recurs gives each kind it makes the engine's recurs_from
  ;; field, law on that kind as those guards are on theirs
  (let [kinds (-> (into {} (map (juxt :kind identity)) resources)
                  occupies/derive-kinds
                  ;; …and a member ref that says who may be named gets
                  ;; its <field>-role and <field>-in-team guards
                  qualifies/derive-kinds
                  recurs/derive-kinds
                  ;; …and an account's bounds write the guards of every
                  ;; door whose :posts could break them (ledger step 5)
                  bounds/derive-kinds)
        reg {:kinds (into (sorted-map) (map (fn [[k r]] [k (assemble r)])) kinds)}
        {:keys [warnings info]} (assembly/run-all reg)]
    (doseq [w warnings]
      (binding [*out* *err*]
        (println (str "waymark10 usability warning " w))))
    (vary-meta reg assoc :waymark10/warnings (vec warnings) :waymark10/info (vec info))))

(defn rdef
  "The assembled definition of one kind, nil when unregistered."
  [reg kind]
  (get-in reg [:kinds kind]))

(defn kinds
  "The registered kinds, sorted."
  [reg]
  (sort (keys (:kinds reg))))
