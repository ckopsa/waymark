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
  10. dry-run exits: {:valid true} + warnings; :dry-run :partial is
      the judged-when-answerable rehearsal (design §23)
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
  - bulk! (N resources — one input over ids, or each row its own
    input via items, waymark-pywy.4): non-atomic runs one transaction
    PER item so a refusal never poisons neighbors; :atomic runs ONE
    transaction and any refusal rolls everything back, answering a
    409 that carries the report; on_error in the body picks
    continue/stop/atomic and may only tighten the declaration;
    acknowledgement is per item in the items shape and the header
    is refused there; :defer-over hands over-threshold
    calls to the job resource (phase 9b closed the phase-7 punt) —
    bulk! returns {:deferred {…}} and the router mints the job and
    answers 202, so this namespace stays free of a jobs require.
    Whole-call idempotency: the stored report replays byte-identical
    under the bulk:<action> marker; a DEFERRED call does not
    participate (recorded: the job row is its record — replaying a
    deferred call mints a second job). The call's key is STAMPED on
    every item transition but RECORDED once (waymark-pywy.5): items
    run with :record-key? false, so finish! writes the key into the
    row's transition and never into the idempotency store — the log
    says which call each item came from (the origin-key folds in
    feed/ and mcp/ count one action per item) while a replayed call
    still answers the stored report and never doubles. A deferred
    call's items carry the same key through the job's data. A
    dry-run (design §23) judges
    every id through the same per-item algorithm — self-keyed
    verdicts, nothing committed, no key demanded or recorded, and
    never a deferral (a job is an effect; over-threshold rehearsals
    judge inline under max-items).
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
    child, system actor, the parent transition's correlation id.

  The decision record (spec-decision-record.md, waymark-442.5):
  step 9's guard loop no longer drops its allows. For a kind that
  declares :retain {:judgment true}, each guard's declared :vars are
  evaluated over the scope it judged and ride the transition's
  `judgment` column — the refusal sentence the guard did not have to
  give. WHICH guards judged is not stored at all: it derives from the
  row's law_revision through waymark10.server.decision/basis, free
  and retroactive to every transition ever logged. Secret fields are
  subtracted at write time; :adopt records nothing; a dry run records
  nothing.

  Dry-run parity (design §23, 2026-07-10), recorded:
  - create!, bulk! and batch! honor :dry-run as invoke! always did.
    create! rehearses waymark9's tiers (schema-only without create
    guards — no session opened; declared create guards judged with
    warnings riding the body); never an insert, an :on-create, or an
    idempotency demand. bulk!/batch! answer per-item verdicts with
    nothing committed, and a dry-run never defers — a job is an
    effect.
  - :dry-run :partial judges only what the caller provided: provided
    entries are schema-checked (absence is not an error) and only the
    guard leaves whose every judged field arrived are evaluated — the
    rest are named, :awaiting, never failed."
  (:require [clojure.string :as str]
            [waymark10.derived :as derived]
            [waymark10.effect :as effect]
            [waymark10.groups :as groups]
            [waymark10.guards :as g]
            [waymark10.lines :as line-items]
            [waymark10.machine :as machine]
            [waymark10.money :as money]
            [waymark10.posts :as posts]
            [waymark10.schema :as schema]
            [waymark10.server.at-rest :as at-rest]
            [waymark10.server.decision :as decision]
            [waymark10.server.drafts :as drafts]
            [waymark10.server.judgment :as judgment]
            [waymark10.server.patch :as patch]
            [waymark10.server.predecessor :as predecessor]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.server.work-calendar :as work-calendar]
            [waymark10.summary :as summary]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn etag [kind id version]
  (str "W/\"" (name kind) "-" id "-v" version "\""))

(defn actor-map
  "The actor stamped on a transition: whose hand, under whose leash,
  and — when the session declared one — which model was running.

  The grant rides here (waymark-sfe) because a write made while
  presenting a live grant should read \"declined by <agent> under
  grant-…\"; the model rides here for the same reason (spec-seat.md
  R-9.5): a seat's history must say which model moved the row. Both
  are facts ABOUT the actor, the column is jsonb, so neither needs a
  migration or a field of its own — and both are simply absent when
  there was nothing to say, which is the honest thing to write. The
  model is the session's CLAIM, unverified here as everywhere.

  `allowed_by` is the person whose Allow this write replays (held-
  calls/door-principal sets it from the held call's `decided_by`), so
  the hand stays the author's and the log still says whose yes it was;
  the correction count reads it (store/corrections-by-model).

  `requested_by` is the agent whose held grant create the person's
  Allow ran as the person (held-calls/door-principal, HC1): the hand
  is the person's, and the log still says which agent asked.

  `scheduled` is the scheduled action whose run this write is
  (scheduled/runner-of, docs/spec-scheduled-actions.md R-4.2): the hand
  is the scheduler's, and the log says the clock moved it.

  `acted_by` is the seat that acted as this cast member on a clone
  (router/cast-principal, clone guard 2/3): the hand is the cast
  member's, and the log says whose seat moved it.

  `why` is the hand's own one sentence about this move, given on the
  invoke (router/move-why, ticket 1d8f5f1c): the row's history shows it
  beside the move."
  [principal grant-id]
  (cond-> {:type (name (:type principal))
           :id (:id principal)
           :display (:display principal)}
    grant-id (assoc :grant grant-id)
    (:model principal) (assoc :model (:model principal))
    (:allowed-by principal) (assoc :allowed_by (str (:allowed-by principal)))
    (:requested-by principal) (assoc :requested_by (str (:requested-by principal)))
    (:scheduled principal) (assoc :scheduled (str (:scheduled principal)))
    (:acted-by principal) (assoc :acted_by (str (:acted-by principal)))
    (:why principal) (assoc :why (str (:why principal)))))

(defn- body-digest [body]
  ;; exact decimals in a wire body digest as their {"dec" …} nodes
  ;; (batch H — the first :decimal input field met the digest);
  ;; everything else is the same canonical bytes as ever. Keyed once a
  ;; data key is set (ticket dbeb2f25): an unsalted hash of a body that
  ;; carries a sealed credential would let the log confirm a guess
  (at-rest/keyed-digest (wire/digest (wire/dec-nodes (or body {})))))

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

(defn action-names
  "Every action-name string a request (or a grant scope entry) may
  name on this kind — declared actions (:flow rows already desugared
  into :actions at declaration time), generated field editors, and
  the create verb: exactly the vocabulary check-action! enforces and
  well-known publishes. A mirror kind adds the manual sync trigger's
  flavors (resync, discover) — kind-level doors like the create verb,
  never row actions — so an ask can name the trigger and a leash be
  granted it (the door consults :action? on exactly these strings)."
  [rdef]
  (into (sorted-set)
        (map name)
        (concat (keys (:actions rdef)) (:create-action-names rdef)
                (map :action (:creates rdef))
                (when (:mirror rdef) [:resync :discover]))))

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

(defn- balance-in
  "An account's balance as its row carries it (docs/spec-ledger-money.md
  §4): nil — unknown to the law — for an account no kind declares, for a
  house account, whose balance no row carries yet, and for a row that is
  not there. `opts` is load-row's: {:for-update true} for the write's
  locked read."
  [engine tx account-kind id account-name opts]
  (when-some [trdef (get (resources engine) account-kind)]
    (when-some [acct (get-in trdef [:accounts account-name])]
      (when (some? id)
        (when-some [raw (store/load-row (:storage engine) tx account-kind (str id) opts)]
          (let [v (get-in (decode-row trdef raw) [:data account-name])]
            (if (some? v) v (money/zero (:unit acct)))))))))

(declare rate-on)

(defn- read-hooks
  "The render probe's :read/:find/:sum — make-ctx's enforcement hooks
  twinned for the READ path, each call over a short transaction of
  its own. An engine booted with :probe-reads true hands one instance
  of these to every render ctx (router/render-opts), so a
  cross-resource acceptance set enumerates as the picker enum on the
  envelope (discovery = the advertised set, waymark-1pq) and a
  cross-row code guard tells its real verdict instead of advertising
  optimistically. A per-instance cache (one instance per request)
  keeps the repeated probes — the same plan read by every action's
  gate — to one query each. The invoke-side :render-fn takes none of
  THESE: it runs inside the write's own transaction (engine
  docstring), so it is lent that write's own ctx :read/:find instead
  — its :computed fields read what the write left (ticket 82589f6e) —
  and its doors still advertise optimistically; the follow-up GET
  tells the folded truth."
  [engine]
  (let [st (:storage engine)
        cache (atom {})
        through (fn [k thunk]
                  (if-some [e (find @cache k)]
                    (val e)
                    (let [v (thunk)]
                      (swap! cache assoc k v)
                      v)))]
    {:read (fn [target-kind id]
             (through
              [:read target-kind (str id)]
              #(when-some [trdef (get (resources engine) target-kind)]
                 (some->> (store/with-tx
                           st (fn [tx] (store/load-row st tx target-kind
                                                       (str id) {})))
                          (decode-row trdef)))))
     :find (fn [target-kind where opts]
             (through
              [:find target-kind where opts]
              #(when-some [trdef (get (resources engine) target-kind)]
                 (mapv (partial decode-row trdef)
                       (store/with-tx
                        st (fn [tx] (store/query-rows
                                     st tx target-kind (or where {})
                                     (merge {:limit 100} opts))))))))
     ;; the aggregate twin (waymark-fp62.7.13): the seat's fire door
     ;; judges the week's fuel, and an envelope that could not ask for
     ;; the sum would narrate a wall that has already lifted. Memoed
     ;; like the rest — one page, one seat, one SUM.
     :sum (fn [target-kind field conds]
            (through
             [:sum target-kind field conds]
             #(when (get (resources engine) target-kind)
                (store/with-tx
                 st (fn [tx] (store/sum-matching st tx target-kind
                                                 field conds))))))
     ;; the ledger's read-path twins (docs/spec-ledger-money.md §9): a
     ;; balance as its row carries it, unlocked, and the rate in effect,
     ;; so a door a bound or a rate decides is judged on the probe and in
     ;; the sweep instead of pending
     :balance (fn [account-kind id account-name]
                (through
                 [:balance account-kind (str id) account-name]
                 #(store/with-tx
                   st (fn [tx] (balance-in engine tx account-kind id account-name {})))))
     :rate-of (fn [table key-vals on]
                (through
                 [:rate-of table key-vals (str on)]
                 #(store/with-tx st (fn [tx] (rate-on engine tx table key-vals on)))))
     ;; the log's read-path twin (docs/spec-undo.md). An undo door is
     ;; the one affordance whose availability is a fact about the CLOCK
     ;; and about whose hand is asking, so a card that advertised it
     ;; optimistically would offer a way back that is already gone. The
     ;; memo matters here more than anywhere: a feed page renders many
     ;; rows and each would otherwise cost its own query.
     :last-transition
     (fn [row]
       (through
        [:last-transition (:kind row) (str (:id row))]
        #(first (store/with-tx
                 st (fn [tx] (store/transitions
                              st tx {:kind (:kind row) :resource-id (:id row)}
                              {:newest-first true :limit 1}))))))}))

(defn render-hooks
  "`read-hooks`, and the `:calendar` hook (docs/spec-calendar-time.md
  §3) over their own :read and :find: the render probe and the
  law-sweep name the governing work_calendar from the same memo the
  rest of their reads ride. The hook carries that :read as its metadata,
  so an age's :until can follow a ref (`live-cracks/deadline`)."
  [engine]
  (let [hooks (read-hooks engine)]
    (assoc hooks :calendar (some-> (work-calendar/hook (:read hooks) (:find hooks))
                                   (with-meta {:read (:read hooks)})))))

(defn- law-conds
  "An exists/none/count-rows where as the store's conds
  (docs/spec-related-row-law.md §3): :state and :id are the row's own
  columns, any other field a data field cast as the target's schema
  types it — the condition list a :related edge hands ids-matching."
  [trdef conds]
  (let [text #(if (keyword? %) (name %) (str %))
        cast (fn [field]
               (let [s (schema/field-schema (:schema trdef) field)
                     s (if (and (vector? s) (= :maybe (first s))) (second s) s)]
                 (case (if (vector? s) (first s) s)
                   :waymark/date "date"
                   :waymark/instant "timestamptz"
                   :boolean "boolean"
                   :int "bigint"
                   (:double :decimal) "numeric"
                   "text")))]
    (mapv (fn [{:keys [field op value values]}]
            (cond-> (case field
                      :state {:target :state :op op}
                      :id {:target :id :op op}
                      {:target :data :field (name field) :cast (cast field) :op op})
              (= :in op) (assoc :values (mapv text values))
              (not= :in op) (assoc :value (text value))))
          conds)))

(defn- rate-on
  "(rate …)'s read (docs/spec-ledger-money.md §8, ledger step 7): the
  :value of the row of the kind declaring `table` whose :key fields
  carry `key-vals` and whose :effective is the latest on or before
  `on`. nil where no row is, where no kind declares the table, or where
  the key names other fields than the table's."
  [engine tx table key-vals on]
  (when-some [trdef (some #(when (= table (get-in % [:rate-table :name])) %)
                          (vals (resources engine)))]
    (let [{ks :key value :value eff :effective} (:rate-table trdef)]
      (when (= (set ks) (set (keys key-vals)))
        (let [conds (law-conds trdef (conj (mapv (fn [k] {:field k :op := :value (get key-vals k)}) ks)
                                           {:field eff :op :<= :value (str on)}))
              [hit] (store/search-rows (:storage engine) tx (:kind trdef) conds
                                       {:order-by eff :desc true :limit 1})]
          (when hit (get-in (decode-row trdef hit) [:data value])))))))

(declare invoke-in-tx! create-in-tx! create-law-revision create-walled-guards
         create-guard-pass)

(defn- tx-calendar
  "The `:calendar` hook (docs/spec-calendar-time.md §3) over the
  write's own transaction, as make-ctx's :read and :find read it: a
  holiday added in this transaction moves this write's verdict."
  [engine tx]
  (work-calendar/hook
   (fn [target-kind id]
     (when-some [trdef (get (resources engine) target-kind)]
       (some->> (store/load-row (:storage engine) tx target-kind (str id) {})
                (decode-row trdef))))
   (fn [target-kind where opts]
     (when-some [trdef (get (resources engine) target-kind)]
       (mapv #(decode-row trdef %)
             (store/query-rows (:storage engine) tx target-kind
                               (or where {}) (merge {:limit 100} opts)))))))

(defn- make-ctx
  "The invocation context for ONE write. `opts`:

  - `:correlation-id` — the request's, threaded through every inner
    write so a cascade reads as one story in the log.
  - `:self` — `{:kind … :action …}` naming THIS write, handed to the
    ctx :invoke / :create doors so the inner write they open knows
    what it was opened inside of.
  - `:within` — the `:self` of the write this ctx was opened INSIDE,
    or nil at the wire boundary. It rides the ctx as `(:within ctx)`
    (waymark-jfv.20), which is the one fact a guard cannot otherwise
    learn: whether the door it stands on was reached by a client's
    request or by another kind's own handler in the same transaction.
    `composition_request`'s `answer` door is the first to read it —
    a request is answered by an outcome's own staging and by nothing
    else, and without this key that law was a grant's promise rather
    than a wall. The render probe and the dry-run rehearsals never set
    it, so a door walled on it renders refused, which is the truth: no
    client taps it. A guard reading it declares `:reads [:within]`.
  - `:grant` — the guard's-eye view of the X-Waymark-Grant this
    request presented (waymark-sfe), `{:id :action? :row?}` minted by
    `grants/visibility` and nil for every principal that presented
    none or presented a dead one. It rides the ctx as `(:grant ctx)`,
    which is what lets a wall refuse an agent UNLESS its own scope
    admits the door (`guards/unless-granted`). It follows the hand
    into inner writes: a cross-write opened by a granted invoke is
    judged against the same leash, so `outcome.make_it_so` reaching
    `outcome_piece.take` needs both named in the scope. A guard
    reading it declares `:reads [:grant]`.
  - `:power` — the ENGINE'S OWN HAND ON A POWER
    (waymark-fp62.7.16), built by the door from this engine's Gate
    caller and this request's visibility
    (`gate-proxy/power-of`), and absent for every request that wears
    no live grant. It rides the ctx as `(:power ctx)`: a function of
    a tool name and its arguments that answers Gate's payload, or
    nil. The HANDLER holds it and the guards do not — a guard judges
    and never reaches a wire, so `guard-ctx` drops it beside the
    pen. It follows the hand into an inner invoke, as `:grant` does,
    and a birth carries none: a create door reads no mail.
    `inbox_item.research` is the first handler to read it.
  - `:idempotency-key` — the `Idempotency-Key` this invoke rides
    under, or nil. It rides the ctx as `(:idempotency-key ctx)`, the
    same string `land!` stamps on the transition, so a handler can say
    which door a write came through. `held_call`'s two verdicts are
    the first to read it."
  ([engine tx mode principal] (make-ctx engine tx mode principal nil))
  ([engine tx mode principal {:keys [correlation-id self within grant power
                                     idempotency-key]}]
   (let [;; the cross-WRITE door (waymark9 Ctx.invoke): handlers and
         ;; on-create hooks write OTHER rows through the same
         ;; transaction and the full per-item algorithm. Only a real
         ;; write mode carries it — a probe or dry-run ctx must not
         ;; hand a guard a pen. Inner results collect on the sink;
         ;; finish! rides them out as :inner-writes so after-write!
         ;; runs their lifecycle/cascade/maintenance post-commit.
         sink (when (= :invoke mode) (atom []))
         ;; the FOLLOW-UP queue (ticket da882851): the calls the handler
         ;; wants made AFTER this transaction commits. Only a real
         ;; write mode carries it, as with the pen: a rehearsal queues
         ;; nothing. land! rides the queue out as :follow-ups and
         ;; after-write! makes each call.
         follow-sink (when (= :invoke mode) (atom []))]
     (t/ctx {:principal principal
             :now ((:now-fn engine))
             :services (:services engine)
             :mode mode
             :inner-sink sink
             :follow-sink follow-sink
             ;; the FOLLOW-UP door (ticket da882851): a handler names one
             ;; call, `{:kind :id :action :input}`, and the engine makes
             ;; it after this write's transaction commits — an ordinary
             ;; invoke! under THIS principal, leash and correlation id,
             ;; judged by its own guards against the committed rows.
             ;; Where ctx :invoke writes inside the transaction and its
             ;; refusal rolls the outer write back, a follow-up's
             ;; refusal is logged and the outer write stands (see
             ;; follow-ups!). Guards never see it.
             :follow-up
             (when follow-sink
               (fn ctx-follow-up [{:keys [kind id action input]}]
                 (when-not (and (keyword? kind) (some? id) (keyword? action))
                   (throw (t/definition-error
                           "ctx follow-up: needs :kind, :id and :action")))
                 (swap! follow-sink conj
                        {:kind kind :id (str id) :action action :input input
                         :opts {:principal principal
                                :grant grant
                                :power power
                                :correlation-id correlation-id}})
                 nil))
             ;; the presented leash, as the guards see it (waymark-sfe)
             :grant grant
             ;; …and the engine's own hand on the powers that leash
             ;; admits (waymark-fp62.7.16) — see the docstring. nil
             ;; for every request that wears no live grant, and
             ;; dropped from the guard ctx: a guard judges, and a
             ;; guard that reached a wire would judge differently on
             ;; a day Gate was dark.
             :power power
             ;; the write this ctx was opened inside of, or nil at the
             ;; wire — see the docstring
             :within within
             ;; THIS write, `{:kind :action}`, as a guard reads it: a
             ;; declarative (hold "…") knows the door its replay stands
             ;; on from here (ticket 8e02609c), and without it no
             ;; allowed held call ever cleared the hold that minted it
             :self self
             ;; the key this invoke rides under, as the transition
             ;; will carry it — see the docstring
             :idempotency-key idempotency-key
             :invoke
             (when sink
               (fn ctx-invoke [target-kind id action-name body & [opts]]
                 (let [trdef (or (get (resources engine) target-kind)
                                 (throw (t/definition-error
                                         (str "ctx invoke: unknown kind "
                                              target-kind))))
                       adefn (or (some-> (get-in trdef [:actions action-name])
                                         (assoc :name action-name))
                                 (throw (p/no-such-action target-kind
                                                          action-name)))]
                   ;; a bulk action is a collection affordance; its row
                   ;; form does not exist here either
                   (when (:bulk adefn)
                     (throw (p/no-such-action target-kind action-name)))
                   (let [;; THE ENGINE'S OWN HAND (ticket 552b2fd2): a handler
                         ;; that keeps an invariant of its kind names a
                         ;; :system actor as `:as`, and the inner write is
                         ;; that actor's, not the caller's — so it wears no
                         ;; leash and holds no power, and its transition
                         ;; names the engine. Only a :system principal: a
                         ;; handler never writes as a person.
                         as (:as opts)
                         _ (when (and as (not= :system (:type as)))
                             (throw (t/definition-error
                                     "ctx invoke: :as takes a :system principal")))
                         principal (or as principal)
                         grant (when-not as grant)
                         power (when-not as power)
                         res (invoke-in-tx!
                              engine tx trdef target-kind (str id) adefn
                              (body-digest body) body
                              {:principal principal
                               :correlation-id correlation-id
                               ;; the leash follows the hand
                               ;; (waymark-sfe): an inner write opened
                               ;; by a granted invoke is judged against
                               ;; the same scope, never waived by being
                               ;; reached from inside
                               :grant grant
                               ;; …and so does the engine's own hand
                               ;; on that leash's powers
                               ;; (waymark-fp62.7.16)
                               :power power
                               ;; the inner door learns whose hand
                               ;; opened it (waymark-jfv.20)
                               :within self
                               :require-key? false
                               ;; a FENCED target takes its etag the way
                               ;; an honest client would supply one —
                               ;; the worksheet's own spelling
                               ;; (worksheet/apply-invocations!), which
                               ;; reads the row's current version and
                               ;; hands it over rather than waiving the
                               ;; wall. The caller supplies it because
                               ;; only the caller knows which version it
                               ;; MEANT to write over; a cross-write
                               ;; that quietly waived the fence would be
                               ;; the one door in the tree where "the
                               ;; resource changed since you read it"
                               ;; stopped being asked (waymark-0k4).
                               :if-match (:if-match opts)
                               :acknowledged (or (:acknowledged opts) #{})})]
                     (swap! sink conj {:kind target-kind
                                       :action action-name
                                       :res res})
                     res))))
             ;; the cross-BIRTH door (chore's queue verb demanded it):
             ;; handlers birth rows of OTHER kinds through the same
             ;; transaction and the full create algorithm — the born
             ;; row rides the inner sink, so its lifecycle, cascades
             ;; and maintenance run post-commit exactly like a ctx
             ;; :invoke write's. Advertise the birth with :touches
             ;; {:kind … :action <its create name>}.
             :create
             (when sink
               (fn ctx-create [target-kind body & [opts]]
                 (let [trdef (or (get (resources engine) target-kind)
                                 (throw (t/definition-error
                                         (str "ctx create: unknown kind "
                                              target-kind))))
                       create-action (first (:create-action-names trdef))
                       ;; a handler holds DECODED values (a LocalDate,
                       ;; not its string); the door wire-shapes them
                       ;; against the target's create model so the
                       ;; digest and the create algorithm see exactly
                       ;; what the wire would have carried
                       body (schema/encode
                             (or (:create-schema trdef) (:schema trdef))
                             (or body {}))
                       res (create-in-tx!
                            engine tx target-kind body
                            {:principal principal
                             :correlation-id correlation-id
                             :grant grant
                             :within self
                             :acknowledged (or (:acknowledged opts) #{})})]
                   (swap! sink conj {:kind target-kind
                                     :action create-action
                                     :res res})
                   res)))
             ;; the REHEARSAL door (docs/spec-scheduled-actions.md
             ;; R-3.1): one door of another kind, judged as this
             ;; principal under this leash and written nowhere — the
             ;; invoke door's own dry run inside this transaction, or
             ;; the create rehearsal in full when `id` is nil. It throws
             ;; what the write would throw, an unacknowledged warning
             ;; included. A guard may hold it: it is no pen.
             :rehearse
             (fn ctx-rehearse [target-kind id action-name body & [opts]]
               (let [trdef (or (get (resources engine) target-kind)
                               (throw (p/not-found target-kind id)))
                     acknowledged (or (:acknowledged opts) #{})
                     res (if (nil? id)
                           (let [model (or (:create-schema trdef) (:schema trdef))
                                 inp (schema/apply-defaults
                                      model (schema/decode model (or body {})))]
                             (when-some [errors (schema/closed-errors model inp)]
                               (throw (p/schema-invalid :create errors)))
                             {:valid? true
                              :warnings (not-empty
                                         (:warned
                                          (create-guard-pass
                                           (create-walled-guards trdef) inp
                                           (make-ctx engine tx :dry-run principal
                                                     {:grant grant})
                                           acknowledged)))})
                           (invoke-in-tx!
                            engine tx trdef target-kind (str id)
                            (or (some-> (get-in trdef [:actions action-name])
                                        (assoc :name action-name))
                                (throw (p/no-such-action target-kind
                                                         action-name)))
                            (body-digest body) body
                            {:principal principal
                             :grant grant
                             :dry-run true
                             :acknowledged acknowledged}))]
                 (when-some [warned (seq (:warnings res))]
                   (throw (p/warning-refused (if id action-name :create) warned)))
                 res))
             ;; …and the law a birth of a kind would be stamped by now
             ;; (R-2.4): what a scheduled create pins
             :law-of (fn [target-kind]
                       (when-some [trdef (get (resources engine) target-kind)]
                         (create-law-revision engine trdef target-kind)))
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
             ;; …and the AGGREGATE twin (waymark-fp62.7.13). A guard
             ;; that judges a SUM — the seat's rolling week of fuel
             ;; against its budget — asks for the number, not for the
             ;; page of rows :find would hand it: the wall itself is
             ;; one store/sum-matching over conds the kind spells, and
             ;; a guard that re-summed a page would be a second answer
             ;; to one arithmetic. The write's own transaction, like
             ;; its siblings; a guard reading it declares
             ;; :reads [:storage].
             :sum (fn [target-kind field conds]
                    (when (get (resources engine) target-kind)
                      (store/sum-matching (:storage engine) tx target-kind
                                          field conds)))
             ;; …and the existence twin (docs/spec-related-row-law.md
             ;; §3): exists/none/count-rows ask for ids, or a count,
             ;; over the conds their where spells — never a page of rows
             :rows-matching (fn [target-kind conds limit]
                              (when-some [trdef (get (resources engine) target-kind)]
                                (store/ids-matching (:storage engine) tx target-kind
                                                    (law-conds trdef conds) limit)))
             :count-matching (fn [target-kind conds]
                               (when-some [trdef (get (resources engine) target-kind)]
                                 (store/count-matching (:storage engine) tx target-kind
                                                       (law-conds trdef conds))))
             ;; the ledger's reader (docs/spec-ledger-money.md §4, step
             ;; 5): an account's balance, read under its row's lock in
             ;; the write's own transaction, so two doors judged at once
             ;; for the last mug are judged one after the other. nil —
             ;; unknown to the law — for an account no kind declares and
             ;; for a house account, whose balance no row carries yet
             :balance (fn [account-kind id account-name]
                        (balance-in engine tx account-kind id account-name {:for-update true}))
             ;; the rate tables' reader (docs/spec-ledger-money.md §8,
             ;; step 7): nil — unknown to the law — where no row is.
             ;; :rate-of, since :rate is the rate limiter's coordinator
             :rate-of (fn [table key-vals on] (rate-on engine tx table key-vals on))
             ;; the house's calendar (docs/spec-calendar-time.md §3):
             ;; the row's work_calendar ref, or nothing for the
             ;; default, → the calendar that governs, or nil
             :calendar (tx-calendar engine tx)
             ;; the house's :rounding (docs/spec-ledger-money.md §2),
             ;; which (round m) reads
             :rounding (:rounding engine)
             ;; the vocabulary hook (waymark-vnc): kind → its declared
             ;; action-name strings, nil for a kind this engine does
             ;; not serve — the same registry check-action! and render
             ;; consult, so a guard judging a grant scope refuses a
             ;; (kind, action) pair that does not exist
             :action-names (fn [target-kind]
                             (some-> (get (resources engine)
                                          (keyword target-kind))
                                     action-names))
             ;; the registry consult (views as resources, waymark-rla):
             ;; a kind token or a collection plural → the assembled
             ;; rdef, nil when this engine serves no such kind — how
             ;; the saved_view write gate validates a user-authored
             ;; view against the target kind's own declared law
             :rdef-of (fn [token]
                        (let [rs (resources engine)
                              t (name token)]
                          (or (get rs (keyword t))
                              (some (fn [[_ r]] (when (= t (:plural r)) r))
                                    rs))))
             ;; …and the whole registry (ticket 5f3c276c): every kind
             ;; this engine serves, assembled. The seat doors read it
             ;; to run the cracks audit over the seats as they would
             ;; stand; the render probe carries none, and a guard that
             ;; finds none declines to guess
             :rdefs (fn [] (vals (resources engine)))
             ;; the log read by LEASH (seat health 1, ticket fad586b7):
             ;; the transitions an actor carrying this grant made in a
             ;; window, a nil bound open — how a sitting's close reads
             ;; what the sitting moved. The write's own transaction.
             :transitions-under
             (fn [grant-id since until]
               (store/transitions-under-grant (:storage engine) tx
                                              (str grant-id) since until {}))
             ;; the newest matching transition's actor id — or, with
             ;; :every, each face of every actor of it (did, §4)
             :actor-of (fn [row transition & every]
                         (apply g/actors-of
                                (store/transitions
                                 (:storage engine) tx
                                 {:kind (:kind row) :resource-id (:id row)}
                                 {:newest-first true})
                                transition every))
             ;; …and its sibling, which asks the log the OTHER question
             ;; (docs/spec-undo.md): not "who did X" but "what was the
             ;; last thing done here, by whom, and when". A guard
             ;; reading it declares :reads [:transitions]. One record,
             ;; not a page: an undo pops the stack, so nothing below
             ;; the top is any door's business.
             :last-transition
             (fn [row]
               (first (store/transitions
                       (:storage engine) tx
                       {:kind (:kind row) :resource-id (:id row)}
                       {:newest-first true :limit 1})))}))))

(defn- encode-row [rdef row]
  (update row :data #(schema/encode (:schema rdef) %)))

(defn- summary-of
  "The row's line, read on the engine's clock `now` (ticket d10e6c45)."
  [rdef row now]
  (summary/line rdef (assoc row :kind (:kind rdef)) now))

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
  "field → {:kind … :label … :carry …} for every :waymark/ref entry
  of one :map form that declares a :label (the maintained label
  garnish) or a :carry ({source target} — further target data fields
  copied alongside, the label doctrine for facts the referring row
  wants at a glance)."
  [form]
  (into {}
        (keep (fn [[f {:keys [properties schema]}]]
                (when (and (= :waymark/ref (schema-head* schema))
                           (:kind properties)
                           (or (:label properties) (:carry properties)))
                  [f (select-keys properties [:kind :label :carry])])))
        (schema/entry-map form)))

(defn- target-of
  "The referenced row, decoded and kind-stamped; nil when the target
  is gone (dangling refs are the guards' problem, loudly — never the
  garnish pass's)."
  [engine tx target-kind id]
  (when-some [trdef (get (resources engine) target-kind)]
    (some->> (store/load-row (:storage engine) tx target-kind (str id) {})
             (decode-row trdef)
             (#(assoc % :kind target-kind)))))

(defn- label-of
  "The target's declared label: its :label-template, defaulting to
  \"{data.name}\" when the target schema declares :name; nil when the
  target is unlabelable."
  [engine target]
  (let [trdef (get (resources engine) (:kind target))]
    (when-some [template
                (or (:label-template trdef)
                    (when (contains? (set (schema/entry-keys (:schema trdef)))
                                     :name)
                      "{data.name}"))]
      (summary/render template target))))

(defn- label-pass [engine tx specs m before]
  (reduce-kv
   (fn [m f {:keys [kind label carry]}]
     (let [new-id (get m f)]
       (cond
         (= new-id (get before f)) m

         (nil? new-id)
         (reduce (fn [m dst] (assoc m dst nil))
                 m
                 (cond-> (vec (vals carry)) label (conj label)))

         :else
         (if-some [target (target-of engine tx kind new-id)]
           (reduce-kv (fn [m src dst]
                        (assoc m dst (get-in target [:data src])))
                      (if-some [lv (when label (label-of engine target))]
                        (assoc m label lv)
                        m)
                      carry)
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

(defn- out-of-state-says
  "The action's own sentence about a row outside its `:from`
  (`:out-of-state-says`, a fn of row and the GUARD ctx — reads, no
  pen), or nil. Narration only: the refusal is already decided, so a
  narrator that throws costs its sentence and never the 409."
  [defn row ctx]
  (when-some [f (:out-of-state-says defn)]
    (try (some-> (f row ctx) str str/trim not-empty)
         (catch Exception _ nil))))

(defn- narration-row
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

(defn- deny-outcome
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
               :becomes-available (g/becomes-available d v nrow)}
              {:kind (:kind rdef) :id (:id row)
               :summary (summary-of rdef row now)})))))

(defn- run-guards
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
                (decision/evidence-scope rdef row inp ctx))]
    (reduce
     (fn [acc guard]
       (let [[v d] (g/evaluate guard row inp ctx)
             deny? (t/deny? v)
             acc (cond-> acc
                   scope (update :basis conj
                                 (decision/entry guard deny? d acknowledged
                                                 scope)))]
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

(defn- split-leaves
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

(defn- partial-verdict
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

(defn unchanged
  "A handler's answer that says it moved nothing: `row` itself, marked.
  When the door's state is the row's own, invoke lands no transition,
  no version bump and no history entry, and answers the row with its
  latest transition, as a natural replay does (:replayed? :unchanged).
  It is for a door that must ASK on every call and writes only when
  the answer moved (mcp_server discover asks the server each time)."
  [row]
  (vary-meta row assoc ::unchanged true))

(defn- stored-lines
  "The line rows one parent holds now, as their data in place order —
  what (lines) reads at a door that does not take `lines` (ledger step
  6b, docs/spec-ledger-money.md §6)."
  [engine tx rdef parent-id]
  (let [{child :kind via :parent} (:lines rdef)
        crdef (get (resources engine) child)]
    (mapv #(:data (if crdef (decode-row crdef %) %))
          (store/search-rows (:storage engine) tx child
                             [{:target :data :field via :op := :value (str parent-id)}]
                             {:order-by :n}))))

(defn- door-lines
  "(lines) at a door: the input's when the door takes `lines`, the
  stored set otherwise."
  [engine tx rdef row inp]
  (if (and (map? inp) (contains? inp :lines))
    (vec (:lines inp))
    (stored-lines engine tx rdef (:id row))))

(defn- posting-entries
  "The entries a landing door posts (ledger step 4): its :posts over the
  row as it leaves it, then — when it is the :undo of a posting door
  that made the row's last move — that move's postings, reversed. An
  entry that cannot be written refuses the door."
  [engine tx rdef row defn inp ctx materialized]
  (vec
   (concat
    (when-some [ps (seq (:posts defn))]
      (try (posts/entries ps (cond-> {:data (:data materialized) :input inp
                                      :now (:now ctx) :self (str (:id row))
                                      :calendar (:calendar ctx)}
                               (:lines rdef) (assoc :lines (door-lines engine tx rdef row inp))))
           (catch clojure.lang.ExceptionInfo e
             (if (:posts-refused (ex-data e))
               (throw (p/schema-invalid (:name defn) {:posts [(ex-message e)]}))
               (throw e)))))
    (when-some [undoes (not-empty (:reverses-posts defn))]
      (let [[latest] (store/transitions (:storage engine) tx
                                        {:kind (:kind rdef) :resource-id (:id row)}
                                        {:newest-first true :limit 1})]
        (when (and latest (contains? undoes (keyword (name (:action latest)))))
          ((requiring-resolve 'waymark10.server.postings/reversals)
           engine tx (:id latest))))))))

(defn- own-balances
  "`data` with this row's own accounts moved by `entries`, so the row a
  posting door saves already holds its balance; `own` is [kind id]."
  [data own entries]
  (reduce (fn [d {:keys [account-kind account-row account-name amount]}]
            (if (= own [(name account-kind) (str account-row)])
              (update d (keyword (name account-name))
                      #(if (some? %) (money/plus % amount) amount))
              d))
          data entries))

(defn- house-zone
  "The zone of the calendar that governs the write (ledger step 9a), as
  ctx's :calendar hook answers it — nil when nothing governs."
  [ctx]
  (when-some [calendar-of (:calendar ctx)]
    (:zone (calendar-of))))

(defn- land!
  "Steps 12–15: tamper refusal, materialize, advance, append, save,
  idempotency store — of the row the handler answered."
  [engine tx rdef row defn inp ctx handled
   {:keys [digest overridden basis idempotency-key principal correlation-id
           record-key?]
    :or {record-key? true}}]
  (let [now (:now ctx)
        _ (when-some [facts (seq (derived/tampered rdef row handled now (:calendar ctx)))]
            (throw (p/derived-tampered (:name defn) (vec facts))))
        ;; an account's balance (ledger step 3c) moves only by
        ;; postings: a handler that writes one is refused, whoever
        ;; holds the door
        _ (when-some [moved (money/balances-moved (:accounts rdef) (:data row)
                                                  (:data handled))]
            (throw (p/schema-invalid (:name defn) moved)))
        ;; ref labels + one-of clears (phase 8): engine passes, never
        ;; handler work — the handler sets the ref, the engine writes
        ;; the label and clears the other arm
        handled (update handled :data
                        #(labels-and-groups engine tx rdef % (:data row)))
        materialized (derived/materialize rdef handled now (:calendar ctx))
        ;; the door's postings (ledger step 4): this row's own accounts
        ;; move here, before it is saved; post! moves the others and
        ;; writes every posting, in this transaction, under the log row
        entries (posting-entries engine tx rdef row defn inp ctx materialized)
        own [(name (:kind rdef)) (str (:id row))]
        advanced (-> materialized
                     (update :data own-balances own entries)
                     (assoc :state (:to defn))
                     (update :version inc))
        advanced (assoc advanced :summary (summary-of rdef advanced (:now ctx)))
        record (store/append-transition!
                (:storage engine) tx
                {:kind (:kind rdef)
                 :resource-id (:id row)
                 :action (:name defn)
                 :from-state (:state row)
                 :to-state (:to defn)
                 ;; whose hand, under whose leash, on which model
                 ;; (actor-map: waymark-sfe, spec-seat.md R-9.5)
                 :actor (actor-map principal (:id (:grant ctx)))
                 :law-revision (:law-revision row)
                 :input-digest digest
                 :inputs (when (:record defn) inp)
                 :acknowledged (not-empty (vec overridden))
                 ;; the decision record: the basis was built at
                 ;; judgment time and threaded here — finish! receives
                 ;; ctx, not guard-ctx, so nothing is re-evaluated and
                 ;; the record says what the guards actually saw
                 :judgment (decision/record (:law-revision row) basis
                                            overridden)
                 ;; tier 3: the document as this write leaves it
                 :after (decision/after-record rdef (:data advanced))
                 :correlation-id correlation-id
                 :idempotency-key idempotency-key
                 :summary (or (when-some [says (:log-says defn)]
                                (some-> (says advanced inp) str not-empty))
                              (:summary advanced))})
        saved (store/save-row! (:storage engine) tx (:kind rdef)
                               (encode-row rdef (dissoc advanced :summary))
                               (:version row))]
    (when (seq entries)
      ((requiring-resolve 'waymark10.server.postings/post!)
       engine tx {:kind (:kind rdef) :id (:id row) :action (:name defn)
                  :log-id (:id record) :zone (house-zone ctx)}
       entries {:settled own}))
    ;; the effort landed; its draft has served its purpose — consumed
    ;; in the same commit as the write it composed (phase 7)
    (when (get-in defn [:edit :draft])
      (store/delete-draft! (:storage engine) tx (:kind rdef) (:id row)
                           (:name defn) (drafts/audience-of defn principal)))
    ;; the record is the CALL's: a fan-out item (bulk!/batch!/the job
    ;; worker) rides its call's key with :record-key? false — stamped
    ;; on the transition above, so the log says which call it came
    ;; from, and never stored here, so the whole-call report stays
    ;; the one record under that key (waymark-pywy.5)
    (when (and idempotency-key record-key?)
      (store/idempotency-store!
       (:storage engine) tx idempotency-key (:kind rdef) (:name defn) digest
       200 (if-some [render-fn (:render-fn engine)]
             ;; the render seam (phase 3): replay serves the same
             ;; envelope bytes the first execution answered with
             (render-fn rdef (decode-row rdef saved)
                        {:read (:read ctx) :find (:find ctx)})
             (wire/write-json {:id (:id saved) :state (name (:state advanced))
                               :version (:version advanced)
                               :summary (:summary advanced)}))
       "application/waymark+json"))
    ;; writes the handler made through ctx :invoke ride out with the
    ;; result — after-write! owes each its post-commit pass
    ;; …and so do the follow-ups it queued through ctx :follow-up, which
    ;; after-write! makes once this transaction has committed
    (let [inner (some-> (:inner-sink ctx) deref not-empty)
          follow (some-> (:follow-sink ctx) deref not-empty)]
      (cond-> {:row (decode-row rdef saved) :transition record}
        inner (assoc :inner-writes inner)
        follow (assoc :follow-ups follow)))))

;; ── the outbox (docs/spec-integrations.md § 4, build step 5) ─────────

(declare insert-quiet!)

(def effects-actor
  "The system actor that lands an effect's answer on the row that asked
  for it, through the door's hidden `<door>_landed` and `<door>_failed`."
  (t/principal {:id "waymark10-effects" :type :system :display "Effects"}))

(defn- effect-ask
  "The call an effect door asks for, over `row` (effect/ask), with the
  `effect_call` already kept under its power and key as :found, or nil.
  A key that reads nothing on this row refuses the door: without one
  the call cannot be made once."
  [engine tx rdef row defn inp now]
  (let [call (effect/ask (:effect defn) row inp now)
        crd (or (get (resources engine) :effect_call)
                (throw (t/definition-error
                        (str (name (:kind rdef)) "/" (name (:name defn))
                             " declares an :effect, and this engine enrolls no effect_call kind"))))]
    (when (str/blank? (:key call))
      (throw (p/problem :effect-no-key 409 "No idempotency key"
                        {:detail (str (name (:name defn))
                                      "'s :idempotency-key reads nothing on this row, so its call cannot be made once.")})))
    (assoc call :found
           (some->> (store/query-rows (:storage engine) tx :effect_call
                                      {:power (:power call) :idempotency_key (:key call)}
                                      {:limit 1})
                    first
                    (decode-row crd)))))

(defn- effect-probe
  "What an effect door's rehearsal says of its call (§ 7): the power,
  the key, and that it would call once — or write a `done` answer
  again. It calls nothing."
  [engine tx rdef row defn inp now]
  (let [{:keys [found] :as call} (effect-ask engine tx rdef row defn inp now)]
    {:power (:power call)
     :key (:key call)
     :says (effect/says call (= :done (:state found)))}))

(defn- outbox!
  "An effect door's outbox, in the door's own transaction (§ 4): one
  `pending` effect_call for the call it asks for, unless its power and
  key already have one. `effect_call` is unique on (power, key). A
  `done` call is reused: nothing is called, and its answer is written
  again through `<door>_landed` once this transaction commits; a
  `failed` one lands its failure again the same way, and a pending one
  lands on its own."
  [engine tx rdef row defn inp ctx {:keys [principal correlation-id]}]
  (let [{:keys [found] :as call} (effect-ask engine tx rdef row defn inp (:now ctx))
        aname (:name defn)
        again (fn [door input]
                (swap! (:follow-sink ctx) conj
                       {:kind (:kind rdef) :id (str (:id row)) :action door
                        :input (assoc input :effect_call (str (:id found)))
                        :opts {:principal effects-actor
                               :correlation-id correlation-id}}))]
    (cond
      (nil? found)
      (insert-quiet! engine tx :effect_call
                     (cond-> {:power (:power call)
                              :idempotency_key (:key call)
                              :target_kind (name (:kind rdef))
                              :target (str (:id row))
                              :door (name aname)
                              :input (wire/write-json (:input call))
                              :on_behalf_of (str (or (:id principal) "anonymous"))}
                       (:why call) (assoc :why (:why call)))
                     {:principal principal})

      (= :done (:state found))
      (again (effect/landed-door aname)
             (let [a (some-> (get-in found [:data :answer]) wire/read-json)]
               (if (map? a) {:answer a} {})))

      (= :failed (:state found))
      (again (effect/failed-door aname)
             {:reason (or (get-in found [:data :reason]) "The call failed.")}))))

(defn- unwind!
  "The compensations a door that `:unwinds` adds in its own transaction
  (§ 5). For each named effect whose call on this row is `done` and
  whose `:when` holds over the row as the door leaves it: one `pending`
  effect_call for its `:compensate` call, a door's or a power's, keyed `undo:<the
  original key>`, newest effect first, and none a second time. A
  compensation that fails lands that door's own `:fails-to`; the door
  that unwound is not rolled back."
  [engine tx rdef row defn ctx {:keys [principal]}]
  (when-some [crd (get (resources engine) :effect_call)]
    (let [st (:storage engine)
          now (:now ctx)
          done (mapv #(decode-row crd %)
                     (store/query-rows st tx :effect_call
                                       {:target (str (:id row)) :state :done} {:limit 500}))
          undone (distinct
                  (for [u (:unwinds defn)
                        :when (effect/unwinds? u row now)
                        c done
                        :when (and (= (name (:kind rdef)) (str (get-in c [:data :target_kind])))
                                   (= (name (:effect u)) (str (get-in c [:data :door]))))]
                    c))]
      (doseq [c (sort-by :created-at #(compare %2 %1) undone)
              :let [[door eff] (effect/compensation
                                rdef (keyword (str (get-in c [:data :door]))))
                    call (effect/ask eff row {} now)
                    k (effect/undo-key (get-in c [:data :idempotency_key]))]
              :when (empty? (store/query-rows st tx :effect_call
                                              {:power (:power call) :idempotency_key k}
                                              {:limit 1}))]
        (insert-quiet! engine tx :effect_call
                       (cond-> {:power (:power call)
                                :idempotency_key k
                                :target_kind (name (:kind rdef))
                                :target (str (:id row))
                                :door (name door)
                                :input (wire/write-json (:input call))
                                :on_behalf_of (str (or (:id principal) "anonymous"))}
                         (:why call) (assoc :why (:why call)))
                       {:principal principal})))))

(declare write-lines!)

(defn- defaulted-lines
  "The input with each of its lines' omitted :defaults filled (ledger
  step 6c, docs/spec-ledger-money.md §6), read through follow over the
  door's own :read, so a line's price is its product's when the door
  ran."
  [rdef ctx inp]
  (cond-> inp
    (and (get-in rdef [:lines :defaults]) (map? inp) (contains? inp :lines))
    (update :lines #(line-items/defaulted rdef (:read ctx) %))))

(defn- with-totals
  "The row's data with its kind's :totals (ledger step 6c) computed
  from these lines, in the door's own transaction, so the row is
  written with them and its postings read them. A total with no value
  is left out."
  [data rdef ls]
  (if-some [ts (not-empty (get-in rdef [:lines :totals]))]
    (merge (apply dissoc data (keys ts)) (line-items/totals rdef ls))
    data))

(defn- sans-lines
  "The input as the row's own data reads it: a kind that declares
  :lines (ledger step 6a) takes its `lines` into line rows, never into
  the parent's data."
  [rdef inp]
  (cond-> inp (and (:lines rdef) (map? inp)) (dissoc :lines)))

(defn- finish!
  "Step 11, the handler, then `land!` — unless the handler answered
  `unchanged` on an in-state door, which writes nothing. A door that
  took `lines` replaces its row's lines in this transaction."
  [engine tx rdef row defn inp ctx opts]
  (let [handled (if-some [h (:handler defn)]
                  (let [out (h row (sans-lines rdef inp) ctx)]
                    (when-not (map? out)
                      (throw (t/definition-error
                              (str "handler for " (name (:name defn))
                                   " must return the row"))))
                    out)
                  row)
        ;; its totals (ledger step 6c) are computed here, in this
        ;; transaction, from the lines the door writes
        handled (cond-> handled
                  (and (:lines rdef) (map? inp) (contains? inp :lines))
                  (update :data with-totals rdef (:lines inp)))]
    (if (and (::unchanged (meta handled)) (= (:state row) (:to defn)))
      (let [[latest] (store/transitions (:storage engine) tx
                                        {:kind (:kind rdef) :resource-id (:id row)}
                                        {:newest-first true :limit 1})]
        {:row row :transition latest :replayed? :unchanged})
      (do
        ;; the outbox (§ 4): the call rides this transaction, so the
        ;; transition and its effect_call commit or roll back together
        (when (:effect defn)
          (outbox! engine tx rdef (assoc row :data (:data handled)) defn inp ctx opts))
        ;; the undo (§ 5) rides it too; an effect door unwinds when it lands
        (when (and (:unwinds defn) (not (:effect defn)))
          (unwind! engine tx rdef (assoc row :data (:data handled)) defn ctx opts))
        (let [res (land! engine tx rdef row defn inp ctx handled opts)]
          (when (and (:lines rdef) (map? inp) (contains? inp :lines))
            (write-lines! engine tx rdef (:id row) (:lines inp)
                          (:principal opts) true))
          res)))))

;; ── the lifecycle seam (phase 5) ────────────────────────────────────

(declare cascade! invoke!)

(def ^:private ^:dynamic *following*
  "True while after-write! makes a write's follow-ups: the bound on
  their nesting. Every write made under it — the follow-up itself, its
  inner writes, its cascade — may queue, and nothing it queued runs."
  false)

(defn- follow-ups!
  "Make the calls a committed write queued through ctx :follow-up
  (ticket da882851), in the order it queued them. Each is an ordinary
  invoke! — its own transaction, the full algorithm, its own transition
  and after-write! pass — under the principal, leash and correlation id
  of the write that queued it, and under the key
  `follow-up:<outer transition id>:<index>`, so a second delivery of
  the same outer transition replays instead of writing twice.

  A follow-up that REFUSES is said on *err* and answered as :refused;
  the outer write committed before it was tried and stands.

  Follow-ups do not nest past ONE level: what a follow-up queues is
  dropped with a *err* line, never run. → one outcome per call,
  `{:kind :id :action}` plus `:res` or `:refused`; nil when dropped."
  [engine res]
  (let [tid (get-in res [:transition :id])
        say (fn [{:keys [kind id action]} why]
              (binding [*out* *err*]
                (println (str "waymark10: follow-up " (name kind) "/" id " "
                              (name action) " of transition " tid " " why))))]
    (if *following*
      (run! #(say % "dropped: a follow-up queues no follow-up")
            (:follow-ups res))
      (binding [*following* true]
        (vec
         (map-indexed
          (fn [i {:keys [kind id action input opts] :as call}]
            (try
              {:kind kind :id id :action action
               :res (invoke! engine kind id action input
                             (assoc opts :idempotency-key
                                    (str "follow-up:" tid ":" i)))}
              (catch Exception e
                (say call (str "refused: " (ex-message e)))
                {:kind kind :id id :action action
                 :refused (or (ex-message e) (str (class e)))})))
          (:follow-ups res)))))))

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
  action's :from states).

  Ticket da882851 adds the FOLLOW-UPS, last: the calls the handler
  queued through ctx :follow-up run after the cascade and the
  maintenance pass, so each reads the rows as this write left them.
  A refusing follow-up does not roll this write back — it committed
  first — and a replayed or rehearsed write runs none. The outcomes
  ride the result as :followed; the result's :row is the outer write's
  own, and predates them."
  [engine kind action-name res]
  (if (and (:transition res) (nil? (:replayed? res)))
    (do
      ;; inner writes (ctx :invoke) drain FIRST: their cascades and
      ;; maintenance land before the outer row's own pass, so the
      ;; response's rollups tell the post-inner truth (absorb's
      ;; survivor answers with its repointed products already counted)
      (doseq [iw (:inner-writes res)]
        (after-write! engine (:kind iw) (:action iw) (:res iw)))
      (when-some [lc (:lifecycle engine)]
        (lc engine kind action-name res))
      (cascade! engine kind action-name res)
      (let [out (if-some [m (:maintain engine)]
                  (or (m engine kind action-name res) res)
                  res)]
        (if (seq (:follow-ups res))
          (let [done (follow-ups! engine res)]
            (cond-> (dissoc out :follow-ups)
              done (assoc :followed done)))
          out)))
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
  sweep counts every stamped row).

  Writes NO decision record, whatever the kind retains: a restamp is
  the engine moving a row's law, not a judgment of it — there are no
  guards to record, and an empty object would make the column lie
  about coverage."
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
                         :actor (actor-map principal nil)
                         :law-revision target
                         :input-digest (body-digest nil)
                         :correlation-id correlation-id
                         :summary (summary-of rdef row ((:now-fn engine)))})
                saved (store/save-row! (:storage engine) tx kind
                                       (encode-row rdef advanced)
                                       (:version row))]
            {:row (decode-row rdef saved) :transition record}))))))

(declare invoke-declared!)

(defn invoke!
  "One write. opts: :principal (required), :if-match, :idempotency-key,
  :dry-run, :acknowledged (set of guard names), :correlation-id,
  :grant (the presented leash as the guards see it — waymark-sfe),
  :power (the engine's own hand on the powers that leash admits, for
  the handler — waymark-fp62.7.16; see make-ctx)."
  [engine kind id action-name body opts]
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
  each owned child sitting in that action's :from states. Pages via
  the child's :via ref with a growing fetch window + a seen set — a
  SELF-LOOP child action (the cascaded child stays in the :from
  filter, e.g. reprice) must still terminate, so already-cascaded ids
  never count as due and the window widens past them. The default
  created_at order is stable enough: the window growing past the
  total row count bounds the loop either way."
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
      (loop [seen #{}]
        (let [limit (+ 200 (count seen))
              children (store/with-tx (:storage engine)
                         (fn [tx]
                           (store/query-rows (:storage engine) tx child-kind
                                             {(:via edge) parent-id}
                                             {:limit limit})))
              due (filterv #(and (contains? eligible (:state %))
                                 (not (contains? seen (:id %))))
                           children)]
          (when (seq due)
            (doseq [child due]
              (invoke! engine child-kind (:id child) child-action nil
                       {:principal actor
                        :correlation-id cid
                        :idempotency-key (when-not idempotent?
                                           (str (random-uuid)))}))
            (when (= limit (count children))
              (recur (into seen (map :id) due)))))))))

(defn- hand-written-errors
  "The engine-written fields (schema/engine-written-fields of the kind's
  :schema) an input carries from a hand — {field [why]}, or nil. A
  :system principal (the seed loader, the registrar, a pass) writes
  them; every other principal is refused as schema-invalid, before any
  guard runs. An account's balance (ledger step 3c) is refused from
  every principal, :system's too: only postings move it."
  [rdef principal inp]
  (not-empty
   (merge
    (when-not (= :system (:type principal))
      (into {}
            (keep (fn [k]
                    (when (contains? inp k)
                      [k ["written by the engine, never by hand"]])))
            (schema/engine-written-fields (:schema rdef))))
    (into {}
          (keep (fn [k]
                  (when (contains? inp k)
                    [k [(money/by-postings k)]])))
          (keys (:accounts rdef))))))

(defn- invoke-in-tx!
  "Steps 2–15 inside the caller's transaction — the single-write body
  invoke-declared! wraps in its own with-tx; bulk items and batch
  inputs (phase 7) run it inside the fan-out's transaction.
  :require-key? false waives step 2's 428 for fan-out items;
  :record-key? false makes a present key a STAMP rather than a
  record (waymark-pywy.5) — written on the transition, neither
  looked up nor stored, because the call that fanned out owns the
  one record under it."
  [engine tx rdef kind id defn digest body
   {:keys [principal if-match idempotency-key dry-run acknowledged
           correlation-id require-key? record-key? within grant power]
    :or {acknowledged #{} require-key? true record-key? true}}]
  (let [action-name (:name defn)]
    ;; 2. idempotency: requirement, then stored replay
    (when (and require-key?
               (not dry-run)
               (not (get-in defn [:safety :idempotent]))
               (nil? idempotency-key))
      (throw (p/idempotency-key-required action-name)))
    (if-some [hit (when (and (not dry-run) idempotency-key record-key?)
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
            ctx (make-ctx engine tx (if dry-run :dry-run :invoke) principal
                          {:correlation-id correlation-id
                           :within within
                           :grant grant
                           :power power
                           :idempotency-key idempotency-key
                           :self {:kind kind :action action-name}})
            ;; guards judge; they never write — the pen stays with the
            ;; handler (and :on-create), so guard evaluation gets a
            ;; ctx without the cross-write door, and without the hand
            ;; on a power (waymark-fp62.7.16): a wall that read mail
            ;; would refuse differently on a day Gate was dark
            guard-ctx (-> (dissoc ctx :invoke :create :inner-sink :power
                                  :follow-up :follow-sink)
                          ;; the door this write stands on (ticket
                          ;; a77bf705): a guard several doors carry
                          ;; reads which one judges it from here, and
                          ;; not from the shape of the body
                          (assoc :door action-name)
                          ;; the row's stored lines (ledger step 6b),
                          ;; read only when a guard asks for (lines)
                          (cond-> (:lines rdef)
                            (assoc :stored-lines (delay (stored-lines engine tx rdef id)))))]
        (if-not (contains? (:from defn) (:state row))
          ;; 5. out of state: replay, conceal, or narrate
          (or (natural-replay engine tx rdef row defn digest within)
              (when (probe-hidden-only? defn row guard-ctx)
                (throw (p/not-found kind id)))
              (throw (p/wrong-state action-name (:state row) (:from defn)
                                    {:kind kind :id id
                                     :summary (summary-of rdef row ((:now-fn engine)))}
                                    (out-of-state-says defn row guard-ctx)
                                    (machine/roads rdef defn (:state row)))))
          (do
            ;; concealment precedes everything in-state too (phase 8's
            ;; ordering amendment, forced by the mirror's sync doors):
            ;; what a hide-flagged guard conceals must not leak through
            ;; the fence (412), input validation (422), or a natural
            ;; replay (200) — the wire answers 404 first
            (when (probe-hidden-only? defn row guard-ctx)
              (throw (p/not-found kind id)))
            ;; 6. the fence. An edit door (ticket 5120da15) is fenced
            ;; whenever its caller names the version it read, as the
            ;; etag or the bare number (`if_version` over MCP), and a
            ;; stale one refuses naming which of its fields moved since
            (let [patch? (and (patch/prefill defn) (patch/patch? body))
                  current (etag kind id (:version row))
                  given (some-> if-match str str/trim not-empty)
                  given (if (and given (re-matches #"\d+" given))
                          (etag kind id given)
                          given)
                  ;; the fields that moved since `given`, from the
                  ;; retained document, for either mode's refusal
                  moved #(patch/moved defn
                                      (store/transitions
                                       (:storage engine) tx
                                       {:kind kind :resource-id (:id row)}
                                       {})
                                      (patch/read-version given)
                                      (:version row)
                                      (:data raw))]
              (cond
                (and patch? given (not= given current))
                (throw (patch/stale action-name
                                    {:kind kind :id id :etag current}
                                    (moved)))
                (and (get-in defn [:safety :fence]) (not= given current))
                (throw (p/version-conflict action-name
                                           {:kind kind :id id
                                            :etag current}
                                           (when (and given (patch/prefill defn))
                                             (moved))))))
            ;; 7. input validation — decode first (validation
            ;; speaks schema types), closed maps refuse unknowns.
            ;; The partial rehearsal (:dry-run :partial, design §23)
            ;; judges only the entries the caller provided — a
            ;; half-built form has no required-missing story yet,
            ;; but a provided field's errors (and an unknown key)
            ;; refuse exactly as ever
            (let [partial? (= :partial dry-run)
                  ;; an edit door takes a patch when the caller asks
                  ;; for one with `patch: true` (ticket 5120da15): what
                  ;; the caller left out keeps its stored value and a
                  ;; list may arrive as {add, remove}, resolved here so
                  ;; validation, the guards, the handler and the log
                  ;; all read the whole input. Without the flag the
                  ;; input is wholesale, as ever
                  patch? (and (patch/prefill defn) (patch/patch? body))
                  body (cond-> body
                         (patch/prefill defn) patch/strip
                         (and patch? (not partial?))
                         (->> (patch/resolve-input defn (:data raw))))
                  inp (if (:input defn)
                        (let [decoded (schema/apply-defaults
                                       (:input defn)
                                       (schema/decode (:input defn) (or body {})))
                              errors (cond-> (schema/closed-errors
                                              (:input defn) decoded)
                                       partial?
                                       (-> (select-keys (keys (or body {})))
                                           not-empty))]
                          (when errors
                            (throw (p/schema-invalid action-name errors)))
                          (when-some [hand (when (:edit defn)
                                             (hand-written-errors rdef principal decoded))]
                            (throw (p/schema-invalid action-name hand)))
                          decoded)
                        (if (seq body)
                          (throw (p/schema-invalid
                                  action-name
                                  (into {} (map (fn [[k _]] [k ["unexpected field"]])) body)))
                          nil))
                  ;; a line's omitted defaults fill here (ledger step
                  ;; 6c), so the guards, the handler and the lines
                  ;; written all read the line as the door placed it
                  inp (defaulted-lines rdef ctx inp)]
              ;; 8. natural replay before guards
              (or (when (and (not dry-run)
                             (get-in defn [:safety :idempotent])
                             (not (false? (:replay defn))))
                    (natural-replay engine tx rdef row defn digest within))
                  ;; 9. the guard loop — partial judges only the
                  ;; leaves whose every judged field arrived
                  (if partial?
                    (partial-verdict defn row inp guard-ctx acknowledged rdef
                                     (set (keys (or body {}))))
                    (let [{:keys [warned overridden basis]}
                          (run-guards defn row inp guard-ctx acknowledged rdef)]
                      (cond
                        ;; 10. dry-run exits before any effect — and a
                        ;; rehearsal RECORDS nothing either (§23): the
                        ;; basis dies with the transaction that never
                        ;; wrote a transition
                        ;; the rehearsal's exit also says what would
                        ;; move — the states either side and the
                        ;; fields the input names — for the bulk
                        ;; door's per-item `would` (waymark-pywy.4)
                        dry-run (cond-> {:valid? true :warnings (not-empty warned)
                                         :from (:state row) :to (:to defn)
                                         :fields (vec (sort (map p/wire-key (keys inp))))}
                                  ;; an effect door says the call it would
                                  ;; add, and calls nothing (§ 7)
                                  (:effect defn)
                                  (assoc :effects [(effect-probe engine tx rdef row defn inp
                                                                 (:now ctx))]))
                        (seq warned) (throw (p/warning-refused action-name warned))
                        :else (finish! engine tx rdef row defn inp ctx
                                       {:digest digest
                                        :overridden overridden
                                        :basis basis
                                        :idempotency-key idempotency-key
                                        :record-key? record-key?
                                        :principal principal
                                        :correlation-id correlation-id}))))))))))))

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

(def ^:private on-error-modes
  "The bulk door's `on_error` vocabulary: continue is the partial-
  success report, atomic is one transaction that any refusal rolls
  back, stop halts at the first refusal and reports what ran and
  what did not."
  #{"continue" "stop" "atomic"})

(defn- on-error-mode
  "The mode a bulk call runs under: the declaration's (atomic when
  `:bulk {:atomic true}`, else continue) unless the body asks — and
  the body may only TIGHTEN. A declared-atomic action is a promise
  the collection made; a caller asking it to continue past a refusal
  is asking the declaration to be somebody else's."
  [action-name spec body]
  (let [given (:on_error body)
        declared (if (:atomic spec) "atomic" "continue")]
    (cond
      (nil? given) declared
      (not (contains? on-error-modes given))
      (throw (p/schema-invalid action-name
                               {:on_error ["one of continue, stop, atomic"]}))
      (and (:atomic spec) (not= "atomic" given))
      (throw (p/schema-invalid
              action-name
              {:on_error [(str (name action-name)
                               " is declared atomic; on_error can only be atomic")]}))
      :else given)))

(defn- item-errors
  "One `items` entry's contract violations, sentences prefixed with
  its index — id a non-blank string, input a map when present,
  acknowledge an array of guard names when present, nothing else."
  [i it]
  (let [at (str "item " i ": ")]
    (cond-> []
      (not (and (string? (:id it)) (not (str/blank? (:id it)))))
      (conj (str at "id must be a non-blank string"))
      (and (contains? it :input) (some? (:input it)) (not (map? (:input it))))
      (conj (str at "input must be an object"))
      (and (contains? it :acknowledge) (some? (:acknowledge it))
           (not (and (vector? (:acknowledge it))
                     (every? string? (:acknowledge it)))))
      (conj (str at "acknowledge must be an array of guard names"))
      (seq (dissoc it :id :input :acknowledge))
      (conj (str at "unexpected field "
                 (str/join ", " (map name (keys (dissoc it :id :input :acknowledge)))))))))

(defn- bulk-items
  "Both bulk body shapes as ONE item list — {:id :input :acknowledged}
  per row, validated (every violation is the 422 an input field
  draws, keyed on the shape's own field).

  `{:ids […] …input}` is one input over N rows, the phase-7 shape:
  every item carries the same input and the call-level acknowledged
  set (the Waymark-Acknowledge header — one input, one reading, one
  acknowledgement).

  `{:items [{:id :input? :acknowledge?}]}` is N rows each with its
  own input (waymark-pywy.4). Acknowledgement is PER ITEM here, and
  the header is refused rather than spread: a blanket acknowledgement
  over rows whose warnings the caller has not read one by one is a
  guard-override device, and the door will not be one."
  [action-name body acknowledged]
  (let [body (or body {})
        ids (:ids body)
        items (:items body)]
    (cond
      (and (contains? body :ids) (contains? body :items))
      (throw (p/schema-invalid action-name
                               {:items ["one of ids or items, not both"]}))

      (contains? body :items)
      (do
        (when-not (and (vector? items) (seq items) (every? map? items))
          (throw (p/schema-invalid
                  action-name
                  {:items ["required, non-empty array of {id, input?, acknowledge?} objects"]})))
        (when-some [extras (seq (dissoc body :items :on_error))]
          (throw (p/schema-invalid
                  action-name
                  (into {} (map (fn [[k _]] [k ["unexpected field — each item carries its own input"]]))
                        extras))))
        (when-some [errors (seq (into [] (comp (map-indexed item-errors) cat) items))]
          (throw (p/schema-invalid action-name {:items (vec errors)})))
        (when (seq acknowledged)
          (throw (p/problem
                  :acknowledge-per-item 422 "Acknowledge is per item"
                  {:detail (str "The items shape acknowledges per item: name "
                                "each item's warned guards in its own "
                                "`acknowledge` array. A Waymark-Acknowledge "
                                "header would spread one reading over every "
                                "row, and no row was read that way.")
                   :action-attempted action-name
                   :acknowledge {:field "items[].acknowledge"
                                 :names (mapv name acknowledged)}})))
        (mapv (fn [it]
                {:id (:id it)
                 :input (not-empty (:input it))
                 :acknowledged (into #{} (map keyword) (:acknowledge it))})
              items))

      :else
      (do
        (when-not (and (vector? ids) (seq ids) (every? string? ids))
          (throw (p/schema-invalid action-name
                                   {:ids ["required, non-empty array of ids"]})))
        (let [input (not-empty (dissoc body :ids :on_error))]
          (mapv (fn [id] {:id id :input input :acknowledged acknowledged})
                ids))))))

(defn- where-items
  "The third bulk body shape (rain day C): `{:where {param value}
  …input}` — one input over every row the collection's own filter
  matches, acknowledged at call level as `ids` is. `resolve` is the
  router's seam (this namespace reads no collection): given the where
  map it answers the matched ids, in the collection's order, read under
  the caller's grant. They are resolved HERE, before anything runs or
  defers, so a job runs a fixed list. An empty match is an empty list,
  and the report says nothing ran."
  [action-name body acknowledged resolve]
  (let [where (:where body)]
    (cond
      (or (contains? body :ids) (contains? body :items))
      (throw (p/schema-invalid action-name
                               {:where ["one of ids, items or where, not two"]}))

      (not (and (map? where) (seq where)))
      (throw (p/schema-invalid
              action-name
              {:where ["required, a non-empty object of the collection's query parameters"]}))

      (nil? resolve)
      (throw (p/schema-invalid action-name
                               {:where ["this door takes ids or items"]}))

      :else
      (let [input (not-empty (dissoc body :where :on_error))]
        (mapv (fn [id] {:id id :input input :acknowledged acknowledged})
              (resolve where))))))

(defn- item-verdict
  "One bulk item's rehearsal verdict — self-keyed, the guard's own
  sentence on a refusal, and on an ok the `would`: the state the row
  would leave and reach and the fields the input names. The
  rehearsal never fires the handler (§23's iron rule), so what a
  handler would write is exactly what it does not claim to know."
  [href id res]
  (cond-> {:self (href id) :verdict "ok"}
    (:warnings res) (assoc :warnings (:warnings res))
    (:to res) (assoc :would (cond-> {:from (name (:from res))
                                     :to (name (:to res))}
                              (seq (:fields res)) (assoc :fields (:fields res))))))

(defn- deferred-marker
  "What an over-threshold call hands the job resource: the phase-9b
  marker ({:kind :action :ids :input}) — and, for the items shape,
  each row's own input and acknowledged names, index-aligned with
  `:ids`, so the worker's `bulk-item!` runs exactly the call that was
  deferred. The call's idempotency key rides too (waymark-pywy.5): a
  deferred call keeps no whole-call record, but its items are stamped
  with the key exactly as a synchronous call's are, so the log reads
  the same whichever side of the threshold a call fell."
  [kind action-name body items idempotency-key]
  (cond-> (if (contains? body :items)
            {:kind kind
             :action action-name
             :ids (mapv :id items)
             :inputs (mapv #(or (:input %) {}) items)
             :acknowledged (mapv #(mapv name (:acknowledged %)) items)}
            {:kind kind
             :action action-name
             :ids (mapv :id items)
             :input (:input (first items))})
    idempotency-key (assoc :idempotency-key idempotency-key)))

(defn bulk!
  "N resources, one call: POST /api/{plural}/-/{action} with either
  body shape `bulk-items` reads — `{:ids [...] …input}` (one input
  over many rows) or `{:items [{:id :input? :acknowledge?}]}` (each
  row its own input, acknowledged per item). Guards run per row
  through the same per-item algorithm as a single invoke. Returns
  {:report wire-doc} or an idempotency replay; atomic refusals throw
  (see the ns docstring); a dry-run answers {:valid? … :verdicts […]}.

  `on_error` in the body picks the mode — continue (the partial-
  success report, one transaction per item), stop (halt at the first
  refusal; the report says what ran and what did not), atomic (one
  transaction, any refusal rolls all back) — and may only tighten
  the declaration's own. opts: :principal, :idempotency-key,
  :acknowledged, :correlation-id, :dry-run, :grant, and :where-ids —
  the router's resolver for the third shape, `{:where {…} …input}`
  (`where-items`), called with the where map and :max-items.

  Whole-call idempotency is the convention both shapes keep: the
  call's key stores the report (`fan-out-store!`), and a replay is
  the CALL's promise, not the row's. Every item transition carries
  the call's key all the same (waymark-pywy.5) — stamped, not
  recorded: items run with :record-key? false, so the key lands in
  the log (one action per item for `feed/actions-from-feed` and
  `mcp/actions-from-mcp`, which fold by the key's prefix) while the
  idempotency store holds exactly the one whole-call record.

  `:on-item`, when given, is called with each item's result once the
  item has committed and its after-write ran: the router's seam for
  what a single invoke does after its own commit (a self walk's
  recording, walks/record-own!). A rehearsal, a deferral and a replay
  of the whole call run none. In a partial bulk a throw from the
  after-write or from `:on-item` is logged and changes nothing in the
  report: the row's transaction committed, so the row succeeded.

  `:on-refused`, when given, is called with a refused row's id and the
  refusal it threw, in a partial bulk and in an atomic one alike: the
  router's seam for what a single invoke does with its refusal (a self
  walk's recording, walks/record-refused!). A rehearsal runs none, and
  a throw from it is logged and changes nothing."
  [engine kind action-name body
   {:keys [principal idempotency-key acknowledged correlation-id
           dry-run grant on-item on-refused where-ids]
    :or {acknowledged #{}}}]
  (let [rdef (rdef-of engine kind)
        defn (some-> (get-in rdef [:actions action-name])
                     (assoc :name action-name))
        _ (when-not (:bulk defn)
            (throw (p/no-such-action kind action-name)))
        spec (fan-out-spec (:bulk defn))
        max-items (:max-items spec 100)
        body (or body {})
        items (if (contains? body :where)
                (where-items action-name body acknowledged
                             (when where-ids #(where-ids % max-items)))
                (bulk-items action-name body acknowledged))
        mode (on-error-mode action-name spec body)
        href #(str "/api/" (:plural rdef) "/" %)]
    (if (and (not dry-run)
             (when-some [threshold (:defer-over spec)]
               (< threshold (count items))))
      ;; phase 9b closes the phase-7 punt: an over-threshold call
      ;; defers to the job resource — bulk! answers the marker, the
      ;; router mints the job and 202s (which keeps this namespace
      ;; free of a jobs require). A job runs continue-mode by its
      ;; nature (refusals recorded per row, never stopping the rest),
      ;; so a caller who asked for stop or atomic over the threshold
      ;; is told the two do not fit rather than handed a job that
      ;; would quietly run as continue.
      (if (and (some? (:on_error body)) (not= "continue" (:on_error body))
               (not (:atomic spec)))
        (throw (p/schema-invalid
                action-name
                {:on_error [(str "over " (:defer-over spec) " items this call "
                                 "defers to a job, which runs continue; send "
                                 "at most " (:defer-over spec) " items for "
                                 (:on_error body))]}))
        {:deferred (deferred-marker kind action-name body items
                                    idempotency-key)})
      (do
        (when (< max-items (count items))
          (throw (p/schema-invalid
                  action-name
                  {(if (contains? body :items) :items :ids)
                   [(str "at most " max-items " "
                         (if (contains? body :items) "items" "ids")
                         " per call")]})))
        (let [digest (body-digest body)
              marker (keyword (str "bulk:" (name action-name)))
              item-opts (fn [it]
                          {:principal principal
                           :acknowledged (:acknowledged it)
                           :grant grant
                           :require-key? false})]
          (if dry-run
            ;; the rehearsal fans out too (design §23): every item
            ;; judged through the same per-item algorithm with
            ;; :dry-run riding along — no lock, no key demanded or
            ;; recorded, nothing committed, never a deferral (a job
            ;; is an effect). One transaction per item so a poisoned
            ;; read never taints its neighbors; verdicts are
            ;; self-keyed (waymark9's batch dry-run vocabulary —
            ;; 9 never grew a bulk one, recorded).
            (let [verdicts
                  (mapv
                   (fn [{:keys [id input] :as it}]
                     (try
                       (let [res (store/with-tx (:storage engine)
                                   (fn [tx]
                                     (invoke-in-tx! engine tx rdef kind id
                                                    defn (body-digest input) input
                                                    (assoc (item-opts it)
                                                           :dry-run dry-run))))]
                         (item-verdict href id res))
                       (catch Exception e
                         (if (refusal? e)
                           {:self (href id) :verdict "refused"
                            :reason (problem-reason e)}
                           (do (binding [*out* *err*]
                                 (println "waymark10 bulk dry-run item error:"
                                          (name kind) id "-" (ex-message e)))
                               {:self (href id) :verdict "failed"
                                :reason "Internal error while judging this item."})))))
                   items)]
              {:valid? (every? #(= "ok" (:verdict %)) verdicts)
               :verdicts verdicts})
            (or (fan-out-replay engine kind action-name marker digest
                                idempotency-key (get-in defn [:safety :idempotent]))
              (let [cid (or correlation-id (str (random-uuid)))
                    run-item (fn [tx {:keys [id input] :as it}]
                               (invoke-in-tx! engine tx rdef kind id defn
                                              (body-digest input) input
                                              (assoc (item-opts it)
                                                     :correlation-id cid
                                                     ;; the call's key: stamped
                                                     ;; on the item's transition,
                                                     ;; recorded once below
                                                     :idempotency-key idempotency-key
                                                     :record-key? false)))
                    ;; the partial loop's post-commit pass: the item's
                    ;; transaction has committed, so a throw here is
                    ;; logged on its own and never reported as the
                    ;; row's failure. Each half is caught apart, so a
                    ;; broken after-write does not cost the row its
                    ;; :on-item
                    committed! (fn [id res]
                                 (try
                                   (after-write! engine kind action-name res)
                                   (catch Exception e
                                     (binding [*out* *err*]
                                       (println "waymark10 bulk item after-write error:"
                                                (name kind) id "-" (ex-message e)))))
                                 (when on-item
                                   (try
                                     (on-item res)
                                     (catch Exception e
                                       (binding [*out* *err*]
                                         (println "waymark10 bulk item on-item error:"
                                                  (name kind) id "-" (ex-message e)))))))
                    ;; a refused row's own pass: the refusal stands
                    ;; whatever this does, so a throw here is logged
                    refused! (fn [id e]
                               (when on-refused
                                 (try
                                   (on-refused id e)
                                   (catch Exception x
                                     (binding [*out* *err*]
                                       (println "waymark10 bulk item on-refused error:"
                                                (name kind) id "-" (ex-message x)))))))
                    data
                    (if (= "atomic" mode)
                      ;; all-or-nothing: one transaction, any refusal
                      ;; rolls the whole call back — the 409 carries
                      ;; the report
                      (let [at (volatile! nil)
                            results
                            (try
                              (store/with-tx (:storage engine)
                                (fn [tx]
                                  (mapv (fn [it] (vreset! at (:id it)) (run-item tx it))
                                        items)))
                              (catch Exception e
                                (when (refusal? e) (refused! @at e))
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
                        ;; every row committed: the same guarded pass
                        ;; as the partial loop, so a throw here costs
                        ;; neither the report nor the rows after it
                        (doseq [[it res] (map vector items results)]
                          (committed! (:id it) res))
                        {:succeeded (count items) :refused 0 :failed 0 :refusals []})
                      ;; partial success: one transaction PER item — a
                      ;; refusal never poisons its neighbors. Stop mode
                      ;; is the same loop cut at the first item that
                      ;; did not land, with the rest reported unrun.
                      (let [stop? (= "stop" mode)
                            rep
                            (reduce
                             (fn [rep {:keys [id] :as it}]
                               (let [rep
                                     (try
                                       (let [res (store/with-tx (:storage engine)
                                                   #(run-item % it))]
                                         (committed! id res)
                                         (update rep :succeeded inc))
                                       (catch Exception e
                                         (if (refusal? e)
                                           (-> (do (refused! id e) rep)
                                               (update :refused inc)
                                               (update :refusals conj
                                                       {:self (href id)
                                                        :reason (problem-reason e)
                                                        ;; the router counts the
                                                        ;; 409s and stamps the
                                                        ;; last one's law;
                                                        ;; stripped below
                                                        :status (:status (ex-data e))
                                                        :type (:type (ex-data e))
                                                        :guard (:guard (ex-data e))}))
                                           (do (binding [*out* *err*]
                                                 (println "waymark10 bulk item error:"
                                                          (name kind) id "-" (ex-message e)))
                                               (-> rep
                                                   (update :failed inc)
                                                   (update :refusals conj
                                                           {:self (href id)
                                                            :reason "Internal error while processing this item."}))))))
                                     ran (+ (:succeeded rep) (:refused rep) (:failed rep))]
                                 (if (and stop? (< (:succeeded rep) ran))
                                   (reduced (assoc rep :ran ran))
                                   rep)))
                             {:succeeded 0 :refused 0 :failed 0 :refusals []}
                             items)]
                        (if stop?
                          (let [ran (or (:ran rep) (count items))
                                rest (subvec items ran)]
                            (-> (dissoc rep :ran)
                                (assoc :skipped (count rest)
                                       :not-run (mapv #(hash-map :self (href (:id %)))
                                                      rest))))
                          rep)))
                    ;; per-item 409s leave beside the report, not in
                    ;; it: the stored replay and the wire stay the
                    ;; shape they were (waymark-fp62.7.11)
                    conflicted (filter #(= 409 (:status %)) (:refusals data))
                    conflicts (count conflicted)
                    doc (report-doc action-name
                                    (update data :refusals
                                            (partial mapv #(dissoc % :status :type :guard)))
                                    nil)]
                (fan-out-store! engine kind marker digest idempotency-key doc)
                ;; the newest per-item 409's problem type and guard ride
                ;; beside the count, for the sitting's :last_refusal
                (cond-> {:report doc :conflicts conflicts}
                  (seq conflicted)
                  (assoc :last-conflict
                         (select-keys (last conflicted) [:type :guard])))))))))))

(defn bulk-item!
  "One id through the SAME per-item algorithm bulk!'s partial-success
  path runs — its own transaction, after-write! included, step 2's
  428 waived (the caller owns the call-level record), and the call's
  :idempotency-key stamped on the item's transition without being
  recorded (waymark-pywy.5). Exposed for the deferred-jobs worker
  (phase 9b): a job item and a bulk item are one code path. Refusals
  throw exactly as a single invoke's would."
  [engine kind action-name id body
   {:keys [principal acknowledged correlation-id grant idempotency-key]
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
                         :grant grant
                         :idempotency-key idempotency-key
                         :require-key? false
                         :record-key? false}))))))

(defn batch!
  "N inputs, one resource: POST /api/{plural}/{id}/-/{action}/batch
  with body {:inputs [{…} …]}. Always atomic — one transaction, one
  row lock, inputs applied in order, each input its own transition;
  the first refusal aborts the whole batch with a 409 naming its
  index. Returns {:report wire-doc} or an idempotency replay.

  A dry-run (design §23) judges every input through the same per-item
  algorithm against the row AS IT STANDS — one transaction, no lock,
  nothing committed, EVERY verdict reported (the real atomic batch
  aborts at its first refusal). Recorded deviation from waymark9 both
  ways: 9's batch dry-run executed under a doomed transaction —
  handlers ran, then rollback — so verdict i saw input i-1's effects;
  10's iron rule is that a rehearsal never fires a handler, so each
  input is judged independently.

  `:on-item`, when given, is called with each input's result once the
  batch has committed and its after-write ran, as `bulk!` calls it for
  each row. A rehearsal and a replay of the whole call run none."
  [engine kind id action-name body
   {:keys [principal idempotency-key acknowledged correlation-id
           dry-run grant on-item]
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
    (if dry-run
      ;; the batch rehearsal: no key demanded or read (the caller is
      ;; asking, not acting), each input its own verdict
      (store/with-tx (:storage engine)
        (fn [tx]
          (let [item-opts {:principal principal
                           :acknowledged acknowledged
                           :grant grant
                           :dry-run dry-run
                           :require-key? false}
                verdicts
                (into []
                      (map-indexed
                       (fn [i input]
                         (try
                           (let [res (invoke-in-tx! engine tx rdef kind id
                                                    defn (body-digest input)
                                                    input item-opts)]
                             (cond-> {:index i :verdict "ok"}
                               (:warnings res)
                               (assoc :warnings (:warnings res))))
                           (catch Exception e
                             (if (refusal? e)
                               {:index i :verdict "refused"
                                :reason (problem-reason e)}
                               (throw e))))))
                      inputs)]
            {:valid? (every? #(= "ok" (:verdict %)) verdicts)
             :verdicts verdicts})))
      (let [digest (body-digest body)
            marker (keyword (str "batch:" (name action-name)))]
        (or (fan-out-replay engine kind action-name marker digest
                            idempotency-key (get-in defn [:safety :idempotent]))
          (let [cid (or correlation-id (str (random-uuid)))
                item-opts {:principal principal
                           :acknowledged acknowledged
                           :grant grant
                           :correlation-id cid
                           ;; each input's transition carries the
                           ;; call's key; the report stays the one
                           ;; record under it (waymark-pywy.5)
                           :idempotency-key idempotency-key
                           :require-key? false
                           :record-key? false}
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
              (after-write! engine kind action-name res)
              (when on-item (on-item res)))
            (let [doc (report-doc action-name
                                  {:succeeded (count inputs)
                                   :refused 0 :failed 0 :refusals []}
                                  {:links {:target {:href (str "/api/" (:plural rdef)
                                                               "/" id)}}})]
              (fan-out-store! engine kind marker digest idempotency-key doc)
              {:report doc})))))))

(defn create-law-revision
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

(defn- create-walled-guards
  "The create door's guards, with the framework's dangling-ref wall
  behind them (waymark-fp62.4.1, R-3): a birth that names a row
  which does not stand refuses AT THE DOOR, and the sentence names the
  field and the kind it expected. The wall is built from the create
  model's own `:kind` entries, so a kind writes nothing and cannot
  forget it.

  A MINT never meets it (create-in-tx! passes the empty vector): the
  engine's own discovery birth records what an external authority
  already has, and it speaks the full schema rather than the author's
  create door."
  [rdef]
  (let [refs (:create-ref-fields rdef)]
    (cond-> (vec (:create-guards rdef))
      (seq refs) (conj (g/names-a-row-that-stands refs)))))

(def ^:private engine-only-door
  "The wall in front of a create door held `:by #{:engine}`: only a
  :system principal walks it, under any grant."
  (g/guard
   {:name :not_yours
    :explain "This door is the engine's own: only the engine's hand walks it."
    :reads [:principal]
    :check (with-meta
             (fn [_ _ ctx]
               (if (= :system (:type (:principal ctx)))
                 (t/allow)
                 (t/deny)))
             {:waymark10/form '(fn [row inp ctx]
                                 (= :system (:type (:principal ctx))))})}))

(defn create-door
  "The extra create door named `action` among the kind's :creates, or
  nil — nil too for the kind's ordinary create verb."
  [rdef action]
  (when (some? action)
    (let [a (keyword (name action))]
      (some #(when (= a (:action %)) %) (:creates rdef)))))

(defn- create-spec
  "What one create door births with: {:action :model :guards
  :on-create}. No `action`, or one of the kind's create verbs, is the
  ordinary create; an action :creates declares is that door, with its
  own input, guards and :on-create, and its `:by #{:engine}` walled in
  front of its guards. Any other name is a caller's mistake."
  [rdef action mint?]
  (if-some [door (create-door rdef action)]
    {:action (:action door)
     :model (:input door)
     :guards (cond->> (vec (:guards door))
               (contains? (:by door) :engine) (into [engine-only-door]))
     :on-create (:on-create door)}
    (let [a (some-> action name keyword)]
      (when (and a (not (contains? (:create-action-names rdef) a)))
        (throw (t/definition-error
                (str (name (:kind rdef)) " declares no create door named "
                     (name a)))))
      {:action (or a (first (:create-action-names rdef)))
       :model (if mint?
                (:schema rdef)
                (or (:create-schema rdef) (:schema rdef)))
       :guards (if mint? [] (create-walled-guards rdef))
       :on-create (:on-create rdef)})))

(defn- create-guard-pass
  "The create-time guard grading (design E9), ONE reduce for the real
  path, the full rehearsal, and the partial rehearsal — so the three
  judgments cannot drift: the validated create input judged with row
  nil (no instance exists yet), warnings collect (acknowledged names
  pass and are recorded), hard denies refuse.

  `scope` is the decision record's evidence scope, or nil. The two
  rehearsals pass nil and record nothing — §23's promise that a dry
  run neither demands, consumes, nor RECORDS, spelled at the create
  door too."
  ([guards inp ctx acknowledged]
   (create-guard-pass guards inp ctx acknowledged nil))
  ([guards inp ctx acknowledged scope]
   (reduce
    (fn [acc guard]
      (let [[v d] (g/evaluate guard nil inp ctx)
            deny? (t/deny? v)
            acc (cond-> acc
                  scope (update :basis conj
                                (decision/entry guard deny? d acknowledged
                                                scope)))]
        (if-not deny?
          acc
          (if (= :warning (:severity d))
            (if (contains? acknowledged (:name d))
              (update acc :overridden conj (:name d))
              (update acc :warned conj {:name (:name d)
                                        :reason (g/render-reason d v nil)
                                        :remedies (g/remedy-doors d)}))
            (throw (p/guard-refused :create nil
                                    (g/render-reason d v nil)
                                    {:guard (:name d)
                                     :remedies (g/remedy-doors d)
                                     :resolved-remedies (g/resolve-remedies d nil inp (:evidence v))
                                     :evidence (:evidence v)
                                     :open (:open d)}
                                    nil))))))
    {:warned [] :overridden [] :basis (when scope [])}
    guards)))

(defn- create-dry-run!
  "The create rehearsal (design §23; waymark9 _create_entry's tiers):
  no idempotency key demanded or read, nothing inserted, no
  :on-create (its side effects must not fire), no transition. Tier
  one — no create guards: schema validation IS the answer, and no
  session is even opened. Tier two — declared create guards judged
  exactly as the real path: refuse-severity throws its 409, pending
  warnings ride the body as data. :dry-run :partial narrows both
  tiers to what the caller provided (§23's judged-when-answerable
  mode): provided entries schema-checked, fully covered guard leaves
  judged, the rest :awaiting — on the verdict, and on a judged leaf's
  409 exactly as partial-verdict names them."
  [engine rdef guards model body principal acknowledged mode grant]
  (let [partial? (= :partial mode)
        inp (schema/decode model (or body {}))
        errors (cond-> (schema/closed-errors model inp)
                 partial? (-> (select-keys (keys (or body {})))
                              not-empty))]
    (when errors (throw (p/schema-invalid :create errors)))
    (when-some [hand (hand-written-errors rdef principal inp)]
      (throw (p/schema-invalid :create hand)))
    (if (empty? guards)
      (if partial? {:valid? true :judged [] :awaiting []} {:valid? true})
      (store/with-tx (:storage engine)
        (fn [tx]
          ;; the rehearsal wears the same leash the real create would
          ;; (waymark-sfe): a grantable create wall must answer a
          ;; dry-run exactly as it will answer the write
          (let [ctx (make-ctx engine tx :dry-run principal {:grant grant})]
            (if partial?
              (let [{:keys [judged awaiting]}
                    (split-leaves guards
                                  (set (keys (or body {}))))
                    {:keys [warned]}
                    (try
                      (create-guard-pass judged inp ctx acknowledged)
                      (catch clojure.lang.ExceptionInfo e
                        (if (and (seq awaiting)
                                 (= :guard-refused
                                    (:waymark10/problem (ex-data e))))
                          (throw (ex-info (ex-message e)
                                          (assoc (ex-data e)
                                                 :awaiting (mapv :name awaiting))
                                          e))
                          (throw e))))]
                {:valid? true
                 :judged (mapv :name judged)
                 :awaiting (mapv :name awaiting)
                 :warnings (not-empty warned)})
              (let [{:keys [warned]}
                    (create-guard-pass guards inp ctx acknowledged)]
                {:valid? true :warnings (not-empty warned)}))))))))

(defn create!
  "The initial-state transition. Validates against :create-schema (or
  :schema), runs create guards with row nil, runs :on-create, then
  materializes, inserts, and logs under the kind's create action name.
  The transition's to-state is the row's state AFTER :on-create — a
  declared create landing (a held definition is born :proposed) logs
  honestly.

  Dry-run (design §23): :dry-run true rehearses waymark9's tiers —
  see create-dry-run! above — and :dry-run :partial judges only what
  the caller provided.

  Phase 10 (the client found the gap): a PRESENT Idempotency-Key is
  honored exactly as invoke's — stored replay is byte-identical,
  reuse with a different body refuses 409. Recorded deviation from
  waymark9: the 428 requirement on keyless creates stays waived —
  v10 never demanded it and every enrolled app creates bare; the
  affordance-following client always sends one.

  :mint? true is the ENGINE's own birth door (mirror discovery — a
  system pass recording a row the external authority already has):
  the body validates against the full :schema rather than the
  author's :create-schema, and the author's create guards are not
  consulted — they judge the create-schema's vocabulary, which a
  mint doesn't speak. Never set from a request path.

  :action names the create door (default: the kind's create verb). An
  extra door the kind declares under :creates validates against its own
  :input, runs its own guards and :on-create, and the log row names it."
  [engine kind body {:keys [principal acknowledged dry-run mint? grant action]
                     :or {acknowledged #{}}
                     :as opts}]
  (let [rdef (rdef-of engine kind)
        {model :model create-action :action guards :guards}
        (create-spec rdef action mint?)]
    (if dry-run
      (let [verdict (create-dry-run! engine rdef guards model body principal
                                     acknowledged dry-run grant)
            ;; a kind that declares :on-rehearse says what the create
            ;; would make: asked of the full rehearsal alone, after the
            ;; guards passed and outside their transaction
            preview (when (and (true? dry-run) (:valid? verdict))
                      (when-some [f (:on-rehearse rdef)]
                        (f engine (schema/decode model (or body {}))
                           {:principal principal :grant grant})))]
        (cond-> verdict preview (assoc :preview preview)))
      (after-write!
       engine kind create-action
       (store/with-tx (:storage engine)
        (fn [tx]
          (create-in-tx! engine tx kind body
                         (assoc opts :acknowledged acknowledged))))))))

(declare create-in-tx!)

(defn insert-quiet!
  "The engine's QUIET birth door: insert one row of `kind` inside an
  existing transaction, with NO transition and no guard pass. → the
  row as stored (decoded shape, no :summary).

  One caller, and it is the reason the door exists: a transcript's
  lines (server/transcripts, docs/spec-transcript.md R-5.7). A run of
  a few thousand lines arrives in one post, and the post is the write
  worth recording; a transition per line would put the transcript in
  the log a second time, and the log is the record of decisions, not
  of bytes. The row is stamped exactly as `create-in-tx!` stamps one
  (its id, the initial state, version 1, the kind's shape, the owner
  and the kind's current law) so it reads, renders and filters like
  any other row of its kind.

  The data is the caller's, validated by the caller: this door runs
  no schema check, because its one caller builds every field itself
  and a refusal here would lose a line the hook already sent.

  `:state` births the row in a state other than the kind's initial
  one. Its second caller is the clock sweep's record of a fire nobody
  sat in (`wakes/sweep-missed!`): a sitting born closed, because
  nothing ever opened it and a transition would log a close no run
  made."
  [engine tx kind data {:keys [principal id state]}]
  (let [rdef (rdef-of engine kind)
        row {:id (or id (str (random-uuid)))
             :state (or state (:initial rdef))
             :version 1
             :data data
             :shape (:shape rdef 1)
             :owner (:id principal)
             :law-revision (create-law-revision engine rdef kind)}]
    (store/insert-row! (:storage engine) tx kind (encode-row rdef row))
    row))

(defn update-quiet!
  "The quiet door for a row that exists: lock the row of `kind` under
  `id` inside an existing transaction, put its data through `f` and save
  it a version on — with NO transition and no guard pass. → the row as
  saved, or nil when there is none.

  One caller: the ledger (server/postings, step 3c), moving an account's
  projected balance in the transaction that writes its postings. The
  posting door's own transition is the write the log records, and a
  balance is no door's to write (hand-written-errors, land!)."
  [engine tx kind id f]
  (let [rdef (rdef-of engine kind)
        st (:storage engine)]
    (when-some [raw (store/load-row st tx kind (str id) {:for-update true})]
      (let [row (decode-row rdef raw)]
        (decode-row rdef (store/save-row! st tx kind
                                          (encode-row rdef (-> row
                                                               (update :data f)
                                                               (update :version inc)))
                                          (:version row)))))))

(defn- write-lines!
  "Ledger step 6a (docs/spec-ledger-money.md §6): the line rows of one
  parent, written in the door's transaction through the quiet birth
  door. The parent's transition is the write the log records, and its
  :inputs carry the lines; a transition per line would say it twice.
  `replace?` (an edit) deletes the stored set first, so the door's list
  IS the set. Each line is stamped with its parent and its place, `n`."
  [engine tx rdef parent-id lines principal replace?]
  (let [{child :kind via :parent} (:lines rdef)
        st (:storage engine)
        pid (str parent-id)]
    (when replace?
      (when-some [ids (seq (store/ids-matching
                            st tx child
                            [{:target :data :field via :cast "text"
                              :op := :value pid}]
                            10000))]
        (store/delete-rows! st tx child (vec ids))))
    (doseq [[n line] (map-indexed vector lines)]
      (insert-quiet! engine tx child (assoc line via pid :n n)
                     {:principal principal}))))

(defn create-mints!
  "The engine's BULK birth door — create! with :mint? true over MANY
  bodies, chunk-transacted: one commit per chunk of 200 instead of
  one per row, which at a five-figure first fill is the difference
  between hours and minutes. Row semantics are create!'s exactly
  (create-in-tx! per body, each result's after-write! pass after the
  chunk commits, in order). A chunk that refuses wholesale — the
  usual cause is a unique race with a peer replica's simultaneous
  mint — falls back to row-at-a-time for ITS bodies, so one
  contested id never undoes its neighbors' pass; a row that still
  refuses is skipped (the next discovery pass retries). → the number
  minted."
  [engine kind bodies opts]
  (let [opts (assoc opts :mint? true)
        rdef (rdef-of engine kind)
        create-action (first (:create-action-names rdef))]
    (reduce
     (fn [n chunk]
       (+ (long n)
          (try
            (let [results (store/with-tx (:storage engine)
                            (fn [tx]
                              (mapv #(create-in-tx! engine tx kind % opts)
                                    chunk)))]
              (run! #(after-write! engine kind create-action %) results)
              (count results))
            (catch Exception _
              (reduce (fn [m body]
                        (try (create! engine kind body opts)
                             (inc (long m))
                             (catch Exception _ m)))
                      0 chunk)))))
     0 (partition-all 200 bodies))))

(defn- create-in-tx!
  "The create algorithm inside an EXISTING transaction — create!'s
  body, extracted so the ctx :create door can birth rows of other
  kinds mid-write (chore's queue verb demanded it). Returns
  {:row … :transition …} (or the idempotent replay); the caller owes
  the result its after-write! pass — create! runs it directly, an
  inner birth rides the sink out and after-write! drains it.

  During :on-create the ctx :create door DEFERS (the plan_day
  fan-out's demand): births queue and land right after this row's own
  insert and transition, so a child's ref-label pass reads a real
  parent and the log orders parent before child. Each birth runs the
  child's full create algorithm — its own :on-create may birth
  grandchildren; a declaration cycle refuses at depth 8."
  [engine tx kind body {:keys [principal acknowledged correlation-id id
                               idempotency-key mint? depth within grant
                               action recurs-from]
                        :or {acknowledged #{} depth 0}}]
  (let [rdef (rdef-of engine kind)
        {model :model create-action :action door-guards :guards
         on-create :on-create} (create-spec rdef action mint?)
        digest (body-digest body)]
    (if-some [hit (when idempotency-key
                    (store/idempotency-lookup (:storage engine) tx
                                              idempotency-key kind))]
      (if (and (= (:action hit) create-action)
               (= (:request-digest hit) digest))
        {:replayed? :idempotency :response hit}
        (throw (p/idempotency-key-reuse create-action)))
      (let [inp (schema/decode model (or body {}))
            ;; declared defaults fill absent keys before validation —
            ;; but never on a mint: a mirror records what the
            ;; authority has, and absent means absent
            inp (if mint? inp (schema/apply-defaults model inp))
            _ (when-some [errors (schema/closed-errors model inp)]
                (throw (p/schema-invalid :create errors)))
            _ (when-some [hand (hand-written-errors rdef principal inp)]
                (throw (p/schema-invalid :create hand)))
            ctx (make-ctx engine tx :invoke principal
                          {:correlation-id correlation-id
                           :within within
                           :grant grant
                           :self {:kind kind :action create-action}})
            inp (defaulted-lines rdef ctx inp)
            {:keys [warned overridden basis]}
            (create-guard-pass door-guards
                               inp (cond-> (dissoc ctx :invoke :create :inner-sink
                                                   :power :follow-up :follow-sink)
                                     ;; a birth holds no lines yet: (lines)
                                     ;; reads the input's (ledger step 6b)
                                     (:lines rdef) (assoc :stored-lines []))
                               acknowledged
                               ;; the birth's evidence scope: no row
                               ;; exists yet, so the create input IS
                               ;; the document the guards read
                               (when (decision/retains? rdef)
                                 (decision/evidence-scope rdef nil inp ctx)))]
        (when (seq warned)
          (throw (p/warning-refused :create warned)))
        (let [now (:now ctx)
              row {:id (or id (str (random-uuid)))
                   :state (:initial rdef)
                   :version 1
                   ;; each account is born at zero (ledger step 3c),
                   ;; so "under 5" finds a row nothing posted to yet
                   :data (cond-> (merge (money/opening-balances (:accounts rdef))
                                        (sans-lines rdef inp))
                           ;; its totals (ledger step 6c), from the
                           ;; lines it is born with
                           (:lines rdef) (with-totals rdef (:lines inp)))
                   :shape (:shape rdef 1)
                   :owner (:id principal)
                   :law-revision (create-law-revision engine rdef kind)}
              ;; predecessor refs resolve first (design E7, batch E —
              ;; the one batch-E invoke seam, documented): a
              ;; :waymark/ref entry declaring {:predecessor {…}}
              ;; fills from the newest existing sibling when the
              ;; body left it blank, BEFORE :on-create so the hook
              ;; may read the resolved sibling (waymark9 invoke.py's
              ;; step order). All machinery lives in
              ;; waymark10.server.predecessor; this line only runs
              ;; it at the create algorithm's honest slot.
              row (predecessor/resolve! (:storage engine) tx
                                        (resources engine) rdef row)
              ;; the hook's birth door defers: this row is not yet
              ;; inserted, so a child born NOW would precede its
              ;; parent in storage and the log — queue, drain below
              births (atom [])
              octx (assoc ctx :create
                          (fn [target-kind body & [opts]]
                            (when-not (get (resources engine) target-kind)
                              (throw (t/definition-error
                                      (str "ctx create: unknown kind "
                                           target-kind))))
                            (when (>= depth 8)
                              (throw (t/definition-error
                                      (str "ctx create: birth depth 8 — a "
                                           "cycle in the declarations"))))
                            (swap! births conj {:kind target-kind
                                                :body body :opts opts})
                            nil))
              row (if-some [oc on-create] (oc row octx) row)
              ;; a :recurs run's made row keeps the key it was made
              ;; under (scheduled/run-key): the engine's field, past the
              ;; closed create model no hand may write it through
              row (cond-> row
                    recurs-from (assoc-in [:data :recurs_from] recurs-from))
              ;; ref labels + one-of at birth too: before nil, every
              ;; set ref is newly set
              row (update row :data
                          #(labels-and-groups engine tx rdef % nil))
              row (derived/materialize rdef row now (:calendar ctx))
              row (assoc row :summary (summary-of rdef row ((:now-fn engine))))
              _ (store/insert-row! (:storage engine) tx kind
                                   (encode-row rdef (dissoc row :summary)))
              ;; its lines (ledger step 6a) follow it in, in this
              ;; transaction: a refused line already refused the door
              _ (when (and (:lines rdef) (seq (:lines inp)))
                  (write-lines! engine tx rdef (:id row) (:lines inp)
                                principal false))
              record (store/append-transition!
                      (:storage engine) tx
                      {:kind kind
                       :resource-id (:id row)
                       :action create-action
                       :from-state nil
                       :to-state (:state row)
                       ;; finish!'s own stamp, same map (actor-map):
                       ;; a delegate's ask reads "filed by <agent>
                       ;; under grant-…" or it reads as the agent's
                       ;; own initiative
                       :actor (actor-map principal (:id grant))
                       :law-revision (:law-revision row)
                       :input-digest digest
                       :acknowledged (not-empty (vec overridden))
                       ;; the birth's decision record, same law as the
                       ;; row it just stamped
                       :judgment (decision/record (:law-revision row) basis
                                                  overridden)
                       ;; tier 3: the document as it was born
                       :after (decision/after-record rdef (:data row))
                       :correlation-id correlation-id
                       ;; the key a client sent rides the birth's own
                       ;; transition exactly as finish! stamps it on an
                       ;; invoke's (waymark-jfv.20 found the gap): the
                       ;; feed's origin convention reads this column,
                       ;; and a CREATE tapped from a card — a quick
                       ;; reason, the crown's own ask — was invisible
                       ;; to actions-from-feed without it
                       :idempotency-key idempotency-key
                       :summary (:summary row)})
              ;; drain the :on-create births: full create algorithm,
              ;; same transaction, each riding the inner sink so
              ;; after-write! owes it the post-commit passes and the
              ;; parent's response counts its children honestly
              _ (doseq [{:keys [kind body opts]} @births]
                  (let [trdef (rdef-of engine kind)
                        tmodel (or (:create-schema trdef) (:schema trdef))
                        res (create-in-tx!
                             engine tx kind
                             ;; the hook lives in DECODED space (its
                             ;; dates are LocalDates); the create
                             ;; algorithm speaks wire
                             (schema/encode tmodel (or body {}))
                             {:principal principal
                              :correlation-id correlation-id
                              ;; …and under the same leash
                              :grant grant
                              ;; a deferred birth is still opened
                              ;; inside THIS create's hand
                              :within {:kind kind :action create-action}
                              :acknowledged (or (:acknowledged opts) #{})
                              :depth (inc depth)})]
                    (swap! (:inner-sink ctx) conj
                           {:kind kind
                            :action (first (:create-action-names trdef))
                            :res res})))]
          (when idempotency-key
            (store/idempotency-store!
             (:storage engine) tx idempotency-key kind create-action digest
             201 (if-some [render-fn (:render-fn engine)]
                   ;; row is already decoded; the same envelope the
                   ;; live 201 answers with
                   (render-fn rdef (dissoc row :summary)
                              {:read (:read ctx) :find (:find ctx)})
                   (wire/write-json {:id (:id row)
                                     :state (name (:state row))
                                     :version (:version row)
                                     :summary (:summary row)}))
             "application/waymark+json"))
          ;; :on-create writes made through ctx :invoke/:create ride
          ;; out too
          (let [inner (some-> (:inner-sink ctx) deref not-empty)
                follow (some-> (:follow-sink ctx) deref not-empty)]
            (cond-> {:row row :transition record}
              inner (assoc :inner-writes inner)
              follow (assoc :follow-ups follow))))))))

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
