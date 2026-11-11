(ns waymark10.server.invoke.births
  "The create door's parts and the quiet writers (moved out of
  waymark10.server.invoke, ticket bab6336f): the law a birth stamps,
  the create door's spec and its walled guards, the one guard pass the
  real create and both rehearsals share, the engine-written fields a
  hand may not carry, the quiet birth and update doors, and a door's
  line rows (ledger step 6), with the phase-2 engine map and the check
  that only the engine's own hand walks a door inside a caller's
  transaction. `invoke/create!` runs the birth; nothing here opens a
  transaction or makes a call."
  (:require [waymark10.guards :as g]
            [waymark10.lines :as line-items]
            [waymark10.money :as money]
            [waymark10.schema :as schema]
            [waymark10.server.decision :as decision]
            [waymark10.server.invoke.labels :refer [encode-row]]
            [waymark10.server.invoke.rows :refer [decode-row rdef-of]]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

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

(defn create-walled-guards
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

(defn create-spec
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

(defn create-guard-pass
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
                                     :open (:open d)
                                     :no-remedy (:no-remedy d)}
                                    nil))))))
    {:warned [] :overridden [] :basis (when scope [])}
    guards)))

(defn hand-written-errors
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

(defn defaulted-lines
  "The input with each of its lines' omitted :defaults filled (ledger
  step 6c, docs/spec-ledger-money.md §6), read through follow over the
  door's own :read, so a line's price is its product's when the door
  ran."
  [rdef ctx inp]
  (cond-> inp
    (and (get-in rdef [:lines :defaults]) (map? inp) (contains? inp :lines))
    (update :lines #(line-items/defaulted rdef (:read ctx) %))))

(defn with-totals
  "The row's data with its kind's :totals (ledger step 6c) computed
  from these lines, in the door's own transaction, so the row is
  written with them and its postings read them. A total with no value
  is left out."
  [data rdef ls]
  (if-some [ts (not-empty (get-in rdef [:lines :totals]))]
    (merge (apply dissoc data (keys ts)) (line-items/totals rdef ls))
    data))

(defn sans-lines
  "The input as the row's own data reads it: a kind that declares
  :lines (ledger step 6a) takes its `lines` into line rows, never into
  the parent's data."
  [rdef inp]
  (cond-> inp (and (:lines rdef) (map? inp)) (dissoc :lines)))

(defn write-lines!
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

(defn engine-hand!
  [principal door]
  (when-not (= :system (:type principal))
    (throw (t/definition-error
            (str door " takes the engine's own actor, a :system principal")))))

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
