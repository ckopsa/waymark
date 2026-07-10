(ns waymark10.server.invoke
  "THE transition algorithm — every write in every application is one
  call of invoke! (or create!). The step order is waymark9
  invoke.py's, reproduced exactly; each numbered step below matches
  the Python original:

   1. resolve the action                            (404)
   2. idempotency-key requirement + stored replay   (428 / replay)
   3. load the row FOR UPDATE — one lock per invocation
   4. the row's law resolves the guards (waymark10.server.judgment:
      a non-resident stamp is judged by its revision's stored trees)
   5. out of state → natural replay, else concealment (404), else
      wrong-state with becomes-available              (409)
   6. the fence: If-Match vs the current etag         (412)
   7. input validation, field-keyed errors            (422)
   8. NATURAL REPLAY BEFORE GUARDS (2.0 ordering): if the latest
      transition is this action with this digest and the state is its
      outcome, the first execution's guards already passed — and
      re-running them can honestly deny. Replay is the byte-honest
      answer.
   9. the guard loop: warnings collect (acknowledged names pass and
      are recorded), hard denies refuse               (409)
  10. dry-run exits: {:valid true} + warnings
  11. the handler (pure: row → row), tamper refusal
  12. derived materialization, in the same commit
  13. state advance, version+1, summary
  14. transition append (defined_by = the row's law) + save
  15. idempotency store — replay is byte-identical

  Bodies are WIRE-SHAPED JSON values (parsed JSON: strings for dates,
  exact decimals) — the input digest hashes the wire bytes, so a
  schema-typed body is refused at the digest. Decoding to schema
  types happens inside, at step 7.

  Phase 5 additions, each at its numbered seam: step 4 reads the
  judgment overlay; create! stamps :law-revision from the kind's law
  slots (an after=true pilot claims new creates); the engine-injected
  :adopt action restamps a row to its governing law; and after-write!
  — the lifecycle seam — runs the definitions machinery's effects
  after a committed, non-replayed write (waymark9's DefinitionLifecycle
  after_commit, made synchronous: it runs post-commit in the same
  call, so a crash between commit and effect is the boot-revise crash
  window, re-detected at the next boot).

  Returns {:row … :transition … :replayed? … :valid? …}; rendering to
  the envelope is phase 3's job.

  Phase 7 additions, recorded:
  - the single-write body (steps 2–15) is extracted to invoke-in-tx!
    so bulk items and batch inputs run the SAME per-item algorithm
    inside the fan-out's transaction — the step order is untouched;
    :require-key? false waives step 2's 428 for fan-out items (the
    bulk/batch call itself owns the key).
  - a :bulk action's row form does not exist: single-invoking it is
    404 (waymark9's allow_bulk gate).
  - bulk! (one input, N resources): non-atomic runs one transaction
    PER item so a refusal never poisons neighbors; :atomic runs ONE
    transaction and any refusal rolls everything back, answering a
    409 that carries the report; :defer-over hands over-threshold
    calls to the job resource (phase 9b closed the phase-7 punt) —
    bulk! returns {:deferred {…}} and the router mints the job and
    answers 202, so this namespace stays free of a jobs require.
    Whole-call idempotency: the stored report replays byte-identical
    under the bulk:<action> marker; a DEFERRED call does not
    participate (recorded: the job row is its record — replaying a
    deferred call mints a second job).
  - batch! (N inputs, one resource): always atomic, one transaction,
    one row lock held throughout, inputs applied in order — each
    input is its own transition through the full per-item algorithm
    (waymark9 semantics; consecutive identical idempotent inputs
    natural-replay into one). The first refusal aborts the whole
    batch with a 409 naming the input's index — recorded deviation:
    waymark9 kept judging refused batches to report every verdict.
  - finish! consumes the acted action's draft in the write's own
    transaction (see waymark10.server.drafts) — the smallest honest
    seam.
  - after-write! runs per successfully committed bulk/batch item, so
    the phase-5/6 hooks see fan-out writes like any other.

  Phase 8 additions (the mealplan10 dogfood's engine gaps), recorded:
  - make-ctx gains :read (kind, id → decoded row or nil) and :find
    (kind, where, opts → decoded rows) — cross-resource guard reads
    and on-create resolution, same transaction as the write. The
    :reads [:kind] discipline on guards already named the dependency;
    this is the hook. Render's probe ctx stays storage-free (render
    is pure): an acceptance set that reads another kind must decline
    (return nil) when ctx carries no :read/:find.
  - decode-row is public and owns the SHAPE step: a stored row older
    than the declaration folds forward through (:upcasts rdef) at
    load, lazily, and the declared shape stamps at the next write.
    Upcasts must be idempotent over already-upcast documents — a
    maintenance write (update-data!) persists upcast data without the
    shape stamp, so the fold can run again at the next load.
  - finish!/create! maintain declared ref LABELS ({:kind … :label …}
    on a :waymark/ref entry, data root and vector-of-map items):
    whenever a write changes a labeled ref, the engine reads the
    target in the same transaction and writes its label (the target
    kind's :label-template, default \"{data.name}\" when the target
    declares :name). Recorded scope: labels refresh at the write that
    sets the ref — a target rename does not re-fan-out (waymark9's
    maintainer did; the v10 seam is a named punt).
  - the one-of pass (waymark10.groups) runs after labels — filling
    one arm clears the others, cleared labels included.
  - after-write! CASCADES declared owns edges: a parent action named
    in an edge's :on map invokes the child action on every owned
    child in the child action's :from states — through invoke!, per
    child, system actor, the parent transition's correlation id."
  (:require [clojure.string :as str]
            [waymark10.derived :as derived]
            [waymark10.groups :as groups]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.server.drafts :as drafts]
            [waymark10.server.judgment :as judgment]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn etag [kind id version]
  (str "W/\"" (name kind) "-" id "-v" version "\""))

(defn- body-digest [body]
  (wire/digest (or body {})))

(defn resources
  "The kind → rdef map this engine serves: the phase-5 registry atom's
  current snapshot when the definitions boot installed one, else the
  static phase-2 map — old engines keep working with law slots absent."
  [engine]
  (if-some [reg (:registry engine)]
    (:kinds @reg)
    (:resources engine)))

(defn- rdef-of [engine kind]
  (or (get (resources engine) kind)
      (throw (p/not-found kind "?"))))

(defn upcast-row
  "The load boundary's shape step (phase 8): a stored row older than
  the declaration folds forward through :upcasts — one fn per shape,
  raw JSON document in and out — and carries the declared shape, so
  the next write stamps it. Idempotence over already-upcast documents
  is the upcast author's obligation (a maintenance write persists
  upcast data without the stamp)."
  [rdef row]
  (let [stored (:shape row 1)
        declared (:shape rdef 1)]
    (if (< stored declared)
      (-> row
          (update :data (fn [d]
                          (reduce (fn [d s] ((get (:upcasts rdef) s) d))
                                  d
                                  (range stored declared))))
          (assoc :shape declared))
      row)))

(defn decode-row
  "The load boundary owns coercion: stored JSON upcasts to the
  declared shape, then becomes schema types (ISO strings → LocalDate)
  so laws compare real values."
  [rdef row]
  (update (upcast-row rdef row) :data #(schema/decode (:schema rdef) %)))

(defn- make-ctx [engine tx mode principal]
  (t/ctx {:principal principal
          :now ((:now-fn engine))
          :services (:services engine)
          :mode mode
          ;; the cross-resource read hooks (phase 8): guards declared
          ;; :reads [:kind] and on-create resolution read OTHER kinds
          ;; through the write's own transaction — decoded rows, the
          ;; same values the target's own laws compare
          :read (fn [target-kind id]
                  (when-some [trdef (get (resources engine) target-kind)]
                    (some->> (store/load-row (:storage engine) tx
                                             target-kind (str id) {})
                             (decode-row trdef))))
          :find (fn [target-kind where opts]
                  (when-some [trdef (get (resources engine) target-kind)]
                    (mapv #(decode-row trdef %)
                          (store/query-rows (:storage engine) tx target-kind
                                            (or where {})
                                            (merge {:limit 100} opts)))))
          :actor-of (fn [row transition]
                      ;; the newest matching transition's actor id
                      (some (fn [rec]
                              (when (= (:action rec) transition)
                                (get-in rec [:actor :id])))
                            (store/transitions
                             (:storage engine) tx
                             {:kind (:kind row) :resource-id (:id row)}
                             {:newest-first true})))}))

(defn- encode-row [rdef row]
  (update row :data #(schema/encode (:schema rdef) %)))

(defn- summary-of [rdef row]
  (summary/render (:summary rdef) (assoc row :kind (:kind rdef))))

;; ── ref labels (phase 8, design §4) ─────────────────────────────────

(defn- schema-head*
  "The leaf type of an entry's schema form, :maybe unwrapped."
  [s]
  (let [s (if (and (vector? s) (= :maybe (first s))) (second s) s)]
    (if (vector? s) (first s) s)))

(defn- item-map-form
  "The [:map …] item form of a vector-of-map field, nil otherwise."
  [form k]
  (when-some [s (schema/field-schema form k)]
    (when (and (vector? s) (= :vector (first s)))
      (let [item (last s)]
        (when (and (vector? item) (= :map (first item)))
          item)))))

(defn- labeled-ref-specs
  "field → {:kind … :label …} for every :waymark/ref entry of one
  :map form that declares both — the refs whose labels the engine
  maintains."
  [form]
  (into {}
        (keep (fn [[f {:keys [properties schema]}]]
                (when (and (= :waymark/ref (schema-head* schema))
                           (:kind properties)
                           (:label properties))
                  [f (select-keys properties [:kind :label])])))
        (schema/entry-map form)))

(defn- label-value
  "The target's declared label: its :label-template, defaulting to
  \"{data.name}\" when the target schema declares :name; nil when the
  target is gone or unlabelable (dangling refs are the guards'
  problem, loudly — never the label pass's)."
  [engine tx target-kind id]
  (when-some [trdef (get (resources engine) target-kind)]
    (when-some [raw (store/load-row (:storage engine) tx target-kind
                                    (str id) {})]
      (when-some [template
                  (or (:label-template trdef)
                      (when (contains? (set (schema/entry-keys (:schema trdef)))
                                       :name)
                        "{data.name}"))]
        (summary/render template
                        (assoc (decode-row trdef raw) :kind target-kind))))))

(defn- label-pass [engine tx specs m before]
  (reduce-kv
   (fn [m f {:keys [kind label]}]
     (let [new-id (get m f)]
       (cond
         (= new-id (get before f)) m
         (nil? new-id) (assoc m label nil)
         :else (if-some [lv (label-value engine tx kind new-id)]
                 (assoc m label lv)
                 m))))
   m
   specs))

(defn- maintain-ref-labels
  "Denormalized ref labels are generated, never hand-copied: whenever
  a write changes a labeled ref — data root or vector-of-map items —
  the engine reads the target in the same transaction and writes its
  label. before nil (create) treats every set ref as changed. Recorded
  scope: labels refresh at the write that SETS the ref; a target
  rename does not fan back out (named punt — waymark9's maintainer
  did)."
  [engine tx rdef data before]
  (let [root-specs (labeled-ref-specs (:schema rdef))
        data (if (seq root-specs)
               (label-pass engine tx root-specs data before)
               data)]
    (reduce
     (fn [data field]
       (let [specs (some-> (item-map-form (:schema rdef) field)
                           labeled-ref-specs)]
         (if (empty? specs)
           data
           (let [prev (vec (get before field))]
             (update data field
                     (fn [items]
                       (vec (map-indexed
                             (fn [i item]
                               (label-pass engine tx specs item (get prev i)))
                             items))))))))
     data
     (schema/entry-keys (:schema rdef)))))

(defn- labels-and-groups
  "The two engine passes every write's data goes through after the
  handler: ref labels first, then one-of enforcement — so a cleared
  arm clears its label with it."
  [engine tx rdef data before]
  (-> (maintain-ref-labels engine tx rdef data before)
      (as-> d (groups/enforce rdef d before))))

(defn- natural-replay
  "The latest transition, when it is this same action with this same
  input digest and the row already sits at its outcome. Guards do NOT
  re-run — the first execution's guards already passed."
  [engine tx rdef row defn digest]
  (let [[latest] (store/transitions (:storage engine) tx
                                    {:kind (:kind rdef) :resource-id (:id row)}
                                    {:newest-first true :limit 1})]
    (when (and latest
               (= (:action latest) (:name defn))
               (= (:input-digest latest) digest)
               (= (:state row) (:to defn)))
      {:row row :transition latest :replayed? :natural})))

(defn- probe-hidden-only?
  "Concealment holds out-of-state too: when a hide-flagged guard
  denies on probe, the wire must 404, not narrate."
  [defn row ctx]
  (boolean
   (some (fn [guard]
           (let [[v d] (g/evaluate guard row nil (assoc ctx :mode :probe))]
             (and (t/deny? v) (:hide d))))
         (:guards defn))))

(defn- run-guards
  "→ {:warned [{:name :reason :remedies}] :overridden [names]} or
  throws guard-refused on the first hard deny."
  [defn row inp ctx acknowledged rdef]
  (reduce
   (fn [acc guard]
     (let [[v d] (g/evaluate guard row inp ctx)]
       (if-not (t/deny? v)
         acc
         (cond
           (= :warning (:severity d))
           (if (contains? acknowledged (:name d))
             (update acc :overridden conj (:name d))
             (update acc :warned conj
                     {:name (:name d)
                      :reason (g/render-reason d v row)
                      :remedies (:remedies d)}))

           ;; concealment holds in-state too: what render hides by a
           ;; hide-flagged guard, the wire must 404, not narrate —
           ;; a 409 here would leak the very reason hide= conceals
           (:hide d)
           (throw (p/not-found (:kind rdef) (:id row)))

           :else
           (throw (p/guard-refused
                   (:name defn) (:state row)
                   (g/render-reason d v row)
                   {:guard (:name d)
                    :remedies (:remedies d)
                    :becomes-available (g/becomes-available d v row)}
                   {:kind (:kind rdef) :id (:id row)
                    :summary (summary-of rdef row)}))))))
   {:warned [] :overridden []}
   (:guards defn)))

(defn- finish!
  "Steps 11–15: handler, tamper refusal, materialize, advance,
  append, save, idempotency store."
  [engine tx rdef row defn inp ctx
   {:keys [digest overridden idempotency-key principal correlation-id]}]
  (let [now (:now ctx)
        handled (if-some [h (:handler defn)]
                  (let [out (h row inp ctx)]
                    (when-not (map? out)
                      (throw (t/definition-error
                              (str "handler for " (name (:name defn))
                                   " must return the row"))))
                    out)
                  row)
        _ (when-some [facts (seq (derived/tampered rdef row handled now))]
            (throw (p/derived-tampered (:name defn) (vec facts))))
        ;; ref labels + one-of clears (phase 8): engine passes, never
        ;; handler work — the handler sets the ref, the engine writes
        ;; the label and clears the other arm
        handled (update handled :data
                        #(labels-and-groups engine tx rdef % (:data row)))
        materialized (derived/materialize rdef handled now)
        advanced (-> materialized
                     (assoc :state (:to defn))
                     (update :version inc))
        advanced (assoc advanced :summary (summary-of rdef advanced))
        record (store/append-transition!
                (:storage engine) tx
                {:kind (:kind rdef)
                 :resource-id (:id row)
                 :action (:name defn)
                 :from-state (:state row)
                 :to-state (:to defn)
                 :actor {:type (name (:type principal))
                         :id (:id principal)
                         :display (:display principal)}
                 :law-revision (:law-revision row)
                 :input-digest digest
                 :inputs (when (:record defn) inp)
                 :acknowledged (not-empty (vec overridden))
                 :correlation-id correlation-id
                 :idempotency-key idempotency-key
                 :summary (:summary advanced)})
        saved (store/save-row! (:storage engine) tx (:kind rdef)
                               (encode-row rdef (dissoc advanced :summary))
                               (:version row))]
    ;; the effort landed; its draft has served its purpose — consumed
    ;; in the same commit as the write it composed (phase 7)
    (when (get-in defn [:edit :draft])
      (store/delete-draft! (:storage engine) tx (:kind rdef) (:id row)
                           (:name defn) (drafts/audience-of defn principal)))
    (when idempotency-key
      (store/idempotency-store!
       (:storage engine) tx idempotency-key (:kind rdef) (:name defn) digest
       200 (if-some [render-fn (:render-fn engine)]
             ;; the render seam (phase 3): replay serves the same
             ;; envelope bytes the first execution answered with
             (render-fn rdef (decode-row rdef saved))
             (wire/write-json {:id (:id saved) :state (name (:state advanced))
                               :version (:version advanced)
                               :summary (:summary advanced)}))
       "application/waymark+json"))
    {:row (decode-row rdef saved) :transition record}))

;; ── the lifecycle seam (phase 5) ────────────────────────────────────

(declare cascade!)

(defn- after-write!
  "A committed, non-replayed write may carry law-lifecycle effects: a
  definition transition flips slots and restamps populations; an adopt
  triggers the supersede-when-empty sweep. The hook is the engine's
  :lifecycle fn (waymark10.server.definitions/lifecycle), installed by
  the boot; engines without it pay nothing. Runs AFTER the write's
  transaction — the simplest correct seam: effects use ordinary
  create!/invoke! calls and bulk restamps in their own transactions,
  and a crash in between is the boot-revise crash window (the next
  boot re-detects). Recorded deviation: waymark9 superseded the prior
  revision inside the promote's own transaction; v10 runs the whole
  effect post-commit, so two current definition rows can coexist for
  the effect's duration.

  Phase 6's one invoke.clj seam, recorded: the :maintain hook
  (waymark10.server.maintainer/after-write) rides the same
  post-commit call — cross-row count recompute and the clock index —
  and create! now routes through after-write! so births feed both
  hooks. :maintain may refresh the result's :row (the response tells
  the maintained truth); the stored idempotency envelope, rendered
  inside the write's transaction, predates it by design.

  Phase 8 adds the CASCADE between the two: a parent action a declared
  owns edge's :on map names fans out to the owned children BEFORE the
  parent's own maintenance pass, so the response's rollup counts tell
  the post-cascade truth. Each child write is an ordinary invoke! —
  own transaction, full algorithm, its own after-write! — under the
  system cascade actor and the parent transition's correlation id;
  redelivery is a natural no-op (children are selected by the child
  action's :from states)."
  [engine kind action-name res]
  (if (and (:transition res) (nil? (:replayed? res)))
    (do
      (when-some [lc (:lifecycle engine)]
        (lc engine kind action-name res))
      (cascade! engine kind action-name res)
      (if-some [m (:maintain engine)]
        (or (m engine kind action-name res) res)
        res))
    res))

;; ── the engine-injected adopt (phase 5) ─────────────────────────────

(defn- where-claims?
  "Does a pilot population's where= equality map claim this decoded
  row? String-fallback comparison, the acceptance-membership
  discipline."
  [where row]
  (every? (fn [[f expected]]
            (let [v (get-in row [:data f])]
              (or (= v expected) (= (str v) (str expected)))))
          where))

(defn- adopt-target
  "The revision a row adopts INTO: the piloted revision when its
  where-population claims the row, else the kind's current law."
  [rdef row]
  (let [p (:piloted-law rdef)]
    (if (and p (some-> (get-in p [:population :where])
                       (where-claims? row)))
      (:revision p)
      (:current-law rdef))))

(defn- adopt!
  "The engine-injected :adopt: a same-state restamp to the row's
  governing law — version+1, logged, no handler, no guards. A machine
  that declares its own :adopt shadows this. Enforcement accepts any
  state, terminal included, though render only advertises it on
  non-terminal rows — recorded deviation: adopting a closed row is
  the maintenance act that lets a grandfathered law finally die (the
  sweep counts every stamped row)."
  [engine rdef kind id {:keys [principal dry-run correlation-id]}]
  (store/with-tx (:storage engine)
    (fn [tx]
      (let [raw (or (store/load-row (:storage engine) tx kind id
                                    {:for-update (not dry-run)})
                    (throw (p/not-found kind id)))
            row (decode-row rdef raw)
            target (adopt-target rdef row)]
        (cond
          dry-run {:valid? true}
          (= target (:law-revision row)) {:row row :replayed? :natural}
          :else
          (let [advanced (-> row
                             (assoc :law-revision target)
                             (update :version inc))
                record (store/append-transition!
                        (:storage engine) tx
                        {:kind kind
                         :resource-id id
                         :action :adopt
                         :from-state (:state row)
                         :to-state (:state row)
                         :actor {:type (name (:type principal))
                                 :id (:id principal)
                                 :display (:display principal)}
                         :law-revision target
                         :input-digest (body-digest nil)
                         :correlation-id correlation-id
                         :summary (summary-of rdef row)})
                saved (store/save-row! (:storage engine) tx kind
                                       (encode-row rdef advanced)
                                       (:version row))]
            {:row (decode-row rdef saved) :transition record}))))))

(declare invoke-declared!)

(defn invoke!
  "One write. opts: :principal (required), :if-match, :idempotency-key,
  :dry-run, :acknowledged (set of guard names), :correlation-id."
  [engine kind id action-name body
   {:keys [principal if-match idempotency-key dry-run acknowledged
           correlation-id]
    :or {acknowledged #{}}
    :as opts}]
  (let [rdef (rdef-of engine kind)]
    (if (and (= :adopt action-name)
             (nil? (get-in rdef [:actions :adopt]))
             (:current-law rdef))
      (do (when (seq body)
            (throw (p/schema-invalid
                    :adopt (into {} (map (fn [[k _]] [k ["unexpected field"]]))
                                 body))))
          (after-write! engine kind :adopt
                        (adopt! engine rdef kind id opts)))
      (invoke-declared! engine rdef kind id action-name body opts))))

;; ── the owns cascade (phase 8, design E4) ───────────────────────────

(def cascade-actor
  (t/principal {:id "waymark-cascade" :type :system :display "Cascade"}))

(defn- cascade!
  "Fan a parent transition out to its owned children: for every owns
  edge whose :on map names this action, invoke the child action on
  each owned child sitting in that action's :from states. Pages of 200
  via the child's :via ref; the loop terminates because cascaded
  children leave the state filter."
  [engine kind action-name res]
  (doseq [edge (:owns (get (resources engine) kind))
          :let [child-action (get (:on edge) action-name)]
          :when child-action]
    (let [child-kind (:kind edge)
          child-rdef (get (resources engine) child-kind)
          eligible (get-in child-rdef [:actions child-action :from])
          idempotent? (get-in child-rdef [:actions child-action
                                          :safety :idempotent])
          actor (assoc cascade-actor
                       :display (str "Cascade — " (name kind) "."
                                     (name action-name)))
          cid (get-in res [:transition :correlation-id])
          parent-id (get-in res [:row :id])]
      (loop []
        (let [children (store/with-tx (:storage engine)
                         (fn [tx]
                           (store/query-rows (:storage engine) tx child-kind
                                             {(:via edge) parent-id}
                                             {:limit 200})))
              due (filterv #(contains? eligible (:state %)) children)]
          (when (seq due)
            (doseq [child due]
              (invoke! engine child-kind (:id child) child-action nil
                       {:principal actor
                        :correlation-id cid
                        :idempotency-key (when-not idempotent?
                                           (str (random-uuid)))}))
            (when (= 200 (count children))
              (recur))))))))

(defn- invoke-in-tx!
  "Steps 2–15 inside the caller's transaction — the single-write body
  invoke-declared! wraps in its own with-tx; bulk items and batch
  inputs (phase 7) run it inside the fan-out's transaction.
  :require-key? false waives step 2's 428 for fan-out items."
  [engine tx rdef kind id defn digest body
   {:keys [principal if-match idempotency-key dry-run acknowledged
           correlation-id require-key?]
    :or {acknowledged #{} require-key? true}}]
  (let [action-name (:name defn)]
    ;; 2. idempotency: requirement, then stored replay
    (when (and require-key?
               (not dry-run)
               (not (get-in defn [:safety :idempotent]))
               (nil? idempotency-key))
      (throw (p/idempotency-key-required action-name)))
    (if-some [hit (when (and (not dry-run) idempotency-key)
                    (store/idempotency-lookup (:storage engine) tx
                                              idempotency-key kind))]
      ;; a key replays only its own action + body; anything else
      ;; is reuse, refused before touching the row
      (if (and (= (:action hit) action-name)
               (= (:request-digest hit) digest))
        {:replayed? :idempotency :response hit}
        (throw (p/idempotency-key-reuse action-name)))
      ;; 3. the row lock
      (let [raw (or (store/load-row (:storage engine) tx kind id
                                    {:for-update (not dry-run)})
                    (throw (p/not-found kind id)))
            row (decode-row rdef raw)
            ;; 4. the row's law judges the row: a non-resident
            ;; stamp resolves this action's guards from that
            ;; revision's stored trees (the judgment overlay)
            defn (judgment/resolve-action rdef defn (:law-revision row))
            ctx (make-ctx engine tx (if dry-run :dry-run :invoke) principal)]
        (if-not (contains? (:from defn) (:state row))
          ;; 5. out of state: replay, conceal, or narrate
          (or (natural-replay engine tx rdef row defn digest)
              (when (probe-hidden-only? defn row ctx)
                (throw (p/not-found kind id)))
              (throw (p/wrong-state action-name (:state row) (:from defn)
                                    {:kind kind :id id
                                     :summary (summary-of rdef row)})))
          (do
            ;; concealment precedes everything in-state too (phase 8's
            ;; ordering amendment, forced by the mirror's sync doors):
            ;; what a hide-flagged guard conceals must not leak through
            ;; the fence (412), input validation (422), or a natural
            ;; replay (200) — the wire answers 404 first
            (when (probe-hidden-only? defn row ctx)
              (throw (p/not-found kind id)))
            ;; 6. the fence
            (when (get-in defn [:safety :fence])
              (let [current (etag kind id (:version row))]
                (when (not= (some-> if-match str/trim) current)
                  (throw (p/version-conflict action-name
                                             {:kind kind :id id
                                              :etag current})))))
            ;; 7. input validation — decode first (validation
            ;; speaks schema types), closed maps refuse unknowns
            (let [inp (if (:input defn)
                        (let [decoded (schema/decode (:input defn) (or body {}))]
                          (when-some [errors (schema/closed-errors (:input defn) decoded)]
                            (throw (p/schema-invalid action-name errors)))
                          decoded)
                        (if (seq body)
                          (throw (p/schema-invalid
                                  action-name
                                  (into {} (map (fn [[k _]] [k ["unexpected field"]])) body)))
                          nil))]
              ;; 8. natural replay before guards
              (or (when (and (not dry-run) (get-in defn [:safety :idempotent]))
                    (natural-replay engine tx rdef row defn digest))
                  ;; 9. the guard loop
                  (let [{:keys [warned overridden]}
                        (run-guards defn row inp ctx acknowledged rdef)]
                    (cond
                      ;; 10. dry-run exits before any effect
                      dry-run {:valid? true :warnings (not-empty warned)}
                      (seq warned) (throw (p/warning-refused action-name warned))
                      :else (finish! engine tx rdef row defn inp ctx
                                     {:digest digest
                                      :overridden overridden
                                      :idempotency-key idempotency-key
                                      :principal principal
                                      :correlation-id correlation-id})))))))))))

(defn- invoke-declared!
  [engine rdef kind id action-name body opts]
  (let [defn (or (some-> (get-in rdef [:actions action-name])
                         (assoc :name action-name))
                 (throw (p/no-such-action kind action-name)))
        digest (body-digest body)]
    ;; a bulk action is a collection affordance; its row form does not
    ;; exist (waymark9's allow_bulk gate) — bulk! fans out to
    ;; invoke-in-tx! directly and never lands here
    (when (:bulk defn)
      (throw (p/no-such-action kind action-name)))
    (after-write!
     engine kind action-name
     (store/with-tx (:storage engine)
       (fn [tx]
         (invoke-in-tx! engine tx rdef kind id defn digest body opts))))))

;; ── bulk and batch (phase 7) ────────────────────────────────────────

(defn- fan-out-spec
  "A :bulk/:batch declaration as a spec map — `true` is the all-default
  spelling."
  [v]
  (if (map? v) v {}))

(defn problem-reason
  "The refusal's sentence — public since phase 9b: the job worker
  reports per-item refusals with the same words a bulk report would."
  [e]
  (let [d (ex-data e)]
    (or (:detail d) (ex-message e))))

(defn refusal?
  "A per-item outcome that is a refusal (a tagged problem or a storage
  version conflict), as opposed to a failure. Public since phase 9b
  (the job worker classifies item outcomes the same way bulk! does)."
  [e]
  (boolean (or (p/problem? e)
               (:waymark10/version-conflict (ex-data e)))))

(defn- report-doc
  "The bulk_report wire document."
  [action-name data extra]
  (p/wire-value (merge {:kind "bulk_report"
                        :action action-name
                        :data data}
                       extra)))

(defn- fan-out-replay
  "Whole-call idempotency for bulk/batch: the stored report replays
  byte-identical under its marker; nil means proceed (and store on
  the way out)."
  [engine kind action-name marker digest idempotency-key idempotent?]
  (when-not idempotent?
    (when (nil? idempotency-key)
      (throw (p/idempotency-key-required action-name))))
  (when idempotency-key
    (when-some [hit (store/with-tx (:storage engine)
                      #(store/idempotency-lookup (:storage engine) %
                                                 idempotency-key kind))]
      (if (and (= (:action hit) marker)
               (= (:request-digest hit) digest))
        {:replayed? :idempotency :response hit}
        (throw (p/idempotency-key-reuse action-name))))))

(defn- fan-out-store!
  [engine kind marker digest idempotency-key doc]
  (when idempotency-key
    (store/with-tx (:storage engine)
      #(store/idempotency-store! (:storage engine) % idempotency-key kind
                                 marker digest 200 (wire/write-json doc)
                                 "application/waymark+json"))))

(defn bulk!
  "One input, N resources: POST /api/{plural}/-/{action} with body
  {:ids [...] …action input…}. Guards run per row through the same
  per-item algorithm as a single invoke. Returns {:report wire-doc}
  or an idempotency replay; atomic refusals throw (see the ns
  docstring). opts: :principal, :idempotency-key, :acknowledged,
  :correlation-id."
  [engine kind action-name body
   {:keys [principal idempotency-key acknowledged correlation-id]
    :or {acknowledged #{}}}]
  (let [rdef (rdef-of engine kind)
        defn (some-> (get-in rdef [:actions action-name])
                     (assoc :name action-name))
        _ (when-not (:bulk defn)
            (throw (p/no-such-action kind action-name)))
        spec (fan-out-spec (:bulk defn))
        max-items (:max-items spec 100)
        ids (:ids body)]
    (when-not (and (vector? ids) (seq ids) (every? string? ids))
      (throw (p/schema-invalid action-name
                               {:ids ["required, non-empty array of ids"]})))
    (if (when-some [threshold (:defer-over spec)]
          (< threshold (count ids)))
      ;; phase 9b closes the phase-7 punt: an over-threshold call
      ;; defers to the job resource — bulk! answers the marker, the
      ;; router mints the job and 202s (which keeps this namespace
      ;; free of a jobs require)
      {:deferred {:kind kind
                  :action action-name
                  :ids ids
                  :input (not-empty (dissoc body :ids))}}
      (do
        (when (< max-items (count ids))
          (throw (p/schema-invalid action-name
                                   {:ids [(str "at most " max-items " ids per call")]})))
        (let [item-body (not-empty (dissoc body :ids))
              item-digest (body-digest item-body)
              digest (body-digest body)
              marker (keyword (str "bulk:" (name action-name)))
              href #(str "/api/" (:plural rdef) "/" %)]
          (or (fan-out-replay engine kind action-name marker digest
                              idempotency-key (get-in defn [:safety :idempotent]))
              (let [cid (or correlation-id (str (random-uuid)))
                    item-opts {:principal principal
                               :acknowledged acknowledged
                               :correlation-id cid
                               :require-key? false}
                    run-item (fn [tx id]
                               (invoke-in-tx! engine tx rdef kind id defn
                                              item-digest item-body item-opts))
                    data
                    (if (:atomic spec)
                      ;; all-or-nothing: one transaction, any refusal
                      ;; rolls the whole call back — the 409 carries
                      ;; the report
                      (let [at (volatile! nil)
                            results
                            (try
                              (store/with-tx (:storage engine)
                                (fn [tx]
                                  (mapv (fn [id] (vreset! at id) (run-item tx id))
                                        ids)))
                              (catch Exception e
                                (if (refusal? e)
                                  (throw (p/problem
                                          :bulk-refused 409 "Atomic bulk refused"
                                          {:detail (str "Atomic bulk "
                                                        (name action-name)
                                                        " aborted: "
                                                        (problem-reason e)
                                                        "; nothing committed.")
                                           :action-attempted action-name
                                           :report {:succeeded 0 :refused 1 :failed 0
                                                    :refusals [{:self (href @at)
                                                                :reason (problem-reason e)}]}}))
                                  (throw e))))]
                        (doseq [res results]
                          (after-write! engine kind action-name res))
                        {:succeeded (count ids) :refused 0 :failed 0 :refusals []})
                      ;; partial success: one transaction PER item — a
                      ;; refusal never poisons its neighbors
                      (reduce
                       (fn [rep id]
                         (try
                           (let [res (store/with-tx (:storage engine)
                                       #(run-item % id))]
                             (after-write! engine kind action-name res)
                             (update rep :succeeded inc))
                           (catch Exception e
                             (if (refusal? e)
                               (-> rep
                                   (update :refused inc)
                                   (update :refusals conj
                                           {:self (href id)
                                            :reason (problem-reason e)}))
                               (do (binding [*out* *err*]
                                     (println "waymark10 bulk item error:"
                                              (name kind) id "-" (ex-message e)))
                                   (-> rep
                                       (update :failed inc)
                                       (update :refusals conj
                                               {:self (href id)
                                                :reason "Internal error while processing this item."})))))))
                       {:succeeded 0 :refused 0 :failed 0 :refusals []}
                       ids))
                    doc (report-doc action-name data nil)]
                (fan-out-store! engine kind marker digest idempotency-key doc)
                {:report doc})))))))

(defn bulk-item!
  "One id through the SAME per-item algorithm bulk!'s partial-success
  path runs — its own transaction, after-write! included, step 2's
  428 waived (the caller owns the call-level record). Exposed for the
  deferred-jobs worker (phase 9b): a job item and a bulk item are one
  code path. Refusals throw exactly as a single invoke's would."
  [engine kind action-name id body
   {:keys [principal acknowledged correlation-id]
    :or {acknowledged #{}}}]
  (let [rdef (rdef-of engine kind)
        defn (or (some-> (get-in rdef [:actions action-name])
                         (assoc :name action-name))
                 (throw (p/no-such-action kind action-name)))
        digest (body-digest body)]
    (after-write!
     engine kind action-name
     (store/with-tx (:storage engine)
       (fn [tx]
         (invoke-in-tx! engine tx rdef kind id defn digest body
                        {:principal principal
                         :acknowledged acknowledged
                         :correlation-id correlation-id
                         :require-key? false}))))))

(defn batch!
  "N inputs, one resource: POST /api/{plural}/{id}/-/{action}/batch
  with body {:inputs [{…} …]}. Always atomic — one transaction, one
  row lock, inputs applied in order, each input its own transition;
  the first refusal aborts the whole batch with a 409 naming its
  index. Returns {:report wire-doc} or an idempotency replay."
  [engine kind id action-name body
   {:keys [principal idempotency-key acknowledged correlation-id]
    :or {acknowledged #{}}}]
  (let [rdef (rdef-of engine kind)
        defn (some-> (get-in rdef [:actions action-name])
                     (assoc :name action-name))
        _ (when-not (:batch defn)
            (throw (p/no-such-action kind action-name)))
        spec (fan-out-spec (:batch defn))
        max-items (:max-items spec 100)
        body (or body {})
        extras (dissoc body :inputs)
        _ (when (seq extras)
            (throw (p/schema-invalid
                    action-name
                    (into {} (map (fn [[k _]] [k ["unexpected field"]])) extras))))
        inputs (:inputs body)]
    (when-not (and (vector? inputs) (seq inputs) (every? map? inputs))
      (throw (p/schema-invalid
              action-name
              {:inputs ["required, non-empty array of input objects"]})))
    (when (< max-items (count inputs))
      (throw (p/schema-invalid
              action-name
              {:inputs [(str "at most " max-items " inputs per batch")]})))
    (let [digest (body-digest body)
          marker (keyword (str "batch:" (name action-name)))]
      (or (fan-out-replay engine kind action-name marker digest
                          idempotency-key (get-in defn [:safety :idempotent]))
          (let [cid (or correlation-id (str (random-uuid)))
                item-opts {:principal principal
                           :acknowledged acknowledged
                           :correlation-id cid
                           :require-key? false}
                at (volatile! 0)
                results
                (try
                  (store/with-tx (:storage engine)
                    (fn [tx]
                      (into []
                            (map-indexed
                             (fn [i input]
                               (vreset! at i)
                               (invoke-in-tx! engine tx rdef kind id defn
                                              (body-digest input) input
                                              item-opts)))
                            inputs)))
                  (catch Exception e
                    (if (refusal? e)
                      (throw (p/problem
                              :batch-refused 409 "Atomic batch refused"
                              {:detail (str "Atomic batch " (name action-name)
                                            " aborted at input " @at ": "
                                            (problem-reason e)
                                            "; nothing committed.")
                               :action-attempted action-name
                               :index @at}))
                      (throw e))))]
            (doseq [res results]
              (after-write! engine kind action-name res))
            (let [doc (report-doc action-name
                                  {:succeeded (count inputs)
                                   :refused 0 :failed 0 :refusals []}
                                  {:links {:target {:href (str "/api/" (:plural rdef)
                                                               "/" id)}}})]
              (fan-out-store! engine kind marker digest idempotency-key doc)
              {:report doc}))))))

(defn- create-law-revision
  "Creates stamp the kind's current law (phase 5); an after=true pilot
  claims new creates for the piloted revision. Engines built without
  the definitions boot carry no law slots and keep the phase-2 stub
  (the engine's :current-law map, default 1)."
  [engine rdef kind]
  (if (contains? rdef :current-law)
    (if (get-in rdef [:piloted-law :population :after])
      (get-in rdef [:piloted-law :revision])
      (:current-law rdef))
    (get-in engine [:current-law kind] 1)))

(defn create!
  "The initial-state transition. Validates against :create-schema (or
  :schema), runs create guards with row nil, runs :on-create, then
  materializes, inserts, and logs under the kind's create action name.
  The transition's to-state is the row's state AFTER :on-create — a
  declared create landing (a held definition is born :proposed) logs
  honestly."
  [engine kind body {:keys [principal acknowledged correlation-id id]
                     :or {acknowledged #{}}}]
  (let [rdef (rdef-of engine kind)
        model (or (:create-schema rdef) (:schema rdef))
        digest (body-digest body)]
    (after-write!
     engine kind (first (:create-action-names rdef))
     (store/with-tx (:storage engine)
      (fn [tx]
        (let [inp (schema/decode model (or body {}))
              _ (when-some [errors (schema/closed-errors model inp)]
                  (throw (p/schema-invalid :create errors)))
              ctx (make-ctx engine tx :invoke principal)
              {:keys [warned overridden]}
              (reduce
               (fn [acc guard]
                 (let [[v d] (g/evaluate guard nil inp ctx)]
                   (if-not (t/deny? v)
                     acc
                     (if (= :warning (:severity d))
                       (if (contains? acknowledged (:name d))
                         (update acc :overridden conj (:name d))
                         (update acc :warned conj {:name (:name d)
                                                   :reason (g/render-reason d v nil)
                                                   :remedies (:remedies d)}))
                       (throw (p/guard-refused :create nil
                                               (g/render-reason d v nil)
                                               {:guard (:name d)
                                                :remedies (:remedies d)}
                                               nil))))))
               {:warned [] :overridden []}
               (:create-guards rdef))]
          (when (seq warned)
            (throw (p/warning-refused :create warned)))
          (let [now (:now ctx)
                row {:id (or id (str (random-uuid)))
                     :state (:initial rdef)
                     :version 1
                     :data inp
                     :shape (:shape rdef 1)
                     :owner (:id principal)
                     :law-revision (create-law-revision engine rdef kind)}
                row (if-some [oc (:on-create rdef)] (oc row ctx) row)
                ;; ref labels + one-of at birth too: before nil, every
                ;; set ref is newly set
                row (update row :data
                            #(labels-and-groups engine tx rdef % nil))
                row (derived/materialize rdef row now)
                row (assoc row :summary (summary-of rdef row))
                _ (store/insert-row! (:storage engine) tx kind
                                     (encode-row rdef (dissoc row :summary)))
                record (store/append-transition!
                        (:storage engine) tx
                        {:kind kind
                         :resource-id (:id row)
                         :action (first (:create-action-names rdef))
                         :from-state nil
                         :to-state (:state row)
                         :actor {:type (name (:type principal))
                                 :id (:id principal)
                                 :display (:display principal)}
                         :law-revision (:law-revision row)
                         :input-digest digest
                         :acknowledged (not-empty (vec overridden))
                         :correlation-id correlation-id
                         :summary (:summary row)})]
            {:row row :transition record})))))))

(defn engine
  "Phase-2 wiring: storage + resources, kinds ensured. Grows into
  server.engine in phase 3."
  [{:keys [storage resources services now-fn]}]
  (let [by-kind (into {} (map (fn [r] [(:kind r) r])) resources)]
    (doseq [r resources]
      (store/ensure-kind! storage r))
    {:storage storage
     :resources by-kind
     :services services
     :now-fn (or now-fn (fn [] (java.time.Instant/now)))}))
