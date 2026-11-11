(ns waymark10.server.invoke.judge
  "Steps 5, 8 and 9 of invoke! (moved out of waymark10.server.invoke,
  ticket 2d02c8ab): the natural replay, concealment and narration out
  of state, the guard loop with its decision-record evidence, and the
  judged-when-answerable partial rehearsal (design §23). One grading,
  `deny-outcome`, serves the full loop and the partial one."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.decision :as decision]
            [waymark10.server.invoke.labels :refer [summary-of]]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn natural-replay
  "The latest transition, when it is this same action with this same
  input digest and the row already sits at its outcome. Guards do NOT
  re-run — the first execution's guards already passed.

  A write `:within` a held call is never answered this way: the
  person's one yes is spent by its one replay (held-calls/forward!,
  whose retry rides its idempotency key), and a second write naming
  the same held call must meet the door's guards and its state, not
  borrow the first write's outcome."
  [engine tx rdef row defn digest within]
  (let [[latest] (when-not (= :held_call (:kind within))
                   (store/transitions (:storage engine) tx
                                      {:kind (:kind rdef) :resource-id (:id row)}
                                      {:newest-first true :limit 1}))]
    (when (and latest
               (= (:action latest) (:name defn))
               (= (:input-digest latest) digest)
               (if (machine/several? defn)
                 (contains? (:to defn) (:state row))
                 (= (:state row) (:to defn))))
      {:row row :transition latest :replayed? :natural})))

(defn probe-hidden-only?
  "Concealment holds out-of-state too: when a hide-flagged guard
  denies on probe, the wire must 404, not narrate."
  [defn row ctx]
  (boolean
   (some (fn [guard]
           (let [[v d] (g/evaluate guard row nil (assoc ctx :mode :probe))]
             (and (t/deny? v) (:hide d))))
         (:guards defn))))

(defn out-of-state-says
  "The action's own sentence about a row outside its `:from`
  (`:out-of-state-says`, a fn of row and the GUARD ctx — reads, no
  pen), or nil. Narration only: the refusal is already decided, so a
  narrator that throws costs its sentence and never the 409."
  [defn row ctx]
  (when-some [f (:out-of-state-says defn)]
    (try (some-> (f row ctx) str str/trim not-empty)
         (catch Exception _ nil))))

(defn narration-row
  "The row a refusal narrates over (waymark-kyg): secret fields
  concealed so a guard's :vars-fn garnish cannot surface a value the
  projection hides — the 409/dry-run reason is a projection like any
  envelope. Judgment already read the FULL row above; this is display
  only. nil passes through (create judges rowless)."
  [rdef row]
  (if-some [secret (and (some? row)
                        (not-empty (schema/secret-fields (:schema rdef))))]
    (update row :data #(apply dissoc % secret))
    row))

(defn deny-outcome
  "One denying guard, graded the way every judgment door shares (the
  full loop and §23's partial rehearsal — one grading, no drift): a
  warning collects (acknowledged names pass, recorded as overridden);
  a hide flag conceals — what render hides the wire must 404, not
  narrate, since a 409 here would leak the very reason hide=
  conceals; anything else refuses with the guard's own sentence.
  Reasons render over the secret-concealed row (waymark-kyg); id,
  state and the gate-safe summary keep the full row."
  [acc v d row inp acknowledged defn rdef now]
  (let [nrow (narration-row rdef row)
        ;; a guard naming no remedy still says where its fields are written
        d (g/with-writers rdef (:name defn) d)]
    (cond
      (= :warning (:severity d))
      (if (contains? acknowledged (:name d))
        (update acc :overridden conj (:name d))
        (update acc :warned conj
                {:name (:name d)
                 :reason (g/render-reason d v nrow)
                 :remedies (g/remedy-doors d)}))

      (:hide d)
      (throw (p/not-found (:kind rdef) (:id row)))

      :else
      (throw (p/guard-refused
              (:name defn) (:state row)
              (g/writers-reason d (g/render-reason d v nrow))
              {:guard (:name d)
               :remedies (g/remedy-doors d)
               :resolved-remedies (g/resolve-remedies d nrow inp (:evidence v))
               :evidence (:evidence v)
               :open (:open d)
               :no-remedy (:no-remedy d)
               :becomes-available (g/becomes-available d v nrow)}
              {:kind (:kind rdef) :id (:id row)
               :summary (summary-of rdef row now)})))))

(defn run-guards
  "→ {:warned [{:name :reason :remedies}] :overridden [names] :basis […]}
  or throws guard-refused on the first hard deny.

  :basis is the decision record's evidence (spec-decision-record),
  collected in the loop that was already running — every allow this
  reduce used to drop on the floor, kept with the values its :vars
  saw. Only for a kind that declares :retain {:judgment true}; every
  other kind pays one boolean and the empty vector it arrived with.
  A hard deny throws before its entry can matter: refusals do not
  commit, so they have no transition to hang from."
  [defn row inp ctx acknowledged rdef]
  (let [scope (when (decision/retains? rdef)
                (decision/evidence-scope rdef row inp ctx))
        ;; the members a team reader read, per guard (spec-teams §2)
        reads (when scope (atom (sorted-set)))
        ctx (cond-> ctx reads (assoc :member-reads reads))]
    (reduce
     (fn [acc guard]
       (let [[v d] (g/evaluate guard row inp ctx)
             deny? (t/deny? v)
             members (when reads (let [m @reads] (reset! reads (sorted-set)) m))
             acc (cond-> acc
                   scope (update :basis conj
                                 (cond-> (decision/entry guard deny? d acknowledged
                                                         scope)
                                   (seq members) (assoc :members (vec members)))))]
         (if-not deny?
           acc
           (deny-outcome acc v d row inp acknowledged defn rdef (:now ctx)))))
     ;; nil, not [] — "this kind retains nothing" and "this action
     ;; declares no guards" are different sentences and the column
     ;; must be able to tell them apart
     {:warned [] :overridden [] :basis (when scope [])}
     ;; the framework's own walls ride behind the declared ones
     ;; (waymark-fp62.4.1) — the same list render probed, so the door
     ;; refuses with the wall the envelope already named
     (g/walled-guards rdef defn row))))

(defn split-leaves
  "The partial rehearsal's coverage split (design §23): guard trees
  flatten to their schema-visible leaves (g/iter-leaves — an :any
  composite is atomic and carries no :judges of its own, so it waits
  with the rest). Judged-when-answerable, literally: an input-judging
  leaf is judged when the provided keys cover its whole :judges set,
  else it waits (:awaiting — named, never failed). A row-only leaf
  that READS beyond the clock is judged too — the pure render probe
  advertises those optimistically (it carries no :read hook), so the
  envelope has NOT told their truth; the rehearsal's ctx has the
  hooks and is the first door that can answer honestly (the authoring
  probe's D6). A pure row-only leaf still drops: the envelope's
  available/unavailable really did tell that one."
  [guards provided]
  (let [leaves (into [] (mapcat g/iter-leaves) guards)
        cross-row? (fn [g] (and (empty? (:judges g))
                                (seq (remove #{:now} (:reads g)))))
        input-leaves (filterv #(seq (:judges %)) leaves)]
    {:judged (into (filterv cross-row? leaves)
                   (filter #(every? provided (:judges %)))
                   input-leaves)
     :awaiting (filterv #(not (every? provided (:judges %))) input-leaves)}))

(defn partial-verdict
  "The judged-when-answerable rehearsal (design §23): evaluate only
  the guard leaves whose entire :judges set the caller provided;
  refusals and warnings grade exactly as the full loop's (deny-outcome
  is shared). → {:valid? true :judged [names] :awaiting [names]
  :warnings […]} — or the same 409/404 the full loop would throw. A
  judged leaf's 409 names the leaves that still wait (`awaiting`), so
  the caller learns the form's guards from the first refusal; the 404
  of a hidden leaf says nothing more."
  [defn row inp ctx acknowledged rdef provided]
  (let [{:keys [judged awaiting]} (split-leaves (g/walled-guards rdef defn row)
                                                provided)
        {:keys [warned]}
        (try
          (reduce
           (fn [acc leaf]
             (let [[v d] (g/evaluate leaf row inp ctx)]
               (if-not (t/deny? v)
                 acc
                 (deny-outcome acc v d row inp acknowledged defn rdef (:now ctx)))))
           {:warned [] :overridden []}
           judged)
          (catch clojure.lang.ExceptionInfo e
            (if (and (seq awaiting)
                     (= :guard-refused (:waymark10/problem (ex-data e))))
              (throw (ex-info (ex-message e)
                              (assoc (ex-data e) :awaiting (mapv :name awaiting))
                              e))
              (throw e))))]
    {:valid? true
     :judged (mapv :name judged)
     :awaiting (mapv :name awaiting)
     :warnings (not-empty warned)}))
